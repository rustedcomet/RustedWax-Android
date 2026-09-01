package com.rustedwax.app.replay

import com.rustedwax.app.detect.FinalizedTrack
import com.rustedwax.app.enrich.VideoFacts
import com.rustedwax.app.scrobble.FinalizationOutcome
import com.rustedwax.youtube.identity.VideoResolution
import com.rustedwax.youtube.identity.VideoResolutionAttempt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Phase 2 parity gate: old transport object versus new domain decomposition.
 *
 * ## Why this file exists, and what it is careful not to claim
 *
 * `<redacted-private-path>` was amended on 2026-08-12 to split the shadow-parity
 * gate in two. Phase 1 owed reference capture and comparison *infrastructure*;
 * Phase 2 onward owes **actual old/new output parity, once a replacement
 * implementation exists to produce a second output**. The amendment is blunt
 * about the failure mode it exists to prevent: running one implementation
 * through two broadcasters "tests the comparator, not parity, and calling it
 * parity would be the most flattering possible misreading of a green suite."
 *
 * So this file is scoped to exactly what Phase 2 replaced. Phase 2 introduced no
 * second reducer and no second resolver; it introduced a second **transport
 * object** — `FinalizedTrack` and its adapter. The two sides here therefore
 * differ in one specific, real way:
 *
 * | Side | What the engine is handed |
 * |---|---|
 * | old / reference | the `SessionSnapshot` the probe froze, unchanged |
 * | new / shadow | that listen decomposed into `FinalizedTrack` and rebuilt |
 *
 * The new side's object is constructed by a genuinely separate body of code
 * (`FinalizedTrack.from` and `toSessionSnapshot`, ~40 assignments across six
 * types). A field dropped, defaulted, swapped into a neighbouring slot, or
 * silently recomputed produces a different payload byte string or a different
 * terminal outcome, and every assertion below is on one of those two.
 *
 * What this is **not**: proof that a future reducer measures the same
 * milliseconds. That comparison has nothing to compare until Phase 3, and
 * `<redacted-private-path>` says so in the same words.
 *
 * ## Shadow-only, still
 *
 * The new side runs through `FinalizationRuntime.finalizeInShadow` throughout, so the
 * replacement path cannot broadcast, cannot queue and cannot retain a dedup
 * claim while parity is being established — the audit's *"the replacement
 * remains shadow-only and must not broadcast until parity is established"*. It
 * is a boundary the run never crosses rather than a broadcaster that declines;
 * `ShadowBoundaryReplayTest` is where that distinction is proven. The
 * no-side-effects half is asserted here too rather than assumed, because a
 * presentation change is exactly the kind of edit that could route around it.
 */
class DomainParityReplayTest : ReplayScenarioTest() {

	// ---- the two sides --------------------------------------------------------

	/** Everything a comparison is allowed to look at. Bytes and typed outcomes only. */
	private data class Output(
		val payloads: List<String>,
		val outcomeKinds: List<String>,
		val refusalReasons: List<String>,
		val refusalKinds: List<ReplayHarness.RefusalKind>,
	)

	private fun outputOf(harness: ReplayHarness) = Output(
		// The payload JSON as the engine built it, taken from the terminal outcome
		// rather than from whatever a broadcaster recorded — a shadow run reaches
		// no broadcaster at all, and reading the decision is closer to the thing
		// being compared than reading a side effect of it would be. Not a parsed
		// view: a comparison that re-read the fields it cared about would agree
		// with itself about a field neither side read.
		payloads = harness.outcomes
			.mapNotNull { it.outcome as? FinalizationOutcome.Eligible }
			.flatMap { eligible -> eligible.payloads.map { it.toJson() } },
		outcomeKinds = harness.outcomeKinds,
		// Also from the outcome rather than from the Not-logged list. The list is a
		// *user-visible effect* of a refusal, and a shadow run deliberately does not
		// write one — reading it here would compare the reference's effects against
		// the shadow's decisions and call the difference a parity failure.
		refusalReasons = harness.outcomes
			.mapNotNull { (it.outcome as? FinalizationOutcome.Refused)?.reason },
		refusalKinds = harness.outcomes
			.mapNotNull { (it.outcome as? FinalizationOutcome.Refused)?.reason }
			.map { ReplayHarness.classify(it) },
	)

