package com.rustedwax.app.replay.reference.current

import com.rustedwax.app.detect.EventLog
import com.rustedwax.app.detect.YouTubeProbe
import com.rustedwax.app.replay.reference.phase01.ParityStep
import com.rustedwax.app.replay.reference.phase01.PlaybackState
import com.rustedwax.app.replay.reference.phase01.ReferenceRun
import com.rustedwax.app.replay.reference.phase01.Context
import com.rustedwax.app.replay.reference.phase01.MediaController
import com.rustedwax.app.replay.reference.phase01.MediaMetadata
import com.rustedwax.app.replay.reference.phase01.MediaSessionManager
import com.rustedwax.app.replay.reference.phase01.SystemClock
import com.rustedwax.app.replay.reference.phase01.VirtualTime
import com.rustedwax.app.replay.reference.phase01.resetSharedState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DestroyedSessionTombstoneTest {

	private val native = YouTubeProbe.YOUTUBE_PACKAGE

	private fun playing(positionMs: Long) =
		ParityStep.Transport(PlaybackState.STATE_PLAYING, positionMs)

	/**
	 * Play, be destroyed, and then be offered back to the probe.
	 *
	 * The trailing advance lets any timer either side armed run out, so a delayed
	 * second finalization would be counted rather than missed.
	 */
	private fun script() = listOf(
		ParityStep.Metadata("Resurrected", durationMs = 240_000, mediaId = "aaaaaaaaaaa"),
		playing(0),
		ParityStep.Advance(120_000),
		ParityStep.DestroySession,
		ParityStep.RelistDestroyedSession,
		ParityStep.Advance(5_000),
		ParityStep.Metadata("The Next One", durationMs = 100_000, mediaId = "bbbbbbbbbbb"),
		playing(0),
		ParityStep.Advance(60_000),
		ParityStep.Advance(120_000),
	)

	private fun run(block: () -> List<com.rustedwax.app.detect.SessionSnapshot>): List<String> {
		EventLog.applyPolicy(true)
		EventLog.clear()
		try {
			block()
			return EventLog.lines.value.toList()
		} finally {
			EventLog.applyPolicy(false)
			EventLog.clear()
		}
	}

	/**
	 * How many watches the probe built, counted from its own arrival line.
	 *
	 * `syncControllers` writes exactly one `[metadata] … (initial)` per `Watch` it
	 * creates, so this counts watch construction rather than inferring it. The
	 * companion `[playback] … (initial)` line is deliberately not used: a session
	 * with no transport yet logs `<null state>`, so keying on it would count some
	 * watches and not others.
	 */
	private fun watchesCreated(log: List<String>): Int =
		log.count { it.contains("[metadata]") && it.contains("(initial)") }

	@Test
	fun `the current implementation refuses a session it has already seen destroyed`() {
		val log = run { CurrentRun.snapshots(script(), native) }

		assertTrue("the run logged nothing at all", log.isNotEmpty())
		assertEquals(
			"a destroyed session was watched again: ${log.filter { it.contains("initial") }}",
			1,
			watchesCreated(log),
		)
	}

	/**
	 * The other half of the divergence.
	 *
	 * Without this, the assertion above would pass just as well against a platform
	 * stand-in that never re-listed anything — proving the harness, not the fix.
	 */
	@Test
	fun `declared divergence - the reference does resurrect the destroyed session`() {
		val old = run { ReferenceRun.snapshots(script(), native) }
		val new = run { CurrentRun.snapshots(script(), native) }

		assertTrue("the reference logged nothing at all", old.isNotEmpty())
		assertEquals(
			"the reference no longer resurrects a destroyed token, so the fix in " +
				"SessionProbe.destroyedTokens is now testing nothing: " +
				old.filter { it.contains("initial") },
			2,
			watchesCreated(old),
		)
		assertEquals(1, watchesCreated(new))
		assertNotEquals(
			"old and current agree, so the tombstone changed nothing",
			watchesCreated(old),
			watchesCreated(new),
		)
	}

	/**
	 * The rule must not collapse two genuinely live sessions from one package.
	 *
	 * Two browser tabs are legitimate and the audit calls them deliberately
	 * ambiguous rather than wrong. A fix that keyed on the *package* would break
	 * them; keying on the token instance does not, and this is what proves the
	 * difference rather than asserting it.
	 */
	@Test
	fun `a second live session in the same package is still watched`() {
		val log = run {
			resetSharedState()
			val time = VirtualTime()
			SystemClock.current = time
			val manager = MediaSessionManager()
			val first = MediaController(native).apply {
				seed(
					MediaMetadata(
						mapOf(
							"android.media.metadata.TITLE" to "Tab One",
							"android.media.metadata.DURATION" to 240_000L,
							"android.media.metadata.MEDIA_ID" to "aaaaaaaaaaa",
						),
					),
					PlaybackState(PlaybackState.STATE_PLAYING, 0),
				)
			}
			val second = MediaController(native).apply {
				seed(
					MediaMetadata(
						mapOf(
							"android.media.metadata.TITLE" to "Tab Two",
							"android.media.metadata.DURATION" to 240_000L,
							"android.media.metadata.MEDIA_ID" to "bbbbbbbbbbb",
						),
					),
					PlaybackState(PlaybackState.STATE_PLAYING, 0),
				)
			}
			manager.publish(listOf(first, second))
			val probe = SessionProbe(Context(manager))
			probe.start()
			time.drain()
			probe.stop(finalizeTracks = false)
			SystemClock.current = VirtualTime()
			emptyList()
		}

		assertEquals(
			"two simultaneously live tokens were not both watched: " +
				log.filter { it.contains("initial") },
			2,
			watchesCreated(log),
		)
	}
}
