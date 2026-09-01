package com.rustedwax.app.scrobble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gate G6: threshold parity with `hive-scrobbler.ts#finalize`. */
class ScrobbleRulesTest {

	private val fourMinutes = 240_000L

	@Test
	fun `below the threshold nothing is scrobbled`() {
		val d = ScrobbleRules.decide(playedMs = 100_000, durationMs = fourMinutes)
		assertFalse(d.shouldScrobble)
		assertTrue(d.skippedBecause!!.contains("below"))
	}

	@Test
	fun `a lost progress surface refuses without claiming a percentage`() {
		// Measured 2026-08-05 in picture-in-picture: the accessibility tree keeps
		// the Shorts root, player and time bar but loses the SeekBar, and
		// YouTube's MediaSession reports STATE_NONE with position 0. Nothing can
		// be measured, which is not the same fact as "0% was played" — and
		// reporting it as the latter hid the cause for most of a day.
		val lost = ScrobbleRules.decide(
			playedMs = 0,
			durationMs = fourMinutes,
			progressSurfaceLost = true,
		)
		assertFalse(lost.shouldScrobble)
		assertFalse(lost.skippedBecause!!.contains("%"))
		assertTrue(lost.skippedBecause!!.contains("cannot be measured"))

		// Same numbers without the marker still report the percentage.
		val ordinary = ScrobbleRules.decide(playedMs = 0, durationMs = fourMinutes)
		assertTrue(ordinary.skippedBecause!!.contains("below"))
	}

	@Test
	fun `a lost progress surface cannot rescue a listen that did clear the bar`() {
		// The marker only ever changes the wording of a refusal. It must never
		// admit or block a decision that the measurement already settled.
		val d = ScrobbleRules.decide(
			playedMs = 145_000,
			durationMs = fourMinutes,
			progressSurfaceLost = true,
		)
		assertEquals(listOf(60), d.percentages)
	}

	@Test
	fun `just past 60 percent scrobbles once`() {
		val d = ScrobbleRules.decide(playedMs = 145_000, durationMs = fourMinutes)
		assertEquals(listOf(60), d.percentages)
	}

	@Test
	fun `a full listen scrobbles once at 100`() {
		val d = ScrobbleRules.decide(playedMs = fourMinutes, durationMs = fourMinutes)
		assertEquals(listOf(100), d.percentages)
	}

	@Test
	fun `a double listen scrobbles twice, capped at 100 each`() {
		// 170% played → upstream emits 2 tx: min(100,170)=100 and round(0.7*100)=70
		val d = ScrobbleRules.decide(playedMs = 408_000, durationMs = fourMinutes)
		assertEquals(listOf(100, 70), d.percentages)
	}

	@Test
	fun `never more than two transactions`() {
		val d = ScrobbleRules.decide(playedMs = fourMinutes * 5, durationMs = fourMinutes)
		assertEquals(2, d.percentages.size)
	}

	@Test
	fun `exactly 160 percent is the second transaction boundary`() {
		assertEquals(1, ScrobbleRules.decide(383_000, fourMinutes).percentages.size)
		assertEquals(2, ScrobbleRules.decide(384_000, fourMinutes).percentages.size)
	}

	@Test
	fun `ads and other short items are ignored`() {
		// The 6-second "track" observed in PHASE0 run 1.
		val d = ScrobbleRules.decide(playedMs = 6_000, durationMs = 6_061)
		assertFalse(d.shouldScrobble)
		assertTrue(d.skippedBecause!!.contains("under the 30s minimum"))
	}

	@Test
	fun `explicit youtube ad UI vetoes a public long short`() {
		val d = ScrobbleRules.decide(
			playedMs = 180_000,
			durationMs = 180_000,
			isShort = true,
			videoResolved = true,
			videoUnlisted = false,
			explicitAdSignal = "Sponsored",
		)
		assertFalse(d.shouldScrobble)
		assertEquals(
			"YouTube's visible UI marked this track as an ad (\"Sponsored\")",
			d.skippedBecause,
		)
	}

