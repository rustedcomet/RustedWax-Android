package com.rustedwax.app.replay

import com.rustedwax.app.enrich.VideoFacts
import com.rustedwax.youtube.identity.VideoResolution
import com.rustedwax.youtube.identity.VideoResolutionAttempt
import com.rustedwax.app.enrich.WatchHistoryHealth
import com.rustedwax.app.enrich.WatchHistoryParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchHistoryBreakerReplayTest : ReplayScenarioTest() {

	private val handle = "@mrtimeedits"

	private fun harness(): ReplayHarness {
		val harness = ReplayHarness(ReplaySource.NATIVE_YOUTUBE)
		harness.env.watchHistory.hasSession = true
		harness.env.watchHistory.nowMillis = { harness.env.clock.nowMillis() }
		return harness
	}

	private fun shortFacts(videoId: String, title: String?, lengthSeconds: Long?) = VideoFacts(
		videoId = videoId,
		title = title,
		author = "Mr Time Edits",
		ownerHandle = handle,
		lengthSeconds = lengthSeconds,
		category = "Entertainment",
		watchPageResolved = true,
		isUnlisted = false,
	)

	/** The account's own Shorts feed names it, and its own watch page agrees. */
	private fun historyNamesShort(
		harness: ReplayHarness,
		videoId: String,
		title: String?,
		lengthSeconds: Long?,
	) {
		harness.env.facts.put(shortFacts(videoId, title, lengthSeconds))
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

	/** One Shorts ad, as the foreground parser classifies it: a label and no handle. */
	private fun playAd(harness: ReplayHarness, label: String, durationMs: Long) {
		harness.feed(
			PlaybackEvent.ForegroundShortObserved(
				title = null,
				ownerHandle = handle,
				durationMs = durationMs,
			),
			PlaybackEvent.AdLabelObserved(label),
			PlaybackEvent.PlaybackStateChanged(playing = true),
			PlaybackEvent.Advance(durationMs),
			PlaybackEvent.Finalized(),
		)
	}

	// ---- 1. ads are not evidence about the account ---------------------------

	@Test
	fun `three Shorts ads add no misses and cannot stand the route down`() {
		val harness = harness()
		// Nothing this account watched matches an ad creative, and nothing ever
		// will: an ad is not a watch-history row.
		harness.env.watchHistory.evidence = {
			VideoResolutionAttempt(refusalReason = "none of the 30 most recent entries match")
		}

		playAd(harness, "Sponsored", 23_000)
		playAd(harness, "Ad · 24 seconds", 24_000)
		playAd(harness, "Sponsored", 15_000)

		assertNull(
			"three ordinary Shorts ads stood the watch-history route down",
			harness.env.watchHistory.health.refusedBecause,
		)
		assertTrue(
			"the route refused to run after three ads",
			harness.env.watchHistory.health.mayRun(harness.env.clock.nowMillis()),
		)
		// Every ordinary-entry lookup an ad reached was offered as non-evidence.
		assertTrue(
			"an ad was offered to the account diagnosis: " +
				"${harness.env.watchHistory.evidenceQueries}",
			harness.env.watchHistory.evidenceQueries.none { it.second },
		)
		// And the veto is untouched: an ad never becomes a scrobble.
		assertEquals(emptyList<Any>(), harness.broadcasts)
	}

	// ---- 2. a Short is never an ordinary entry -------------------------------

	@Test
	fun `Shorts the feed could not corroborate never stand the route down`() {
		val harness = harness()
		// The account's Shorts feed offers candidates and none of them corroborates —
		// the ordinary outcome for a Short YouTube has not written out yet. The
		// ordinary-entry gate can only ever answer "absent" for a Short, because the
		// parser keeps Shorts out of `entries` entirely, so nothing here is evidence
		// about which account the app is on.
		harness.env.watchHistory.shortIds = { listOf("unKnownShrt") }
		harness.env.identity.verifiedCandidates = {
			VideoResolutionAttempt(refusalReason = "no candidate corroborated")
		}
		harness.env.watchHistory.evidence = {
			VideoResolutionAttempt(refusalReason = "none of the 30 most recent entries match")
		}

		repeat(WatchHistoryHealth.MISSES_BEFORE_REFUSING) { i ->
			harness.feed(
				PlaybackEvent.ForegroundShortObserved(
					title = null,
					ownerHandle = "@creator$i",
					durationMs = 20_000,
				),
				PlaybackEvent.PlaybackStateChanged(playing = true),
				PlaybackEvent.Advance(19_000),
				PlaybackEvent.Finalized(),
			)
		}

		assertTrue(
			"the Shorts route never ran, so this scenario proves nothing: " +
				"${harness.env.watchHistory.shortIdQueries}",
			harness.env.watchHistory.shortIdQueries.size >=
				WatchHistoryHealth.MISSES_BEFORE_REFUSING,
		)
		assertNull(
			"three unidentifiable Shorts stood the watch-history route down",
			harness.env.watchHistory.health.refusedBecause,
		)
		assertTrue(
			"a Short was offered to the account diagnosis: " +
				"${harness.env.watchHistory.evidenceQueries}",
			harness.env.watchHistory.evidenceQueries.none { it.second },
		)
	}

	// ---- 3. a corroborated Short is a hit ------------------------------------

	@Test
	fun `a Short corroborated from the account's own feed clears earlier misses`() {
		val harness = harness()
		harness.env.watchHistory.health.recordMiss(0, "one|a|10")
		harness.env.watchHistory.health.recordMiss(0, "two|b|20")
		assertNull(
			"two misses is not yet a diagnosis",
			harness.env.watchHistory.health.refusedBecause,
		)

		historyNamesShort(harness, "shOrtVideo1", "#hoyoverse", 15)
		harness.feed(
			PlaybackEvent.ForegroundShortObserved(
				title = "#hoyoverse",
				ownerHandle = handle,
				durationMs = 15_000,
			),
			PlaybackEvent.PlaybackStateChanged(playing = true),
			PlaybackEvent.Advance(14_000),
			PlaybackEvent.Finalized(),
		)

		assertEquals(1, harness.broadcasts.size)
		assertEquals(
			"the corroborated Short was not reported as proof of the account",
			1,
			harness.env.watchHistory.shortCorroborations,
		)
		// A third miss no longer trips, because the run was cleared.
		harness.env.watchHistory.health.recordMiss(0, "three|c|30")
		assertNull(
			"a corroborated Short did not clear the miss run",
			harness.env.watchHistory.health.refusedBecause,
		)
	}

	@Test
	fun `a refused route is reopened by the Short its own probe corroborates`() {
		val harness = harness()
		val health = harness.env.watchHistory.health
		val refusedAt = harness.env.clock.nowMillis()
		repeat(WatchHistoryHealth.MISSES_BEFORE_REFUSING) {
			health.recordMiss(refusedAt, "gone $it|c|10")
		}
		assertNotNull("the fixture did not reach a refusal", health.refusedBecause)
		assertFalse(health.mayRun(harness.env.clock.nowMillis()))

		// Nothing the feed alone could have named resolves inside the pause. An
		// untitled Short is exactly that shape — it is what picture-in-picture
		// leaves behind, and search cannot name a video it has no title for.
		historyNamesShort(harness, "shOrtVideo9", "Some Canonical Title", 15)
		harness.feed(
			PlaybackEvent.ForegroundShortObserved(
				title = null,
				ownerHandle = handle,
				durationMs = 15_000,
			),
			PlaybackEvent.PlaybackStateChanged(playing = true),
			PlaybackEvent.Advance(14_000),
			PlaybackEvent.Finalized(),
		)
		assertEquals(
			"an untitled Short resolved while the route was stood down",
			0,
			harness.broadcasts.size,
		)
		assertNotNull(health.refusedBecause)

		// The probe comes due. A Shorts read may spend it — and now its verified
		// outcome can pay for it, which is what makes spending it legitimate.
		harness.env.clock.advance(WatchHistoryHealth.RETRY_INTERVAL_MS)
		historyNamesShort(harness, "shOrtVideoA", "Another Canonical Title", 15)
		harness.feed(
			PlaybackEvent.ForegroundShortObserved(
				title = null,
				ownerHandle = handle,
				durationMs = 15_000,
			),
			PlaybackEvent.PlaybackStateChanged(playing = true),
			PlaybackEvent.Advance(14_000),
			PlaybackEvent.Finalized(),
		)

		assertNull(
			"the probe's own corroborated Short did not reopen the route",
			health.refusedBecause,
		)
		assertEquals(1, harness.broadcasts.size)
		assertEquals("shOrtVideoA", harness.broadcasts.single().videoId)
	}

	// ---- 4. the shape the diagnosis is actually for --------------------------

	@Test
	fun `three distinct ordinary native videos absent from a fresh feed still trip it`() {
		val harness = harness()
		harness.env.watchHistory.evidence = {
			VideoResolutionAttempt(refusalReason = "none of the 30 most recent entries match")
		}

		repeat(WatchHistoryHealth.MISSES_BEFORE_REFUSING) { i ->
			harness.feed(
				PlaybackEvent.SessionMetadata(
					title = "Ordinary video $i",
					artist = "Some Channel",
					durationMs = 240_000,
				),
				PlaybackEvent.PlaybackStateChanged(playing = true),
				PlaybackEvent.Advance(200_000),
				PlaybackEvent.Finalized(),
			)
		}

		val reason = harness.env.watchHistory.health.refusedBecause
		assertNotNull(
			"three distinct missing ordinary videos no longer diagnose the account: " +
				"${harness.env.watchHistory.evidenceQueries}",
			reason,
		)
		assertTrue(reason!!.contains("different account"))
		assertTrue(
			"the ordinary videos were not offered as account evidence",
			harness.env.watchHistory.evidenceQueries.all { it.second },
		)
	}

	/** An ordinary browser video whose id the address bar did not name. */
	private fun braveWatching(title: String, channel: String, durationMs: Long) = listOf(
		PlaybackEvent.NotificationObserved(host = "youtube.com", title = title),
		PlaybackEvent.UrlObserved(host = "www.youtube.com"),
		PlaybackEvent.SessionMetadata(title = title, artist = channel, durationMs = durationMs),
		PlaybackEvent.PlaybackStateChanged(playing = true),
	)

	private fun videoFacts(videoId: String, title: String, author: String, lengthSeconds: Long) =
		VideoFacts(
			videoId = videoId,
			title = title,
			author = author,
			lengthSeconds = lengthSeconds,
			category = "Music",
			watchPageResolved = true,
			isUnlisted = false,
		)

	private fun braveHarness(): ReplayHarness {
		val harness = ReplayHarness(ReplaySource.BRAVE)
		harness.env.watchHistory.hasSession = true
		harness.env.watchHistory.nowMillis = { harness.env.clock.nowMillis() }
		return harness
	}

	private fun historyNamesVideo(
		harness: ReplayHarness,
		videoId: String,
		title: String,
		channel: String,
		lengthSeconds: Long,
	) {
		harness.env.facts.put(videoFacts(videoId, title, channel, lengthSeconds))
		harness.env.watchHistory.evidence = {
			VideoResolutionAttempt(
				resolution = VideoResolution(
					videoId = videoId,
					source = "watch history",
					title = title,
					channel = channel,
					lengthSeconds = lengthSeconds,
					uniquelyResolved = true,
					historyVerified = true,
				),
			)
		}
	}

	// ---- 3b. only an established native video is evidence --------------------

	/** An ordinary native video, played for [playedMs] of [durationMs]. */
	private fun nativeVideo(
		harness: ReplayHarness,
		title: String,
		durationMs: Long,
		playedMs: Long,
	) {
		harness.feed(
			PlaybackEvent.SessionMetadata(
				title = title,
				artist = "Some Channel",
				durationMs = durationMs,
			),
			PlaybackEvent.PlaybackStateChanged(playing = true),
			PlaybackEvent.Advance(playedMs),
			PlaybackEvent.Finalized(),
		)
	}

	/**
	 * the watch-history breaker contract and replay requirement.
	 *
	 * Three ordinary native videos previewed for a few seconds each. Identity
	 * resolution still runs — Not logged has to keep its exact hyperlink — but
	 * the account cannot be blamed for not having recorded a five-second
	 * preview, so none of the three is health evidence.
	 */
	@Test
	fun `three brief native previews stay linked and add no health evidence`() {
		val harness = harness()
		val ids = listOf("prevIewVid1", "prevIewVid2", "prevIewVid3")
		val titles = ids.indices.map { "Preview $it" }
		ids.forEachIndexed { i, id ->
			harness.env.facts.put(videoFacts(id, titles[i], "Some Channel", 240))
		}
		harness.env.watchHistory.evidence = { title ->
			val index = titles.indexOf(title)
			if (index < 0) {
				VideoResolutionAttempt(refusalReason = "none of the 30 most recent entries match")
			} else {
				VideoResolutionAttempt(
					resolution = VideoResolution(
						videoId = ids[index],
						source = "watch history",
						title = title,
						channel = "Some Channel",
						lengthSeconds = 240,
						uniquelyResolved = true,
						historyVerified = true,
					),
				)
			}
		}

		titles.forEach { nativeVideo(harness, it, durationMs = 240_000, playedMs = 6_000) }

		assertTrue(
			"no preview reached identity resolution, so the link claim is unproven: " +
				"${harness.env.watchHistory.evidenceQueries}",
			harness.env.watchHistory.evidenceQueries.size >= 3,
		)
		assertTrue(
			"a brief preview was offered to the native diagnosis: " +
				"${harness.env.watchHistory.evidenceQueries}",
			harness.env.watchHistory.evidenceQueries.none { it.second },
		)
		assertNull(
			"three brief previews stood the watch-history route down",
			harness.env.watchHistory.health.refusedBecause,
		)
		// The exact hyperlink survived: every refusal names its video.
		val previewRefusals = harness.refusals.filter { it.title in titles }
		assertEquals(3, previewRefusals.size)
		assertEquals(ids.toSet(), previewRefusals.mapNotNull { it.videoId }.toSet())
		assertEquals("a preview scrobbled", emptyList<Any>(), harness.broadcasts)
	}

	/**
	 * Replay requirement 2: a preview mixed in with real absences neither counts
	 * nor blocks the diagnosis the breaker is actually for.
	 */
	@Test
	fun `a preview plus two qualified absences does not trip, and a third qualified one does`() {
		val harness = harness()
		harness.env.watchHistory.evidence = {
			VideoResolutionAttempt(refusalReason = "none of the 30 most recent entries match")
		}

		nativeVideo(harness, "Brief preview", durationMs = 240_000, playedMs = 5_000)
		nativeVideo(harness, "Qualified one", durationMs = 240_000, playedMs = 200_000)
		nativeVideo(harness, "Qualified two", durationMs = 240_000, playedMs = 200_000)
		assertNull(
			"a preview counted as one of the three: " +
				"${harness.env.watchHistory.evidenceQueries}",
			harness.env.watchHistory.health.refusedBecause,
		)

		nativeVideo(harness, "Qualified three", durationMs = 240_000, playedMs = 200_000)
		assertNotNull(
			"three qualified distinct native absences no longer diagnose the account",
			harness.env.watchHistory.health.refusedBecause,
		)
	}

	/** Replay requirement 8, and the reason the two states are separate at all. */
	@Test
	fun `a browser-only probe recovers a declared fault without touching native misses`() {
		val harness = braveHarness()
		val health = harness.env.watchHistory.health
		// Two unrelated native misses already on the run.
		health.recordMiss(harness.env.clock.nowMillis(), "native one|c|100")
		health.recordMiss(harness.env.clock.nowMillis(), "native two|c|200")
		// YouTube declares a session fault. Both sources are blocked.
		harness.env.watchHistory.declareFault(WatchHistoryParser.Reason.SIGNED_OUT)
		assertFalse(health.mayRun(harness.env.clock.nowMillis(), accountEvidence = false))
		assertFalse(health.mayRun(harness.env.clock.nowMillis(), accountEvidence = true))

		// Fifteen minutes later the user has signed in again. The only thing they
		// play is Brave — and that has to be enough to reopen the route.
		harness.env.clock.advance(WatchHistoryHealth.RETRY_INTERVAL_MS)
		historyNamesVideo(harness, "brAveVideo3", "A Brave Video", "Some Channel", 200)
		harness.feed(
			braveWatching("A Brave Video", "Some Channel", 200_000) +
				PlaybackEvent.Advance(190_000) +
				PlaybackEvent.Finalized(),
		)

		assertNull(
			"a browser probe could not recover a repaired session: " +
				"${harness.terminalRefusalReasons}",
			health.refusedBecause,
		)
		assertEquals(1, harness.broadcasts.size)
		assertEquals("brAveVideo3", harness.broadcasts.single().videoId)
		// The native run survived: a third native miss still diagnoses.
		health.recordMiss(harness.env.clock.nowMillis(), "native three|c|300")
		assertNotNull(
			"browser recovery erased native mismatch evidence",
			health.refusedBecause,
		)
	}

	@Test
	fun `a still-faulted recovery probe keeps the fault and waits another interval`() {
		val harness = braveHarness()
		val health = harness.env.watchHistory.health
		harness.env.watchHistory.declareFault(WatchHistoryParser.Reason.HISTORY_PAUSED)
		harness.env.watchHistory.feedHealthy = false
		harness.env.watchHistory.feedFault = WatchHistoryParser.Reason.HISTORY_PAUSED

		harness.env.clock.advance(WatchHistoryHealth.RETRY_INTERVAL_MS)
		historyNamesVideo(harness, "brAveVideo4", "Another Brave Video", "Some Channel", 200)
		harness.feed(
			braveWatching("Another Brave Video", "Some Channel", 200_000) +
				PlaybackEvent.Advance(190_000) +
				PlaybackEvent.Finalized(),
		)

		assertNotNull(
			"a probe that found the same fault cleared it anyway",
			health.refusedBecause,
		)
		val probedAt = harness.env.clock.nowMillis()
		assertFalse(health.mayRun(probedAt + WatchHistoryHealth.RETRY_INTERVAL_MS - 1))
		assertTrue(health.mayRun(probedAt + WatchHistoryHealth.RETRY_INTERVAL_MS))
	}

	// ---- 4b. the browser is not evidence about the native app ----------------

	/**
	 * A Brave listen cannot diagnose, clear or postpone the native-account pause.
	 *
	 * The diagnosis is a statement about which account the **native YouTube app**
	 * is signed into. Nothing played in a browser is evidence for or against it,
	 * and until now every browser lookup could add a miss, clear the run, and
	 * spend the one probe a stood-down route allows native playback.
	 */
	@Test
	fun `Brave lookups neither modify nor probe the native breaker`() {
		val harness = braveHarness()
		val health = harness.env.watchHistory.health

		// Two native misses already on the run. Browser misses must not make three.
		health.recordMiss(harness.env.clock.nowMillis(), "native one|c|10")
		health.recordMiss(harness.env.clock.nowMillis(), "native two|c|20")
		harness.env.watchHistory.evidence = {
			VideoResolutionAttempt(refusalReason = "none of the 30 most recent entries match")
		}

		repeat(4) { i ->
			harness.feed(
				braveWatching("Brave video $i", "Some Channel", 200_000) +
					PlaybackEvent.Advance(190_000) +
					PlaybackEvent.Finalized(),
			)
		}

		assertTrue(
			"no browser lookup reached the history route, so this proves nothing: " +
				"${harness.env.watchHistory.evidenceQueries}",
			harness.env.watchHistory.evidenceQueries.size >= 3,
		)
		assertNull(
			"browser misses were counted against the native account",
			health.refusedBecause,
		)
		assertTrue(
			"a browser lookup was offered as native-account evidence: " +
				"${harness.env.watchHistory.evidenceQueries}",
			harness.env.watchHistory.evidenceQueries.none { it.second },
		)
		assertTrue(
			"a browser Shorts read was offered as native-account evidence: " +
				"${harness.env.watchHistory.shortIdScopes}",
			harness.env.watchHistory.shortIdScopes.none { it },
		)
		// The native run is untouched: the third *native* miss still diagnoses.
		health.recordMiss(harness.env.clock.nowMillis(), "native three|c|30")
		assertNotNull(
			"browser traffic had cleared the native miss run",
			health.refusedBecause,
		)
	}

	@Test
	fun `a refused native breaker does not stop Brave resolving, and Brave does not spend its probe`() {
		val harness = braveHarness()
		val health = harness.env.watchHistory.health
		val refusedAt = harness.env.clock.nowMillis()
		repeat(WatchHistoryHealth.MISSES_BEFORE_REFUSING) {
			health.recordMiss(refusedAt, "native gone $it|c|10")
		}
		assertNotNull(health.refusedBecause)

		// Brave still resolves through the pause — the native diagnosis is not
		// about it and must not gate it.
		historyNamesVideo(harness, "brAveVideo1", "A Brave Video", "Some Channel", 200)
		harness.feed(
			braveWatching("A Brave Video", "Some Channel", 200_000) +
				PlaybackEvent.Advance(190_000) +
				PlaybackEvent.Finalized(),
		)
		assertEquals(
			"a stood-down native diagnosis blocked a browser listen: " +
				"${harness.terminalRefusalReasons}",
			1,
			harness.broadcasts.size,
		)
		assertEquals("brAveVideo1", harness.broadcasts.single().videoId)

		// And the native probe is intact: it comes due on its own schedule, not
		// one Brave pushed back.
		assertFalse(
			"Brave postponed the native probe",
			health.mayRun(refusedAt + WatchHistoryHealth.RETRY_INTERVAL_MS - 1),
		)
		assertTrue(health.mayRun(refusedAt + WatchHistoryHealth.RETRY_INTERVAL_MS))
	}

	/**
	 * `recentShortIds` falls back to the most recent Shorts when no feed title
	 * matches, so a non-empty list is not proof the track is a Short. Shape must
	 * never be inferred from it.
	 */
	@Test
	fun `fallback Shorts candidates never classify an ordinary browser video as a Short`() {
		val harness = braveHarness()
		// The feed always has Shorts in it, and the fallback offers them whatever
		// the title is. This is an ordinary 200-second video.
		harness.env.watchHistory.shortIds = { listOf("fallbackSh1", "fallbackSh2") }
		harness.env.identity.verifiedCandidates = {
			VideoResolutionAttempt(refusalReason = "no candidate corroborated")
		}
		historyNamesVideo(harness, "brAveVideo2", "An Ordinary Video", "Some Channel", 200)

		harness.feed(
			braveWatching("An Ordinary Video", "Some Channel", 200_000) +
				PlaybackEvent.Advance(190_000) +
				PlaybackEvent.Finalized(),
		)

		assertEquals(
			"the ordinary browser video did not scrobble: " +
				"${harness.terminalRefusalReasons}",
			1,
			harness.broadcasts.size,
		)
		assertEquals("brAveVideo2", harness.broadcasts.single().videoId)
		// The fallback list was read and discarded as the fallback it is: the
		// ordinary-entry route answered, and the read was browser-scoped.
		assertTrue(
			"the fallback Shorts list was never consulted, so this proves nothing",
			harness.env.watchHistory.shortIdScopes.isNotEmpty(),
		)
		assertTrue(
			"a browser Shorts read was offered as native-account evidence",
			harness.env.watchHistory.shortIdScopes.none { it },
		)
		assertTrue(
			"an ordinary browser video was offered to the native diagnosis",
			harness.env.watchHistory.evidenceQueries.none { it.second },
		)
		assertNull(harness.env.watchHistory.health.refusedBecause)
	}

	// ---- 5. the field sequence, end to end -----------------------------------

	@Test
	fun `a PiP Short still resolves after the three ads that used to stand the route down`() {
		// Synthetic callback order: ad, ad, ad, then an untitled Short
		// finalized in picture-in-picture — the shape that has no title of its own
		// and therefore nothing but watch history to identify it.
		val harness = harness()
		harness.env.watchHistory.evidence = {
			VideoResolutionAttempt(refusalReason = "none of the 30 most recent entries match")
		}

		playAd(harness, "Sponsored", 23_000)
		playAd(harness, "Ad · 24 seconds", 24_000)
		playAd(harness, "Sponsored", 15_000)
		// …and the brief ordinary previews a user makes between them.
		nativeVideo(harness, "Preview A", durationMs = 240_000, playedMs = 5_000)
		nativeVideo(harness, "Preview B", durationMs = 240_000, playedMs = 8_000)

		historyNamesShort(harness, "pipShOrtVid", "Romania", 10)
		harness.feed(
			PlaybackEvent.ForegroundShortObserved(
				title = null,
				ownerHandle = handle,
				durationMs = 10_000,
			),
			PlaybackEvent.PlaybackStateChanged(playing = true),
			PlaybackEvent.Advance(4_000),
			PlaybackEvent.ProgressSurfaceLost(inferredMs = 6_000),
			PlaybackEvent.Finalized(),
		)

		assertNull(harness.env.watchHistory.health.refusedBecause)
		assertEquals(
			"the PiP Short lost its identity to the ads that preceded it",
			1,
			harness.broadcasts.size,
		)
		val payload = harness.broadcasts.single()
		assertEquals("pipShOrtVid", payload.videoId)
		assertEquals("Romania", payload.title)
		// Replay requirement 10: at most one broadcast per eligible playback.
		assertEquals(
			"an eligible playback broadcast more than once",
			harness.broadcasts.size,
			harness.broadcasts.map { it.videoId }.distinct().size,
		)
		assertEquals(
			"the ledger refused a duplicate that should never have been attempted",
			emptyList<String>(),
			harness.env.claims.duplicateAttempts,
		)
	}
}
