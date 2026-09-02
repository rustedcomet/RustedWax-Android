package com.rustedwax.app.replay

import com.rustedwax.app.enrich.VideoFacts
import com.rustedwax.youtube.identity.VideoResolution
import com.rustedwax.youtube.identity.VideoResolutionAttempt
import com.rustedwax.app.replay.ReplayHarness.RefusalKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shorts, in the foreground and in picture-in-picture.
 *
 * Shorts are where the identity contract is under the most pressure: the titles
 * are hashtags, the footer keeps losing its resource ids, YouTube stopped
 * drawing the seekbar, and an ad creative is served at a genuine `/shorts/` URL
 * behind a genuine watch page. Every scenario here is a shape that was measured
 * in the field, and the ones that refuse refuse on purpose.
 */
class ShortsAndPipReplayTest : ReplayScenarioTest() {

	private val handle = "@mrtimeedits"

	private fun shortFacts(
		videoId: String,
		title: String?,
		lengthSeconds: Long?,
		unlisted: Boolean? = false,
		resolved: Boolean = true,
	) = VideoFacts(
		videoId = videoId,
		title = title,
		author = "Mr Time Edits",
		ownerHandle = handle,
		lengthSeconds = lengthSeconds,
		category = "Entertainment",
		watchPageResolved = resolved,
		isUnlisted = unlisted,
	)

	/** History names the candidates; each candidate's own page still has to agree. */
	private fun historyNames(
		harness: ReplayHarness,
		videoId: String,
		title: String?,
		lengthSeconds: Long?,
	) {
		harness.env.watchHistory.hasSession = true
		harness.env.watchHistory.shortIds = { listOf(videoId) }
		harness.env.identity.verifiedCandidates = { ids ->
			if (ids == listOf(videoId)) {
				VideoResolutionAttempt(
					resolution = VideoResolution(
						videoId = videoId,
						source = "watch history",
						title = title,
						channel = "Mr Time Edits",
						lengthSeconds = lengthSeconds,
						uniquelyResolved = true,
						ownerHandle = handle,
						historyVerified = true,
					),
				)
			} else {
				VideoResolutionAttempt(refusalReason = "no candidate corroborated")
			}
		}
	}

	// ---- the ordinary foreground Short ---------------------------------------

	@Test
	fun `a foreground Short resolved from watch history scrobbles at the short floor`() {
		val harness = ReplayHarness(ReplaySource.NATIVE_YOUTUBE)
		harness.env.facts.put(shortFacts("shOrtVideo1", "#hoyoverse", 15))
		historyNames(harness, "shOrtVideo1", "#hoyoverse", 15)

		harness.feed(
			PlaybackEvent.ForegroundShortObserved(
				title = "#hoyoverse",
				ownerHandle = handle,
				durationMs = 15_000,
			),
			PlaybackEvent.SessionMetadata(artist = "Mr Time Edits"),
			PlaybackEvent.PlaybackStateChanged(playing = true),
			PlaybackEvent.Advance(14_000),
			PlaybackEvent.Finalized(),
		)

		assertEquals(1, harness.broadcasts.size)
		assertEquals("shOrtVideo1", harness.broadcasts.single().videoId)
		// 15 s clears the 10 s Shorts floor and would have failed the ordinary
		// 30 s one — the exception exists and is proven public.
		assertEquals(emptyList<RefusalKind>(), harness.refusalKinds)
	}

	@Test
	fun `a Short whose footer showed no title is still identified by handle and length`() {

		val harness = ReplayHarness(ReplaySource.NATIVE_YOUTUBE)
		harness.env.facts.put(shortFacts("shOrtVideo2", "Some Canonical Title", 20))
		historyNames(harness, "shOrtVideo2", "Some Canonical Title", 20)

		harness.feed(
			PlaybackEvent.ForegroundShortObserved(
				title = null,
				ownerHandle = handle,
				durationMs = 20_000,
			),
			PlaybackEvent.PlaybackStateChanged(playing = true),
			PlaybackEvent.Advance(20_000),
			PlaybackEvent.Finalized(),
		)

		assertEquals(1, harness.broadcasts.size)
		val payload = harness.broadcasts.single()
		assertEquals("shOrtVideo2", payload.videoId)
		// The screen never showed a title, so the payload carries the canonical
		// one the resolver proved on that video's own watch page.
		assertEquals("Some Canonical Title", payload.title)
	}