	@Test
	fun `public thirty second watch ad is vetoed by automatic and manual central rules`() {
		fun decide() = ScrobbleRules.decide(
			playedMs = 30_000,
			durationMs = 30_000,
			isShort = false,
			videoResolved = true,
			videoUnlisted = false,
			explicitAdSignal = "Sponsored",
		)
		val automatic = decide()
		val manual = decide()
		assertFalse(automatic.shouldScrobble)
		assertFalse(manual.shouldScrobble)
		assertEquals(automatic.skippedBecause, manual.skippedBecause)
		assertEquals(
			"YouTube's visible UI marked this track as an ad (\"Sponsored\")",
			automatic.skippedBecause,
		)
	}

	@Test
	fun `a verified completed listen does not depend on browser coverage`() {
		fun decide() = ScrobbleRules.decide(
			playedMs = 30_000,
			durationMs = 30_000,
			videoResolved = true,
			videoUnlisted = false,
		)
		val automatic = decide()
		val manual = decide()
		assertTrue(automatic.shouldScrobble)
		assertEquals(automatic, manual)
	}

	@Test
	fun `explicit ad evidence still vetoes an otherwise eligible background listen`() {
		fun decide(signal: String? = null) = ScrobbleRules.decide(
			playedMs = 236_000,
			durationMs = 237_000,
			videoResolved = true,
			videoUnlisted = false,
			explicitAdSignal = signal,
		)

		assertTrue(decide().shouldScrobble)
		assertFalse(decide("Visit Advertiser").shouldScrobble)
	}

	@Test
	fun `identity provenance cannot change progress eligibility`() {
		fun decide() = ScrobbleRules.decide(
			playedMs = 240_000,
			durationMs = 240_000,
			videoResolved = true,
			videoUnlisted = false,
		)
		assertTrue(decide().shouldScrobble)
	}

	@Test
	fun `verified identity cannot bypass threshold or explicit ad gates`() {
		assertFalse(
			ScrobbleRules.decide(
				playedMs = 10_000,
				durationMs = 240_000,
			).shouldScrobble,
		)
		val labelled = ScrobbleRules.decide(
			playedMs = 240_000,
			durationMs = 240_000,
			videoResolved = true,
			videoUnlisted = false,
			explicitAdSignal = "Visit Advertiser",
		)
		assertFalse(labelled.shouldScrobble)
		assertTrue(labelled.skippedBecause!!.contains("marked this track as an ad"))
	}

	@Test
	fun `the same central rule gives automatic and manual paths the same verdict`() {
		fun decide() = ScrobbleRules.decide(
			playedMs = 120_000,
			durationMs = 120_000,
			videoResolved = true,
			videoUnlisted = false,
		)
		assertEquals(decide(), decide())
	}

	// region short-clip floor
	//
	// Every 30s-floor rejection in the 2026-07-29 session was a `/shorts/` URL
	// and none were `/watch`, so the exception is scoped by path. It is granted
	// on proof the video exists rather than on its length — see
	// ScrobbleRules.SHORT_MIN_DURATION_SECONDS.

	private fun short(
		playedMs: Long,
		durationMs: Long,
		resolved: Boolean = true,
		unlisted: Boolean? = false,
	) = ScrobbleRules.decide(
		playedMs = playedMs,
		durationMs = durationMs,
		isShort = true,
		videoResolved = resolved,
		videoUnlisted = unlisted,
	)

	@Test
	fun `a verified short scrobbles below thirty seconds`() {
		// The single most common blocked length in the field session.
		val d = short(playedMs = 10_000, durationMs = 10_000)
		assertEquals(listOf(100), d.percentages)
	}

	@Test
	fun `a verified short still has to clear the threshold`() {
		val d = short(playedMs = 4_000, durationMs = 16_000)
		assertFalse(d.shouldScrobble)
		assertTrue(d.skippedBecause!!.contains("below"))
	}

