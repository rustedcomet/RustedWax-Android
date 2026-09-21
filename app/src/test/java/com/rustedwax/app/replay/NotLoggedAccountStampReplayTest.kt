package com.rustedwax.app.replay

import com.rustedwax.app.enrich.VideoFacts
import com.rustedwax.app.scrobble.FinalizationOutcome
import com.rustedwax.app.scrobble.FinalizationRuntime
import com.rustedwax.youtube.identity.VideoResolution
import com.rustedwax.youtube.identity.VideoResolutionAttempt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who each Not-logged row belongs to, decided by the engine that files it.
 *
 * `NotLoggedAccountScopeTest` pins what the selector does with the stamp. This
 * pins the stamp itself, through the real finalization path rather than a
 * hand-built record: a row filtered by an account it was never given correctly
 * is a boundary that only looks like one.
 *
 * The scenario is a Short watched past the notable floor and declined on
 * percentage — a refusal that reaches the user-facing list, which is the only
 * kind of refusal this boundary is about.
 */
class NotLoggedAccountStampReplayTest : ReplayScenarioTest() {

	private val videoId = "shortFloor1"
	private val title = "Nine-second Short"
	private val handle = "@shortowner"

	/** A harness whose vault holds exactly one named account, or nobody. */
	private fun harnessFor(account: String?): ReplayHarness {
		val harness = ReplayHarness(
			ReplaySource.NATIVE_YOUTUBE,
			ReplayEnvironment(
				posting = if (account == null) {
					// No WIF is how the replay vault says "signed out": the port
					// answers null for the account, exactly as the real one does
					// with nothing saved.
					ReplayPostingIdentity(wif = null)
				} else {
					ReplayPostingIdentity(username = account)
				},
			),
		)
		harness.env.facts.put(
			VideoFacts(
				videoId = videoId,
				title = title,
				author = "Short Owner",
				ownerHandle = handle,
				lengthSeconds = 9,
				category = "Entertainment",
				watchPageResolved = true,
				isUnlisted = false,
			),
		)
		harness.env.watchHistory.hasSession = true
		harness.env.watchHistory.shortIds = { listOf(videoId) }
		harness.env.identity.verifiedCandidates = { candidates ->
			if (candidates == listOf(videoId)) {
				VideoResolutionAttempt(
					resolution = VideoResolution(
						videoId = videoId,
						source = "watch history",
						title = title,
						channel = "Short Owner",
						ownerHandle = handle,
						lengthSeconds = 9,
						uniquelyResolved = true,
						historyVerified = true,
					),
				)
			} else {
				VideoResolutionAttempt(refusalReason = "no candidate corroborated")
			}
		}
		return harness
	}

	private fun declineAShort(harness: ReplayHarness) {
		harness.feed(
			PlaybackEvent.ForegroundShortObserved(
				title = title,
				ownerHandle = handle,
				durationMs = 9_000,
			),
			PlaybackEvent.PlaybackStateChanged(playing = true),
			PlaybackEvent.Advance(5_000),
			PlaybackEvent.Finalized(),
		)
	}

	@Test
	fun `a declined listen is filed under the account that was signed in`() {
		val harness = harnessFor("alice")

		declineAShort(harness)

		val row = FinalizationRuntime.skipped.value.single()
		assertEquals("alice", row.account)
		assertTrue(harness.outcomes.single().outcome is FinalizationOutcome.Refused)
		// And nothing went out, which is what makes this a Not-logged row rather
		// than a History one: the stamp is not coming from a signature.
		assertTrue("a declined listen was broadcast", harness.env.broadcaster.sent.isEmpty())
	}

	@Test
	fun `a second account sees none of the first account's declined listens`() {
		// The reported failure, end to end: the skip list is process-global, so
		// A's rows are still in it after B signs in. What changes is what B is
		// shown — and that switching back gives A its rows again.
		val alice = harnessFor("alice")
		declineAShort(alice)

		val rows = FinalizationRuntime.skipped.value
		assertEquals(1, rows.size)
		assertEquals(emptyList<String>(), FinalizationRuntime.skippedFor(rows, "bob").map { it.title })
		assertEquals(1, FinalizationRuntime.skippedFor(rows, "alice").size)
	}

	@Test
	fun `a declined listen with nobody signed in is filed under nobody`() {
		val harness = harnessFor(null)

		declineAShort(harness)

		val row = FinalizationRuntime.skipped.value.single()
		assertNull("a signed-out refusal was given an owner", row.account)
		assertTrue(harness.outcomes.single().outcome is FinalizationOutcome.Refused)
	}

	@Test
	fun `an account that signs in afterwards does not inherit the signed-out row`() {
		// The rule the issue asked for by name. The row was made with no account
		// available; it stays that way, and the account that arrives next sees an
		// empty tab rather than somebody else's evening.
		val harness = harnessFor(null)
		declineAShort(harness)

		val rows = FinalizationRuntime.skipped.value
		assertEquals(1, rows.size)
		assertEquals(emptyList<String>(), FinalizationRuntime.skippedFor(rows, "alice").map { it.title })
		// Still there for the device that made it, which is the whole point of
		// keeping the row at all.
		assertEquals(1, FinalizationRuntime.skippedFor(rows, null).size)
	}
}
