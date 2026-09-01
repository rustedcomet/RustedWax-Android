package com.rustedwax.app.detect

import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real foreground, background and minimised transitions, on the device.
 *
 * ## The gate
 *
 * `<redacted-private-path>` "Surface transitions" moves real foreground/background
 * transitions through the production reducer to the **Phase 3** acceptance gate,
 * and says the modelled half must be retired rather than kept alongside it.
 * `SurfaceTransitionReplayTest` states in its own header that
 * foreground/background/minimised are modelled because "no production input
 * exists" — a `SurfaceChanged` marker is recorded by the replay harness and never
 * dispatched to anything.
 *
 * That is an honest description of a JVM fixture and it is not evidence about
 * Android. This file performs the transitions for real:
 *
 * ```text
 * am-level task/window change  ->  real Activity lifecycle
 *   while a real MediaSession keeps playing
 *   ->  production SessionProbe callbacks -> reducer -> SessionSnapshot -> UI
 * ```
 *
 * ## What it proves, and the one thing it cannot
 *
 * It proves the invariant the modelled half asserted structurally: an ordinary
 * MediaSession's measurement is **unaffected** by the app moving between
 * foreground, background and minimised — the clock keeps running, nothing
 * finalizes falsely, and the listen that eventually ends carries the whole span.
 *
 * It cannot prove native picture-in-picture. PiP inference is reached only for
 * `YouTubeProbe.YOUTUBE_PACKAGE` through `NativeShortsObserver`, which is fed by
 * an accessibility service over the installed YouTube app. Sessions created here
 * belong to this app and resolve to browser capabilities, where
 * `supportsPictureInPictureInference` is false by design. Nothing in this file
 * claims otherwise; that gate is reported separately as NOT ESTABLISHED.
 */
@RunWith(AndroidJUnit4::class)
class SurfaceLifecycleDeviceTest {

	private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
	private val context: Context get() = instrumentation.targetContext

	private lateinit var probe: SessionProbe
	private val finalized = mutableListOf<SessionSnapshot>()
	private val sessions = mutableListOf<MediaSession>()

	private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

	private fun waitFor(what: String, timeoutMs: Long = 15_000, predicate: () -> Boolean) {
		val deadline = System.currentTimeMillis() + timeoutMs
		while (System.currentTimeMillis() < deadline) {
			if (predicate()) return
			Thread.sleep(150)
			onMain {}
		}
		throw AssertionError("timed out waiting for $what")
	}

	/** This class owns one app-local session; unrelated installed sessions are out of scope. */
	private fun watchable(packageName: String): Boolean = packageName == context.packageName

	/**
	 * The snapshot the UI would render right now.
	 *
	 * `SessionProbe.sessions` holds the snapshot built at the *last callback*, not
	 * a value recomputed on read — so a listen that has been playing quietly for
	 * two seconds still publishes the total it had when its last transport event
	 * arrived. `MainActivity` handles that by calling `probe.tick()` on its own
	 * cadence; this does the same, through the same production method, so the
	 * assertions below read what the Now card would actually show.
	 */
	private fun published(title: String): SessionSnapshot? {
		onMain { probe.tick() }
		return probe.sessions.value.firstOrNull {
			it.packageName == context.packageName && it.title == title
		}
	}

	private fun newSession(tag: String): MediaSession {
		lateinit var created: MediaSession
		onMain {
			created = MediaSession(context, tag).also {
				it.isActive = true
			}
			sessions += created
		}
		return created
	}

	private fun activityDump(): String = ParcelFileDescriptor.AutoCloseInputStream(
		instrumentation.uiAutomation.executeShellCommand("dumpsys activity activities"),
	).bufferedReader().use { it.readText() }

	private fun assertResumedSurface(packageName: String) {
		val resumed = activityDump().lineSequence()
			.firstOrNull { it.contains("mResumedActivity:") }
			.orEmpty()
		assertTrue(
			"expected $packageName to own the resumed task/window; Android reported: $resumed",
			resumed.contains(packageName),
		)
	}