	@Test
	fun `a Short YouTube drew no seekbar for is identified without a length`() {
		val harness = ReplayHarness(ReplaySource.NATIVE_YOUTUBE)
		harness.env.facts.put(shortFacts("shOrtVideo3", "#edit", 18))
		historyNames(harness, "shOrtVideo3", "#edit", 18)

		harness.feed(
			PlaybackEvent.ForegroundShortObserved(
				title = "#edit",
				ownerHandle = handle,
				durationMs = null,
			),
			PlaybackEvent.PlaybackStateChanged(playing = true),
			PlaybackEvent.Advance(30_000),
			PlaybackEvent.Finalized(),
		)

		assertEquals(1, harness.broadcasts.size)
		assertEquals("shOrtVideo3", harness.broadcasts.single().videoId)
	}

	// ---- picture-in-picture --------------------------------------------------

	@Test
	fun `a Short whose progress surface went away says so instead of claiming zero percent`() {

		val harness = ReplayHarness(ReplaySource.NATIVE_YOUTUBE)
		harness.env.facts.put(shortFacts("shOrtVideo4", "#clip", 40))
		historyNames(harness, "shOrtVideo4", "#clip", 40)

		harness.feed(
			PlaybackEvent.ForegroundShortObserved(
				title = "#clip",
				ownerHandle = handle,
				durationMs = 40_000,
			),
			PlaybackEvent.PlaybackStateChanged(playing = true),
			PlaybackEvent.Advance(5_000),
			PlaybackEvent.ProgressSurfaceLost(),
			PlaybackEvent.Advance(30_000),
			PlaybackEvent.Finalized(),
		)

		assertEquals(emptyList<ReplayHarness.BroadcastPayload>(), harness.broadcasts)
		assertEquals(listOf(RefusalKind.PROGRESS_SURFACE_LOST), harness.refusalKinds)
		val reason = harness.refusals.single().reason
		assertFalse("a lost surface must never be reported as 0%", reason.contains("played 0%"))
	}

	@Test
	fun `picture-in-picture inference is credited and named separately from measurement`() {
		val harness = ReplayHarness(ReplaySource.NATIVE_YOUTUBE)
		harness.env.facts.put(shortFacts("shOrtVideo5", "#pip", 60))
		historyNames(harness, "shOrtVideo5", "#pip", 60)

		harness.feed(
			PlaybackEvent.ForegroundShortObserved(
				title = "#pip",
				ownerHandle = handle,
				durationMs = 60_000,
			),
			PlaybackEvent.PlaybackStateChanged(playing = true),
			PlaybackEvent.Advance(10_000),
			// 20 s credited from wall-clock while YouTube held a visible window.
			PlaybackEvent.ProgressSurfaceLost(inferredMs = 20_000),
			PlaybackEvent.Finalized(),
		)

		assertEquals(listOf(RefusalKind.PROGRESS_SURFACE_LOST), harness.refusalKinds)
		val reason = harness.refusals.single().reason
		assertTrue("both numbers belong in the record", reason.contains("inferred"))
		assertEquals(30L, harness.refusals.single().playedSeconds)
	}

	@Test
	fun `the field emoji Short survives a complete picture-in-picture finalization`() {
		val emojiTitle = "🥰❤️"
		val emojiHandle = "@emoções_videos"
		val harness = ReplayHarness(ReplaySource.NATIVE_YOUTUBE)
		harness.env.facts.put(
			shortFacts("-8yHg3sb55I", emojiTitle, 60).copy(ownerHandle = emojiHandle),
		)
		harness.env.watchHistory.hasSession = true
		harness.env.watchHistory.shortIds = { listOf("-8yHg3sb55I") }
		harness.env.identity.verifiedCandidates = {
			VideoResolutionAttempt(
				resolution = VideoResolution(
					videoId = "-8yHg3sb55I",
					source = "watch history",
					title = emojiTitle,
					channel = "Emoções Vídeos",
					lengthSeconds = 60,
					uniquelyResolved = true,
					ownerHandle = emojiHandle,
					historyVerified = true,
				),
			)
		}

		harness.feed(
			PlaybackEvent.ForegroundShortObserved(
				title = emojiTitle,
				ownerHandle = emojiHandle,
				durationMs = 60_000,
			),
			PlaybackEvent.ProgressSurfaceLost(inferredMs = 60_000),
			PlaybackEvent.Finalized(),
		)

		assertEquals(1, harness.broadcasts.size)
		assertEquals("-8yHg3sb55I", harness.broadcasts.single().videoId)
	}