	/**
	 * The one clip in the field session that a 10-second floor still rejects.
	 * Deliberate: below this the media session's own timings are noise.
	 */
	@Test
	fun `under ten seconds is rejected even for a verified short`() {
		val d = short(playedMs = 8_000, durationMs = 7_000)
		assertFalse(d.shouldScrobble)
		assertTrue(d.skippedBecause!!.contains("10s"))
	}

	/**
	 * The 30-second fallback is gone, and that is the change.
	 *
	 * Holding an unproven Short to the ordinary floor still admitted it — a
	 * 42-second creative cleared 30 seconds and reached the chain in v0.8.7 for
	 * exactly that reason. A Short is now either proven public, in which case it
	 * gets the 10-second floor, or it is refused. There is no third state where
	 * something unproven is scrobbled anyway because it happened to be long.
	 */
	@Test
	fun `an unresolved short is refused rather than held to the ordinary floor`() {
		val d = short(playedMs = 12_000, durationMs = 12_000, resolved = false, unlisted = null)
		assertFalse(d.shouldScrobble)
		assertTrue(d.skippedBecause!!.contains("didn't resolve"))
		assertFalse("the ordinary floor was quoted", d.skippedBecause!!.contains("30s"))
	}

	@Test
	fun `a long unresolved short is refused too`() {
		val d = short(playedMs = 43_000, durationMs = 42_000, resolved = false, unlisted = null)
		assertFalse("length is not proof", d.shouldScrobble)
	}

	/**
	 * The regression, pinned exactly. On 2026-07-29 an 18-second shorts-feed ad
	 * reached the chain: it was served at `m.youtube.com/shorts/CYgQQqvwwsY`, a
	 * genuine `/shorts/` URL, and its watch page resolved with
	 * `category=People & Blogs`. Resolving proved too little; being *listed* is
	 * what separates it from a real short.
	 */
	@Test
	fun `the shorts-feed ad that reached the chain is now rejected`() {
		val d = short(playedMs = 19_000, durationMs = 18_000, unlisted = true)
		assertFalse(d.shouldScrobble)
		assertTrue(d.skippedBecause!!.contains("unlisted"))
		assertTrue(d.skippedBecause!!.contains("feed ad"))
	}

	/**
	 * v0.8.7's exact failure: unlisted only withheld the 10-second exception,
	 * so a 42-second creative cleared the ordinary floor and reached the chain.
	 */
	@Test
	fun `an unlisted short is rejected even above the ordinary floor`() {
		val d = short(playedMs = 43_000, durationMs = 42_000, unlisted = true)
		assertFalse(d.shouldScrobble)
		assertEquals(
			"a short and the video is unlisted — almost certainly a feed ad",
			d.skippedBecause,
		)
	}

	/**
	 * Absence must not read as "public" — that is the direction that lets an ad
	 * through, so if YouTube renames the field the Short is refused instead of
	 * silently re-opening the leak.
	 */
	@Test
	fun `an absent listed flag fails closed the same way unlisted does`() {
		val d = short(playedMs = 19_000, durationMs = 18_000, unlisted = null)
		assertFalse(d.shouldScrobble)
		assertTrue(d.skippedBecause!!.contains("never said whether it's listed"))
	}

	/**
	 * Resolved *and* unlisted-unknown is the contradictory case: the page came
	 * back and did not answer the one question the exception turns on.
	 */
	@Test
	fun `a resolved short with no listed answer is still refused`() {
		val d = short(playedMs = 40_000, durationMs = 39_000, resolved = true, unlisted = null)
		assertFalse(d.shouldScrobble)
	}

	/** The refusals stay distinguishable, because they need different fixes. */
	@Test
	fun `the reason names which guard rejected the clip`() {
		assertTrue(
			short(12_000, 12_000, resolved = false, unlisted = null)
				.skippedBecause!!.contains("resolve"),
		)
		assertTrue(short(12_000, 12_000, unlisted = true).skippedBecause!!.contains("unlisted"))
		assertTrue(
			short(12_000, 12_000, unlisted = null).skippedBecause!!.contains("listed"),
		)
	}

