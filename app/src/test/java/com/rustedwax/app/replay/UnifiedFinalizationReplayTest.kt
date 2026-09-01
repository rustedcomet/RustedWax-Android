package com.rustedwax.app.replay

import com.rustedwax.app.enrich.VideoFacts
import com.rustedwax.hive.HiveRpc
import com.rustedwax.app.scrobble.FinalizationOutcome
import com.rustedwax.app.scrobble.FinalizationRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Behavior gates for the shared automatic/manual finalization use case. */
class UnifiedFinalizationReplayTest : ReplayScenarioTest() {

	private val videoId = "dQw4w9WgXcQ"

	private fun facts() = VideoFacts(
		videoId = videoId,
		title = "Rick Astley - Never Gonna Give You Up",
		author = "Rick Astley",
		lengthSeconds = 213,
		category = "Music",
		watchPageResolved = true,
		isUnlisted = false,
	)

	private fun watched(playedMs: Long = 200_000) = listOf(
		PlaybackEvent.NotificationObserved(host = "youtube.com"),
		PlaybackEvent.UrlObserved(host = "www.youtube.com", videoId = videoId),
		PlaybackEvent.SessionMetadata(
			title = "Rick Astley - Never Gonna Give You Up",
			artist = "Rick Astley",
			durationMs = 213_000,
		),
		PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
		PlaybackEvent.Advance(playedMs),
		PlaybackEvent.Finalized("track change"),
	)

	private fun run(manual: Boolean): Pair<ReplayHarness, List<String>> {
		val harness = ReplayHarness(ReplaySource.BRAVE)
		harness.env.facts.put(facts())
		if (manual) harness.captureFinalized = { }
		harness.feed(watched())
		val feedback = mutableListOf<String>()
		if (manual) {
			harness.captureFinalized = null
			harness.finalizeLastManually { message, isError ->
				feedback += "${if (isError) "error" else "ok"}:$message"
			}
		}
		return harness to feedback
	}

	@Test
	fun `automatic and manual triggers produce byte-identical payloads and identity routes`() {
		val automatic = run(manual = false).first
		val (manual, feedback) = run(manual = true)

		assertEquals(
			automatic.env.broadcaster.sent.map { it.json },
			manual.env.broadcaster.sent.map { it.json },
		)
		assertEquals(automatic.identityRoutes, manual.identityRoutes)
		assertEquals(listOf("Eligible"), automatic.outcomeKinds)
		assertEquals(listOf("Eligible"), manual.outcomeKinds)
		assertTrue(feedback.single().startsWith("ok:"))
	}

	@Test
	fun `automatic and manual triggers produce the same typed refusal`() {
		fun refuse(manual: Boolean): FinalizationOutcome {
			val harness = ReplayHarness(ReplaySource.BRAVE)
			harness.env.facts.put(facts())
			if (manual) harness.captureFinalized = { }
			harness.feed(watched(playedMs = 30_000))
			if (manual) {
				harness.captureFinalized = null
				harness.finalizeLastManually()
			}
			return harness.outcomes.single().outcome
		}

		assertEquals(refuse(manual = false), refuse(manual = true))
	}

	@Test
	fun `manual then automatic collision produces one dispatch and one duplicate refusal`() {
		val (harness, _) = run(manual = true)

		harness.refinalizeLastListen()

		assertEquals(listOf("Eligible", "Refused"), harness.outcomeKinds)
		assertEquals(1, harness.env.broadcaster.sent.size)
		assertEquals(1, harness.duplicateAttempts.size)
	}

	@Test
	fun `manual may run with auto off but uses every downstream stage unchanged`() {
		val env = ReplayEnvironment(policy = ReplayPolicy(autoScrobble = false))
		val harness = ReplayHarness(ReplaySource.BRAVE, env)
		harness.env.facts.put(facts())
		harness.captureFinalized = { }
		harness.feed(watched())
		harness.captureFinalized = null

		harness.finalizeLastManually()

		assertEquals(listOf("Eligible"), harness.outcomeKinds)
		assertEquals(1, harness.env.broadcaster.sent.size)
		assertEquals(0, env.policy.automaticTransportCommitAttempts)
	}

	@Test
	fun `failed manual dispatch releases the claim and never enters the retry queue`() {
		val env = ReplayEnvironment()
		env.broadcaster.defaultResult = HiveRpc.BroadcastResult.NetworkFailure("offline")
		val harness = ReplayHarness(ReplaySource.BRAVE, env)
		harness.env.facts.put(facts())
		harness.captureFinalized = { }
		harness.feed(watched())
		harness.captureFinalized = null

		val firstFeedback = mutableListOf<Pair<String, Boolean>>()
		harness.finalizeLastManually { message, error -> firstFeedback += message to error }

		assertTrue(firstFeedback.single().second)
		assertEquals(0, env.retryQueue.size())
		assertTrue("failed manual claim must be released", env.claims.claimed.isEmpty())

		env.broadcaster.defaultResult = HiveRpc.BroadcastResult.Success(
			"tx-retry",
			"replay-node",
			HiveRpc.BroadcastResult.Evidence.BLOCK,
		)
		harness.finalizeLastManually()
		assertEquals(2, harness.outcomes.count { it.outcome is FinalizationOutcome.Eligible })
		assertEquals(0, env.retryQueue.size())
	}

	@Test
	fun `manual attempt with no posting account does not strand a dedup claim`() {
		val env = ReplayEnvironment(posting = ReplayPostingIdentity(wif = null))
		val harness = ReplayHarness(ReplaySource.BRAVE, env)
		harness.env.facts.put(facts())
		harness.captureFinalized = { }
		harness.feed(watched())
		harness.captureFinalized = null

		harness.finalizeLastManually()

		assertEquals(listOf("Ignored"), harness.outcomeKinds)
		assertTrue(env.claims.claimed.isEmpty())
		assertEquals(0, env.broadcaster.sent.size)
	}

	@Test
	fun `queue retry dispatches serialized bytes without producing another finalization outcome`() {
		val env = ReplayEnvironment()
		env.broadcaster.defaultResult = HiveRpc.BroadcastResult.NetworkFailure("offline")
		val harness = ReplayHarness(ReplaySource.BRAVE, env)
		harness.env.facts.put(facts())
		harness.feed(watched())
		val outcomesBeforeRetry = harness.outcomes.toList()
		assertEquals(1, env.retryQueue.size())

		env.broadcaster.defaultResult = HiveRpc.BroadcastResult.Success(
			"tx-queued",
			"replay-node",
			HiveRpc.BroadcastResult.Evidence.BLOCK,
		)
		FinalizationRuntime.flushQueue()

		assertEquals(outcomesBeforeRetry, harness.outcomes)
		assertEquals(0, env.retryQueue.size())
		assertFalse(harness.env.broadcaster.sent.isEmpty())
	}
}