	private fun MediaSession.publish(title: String, durationMs: Long, mediaId: String) = onMain {
		setMetadata(
			MediaMetadata.Builder()
				.putString(MediaMetadata.METADATA_KEY_TITLE, title)
				.putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs)
				.putString(MediaMetadata.METADATA_KEY_MEDIA_ID, mediaId)
				.build(),
		)
	}

	private fun MediaSession.transport(state: Int, positionMs: Long) = onMain {
		setPlaybackState(PlaybackState.Builder().setState(state, positionMs, 1f).build())
	}

	// ---- the real window/task transitions -------------------------------------

	/** Bring this app's own Activity to the front: a real task/window change. */
	private fun toForeground() {
		val launch = requireNotNull(
			context.packageManager.getLaunchIntentForPackage(context.packageName),
		).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
		context.startActivity(launch)
		Thread.sleep(2_000)
		onMain {}
		assertResumedSurface(context.packageName)
	}

	/**
	 * Send the whole task to the background by fronting the launcher.
	 *
	 * The home screen taking the foreground is what "minimised" is: the Activity
	 * is stopped, the task is no longer resumed, and the process becomes a cached
	 * background one. This is the transition the modelled marker stood in for.
	 */
	private fun toBackground() {
		val home = Intent(Intent.ACTION_MAIN)
			.addCategory(Intent.CATEGORY_HOME)
			.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
		val homePackage = requireNotNull(
			context.packageManager.resolveActivity(home, 0)?.activityInfo?.packageName,
		) { "Android exposed no HOME activity" }
		context.startActivity(home)
		Thread.sleep(2_000)
		onMain {}
		assertResumedSurface(homePackage)
	}

	@Before
	fun setUp() {
		val enabled = android.provider.Settings.Secure.getString(
			context.contentResolver,
			"enabled_notification_listeners",
		).orEmpty()
		assertTrue(
			"RustedWax does not hold notification access; no media session is readable. " +
				"Grant it: Settings → Apps → Special access → Notification access → RustedWax.",
			enabled.contains(context.packageName),
		)
		onMain {
			NativeSourceSwitches.configureForReplay(
				NativeSourceSwitches.Config(
					youTubeScrobbling = true,
					youtubeEnabled = true,
					youtubeMusicEnabled = true,
				),
			)
			RunScopedEvidence.clearAll(includeCarriedProgress = true)
			probe = SessionProbe(context, ::watchable)
			probe.onTrackFinalized = { finalized += it }
			probe.start()
		}
		Thread.sleep(500)
		onMain {}
		finalized.clear()
	}

	@After
	fun tearDown() {
		onMain {
			sessions.forEach {
				runCatching { it.isActive = false }
				runCatching { it.release() }
			}
			sessions.clear()
		}
		Thread.sleep(400)
		onMain {
			runCatching { probe.stop(finalizeTracks = false) }
			RunScopedEvidence.clearAll(includeCarriedProgress = true)
		}
		finalized.clear()
		// Leave the device on the home screen rather than on this app's UI.
		runCatching { toBackground() }
	}

	/**
	 * Measurement survives foreground → background → foreground, and the listen
	 * that ends afterwards carries the whole span.
	 *
	 * The three windows are two seconds each. If a transition finalized the track,
	 * or stopped the clock, or started a second listen, the single combined total
	 * below cannot hold.
	 */
	@Test
	fun measurement_continues_across_real_foreground_background_transitions() {
		val session = newSession("surface")
		session.publish("Across Surfaces", durationMs = 600_000, mediaId = "aaaaaaaaaaa")
		session.transport(PlaybackState.STATE_PLAYING, 0)
		waitFor("playback to be observed") { published("Across Surfaces")?.isPlaying == true }

		toForeground()
		val afterForeground = requireNotNull(published("Across Surfaces")).playedMs
		assertTrue(
			"nothing was measured while the app was in the foreground",
			afterForeground >= 1_500,
		)
		assertEquals(
			"a surface change finalized the listen",
			emptyList<String?>(),
			finalized.map { it.title },
		)

		toBackground()
		val afterBackground = requireNotNull(published("Across Surfaces")).playedMs
		assertTrue(
			"the clock stopped when the app went to the background: " +
				"$afterForeground ms → $afterBackground ms",
			afterBackground >= afterForeground + 1_500,
		)
		assertEquals(
			"going to the background finalized the listen",
			emptyList<String?>(),
			finalized.map { it.title },
		)

		toForeground()
		val afterReturn = requireNotNull(published("Across Surfaces")).playedMs
		assertTrue(
			"the clock stopped when the app returned: $afterBackground ms → $afterReturn ms",
			afterReturn >= afterBackground + 1_500,
		)

		// End it, and check the whole span arrived as one listen.
		session.publish("Something Else", durationMs = 100_000, mediaId = "bbbbbbbbbbb")
		waitFor("the listen to finalize") { finalized.any { it.title == "Across Surfaces" } }

		val listen = finalized.first { it.title == "Across Surfaces" }
		assertEquals(
			"the surface changes split one listen into several: " +
				finalized.joinToString { "${it.title}/${it.playedMs}ms" },
			1,
			finalized.count { it.title == "Across Surfaces" },
		)
		assertTrue(
			"played ${listen.playedMs}ms for a listen spanning three surfaces",
			listen.playedMs >= 6_000,
		)
		assertNotNull("no instance identity was stamped", listen.trackInstanceToken)
	}

	/**
	 * A session that is *paused* while minimised must not accrue.
	 *
	 * The negative control for the test above: if measurement were driven by the
	 * app's own surface rather than by the transport, a minimised-but-paused
	 * session would keep counting. It must not.
	 */
	@Test
	fun a_paused_session_does_not_accrue_while_minimised() {
		val session = newSession("surface-paused")
		session.publish("Paused While Minimised", durationMs = 600_000, mediaId = "aaaaaaaaaaa")
		session.transport(PlaybackState.STATE_PLAYING, 0)
		waitFor("playback to be observed") {
			published("Paused While Minimised")?.isPlaying == true
		}
		Thread.sleep(1_500)

		session.transport(PlaybackState.STATE_PAUSED, 1_500)
		waitFor("the pause to be observed") {
			published("Paused While Minimised")?.isPlaying == false
		}
		val atPause = requireNotNull(published("Paused While Minimised")).playedMs

		toBackground()
		Thread.sleep(3_000)
		onMain {}

		val afterMinimised = requireNotNull(published("Paused While Minimised")).playedMs
		assertEquals(
			"a paused session accrued while the app was minimised: " +
				"$atPause ms → $afterMinimised ms",
			atPause,
			afterMinimised,
		)
		assertEquals(
			"minimising a paused session finalized it",
			emptyList<String?>(),
			finalized.map { it.title },
		)
	}

	/**
	 * The UI state the Now card renders survives the transitions too.
	 *
	 * `SessionProbe.sessions` is what `MainActivity` collects through
	 * `ProbeHolder`. A listen that measured correctly but stopped being published
	 * would look, to the person holding the phone, exactly like a listen that had
	 * ended.
	 */
	@Test
	fun the_published_ui_state_survives_real_surface_changes() {
		val session = newSession("surface-ui")
		session.publish("Still On The Card", durationMs = 600_000, mediaId = "aaaaaaaaaaa")
		session.transport(PlaybackState.STATE_PLAYING, 0)
		waitFor("the card to show the track") { published("Still On The Card") != null }

		toForeground()
		assertNotNull(
			"the session left the published UI state in the foreground",
			published("Still On The Card"),
		)
		toBackground()
		assertNotNull(
			"the session left the published UI state when minimised",
			published("Still On The Card"),
		)
		assertEquals(
			"the card stopped reporting the track as playing",
			true,
			published("Still On The Card")?.isPlaying,
		)
	}
}