	/** Ordinary `/watch` playback is untouched by any of this. */
	@Test
	fun `an ordinary watch track still needs thirty seconds`() {
		assertFalse(
			ScrobbleRules.decide(
				playedMs = 29_000,
				durationMs = 29_000,
				isShort = false,
				videoResolved = true,
				videoUnlisted = false,
			).shouldScrobble,
		)
		assertTrue(
			ScrobbleRules.decide(
				playedMs = 31_000,
				durationMs = 31_000,
				isShort = false,
				videoResolved = true,
				videoUnlisted = false,
			).shouldScrobble,
		)
	}

	/**
	 * The negative control: nothing about the Shorts rule may reach a track that
	 * was never proven to be one. An unresolved, unlisted-unknown `/watch` track
	 * is the exact input that is refused when it *is* a Short, and it still
	 * scrobbles here.
	 */
	@Test
	fun `an unproven watch track is not swept up by the Shorts refusal`() {
		val d = ScrobbleRules.decide(
			playedMs = 200_000,
			durationMs = 200_000,
			isShort = false,
			videoResolved = false,
			videoUnlisted = null,
		)
		assertTrue("a /watch track was refused by a Shorts guard", d.shouldScrobble)
	}

	@Test
	fun `a watch-path track is never granted the short floor`() {
		val d = ScrobbleRules.decide(
			playedMs = 12_000,
			durationMs = 12_000,
			isShort = false,
			videoResolved = true,
		)
		assertFalse(d.shouldScrobble)
		assertTrue(d.skippedBecause!!.contains("not a verified short"))
	}

	/**
	 * A loop is not a track change, so accumulated progress can be far above
	 * 100%. That must not erase the qualifying first viewing. Video-kind capping
	 * is the mechanism that prevents a second transaction.
	 */
	@Test
	fun `a short left looping keeps one earned video scrobble`() {
		val d = short(playedMs = 600_000, durationMs = 10_000)
		assertTrue(d.shouldScrobble)
		assertTrue(d.probableLoop)
		assertEquals(listOf(100), ScrobbleRules.capForKind(d.percentages, "video"))
		assertEquals(
			listOf(100),
			ScrobbleRules.capForKind(d.percentages, "song", isShort = true),
		)
	}

	@Test
	fun `ordinary end timing overrun is not labelled a loop`() {
		// `played 12s of 10s` — the highest real overrun in the field session.
		val d = short(playedMs = 12_000, durationMs = 10_000)
		assertEquals(listOf(100), d.percentages)
		assertFalse(d.probableLoop)
	}

	/** A genuine full-length double-listen still earns its second transaction. */
	@Test
	fun `full-length tracks remain eligible for a double listen`() {
		val d = ScrobbleRules.decide(playedMs = 480_000, durationMs = fourMinutes)
		assertEquals(2, d.percentages.size)
	}

	@Test
	fun `a detected loop caps even a watch-path song to one transaction`() {
		val d = ScrobbleRules.decide(playedMs = 490_000, durationMs = fourMinutes)
		assertEquals(2, d.percentages.size)
		assertEquals(
			listOf(100),
			ScrobbleRules.capForKind(
				d.percentages,
				kind = "song",
				isShort = false,
				loopDetected = true,
			),
		)
	}
	// endregion

	// region prefilter

	@Test
	fun `prefilter passes a track worth enriching`() {
		assertNull(ScrobbleRules.prefilter(playedMs = 145_000, durationMs = fourMinutes))
	}

	@Test
	fun `prefilter rejects below the threshold without a fetch`() {
		val why = ScrobbleRules.prefilter(playedMs = 100_000, durationMs = fourMinutes)
		assertTrue(why!!.contains("below"))
	}