	/** The reference run: the snapshot the probe froze, handed over unchanged. */
	private fun reference(
		source: ReplaySource,
		configure: (ReplayHarness) -> Unit = {},
		events: (ReplayHarness) -> List<PlaybackEvent>,
	): Output {
		val harness = ReplayHarness(source)
		configure(harness)
		harness.feed(events(harness))
		return outputOf(harness)
	}

	/** The replacement run: the same listen, through the Phase 2 decomposition. */
	private fun domainShadow(
		source: ReplaySource,
		configure: (ReplayHarness) -> Unit = {},
		events: (ReplayHarness) -> List<PlaybackEvent>,
	): Pair<Output, ReplayEnvironment> {
		val env = ReplayEnvironment()
		val harness = ReplayHarness(
			source,
			env,
			presentation = ReplayHarness.Presentation.DOMAIN,
		)
		harness.shadow = true
		configure(harness)
		harness.feed(events(harness))
		return outputOf(harness) to env
	}

	/**
	 * Run one scenario both ways and require byte-identical output.
	 *
	 * Also asserts the shadow side left nothing behind, so a scenario cannot pass
	 * parity by having quietly become a live run.
	 */
	private fun assertParity(
		name: String,
		source: ReplaySource,
		configure: (ReplayHarness) -> Unit = {},
		events: (ReplayHarness) -> List<PlaybackEvent>,
	): Output {
		val old = reference(source, configure, events)
		val (new, env) = domainShadow(source, configure, events)

		assertEquals("$name: payload bytes", old.payloads, new.payloads)
		assertEquals("$name: terminal outcomes", old.outcomeKinds, new.outcomeKinds)
		assertEquals("$name: refusal reasons", old.refusalReasons, new.refusalReasons)
		assertEquals("$name: refusal kinds", old.refusalKinds, new.refusalKinds)

		// Shadow-only, checked on every scenario rather than once.
		assertEquals("$name: shadow claimed the ledger", emptyList<String>(), env.claims.claimed)
		assertEquals("$name: shadow queued a retry", 0, env.retryQueue.size())
		assertEquals(
			"$name: shadow reached the real broadcaster",
			emptyList<RecordingBroadcaster.Sent>(),
			env.broadcaster.sent,
		)
		return old
	}

	// ---- fixtures -------------------------------------------------------------

	private fun rickAstley() = VideoFacts(
		videoId = "dQw4w9WgXcQ",
		title = "Rick Astley - Never Gonna Give You Up",
		author = "RickAstleyVEVO",
		lengthSeconds = 213,
		category = "Music",
		watchPageResolved = true,
		isUnlisted = false,
	)

	// ---- scenario parity ------------------------------------------------------

	@Test
	fun `a browser listen that scrobbles produces identical payload bytes on both sides`() {
		val output = assertParity(
			"browser scrobble",
			ReplaySource.BRAVE,
			configure = { it.env.facts.put(rickAstley()) },
		) {
			listOf(
				PlaybackEvent.NotificationObserved(host = "youtube.com"),
				PlaybackEvent.UrlObserved(host = "www.youtube.com", videoId = "dQw4w9WgXcQ"),
				PlaybackEvent.SessionMetadata(
					title = "Rick Astley - Never Gonna Give You Up",
					artist = "RickAstleyVEVO",
					durationMs = 213_000,
				),
				PlaybackEvent.PlaybackStateChanged(playing = true),
				PlaybackEvent.Advance(200_000),
				PlaybackEvent.Finalized(),
			)
		}

		// The comparison has to be over something. A scenario that refused on both
		// sides would satisfy every assertion above while proving nothing about the
		// fields a payload is built from.
		assertEquals(listOf("Eligible"), output.outcomeKinds)
		assertEquals(1, output.payloads.size)
		assertTrue(output.payloads.single().contains("dQw4w9WgXcQ"))
	}