	// ---- the ad guard --------------------------------------------------------

	@Test
	fun `an unlisted Short is refused as a feed ad however long it ran`() {
		// v0.8.7 let a 42-second unlisted Shorts creative clear the ordinary
		// 30-second floor and reach the chain. Unlisted is the structural
		// signal — an ad creative is unlisted by construction.
		val harness = ReplayHarness(ReplaySource.NATIVE_YOUTUBE)
		harness.env.facts.put(shortFacts("adCreative1", "Blurry: Formula única", 42, unlisted = true))
		historyNames(harness, "adCreative1", "Blurry: Formula única", 42)

		harness.feed(
			PlaybackEvent.ForegroundShortObserved(
				title = "Blurry: Formula única",
				ownerHandle = handle,
				durationMs = 42_000,
			),
			PlaybackEvent.PlaybackStateChanged(playing = true),
			PlaybackEvent.Advance(42_000),
			PlaybackEvent.Finalized(),
		)

		assertEquals(emptyList<ReplayHarness.BroadcastPayload>(), harness.broadcasts)
		assertEquals(listOf(RefusalKind.UNLISTED_SHORT), harness.refusalKinds)
	}

	@Test
	fun `a foreground Short whose enrichment failed fails closed on identity`() {
		// Characterization, and the behaviour is correct: the foreground-Short
		// route's whole gate is the owner handle agreeing on the candidate page
		// *and* on the final fetch. When that final fetch cannot be made there is
		// no handle evidence at all, so the listen refuses on identity rather
		// than falling through to a length rule that was never the question.
		//
		// Worth pinning precisely because it is easy to "fix" by accident: making
		// the missing fetch absence-of-evidence here would let a Short broadcast
		// on one page's say-so, which is the contract `Documentation/Product/IDENTITY.md` forbids.
		val harness = ReplayHarness(ReplaySource.NATIVE_YOUTUBE)
		harness.env.facts.failures += "shOrtVideo6"
		historyNames(harness, "shOrtVideo6", "#temporary", 15)

		harness.feed(
			PlaybackEvent.ForegroundShortObserved(
				title = "#temporary",
				ownerHandle = handle,
				durationMs = 15_000,
			),
			PlaybackEvent.PlaybackStateChanged(playing = true),
			PlaybackEvent.Advance(15_000),
			PlaybackEvent.Finalized(),
		)

		assertEquals(emptyList<ReplayHarness.BroadcastPayload>(), harness.broadcasts)
		assertEquals(listOf(RefusalKind.IDENTITY_CONTRADICTION), harness.terminalRefusalKinds)
		assertTrue(
			harness.terminalRefusalReasons.single().contains("watch-page handle evidence was unavailable"),
		)
		assertEquals(listOf(RefusalKind.IDENTITY_CONTRADICTION), harness.unlinkedRefusalKinds)
	}

	@Test
	fun `a browser Short whose watch page did not resolve is refused outright`() {
		// It used to be held to the ordinary 30 seconds, which looked
		// conservative and was not: 30 seconds admits anything unproven that
		// happens to run long, and a 42-second shorts creative does. Decision D8
		// keeps enrichment non-blocking, so roughly one in eight legitimate
		// short clips is now refused when the fetch fails. That is the direction
		// to fail in: a missed entry can be earned again, a false one is
		// permanent — and the clip below is 15 seconds, so under the old rule it
		// was refused anyway. What changed is the reason, and that a 40-second
		// one is now refused too.
		val harness = ReplayHarness(ReplaySource.BRAVE)
		harness.env.facts.failures += "braveShort2"

		harness.feed(
			PlaybackEvent.NotificationObserved(host = "youtube.com"),
			PlaybackEvent.UrlObserved(host = "m.youtube.com", videoId = "braveShort2", isShort = true),
			PlaybackEvent.SessionMetadata(
				title = "#temporary",
				artist = "Mr Time Edits",
				durationMs = 15_000,
			),
			PlaybackEvent.PlaybackStateChanged(playing = true),
			PlaybackEvent.Advance(15_000),
			PlaybackEvent.Finalized(),
		)

		assertEquals(emptyList<ReplayHarness.BroadcastPayload>(), harness.broadcasts)
		assertEquals(listOf(RefusalKind.UNPROVEN_SHORT), harness.refusalKinds)
		assertTrue(
			"the record must name what could not be proven",
			harness.refusals.single().reason.contains("watch page didn't resolve"),
		)
	}

