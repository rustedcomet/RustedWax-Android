package com.rustedwax.app.detect

import com.rustedwax.core.*
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the real platform actually delivers, measured rather than assumed.
 *
 * ## Why this test exists
 *
 * The JVM parity gate runs both implementations against the stand-in media
 * surface in `replay/reference/phase01/Android.kt`. That file is careful, but it
 * is still a set of claims about Android written by hand:
 *
 *  - a metadata publish delivers `onMetadataChanged` with that bundle;
 *  - a state publish delivers `onPlaybackStateChanged` with position, rate and
 *    `lastPositionUpdateTime` as given;
 *  - releasing a session delivers `onSessionDestroyed`;
 *  - `controller.metadata` and `controller.playbackState` are live getters, not
 *    snapshots taken when the callback was registered;
 *  - two sessions have two different `sessionToken.toString()` values, which is
 *    what `SessionProbe` keys its watch map on;
 *  - an absent key reads as `null` / `0`, not as a default.
 *
 * Every one of those is load-bearing for the parity result, and none of them is
 * proven by a green JVM run. This proves them against the real classes on the
 * real device, which is what makes the parity gate's inputs credible rather than
 * self-consistent.
 *
 * Nothing here touches RustedWax production code. It is the platform under test.
 */
@RunWith(AndroidJUnit4::class)
class MediaSessionPlatformTest {

	private lateinit var session: MediaSession
	private lateinit var controller: MediaController
	private val opened = mutableListOf<MediaSession>()

	private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

	/** Records exactly what the platform delivered, in the order it arrived. */
	private class Recorder : MediaController.Callback() {
		val events = mutableListOf<String>()
		val metadata = mutableListOf<MediaMetadata?>()
		val states = mutableListOf<PlaybackState?>()

		@Volatile
		var latch = CountDownLatch(1)

		override fun onMetadataChanged(md: MediaMetadata?) {
			metadata += md
			events += "metadata"
			latch.countDown()
		}

		override fun onPlaybackStateChanged(state: PlaybackState?) {
			states += state
			events += "state"
			latch.countDown()
		}

		override fun onSessionDestroyed() {
			events += "destroyed"
			latch.countDown()
		}

		fun expect(count: Int = 1) {
			latch = CountDownLatch(count)
		}

		fun await(what: String) {
			assertTrue("the platform never delivered $what", latch.await(5, TimeUnit.SECONDS))
		}
	}

	private lateinit var recorder: Recorder