	@Test
	fun `a native listen carrying an exact MediaSession id agrees on both sides`() {
		val output = assertParity(
			"native scrobble",
			ReplaySource.NATIVE_YOUTUBE,
			configure = { harness ->
				harness.env.facts.put(rickAstley())
				harness.env.identity.search = {
					VideoResolutionAttempt(
						resolution = VideoResolution(
							videoId = "dQw4w9WgXcQ",
							source = "parity fixture",
							title = "Rick Astley - Never Gonna Give You Up",
							channel = "RickAstleyVEVO",
							lengthSeconds = 213,
							uniquelyResolved = true,
						),
					)
				}
			},
		) {
			listOf(
				PlaybackEvent.SessionMetadata(
					title = "Rick Astley - Never Gonna Give You Up",
					artist = "RickAstleyVEVO",
					durationMs = 213_000,
					mediaId = "dQw4w9WgXcQ",
				),
				PlaybackEvent.PlaybackStateChanged(playing = true),
				PlaybackEvent.Advance(200_000),
				PlaybackEvent.Finalized(),
			)
		}

		assertEquals(listOf("Eligible"), output.outcomeKinds)
		assertEquals(1, output.payloads.size)
	}

	@Test
	fun `YouTube Music agrees on both sides, including the artist capability`() {
		// The one source capability with a decided behavioural effect today
		// (`<redacted-private-path>` §3.2). It is derived by `SourceDescriptor` on the
		// new side and by `SourceProfile` on the old, so a wrong answer changes the
		// `artist` field of the payload rather than merely a flag.
		val output = assertParity(
			"YouTube Music",
			ReplaySource.NATIVE_YOUTUBE_MUSIC,
			configure = { harness ->
				harness.env.facts.put(
					VideoFacts(
						videoId = "dQw4w9WgXcQ",
						title = "Never Gonna Give You Up",
						author = "Rick Astley",
						lengthSeconds = 213,
						category = "Music",
						watchPageResolved = true,
						isUnlisted = false,
					),
				)
			},
		) {
			listOf(
				PlaybackEvent.SessionMetadata(
					title = "Never Gonna Give You Up",
					artist = "Rick Astley",
					album = "Whenever You Need Somebody",
					durationMs = 213_000,
					mediaId = "dQw4w9WgXcQ",
				),
				PlaybackEvent.PlaybackStateChanged(playing = true),
				PlaybackEvent.Advance(200_000),
				PlaybackEvent.Finalized(),
			)
		}

		assertEquals(listOf("Eligible"), output.outcomeKinds)
		assertTrue(output.payloads.single().contains("Rick Astley"))
	}

	@Test
	fun `an explicit ad refusal agrees on both sides, reason text included`() {
		val output = assertParity(
			"explicit ad",
			ReplaySource.BRAVE,
			configure = { it.env.facts.put(rickAstley()) },
		) {
			listOf(
				PlaybackEvent.NotificationObserved(host = "youtube.com"),
				PlaybackEvent.UrlObserved(host = "www.youtube.com", videoId = "dQw4w9WgXcQ"),
				PlaybackEvent.SessionMetadata(
					title = "Rick Astley - Never Gonna Give You Up",
					artist = "RickAstleyVEVO",
					durationMs = 213_000,
				),
				PlaybackEvent.AdLabelObserved("Sponsored · Ad"),
				PlaybackEvent.PlaybackStateChanged(playing = true),
				PlaybackEvent.Advance(200_000),
				PlaybackEvent.Finalized(),
			)
		}

		assertEquals(listOf("Refused"), output.outcomeKinds)
		assertEquals(listOf(ReplayHarness.RefusalKind.EXPLICIT_AD), output.refusalKinds)
		assertEquals(emptyList<String>(), output.payloads)
	}

	@Test
	fun `a below-threshold refusal agrees on both sides`() {
		// Measurement fields specifically: `playedMs`, `durationMs` and
		// `percentPlayed` all travel through `PlaybackMeasurement`, and the refusal
		// prose quotes the numbers back, so a unit or rounding change shows up in
		// the reason string rather than only in a boolean.
		val output = assertParity(
			"below threshold",
			ReplaySource.BRAVE,
			configure = { it.env.facts.put(rickAstley()) },
		) {
			listOf(
				PlaybackEvent.NotificationObserved(host = "youtube.com"),
				PlaybackEvent.UrlObserved(host = "www.youtube.com", videoId = "dQw4w9WgXcQ"),
				PlaybackEvent.SessionMetadata(
					title = "Rick Astley - Never Gonna Give You Up",
					artist = "RickAstleyVEVO",
					durationMs = 213_000,
				),
				PlaybackEvent.PlaybackStateChanged(playing = true),
				PlaybackEvent.Advance(60_000),
				PlaybackEvent.Finalized(),
			)
		}

		assertEquals(listOf("Refused"), output.outcomeKinds)
		assertEquals(listOf(ReplayHarness.RefusalKind.BELOW_THRESHOLD), output.refusalKinds)
	}

