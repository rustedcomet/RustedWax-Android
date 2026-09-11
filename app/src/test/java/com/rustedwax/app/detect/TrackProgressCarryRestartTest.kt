package com.rustedwax.app.detect

import com.rustedwax.core.TrackIdentity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The same video started again, mistaken for the same viewing resumed.
 *
	 * Synthetic regression: a first viewing earns 130 seconds and stops near 137
	 * seconds. The same item is reopened near its beginning inside the carry TTL
	 * and plays another 80 seconds.
 *
 * 130 + 80 = 210. The two viewings covered position 7→137 s and 8→88 s — between
 * them **130 s of distinct content, 63 % of the video**, not 102 %.
 *
 * `claim` reached its position guard only when the replacement appeared *later*
 * than `TTL_MS`; inside that minute the title-and-artist key was trusted on its
 * own. The replacement arrived 35 s in, so the guard never ran — and the evidence
 * that would have refused it was sitting in the call: the carry stopped near
 * 137 s and the replacement offered 8 s.
 *
 * Position now outranks the window. It refutes a carry whenever both sides are
 * known and the replacement sits materially *behind* the carried position, which
 * playback cannot do on its own; every other case, including a missing position,
 * is left exactly as it was.
 */
class TrackProgressCarryRestartTest {

	private val native = YouTubeProbe.YOUTUBE_PACKAGE

	/** Representative video identity returned by the watch-history route. */
	private val video = TrackIdentity(
		title = "Chronixx - Spanish Town Rockin' [OFFICIAL AUDIO] | Chronology",
		artist = "ChronixxMusic",
		album = null,
		durationMs = 205_000,
		sourceItemId = "K2grZsqpvEI",
	)

	/** The replacement claims 35 seconds after teardown, inside TTL_MS. */
	private val tornDownAt = 1_000_000L
	private val reopenedAt = tornDownAt + 35_000

	@Before fun setUp() = TrackProgressCarry.clear()
	@After fun tearDown() = TrackProgressCarry.clear()

	private fun progress(
		playedMs: Long,
		lastPositionMs: Long?,
		at: Long = tornDownAt,
	) = TrackProgressCarry.Progress(
		playedMs = playedMs,
		trackStartedAtEpochSec = 1_788_577_591L,
		fastestSpeedSeen = 1.0,
		atMillis = at,
		lastPositionMs = lastPositionMs,
	)

	// ── A / B. the device case ───────────────────────────────────────────────

	@Test
	fun `a replacement that restarted the video does not inherit the first viewing`() {
		assertNotNull(
			TrackProgressCarry.remember(native, video, progress(130_000, lastPositionMs = 137_400)),
		)

		assertNull(
			"the same id 35s later at 8s is a second viewing, not the first resuming",
			TrackProgressCarry.claim(
				native,
				video,
				now = reopenedAt,
				resumePositionMs = 8_056,
				resumeVideoId = "K2grZsqpvEI",
			),
		)
	}

	@Test
	fun `the refused carry is left for its own finalization rather than discarded`() {
		TrackProgressCarry.remember(native, video, progress(130_000, lastPositionMs = 137_400))
		TrackProgressCarry.claim(
			native, video, now = reopenedAt, resumePositionMs = 8_056, resumeVideoId = "K2grZsqpvEI",
		)

		assertEquals(
			"the first viewing still owes the user a listen; its owner's timer collects it",
			1,
			TrackProgressCarry.size(),
		)
	}

	@Test
	fun `the device arithmetic no longer reaches 210s`() {
		TrackProgressCarry.remember(native, video, progress(130_000, lastPositionMs = 137_400))
		val carried = TrackProgressCarry.claim(
			native, video, now = reopenedAt, resumePositionMs = 8_056, resumeVideoId = "K2grZsqpvEI",
		)?.playedMs ?: 0L

		val secondViewing = carried + 79_900
		assertEquals("the second viewing is worth its own 80s and nothing more", 79_900, secondViewing)
		assertTrue(
			"80s of a 205s video is 39%, under the threshold that 210s falsely cleared",
			secondViewing.toDouble() / 205_000 < 0.60,
		)
	}

	// ── C. two overlapping sub-threshold viewings may not combine ────────────

	@Test
	fun `two overlapping partial viewings cannot sum past the threshold`() {
		// 50 s from the start, restart, 80 s from the start again. Distinct
		// coverage is 80 s — 39 % — and the sum would have been 63 %.
		TrackProgressCarry.remember(native, video, progress(50_000, lastPositionMs = 50_000))

		val carried = TrackProgressCarry.claim(
			native, video, now = reopenedAt, resumePositionMs = 1_200, resumeVideoId = "K2grZsqpvEI",
		)?.playedMs ?: 0L

		val second = carried + 80_000
		assertEquals(80_000, second)
		assertTrue(second.toDouble() / 205_000 < 0.60)
	}