	private fun newSession(tag: String): MediaSession =
		MediaSession(context, tag).also {
			it.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS)
			it.isActive = true
			opened += it
		}

	@Before
	fun setUp() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync {
			session = newSession("rustedwax-platform-test")
			controller = MediaController(context, session.sessionToken)
			recorder = Recorder()
			controller.registerCallback(recorder)
		}
	}

	@After
	fun tearDown() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync {
			runCatching { controller.unregisterCallback(recorder) }
			opened.forEach { runCatching { it.release() } }
			opened.clear()
		}
	}

	private fun publishMetadata(build: MediaMetadata.Builder.() -> Unit) {
		recorder.expect()
		InstrumentationRegistry.getInstrumentation().runOnMainSync {
			session.setMetadata(MediaMetadata.Builder().apply(build).build())
		}
		recorder.await("a metadata change")
	}

	private fun publishState(state: Int, positionMs: Long, speed: Float = 1f) {
		recorder.expect()
		InstrumentationRegistry.getInstrumentation().runOnMainSync {
			session.setPlaybackState(
				PlaybackState.Builder()
					.setState(state, positionMs, speed)
					.build(),
			)
		}
		recorder.await("a playback state change")
	}

	@Test
	fun metadata_arrives_on_the_callback_with_the_fields_published() {
		publishMetadata {
			putString(MediaMetadata.METADATA_KEY_TITLE, "Sleepwalking")
			putString(MediaMetadata.METADATA_KEY_ARTIST, "Bring Me The Horizon")
			putString(MediaMetadata.METADATA_KEY_MEDIA_ID, "aaaaaaaaaaa")
			putLong(MediaMetadata.METADATA_KEY_DURATION, 200_000)
		}

		val delivered = recorder.metadata.last()
		assertEquals("Sleepwalking", delivered?.getString(MediaMetadata.METADATA_KEY_TITLE))
		assertEquals(
			"Bring Me The Horizon",
			delivered?.getString(MediaMetadata.METADATA_KEY_ARTIST),
		)
		assertEquals("aaaaaaaaaaa", delivered?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID))
		assertEquals(200_000L, delivered?.getLong(MediaMetadata.METADATA_KEY_DURATION))
	}

	/**
	 * The stand-in answers an unset key with `null` / `0`. So does the platform —
	 * which is what lets `TrackIdentity` treat a missing `DURATION` as silence
	 * rather than as a length of zero.
	 */
	@Test
	fun an_unset_key_reads_as_absent_rather_than_as_a_default() {
		publishMetadata {
			putString(MediaMetadata.METADATA_KEY_TITLE, "No Length Published")
		}

		val delivered = recorder.metadata.last()
		assertNull(delivered?.getString(MediaMetadata.METADATA_KEY_ARTIST))
		assertEquals(0L, delivered?.getLong(MediaMetadata.METADATA_KEY_DURATION))
		assertTrue(
			"DURATION should not be in the key set at all",
			delivered?.keySet()?.contains(MediaMetadata.METADATA_KEY_DURATION) == false,
		)
		assertTrue(
			"TITLE should be in the key set",
			delivered?.keySet()?.contains(MediaMetadata.METADATA_KEY_TITLE) == true,
		)
	}

	@Test
	fun playback_state_arrives_with_the_position_and_rate_published() {
		publishState(PlaybackState.STATE_PLAYING, 42_000, speed = 1.5f)

		val delivered = recorder.states.last()
		assertEquals(PlaybackState.STATE_PLAYING, delivered?.state)
		assertEquals(42_000L, delivered?.position)
		assertEquals(1.5f, delivered?.playbackSpeed ?: 0f, 0.001f)
	}

	/**
	 * `lastPositionUpdateTime` is an `elapsedRealtime` reading, and
	 * `SessionProbe.extrapolatedPosition` extrapolates from it. If it were a
	 * wall-clock instant or zero, every extrapolated position — and so every wrap
	 * and loop decision — would be wrong.
	 */
	@Test
	fun lastPositionUpdateTime_is_an_elapsed_realtime_reading() {
		val before = android.os.SystemClock.elapsedRealtime()
		publishState(PlaybackState.STATE_PLAYING, 1_000)
		val after = android.os.SystemClock.elapsedRealtime()

		val stamp = recorder.states.last()?.lastPositionUpdateTime ?: 0L
		assertTrue(
			"lastPositionUpdateTime=$stamp is not between $before and $after",
			stamp in before..after,
		)
	}

	/**
	 * The getters are live.
	 *
	 * `SessionProbe` reads `controller.metadata` long after registering — at
	 * finalization, at every log line — and a snapshot taken at construction
	 * would answer a question it never asked.
	 */
	@Test
	fun controller_getters_reflect_the_current_session_state() {
		publishMetadata { putString(MediaMetadata.METADATA_KEY_TITLE, "First") }
		assertEquals("First", controller.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE))

		publishMetadata { putString(MediaMetadata.METADATA_KEY_TITLE, "Second") }
		assertEquals("Second", controller.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE))

		publishState(PlaybackState.STATE_PAUSED, 7_000)
		assertEquals(PlaybackState.STATE_PAUSED, controller.playbackState?.state)
	}

	@Test
	fun releasing_a_session_delivers_onSessionDestroyed() {
		recorder.expect()
		InstrumentationRegistry.getInstrumentation().runOnMainSync { session.release() }
		recorder.await("a session destruction")

		assertEquals("destroyed", recorder.events.last())
	}

	/**
	 * Two sessions are two identities.
	 *
	 * `SessionProbe.syncControllers` keys its watch map on
	 * `sessionToken.toString()`; if a rebuilt session reused the string, a
	 * replacement would be mistaken for the original and the continuation carry
	 * would never run.
	 */
	@Test
	fun a_replacement_session_has_a_different_token_string() {
		lateinit var second: MediaSession
		InstrumentationRegistry.getInstrumentation().runOnMainSync {
			second = newSession("rustedwax-platform-test-2")
		}
		assertNotEquals(
			"two live sessions produced the same token string",
			session.sessionToken.toString(),
			second.sessionToken.toString(),
		)
	}

	/**
	 * Callback order is publication order.
	 *
	 * The reducer's contract depends on it — a finalize has to read the outgoing
	 * bundle, so a platform that coalesced or reordered these would change what a
	 * track change measures.
	 */
	// ---- Defect 4 root cause: stale metadata survives a later id-less playback ----

	@Test
	fun metadata_survives_a_later_playback_that_publishes_none() {
		publishMetadata {
			putString(MediaMetadata.METADATA_KEY_TITLE, "Short A")
			putLong(MediaMetadata.METADATA_KEY_DURATION, 66_000)
		}
		// Short A plays out and stops, exactly as a finished Short does.
		publishState(PlaybackState.STATE_PLAYING, 60_000)
		publishState(PlaybackState.STATE_STOPPED, 0)

		// A later playback begins and publishes no metadata of its own.
		publishState(PlaybackState.STATE_PLAYING, 0)

		val stale = controller.metadata
		assertEquals(
			"the controller still serves the previous Short's title",
			"Short A",
			stale?.getString(MediaMetadata.METADATA_KEY_TITLE),
		)
		assertEquals(
			"and its length, which is what made a later listen read as N of N",
			66_000L,
			stale?.getLong(MediaMetadata.METADATA_KEY_DURATION),
		)
		assertEquals(
			"while the live state belongs to the new playback",
			PlaybackState.STATE_PLAYING,
			controller.playbackState?.state,
		)
	}

	/**
	 * Nothing in the bundle says when it was published.
	 *
	 * The corollary that decides the fix: a reader at teardown cannot tell a fresh
	 * title from a 1h28m-old one by inspecting the metadata, because the platform
	 * carries no publication timestamp. Freshness has to come from the session
	 * lifecycle the reader already tracks, not from the bundle.
	 */
	@Test
	fun metadata_carries_no_publication_time_to_judge_freshness_by() {
		publishMetadata {
			putString(MediaMetadata.METADATA_KEY_TITLE, "Short A")
			putLong(MediaMetadata.METADATA_KEY_DURATION, 66_000)
		}
		val md = controller.metadata!!
		val timestampish = md.keySet().filter {
			it.contains("TIME", true) || it.contains("DATE", true) || it.contains("YEAR", true)
		}
		assertTrue(
			"a publication time would make freshness readable off the bundle; found $timestampish",
			timestampish.none { md.getLong(it) != 0L },
		)
	}

	@Test
	fun callbacks_arrive_in_the_order_they_were_published() {
		publishMetadata { putString(MediaMetadata.METADATA_KEY_TITLE, "One") }
		publishState(PlaybackState.STATE_PLAYING, 0)
		publishMetadata { putString(MediaMetadata.METADATA_KEY_TITLE, "Two") }

		assertEquals(listOf("metadata", "state", "metadata"), recorder.events)
	}
}