	/**
	 * The half of the change that is not merely a reworded refusal: a Short long
	 * enough to clear the ordinary floor used to scrobble on length alone when
	 * its page never resolved. That is the shape of the v0.8.7 leak.
	 */
	@Test
	fun `a long browser Short whose watch page did not resolve no longer scrobbles on length`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)
		harness.env.facts.failures += "braveShort3"

		harness.feed(
			PlaybackEvent.NotificationObserved(host = "youtube.com"),
			PlaybackEvent.UrlObserved(host = "m.youtube.com", videoId = "braveShort3", isShort = true),
			PlaybackEvent.SessionMetadata(
				title = "#temporary",
				artist = "Mr Time Edits",
				durationMs = 42_000,
			),
			PlaybackEvent.PlaybackStateChanged(playing = true),
			PlaybackEvent.Advance(42_000),
			PlaybackEvent.Finalized(),
		)

		assertEquals(emptyList<ReplayHarness.BroadcastPayload>(), harness.broadcasts)
		assertEquals(listOf(RefusalKind.UNPROVEN_SHORT), harness.refusalKinds)
	}

	// ---- the user's own switch -----------------------------------------------

	@Test
	fun `Shorts turned off refuses before anything is measured or fetched`() {
		val harness = ReplayHarness(
			ReplaySource.NATIVE_YOUTUBE,
			ReplayEnvironment(policy = ReplayPolicy(disableShorts = true)),
		)
		historyNames(harness, "shOrtVideo7", "#anything", 30)

		harness.feed(
			PlaybackEvent.ForegroundShortObserved(
				title = "#anything",
				ownerHandle = handle,
				durationMs = 30_000,
			),
			PlaybackEvent.PlaybackStateChanged(playing = true),
			PlaybackEvent.Advance(30_000),
			PlaybackEvent.Finalized(),
		)

		assertEquals(listOf(RefusalKind.SHORTS_DISABLED), harness.terminalRefusalKinds)
		assertEquals(listOf(RefusalKind.SHORTS_DISABLED), harness.unlinkedRefusalKinds)
		assertEquals(emptyList<String>(), harness.env.facts.resolved)
		assertEquals(emptyList<ReplayIdentitySource.Route>(), harness.identityRoutes)
	}

	// ---- the loop cap ---------------------------------------------------------

	@Test
	fun `a Short watched past its own end still earns exactly one transaction`() {

		val harness = ReplayHarness(ReplaySource.NATIVE_YOUTUBE)
		harness.env.facts.put(shortFacts("loopyShort1", "#loop", 20))
		historyNames(harness, "loopyShort1", "#loop", 20)

		harness.feed(
			PlaybackEvent.ForegroundShortObserved(
				title = "#loop",
				ownerHandle = handle,
				durationMs = 20_000,
			),
			PlaybackEvent.PlaybackStateChanged(playing = true),
			PlaybackEvent.Advance(20_000),
			PlaybackEvent.LoopObserved,
			PlaybackEvent.Advance(18_000),
			PlaybackEvent.Finalized(),
		)

		assertEquals(1, harness.broadcasts.size)
		assertEquals(1, harness.transactionIds.size)
	}

	// ---- the browser Short ---------------------------------------------------

	@Test
	fun `a browser Short proven by a shorts URL uses the short floor`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)
		harness.env.facts.put(shortFacts("braveShort1", "#hoyoverse", 15))

		harness.feed(
			PlaybackEvent.NotificationObserved(host = "youtube.com"),
			PlaybackEvent.UrlObserved(
				host = "m.youtube.com",
				videoId = "braveShort1",
				isShort = true,
			),
			PlaybackEvent.SessionMetadata(
				title = "#hoyoverse",
				artist = "Mr Time Edits",
				durationMs = 15_000,
			),
			PlaybackEvent.PlaybackStateChanged(playing = true),
			PlaybackEvent.Advance(14_000),
			PlaybackEvent.Finalized(),
		)

		assertEquals(1, harness.broadcasts.size)
		assertEquals("braveShort1", harness.broadcasts.single().videoId)
	}
}