	@Test
	fun `a proven non-YouTube session agrees on both sides`() {
		val output = assertParity("not YouTube", ReplaySource.BRAVE) {
			listOf(
				PlaybackEvent.NotificationObserved(host = "w3schools.com"),
				PlaybackEvent.SessionMetadata(title = "mov_bbb.mp4", durationMs = 60_000),
				PlaybackEvent.PlaybackStateChanged(playing = true),
				PlaybackEvent.Advance(50_000),
				PlaybackEvent.Finalized(),
			)
		}

		assertEquals(listOf("Refused"), output.outcomeKinds)
		assertEquals(listOf(ReplayHarness.RefusalKind.NOT_YOUTUBE), output.refusalKinds)

		// §4.1 keeps an unproven session out of the Not-logged list entirely: the
		// refusal is a typed outcome with **no product-visible row**, because
		// listing the title of a page someone watched somewhere else is the
		// disclosure the event log stopped making. Asserted separately from parity
		// now that the comparison reads decisions rather than their side effects —
		// expecting a row here would be asserting a privacy regression.
		val live = ReplayHarness(ReplaySource.BRAVE)
		live.feed(
			listOf(
				PlaybackEvent.NotificationObserved(host = "w3schools.com"),
				PlaybackEvent.SessionMetadata(title = "mov_bbb.mp4", durationMs = 60_000),
				PlaybackEvent.PlaybackStateChanged(playing = true),
				PlaybackEvent.Advance(50_000),
				PlaybackEvent.Finalized(),
			),
		)
		assertEquals(listOf("Refused"), live.outcomeKinds)
		assertEquals(emptyList<ReplayHarness.RefusalKind>(), live.refusalKinds)
	}

	@Test
	fun `a foreground Short agrees on both sides, including the owner handle route`() {
		// `ownerHandle` and `sourceProof` are the two fields the foreground-Short
		// route turns on, and both travel through `IdentityEvidence`. A snapshot
		// rebuilt without them would silently be judged as an ordinary video.
		val output = assertParity(
			"foreground Short",
			ReplaySource.NATIVE_YOUTUBE,
			configure = { harness ->
				harness.env.facts.put(
					VideoFacts(
						videoId = "dQw4w9WgXcQ",
						title = "a Short",
						author = "Someone",
						ownerHandle = "@someone",
						lengthSeconds = 15,
						category = "Music",
						watchPageResolved = true,
						isUnlisted = false,
					),
				)
				harness.env.watchHistory.hasSession = true
				harness.env.watchHistory.shortIds = { listOf("dQw4w9WgXcQ") }
				harness.env.identity.verifiedCandidates = {
					VideoResolutionAttempt(
						resolution = VideoResolution(
							videoId = "dQw4w9WgXcQ",
							source = "parity fixture",
							title = "a Short",
							channel = "Someone",
							lengthSeconds = 15,
							uniquelyResolved = true,
							ownerHandle = "@someone",
						),
					)
				}
			},
		) {
			listOf(
				PlaybackEvent.SessionMetadata(title = "a Short", durationMs = 15_000),
				PlaybackEvent.ForegroundShortObserved(
					title = "a Short",
					ownerHandle = "@someone",
					durationMs = 15_000,
				),
				PlaybackEvent.PlaybackStateChanged(playing = true),
				PlaybackEvent.Advance(14_000),
				PlaybackEvent.Finalized(),
			)
		}

		// Whatever the engine decides, both sides must decide it identically — and
		// the scenario must actually reach a decision rather than a silent boundary.
		assertEquals(1, output.outcomeKinds.size)
		assertNotEquals("Ignored", output.outcomeKinds.single())
	}