	// ── D. a genuine in-place rebuild still carries ──────────────────────────

	@Test
	fun `a session rebuilt where it left off still carries its progress`() {
		TrackProgressCarry.remember(native, video, progress(130_000, lastPositionMs = 137_400))

		assertEquals(
			"the replacement picked the item up where it stopped — one viewing continuing",
			130_000L,
			TrackProgressCarry.claim(
				native,
				video,
				now = reopenedAt,
				resumePositionMs = 137_900,
				resumeVideoId = "K2grZsqpvEI",
			)?.playedMs,
		)
	}

	@Test
	fun `a rebuild that resumes slightly behind or well ahead still carries`() {
		TrackProgressCarry.remember(native, video, progress(130_000, lastPositionMs = 137_400))
		assertNotNull(
			"a few seconds of buffering rewind is not a restart",
			TrackProgressCarry.claim(
				native, video, now = reopenedAt,
				resumePositionMs = 137_400 - TrackProgressCarry.RESUME_WINDOW_MS + 500,
				resumeVideoId = "K2grZsqpvEI",
			),
		)

		TrackProgressCarry.clear()
		TrackProgressCarry.remember(native, video, progress(130_000, lastPositionMs = 137_400))
		assertNotNull(
			"and playing on unobserved through the gap is the case the carry exists for",
			TrackProgressCarry.claim(
				native, video, now = reopenedAt, resumePositionMs = 170_000,
				resumeVideoId = "K2grZsqpvEI",
			),
		)
	}

	// ── E / F / G. the bounds of the new rule ────────────────────────────────

	@Test
	fun `absent position evidence refutes nothing`() {
		TrackProgressCarry.remember(native, video, progress(130_000, lastPositionMs = 137_400))
		assertNotNull(
			"a replacement that publishes no position keeps the established behaviour",
			TrackProgressCarry.claim(
				native, video, now = reopenedAt, resumePositionMs = null,
				resumeVideoId = "K2grZsqpvEI",
			),
		)

		TrackProgressCarry.clear()
		TrackProgressCarry.remember(native, video, progress(130_000, lastPositionMs = null))
		assertNotNull(
			"and neither does a carry that never recorded where it stopped",
			TrackProgressCarry.claim(
				native, video, now = reopenedAt, resumePositionMs = 8_056,
				resumeVideoId = "K2grZsqpvEI",
			),
		)
	}

	@Test
	fun `the predicate answers only when both positions are known and disagree`() {
		fun p(lastPositionMs: Long?) = progress(130_000, lastPositionMs)

		assertTrue(
			TrackProgressCarry.contradictsCarriedPosition(p(137_400), 8_056),
		)
		assertFalse(
			"exactly at the window is still a rebuild, not a restart",
			TrackProgressCarry.contradictsCarriedPosition(
				p(137_400), 137_400 - TrackProgressCarry.RESUME_WINDOW_MS,
			),
		)
		assertFalse(
			"forward is the unobserved-interruption case",
			TrackProgressCarry.contradictsCarriedPosition(p(137_400), 200_000),
		)
		assertFalse(TrackProgressCarry.contradictsCarriedPosition(p(null), 8_056))
		assertFalse(TrackProgressCarry.contradictsCarriedPosition(p(137_400), null))
	}

	@Test
	fun `a restart is refused outside the window too, as it always was`() {
		TrackProgressCarry.remember(native, video, progress(130_000, lastPositionMs = 137_400))
		assertNull(
			TrackProgressCarry.claim(
				native,
				video,
				now = tornDownAt + TrackProgressCarry.TTL_MS + 5_000,
				resumePositionMs = 8_056,
				resumeVideoId = "K2grZsqpvEI",
			),
		)
	}

	@Test
	fun `a browser continuation near its previous position is untouched`() {
		val chrome = "com.android.chrome"
		val tab = TrackIdentity("LUNA", "Feid", null, 196_000)
		TrackProgressCarry.remember(chrome, tab, progress(96_000, lastPositionMs = 96_000))

		assertEquals(
			96_000L,
			TrackProgressCarry.claim(
				chrome, tab, now = tornDownAt + 4_000, resumePositionMs = 96_500,
			)?.playedMs,
		)
	}

	@Test
	fun `a browser tab that replayed from the start is refused`() {
		val chrome = "com.android.chrome"
		val tab = TrackIdentity("LUNA", "Feid", null, 196_000)
		TrackProgressCarry.remember(chrome, tab, progress(96_000, lastPositionMs = 96_000))

		assertNull(
			TrackProgressCarry.claim(
				chrome, tab, now = tornDownAt + 4_000, resumePositionMs = 0,
			),
		)
	}
}