	@Test
	fun `prefilter rejects anything under the hard floor`() {
		val why = ScrobbleRules.prefilter(playedMs = 9_000, durationMs = 9_000)
		assertTrue(why!!.contains("hard floor"))
	}

	@Test
	fun `explicit ad evidence is reported before progress or duration`() {
		val why = ScrobbleRules.prefilter(
			playedMs = 0,
			durationMs = null,
			explicitAdSignal = "Omitir anuncio",
		)
		assertEquals(
			"YouTube's visible UI marked this track as an ad (\"Omitir anuncio\")",
			why,
		)
	}

	/**
	 * A null duration is worth a lookup — 10 shorts were lost to it in the
	 * field session — but only when enough was played that some admissible
	 * length could clear the threshold. 165 of 198 "no duration" finalizations
	 * had under three seconds of play time and were page-load transitions.
	 */
	@Test
	fun `prefilter allows a lookup when the duration is missing but play time is real`() {
		assertNull(ScrobbleRules.prefilter(playedMs = 9_000, durationMs = null))
	}

	@Test
	fun `prefilter rejects a missing duration with negligible play time`() {
		val why = ScrobbleRules.prefilter(playedMs = 1_000, durationMs = null)
		assertTrue(why!!.contains("too little"))
	}

	@Test
	fun `prefilter refuses a lost progress surface without quoting a percentage`() {
		// The shipped defect (FIELD §3.2): the honest wording was added to
		// decide(), but a sub-threshold Short is rejected here and never reaches
		// decide() at all — so the log went on printing "played 0%, below 60%
		// threshold" for a session that was never measured. This is the common
		// path, and it is the one the field log actually showed.
		val why = ScrobbleRules.prefilter(
			playedMs = 0,
			durationMs = fourMinutes,
			progressSurfaceLost = true,
		)
		assertFalse(why!!.contains("%"))
		assertTrue(why.contains("cannot be measured"))

		// Both stages must word it identically, or the same event reads as two
		// different failures depending on which one caught it.
		assertEquals(
			ScrobbleRules.decide(
				playedMs = 0,
				durationMs = fourMinutes,
				progressSurfaceLost = true,
			).skippedBecause,
			why,
		)
	}

	@Test
	fun `a lost progress surface still cannot rescue a track prefilter would pass`() {
		// The marker only ever changes the wording of a refusal.
		assertNull(
			ScrobbleRules.prefilter(
				playedMs = 145_000,
				durationMs = fourMinutes,
				progressSurfaceLost = true,
			),
		)
		// And an explicit ad signal still outranks it, as in decide().
		assertTrue(
			ScrobbleRules.prefilter(
				playedMs = 0,
				durationMs = fourMinutes,
				explicitAdSignal = "Sponsored",
				progressSurfaceLost = true,
			)!!.contains("marked this track as an ad"),
		)
	}
	// endregion

	@Test
	fun `no duration means no scrobble`() {
		val d = ScrobbleRules.decide(playedMs = 200_000, durationMs = null)
		assertFalse(d.shouldScrobble)
	}

	@Test
	fun `threshold is configurable`() {
		val d = ScrobbleRules.decide(playedMs = 120_000, durationMs = fourMinutes, threshold = 0.5)
		assertEquals(listOf(50), d.percentages)
	}

	/**
	 * The 160% double-listen is for songs. A looping short broadcast the same
	 * clip twice in one block (observed on-chain 2026-07-24, percents 100+76);
	 * videos are watched, not re-listened, so they cap at one transaction.
	 */
	@Test
	fun `double listen applies to songs only`() {
		val double = listOf(100, 76)
		assertEquals(listOf(100, 76), ScrobbleRules.capForKind(double, "song"))
		assertEquals(listOf(100), ScrobbleRules.capForKind(double, "video"))
		assertEquals(listOf(100), ScrobbleRules.capForKind(double, "podcast"))
		// A single-tx decision is untouched either way.
		assertEquals(listOf(84), ScrobbleRules.capForKind(listOf(84), "video"))
	}
}