	@Test
	fun `a listen carried across MediaSession recreation agrees on both sides`() {
		// The instance token is the field Phase 2 added, and this is the sequence
		// that carries it: one listen, two transports, one frozen start. If the
		// adapter dropped the token the rebuilt snapshot would fall back to the
		// start-second key and this scenario is where that would first bite.
		val output = assertParity(
			"session recreation",
			ReplaySource.BRAVE,
			configure = { it.env.facts.put(rickAstley()) },
		) {
			listOf(
				PlaybackEvent.NotificationObserved(host = "youtube.com"),
				PlaybackEvent.UrlObserved(host = "www.youtube.com", videoId = "dQw4w9WgXcQ"),
				PlaybackEvent.SessionMetadata(
					title = "Rick Astley - Never Gonna Give You Up",
					artist = "RickAstleyVEVO",
					durationMs = 213_000,
				),
				PlaybackEvent.PlaybackStateChanged(playing = true),
				PlaybackEvent.Advance(100_000),
				PlaybackEvent.SessionRecreated,
				PlaybackEvent.PlaybackStateChanged(playing = true),
				PlaybackEvent.Advance(100_000),
				PlaybackEvent.Finalized(),
			)
		}

		assertEquals(listOf("Eligible"), output.outcomeKinds)
		assertEquals(1, output.payloads.size)
	}

	@Test
	fun `two consecutive listens agree on both sides, in order`() {
		val output = assertParity(
			"consecutive listens",
			ReplaySource.BRAVE,
			configure = { harness ->
				harness.env.facts.put(rickAstley())
				harness.env.facts.put(
					VideoFacts(
						videoId = "7i_2TJv96Wk",
						title = "Apple - Tim Meets Max at Apple Park",
						author = "Apple",
						lengthSeconds = 31,
						category = "Entertainment",
						watchPageResolved = true,
						isUnlisted = false,
					),
				)
			},
		) {
			listOf(
				PlaybackEvent.NotificationObserved(host = "youtube.com"),
				PlaybackEvent.UrlObserved(host = "www.youtube.com", videoId = "dQw4w9WgXcQ"),
				PlaybackEvent.SessionMetadata(
					title = "Rick Astley - Never Gonna Give You Up",
					artist = "RickAstleyVEVO",
					durationMs = 213_000,
				),
				PlaybackEvent.PlaybackStateChanged(playing = true),
				PlaybackEvent.Advance(200_000),
				PlaybackEvent.Finalized(),
				PlaybackEvent.UrlObserved(host = "www.youtube.com", videoId = "7i_2TJv96Wk"),
				PlaybackEvent.SessionMetadata(
					title = "Apple - Tim Meets Max at Apple Park",
					artist = "Apple",
					durationMs = 31_000,
				),
				PlaybackEvent.PlaybackStateChanged(playing = true),
				PlaybackEvent.Advance(30_000),
				PlaybackEvent.Finalized(),
			)
		}

		assertEquals(listOf("Eligible", "Eligible"), output.outcomeKinds)
		assertEquals(2, output.payloads.size)
	}

	@Test
	fun `the duplicate gate agrees on both sides when one snapshot arrives twice`() {
		// The same immutable snapshot presented a second time — what a
		// `MediaController` teardown racing a rebuild produces. Both sides must
		// reach the ledger with the same dedup key, which is built from the title,
		// the artist and the frozen start, all three of which cross the adapter.
		//
		// **This is the one scenario that cannot use the shadow boundary**, and the
		// reason is a property worth stating rather than working around: a shadow
		// run takes the dedup claim and releases it at once, precisely so it can
		// never suppress a later live run. A ledger that holds nothing afterwards
		// cannot refuse the second presentation, so a shadow duplicate reports
		// `Eligible` twice by construction — which says something true about the
		// boundary and nothing at all about the adapter.
		//
		// So both sides run live here. "Live" still means `RecordingBroadcaster`
		// inside a JVM test: no network exists in this module, and no bytes reach a
		// chain on either side.
		val events = listOf(
			PlaybackEvent.NotificationObserved(host = "youtube.com"),
			PlaybackEvent.UrlObserved(host = "www.youtube.com", videoId = "dQw4w9WgXcQ"),
			PlaybackEvent.SessionMetadata(
				title = "Rick Astley - Never Gonna Give You Up",
				artist = "RickAstleyVEVO",
				durationMs = 213_000,
			),
			PlaybackEvent.PlaybackStateChanged(playing = true),
			PlaybackEvent.Advance(200_000),
			PlaybackEvent.Finalized(),
		)

		fun run(presentation: ReplayHarness.Presentation): Output {
			val harness = ReplayHarness(ReplaySource.BRAVE, presentation = presentation)
			harness.env.facts.put(rickAstley())
			harness.feed(events).refinalizeLastListen()
			// Read before the next harness exists: `refusals` and `transactionIds`
			// come off `FinalizationRuntime`'s own state flows, which the next harness's
			// reset clears.
			return outputOf(harness).also {
				assertEquals("one duplicate attempt", 1, harness.duplicateAttempts.size)
			}
		}

		val old = run(ReplayHarness.Presentation.REFERENCE)
		val new = run(ReplayHarness.Presentation.DOMAIN)

		assertEquals(listOf("Eligible", "Refused"), old.outcomeKinds)
		assertEquals(listOf(ReplayHarness.RefusalKind.ALREADY_SCROBBLED), old.refusalKinds)
		assertEquals(old, new)
	}

	// ---- corpus parity --------------------------------------------------------

	@Test
	fun `every record in the field corpus produces identical output on both sides`() {
		// The scenarios above are chosen; this one is not. Replaying all 1,414
		// imported field finalizations both ways is what makes the parity claim a
		// statement about the adapter rather than about the eight cases someone
		// thought to write down.
		//
		// Refusals dominate the corpus, and that is the point: a refusal's reason
		// string quotes the played milliseconds, the duration and the category back
		// to the user, so it is a far more sensitive comparison of the measurement
		// fields than a boolean would be.
		val corpus = FieldCorpus.records
		assertTrue("corpus should be the full retained run", corpus.size > 1_400)

		val differences = mutableListOf<String>()
		for (record in corpus) {
			val source = FieldCorpus.sourceFor(record.packageName)
			val events = FieldCorpus.eventsFor(record, source)

			val referenceHarness = ReplayHarness(source)
			referenceHarness.feed(events)
			val old = outputOf(referenceHarness)

			val shadowEnv = ReplayEnvironment()
			val shadowHarness = ReplayHarness(
				source,
				shadowEnv,
				presentation = ReplayHarness.Presentation.DOMAIN,
			)
			shadowHarness.shadow = true
			shadowHarness.feed(events)
			val new = outputOf(shadowHarness)

			if (old != new) differences += "#${record.index}: $old vs $new"
			if (shadowEnv.claims.claimed.isNotEmpty()) {
				differences += "#${record.index}: the shadow run claimed the ledger"
			}
		}

		assertEquals(
			"the decomposition must change no output for any field record",
			emptyList<String>(),
			differences,
		)
	}

	// ---- the comparison itself ------------------------------------------------

	@Test
	fun `the comparison can fail — a deliberately corrupted decomposition is caught`() {
		// A parity test that cannot fail is a green light wired to nothing. This
		// runs the same scenario through a decomposition with one field
		// deliberately wrong, and requires the comparison to notice.
		//
		// `durationMs` is chosen because it is the least conspicuous kind of
		// mistake: the payload still builds, the listen still resolves, and only
		// the arithmetic moves.
		val events = listOf(
			PlaybackEvent.NotificationObserved(host = "youtube.com"),
			PlaybackEvent.UrlObserved(host = "www.youtube.com", videoId = "dQw4w9WgXcQ"),
			PlaybackEvent.SessionMetadata(
				title = "Rick Astley - Never Gonna Give You Up",
				artist = "RickAstleyVEVO",
				durationMs = 213_000,
			),
			PlaybackEvent.PlaybackStateChanged(playing = true),
			PlaybackEvent.Advance(200_000),
			PlaybackEvent.Finalized(),
		)

		val referenceHarness = ReplayHarness(ReplaySource.BRAVE)
		referenceHarness.env.facts.put(rickAstley())
		referenceHarness.feed(events)
		val old = outputOf(referenceHarness)

		// The corruption: the same listen, decomposed, rebuilt, and then given a
		// duration a broken adapter might have produced by reading the wrong field.
		val corrupted = FinalizedTrack.from(referenceHarness.finalized.single()).let { track ->
			track.copy(
				measurement = track.measurement.copy(
					durationMs = 400_000,
					percentPlayed = 200_000.0 / 400_000,
				),
			).toSessionSnapshot()
		}

		val corruptedHarness = ReplayHarness(ReplaySource.BRAVE)
		corruptedHarness.shadow = true
		corruptedHarness.env.facts.put(rickAstley())
		corruptedHarness.finalizeDirectly(corrupted)

		assertEquals("the control must still reach a decision", 1, corruptedHarness.outcomes.size)
		assertNotEquals(
			"a wrong duration must change the compared output",
			old.payloads,
			outputOf(corruptedHarness).payloads,
		)
	}
}
