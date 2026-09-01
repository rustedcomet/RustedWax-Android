package com.rustedwax.app.replay

import com.rustedwax.app.detect.TrackProgressCarry
import com.rustedwax.app.scrobble.FinalizationOutcome
import com.rustedwax.app.scrobble.FinalizationRuntime
import com.rustedwax.core.AutomaticWriteAuthorization
import com.rustedwax.core.TrackIdentity
import com.rustedwax.hive.HiveRpc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Red-first regression matrix for Automatic Scrobbling as temporal write authority. */
class AutomaticScrobbleTemporalAuthorizationTest : ReplayScenarioTest() {

	private val videoId = "dQw4w9WgXcQ"

	private fun watched() = listOf(
		PlaybackEvent.NotificationObserved(host = "youtube.com"),
		PlaybackEvent.UrlObserved(host = "www.youtube.com", videoId = videoId),
		PlaybackEvent.SessionMetadata(
			title = "Rick Astley - Never Gonna Give You Up",
			artist = "Rick Astley",
			durationMs = 213_000,
		),
		PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
		PlaybackEvent.Advance(200_000),
		PlaybackEvent.Finalized("track change"),
	)

	private fun ReplayEnvironment.addFacts() {
		facts.put(
			com.rustedwax.app.enrich.VideoFacts(
				videoId = videoId,
				title = "Rick Astley - Never Gonna Give You Up",
				author = "Rick Astley",
				lengthSeconds = 213,
				category = "Music",
				watchPageResolved = true,
				isUnlisted = false,
			),
		)
	}

	private fun captured(
		env: ReplayEnvironment,
		stamp: AutomaticWriteAuthorization = env.policy.automaticWriteAuthorization,
	): Pair<ReplayHarness, com.rustedwax.app.detect.SessionSnapshot> {
		env.addFacts()
		val harness = ReplayHarness(ReplaySource.BRAVE, env)
		harness.captureFinalized = { }
		harness.feed(watched())
		harness.captureFinalized = null
		return harness to harness.finalized.single().copy(automaticWriteAuthorization = stamp)
	}

	@Test
	fun `A target begun while OFF stays ignored after OFF to ON`() {
		val env = ReplayEnvironment(policy = ReplayPolicy(autoScrobble = false))
		val (harness, offEraTarget) = captured(env)

		env.policy.autoScrobble = true
		harness.finalizeDirectly(offEraTarget)

		assertTrue(harness.outcomes.single().outcome is FinalizationOutcome.Ignored)
		assertTrue(env.broadcaster.sent.isEmpty())
	}

	@Test
	fun `B OFF era continuation cannot resurrect when ON before expiry`() {
		TrackProgressCarry.clear()
		val env = ReplayEnvironment(policy = ReplayPolicy(autoScrobble = false))
		val stamp = env.policy.automaticWriteAuthorization
		val progress = TrackProgressCarry.Progress(
			playedMs = 120_000,
			trackStartedAtEpochSec = 1_786_233_600,
			fastestSpeedSeen = 1.0,
			atMillis = 1_000,
			lastPositionMs = 120_000,
			automaticWriteAuthorization = stamp,
		)
		val identity = TrackIdentity("Rick Astley - Never Gonna Give You Up", "Rick Astley", null, 213_000, videoId)
		val token = requireNotNull(TrackProgressCarry.remember("com.brave.browser", identity, progress))
		val expired = requireNotNull(
			TrackProgressCarry.expire(
				"com.brave.browser",
				identity,
				token,
				now = 1_000 + TrackProgressCarry.RESUMED_TTL_MS,
			),
		)
		val (harness, target) = captured(env, expired.automaticWriteAuthorization)

		env.policy.autoScrobble = true
		harness.finalizeDirectly(target)

		assertTrue(harness.outcomes.single().outcome is FinalizationOutcome.Ignored)
		assertTrue(env.broadcaster.sent.isEmpty())
	}

	@Test
	fun `C target begun ON is cancelled when OFF before finalization`() {
		val env = ReplayEnvironment()
		val (harness, target) = captured(env)

		env.policy.autoScrobble = false
		harness.finalizeDirectly(target)

		assertTrue(harness.outcomes.single().outcome is FinalizationOutcome.Ignored)
		assertTrue(env.broadcaster.sent.isEmpty())
	}

	@Test
	fun `D target begun ON is cancelled when OFF during async finalization`() {
		val dispatcher = DeferredDispatcher()
		val env = ReplayEnvironment()
		env.addFacts()
		val harness = ReplayHarness(ReplaySource.BRAVE, env, dispatcher = dispatcher)
		harness.captureFinalized = { }
		harness.feed(watched())
		harness.captureFinalized = null
		val target = harness.finalized.single().copy(
			automaticWriteAuthorization = env.policy.automaticWriteAuthorization,
		)

		harness.finalizeDirectly(target)
		assertTrue(dispatcher.queued > 0)
		// The asynchronous finalization has started, but has not yet crossed the
		// transport-commit point. Revocation therefore owns the ordering.
		FinalizationRuntime.setAutoScrobble(false)
		dispatcher.drain()

		assertEquals(1, harness.outcomes.size)
		assertTrue(harness.outcomes.single().outcome is FinalizationOutcome.Ignored)
		assertEquals(0, env.posting.loadKeyCalls)
		assertTrue(env.broadcaster.sent.isEmpty())
		assertTrue(env.claims.claimed.isEmpty())
	}

	@Test
	fun `SEC007 OFF after final check wins before transport commit`() {
		val dispatcher = DeferredDispatcher()
		val env = ReplayEnvironment()
		env.addFacts()
		val harness = ReplayHarness(ReplaySource.BRAVE, env, dispatcher = dispatcher)
		harness.captureFinalized = { }
		harness.feed(watched())
		harness.captureFinalized = null
		val target = harness.finalized.single().copy(
			automaticWriteAuthorization = env.policy.automaticWriteAuthorization,
		)

		harness.finalizeDirectly(target)
		val finalCheckSucceeded = CountDownLatch(1)
		val resumeTransport = CountDownLatch(1)
		env.policy.beforeAutomaticTransportCommit = {
			finalCheckSucceeded.countDown()
			check(resumeTransport.await(5, TimeUnit.SECONDS)) {
				"transport was not released after the final authorization check"
			}
		}
		val transportFailure = AtomicReference<Throwable?>(null)
		val transportThread = thread(name = "sec007-transport-commit") {
			runCatching { dispatcher.runNext() }.onFailure(transportFailure::set)
		}
		assertTrue(
			"transport did not reach the final authorization check",
			finalCheckSucceeded.await(5, TimeUnit.SECONDS),
		)

		FinalizationRuntime.setAutoScrobble(false)
		resumeTransport.countDown()
		transportThread.join(5_000)
		assertFalse("transport did not finish", transportThread.isAlive)
		transportFailure.get()?.let { throw AssertionError("transport failed", it) }

		dispatcher.drain()
		assertEquals("revoked automatic work loaded the posting key", 0, env.posting.loadKeyCalls)
		assertTrue("revoked automatic work reached broadcast", env.broadcaster.sent.isEmpty())
		assertEquals("revoked automatic work entered the queue", 0, env.retryQueue.size())
		assertTrue("revocation left an unsafe dedup claim", env.claims.claimed.isEmpty())
		assertEquals("revocation created another terminal outcome", 1, harness.outcomes.size)
		assertTrue(harness.outcomes.single().outcome is FinalizationOutcome.Ignored)
		assertEquals(1, env.policy.automaticTransportCommitAttempts)
	}

	@Test
	fun `SEC007 transport commit before OFF sends deterministically`() {
		val dispatcher = DeferredDispatcher()
		val env = ReplayEnvironment()
		env.addFacts()
		val harness = ReplayHarness(ReplaySource.BRAVE, env, dispatcher = dispatcher)
		harness.captureFinalized = { }
		harness.feed(watched())
		harness.captureFinalized = null
		val target = harness.finalized.single().copy(
			automaticWriteAuthorization = env.policy.automaticWriteAuthorization,
		)

		harness.finalizeDirectly(target)
		val transportCommitted = CountDownLatch(1)
		val resumeHandoff = CountDownLatch(1)
		env.policy.afterAutomaticTransportCommit = { committed ->
			check(committed) { "automatic transport did not commit while Auto was ON" }
			transportCommitted.countDown()
			check(resumeHandoff.await(5, TimeUnit.SECONDS)) {
				"transport handoff was not released after commitment"
			}
		}
		val handoffFailure = AtomicReference<Throwable?>(null)
		val handoffThread = thread(name = "sec007-committed-handoff") {
			runCatching { dispatcher.runNext() }.onFailure(handoffFailure::set)
		}
		assertTrue(
			"automatic transport did not reach its commit point",
			transportCommitted.await(5, TimeUnit.SECONDS),
		)

		FinalizationRuntime.setAutoScrobble(false)
		resumeHandoff.countDown()
		handoffThread.join(5_000)
		assertFalse("committed handoff did not finish", handoffThread.isAlive)
		handoffFailure.get()?.let { throw AssertionError("committed handoff failed", it) }
		dispatcher.drain()

		assertEquals(1, env.posting.loadKeyCalls)
		assertEquals(1, env.broadcaster.sent.size)
		assertEquals(0, env.retryQueue.size())
		assertEquals(1, env.claims.claimed.size)
		assertEquals(1, harness.outcomes.size)
		assertTrue(harness.outcomes.single().outcome is FinalizationOutcome.Eligible)
		assertEquals(1, env.policy.automaticTransportCommitAttempts)
	}

	@Test
	fun `E fresh target after re-enable can scrobble`() {
		val env = ReplayEnvironment(policy = ReplayPolicy(autoScrobble = false))
		env.policy.autoScrobble = true
		val (harness, target) = captured(env)

		harness.finalizeDirectly(target)

		assertTrue(harness.outcomes.single().outcome is FinalizationOutcome.Eligible)
		assertEquals(1, env.broadcaster.sent.size)
	}

	@Test
	fun `F carry preserves the original authorization generation`() {
		TrackProgressCarry.clear()
		val stamp = AutomaticWriteAuthorization(41, enabledAtStart = true)
		val progress = TrackProgressCarry.Progress(
			playedMs = 20_000,
			trackStartedAtEpochSec = 1_786_233_600,
			fastestSpeedSeen = 1.0,
			atMillis = 1_000,
			automaticWriteAuthorization = stamp,
		)
		val identity = TrackIdentity("Title", "Artist", null, 60_000, videoId)

		TrackProgressCarry.remember("com.brave.browser", identity, progress)
		val restored = TrackProgressCarry.claim("com.brave.browser", identity, now = 2_000)

		assertEquals(stamp, restored?.automaticWriteAuthorization)
	}

	@Test
	fun `G stale automatic target still produces exactly one terminal outcome`() {
		val env = ReplayEnvironment(policy = ReplayPolicy(autoScrobble = false))
		val (harness, target) = captured(env)
		env.policy.autoScrobble = true

		harness.finalizeDirectly(target)

		assertEquals(1, harness.outcomes.size)
		assertTrue(harness.outcomes.single().outcome is FinalizationOutcome.Ignored)
	}

	@Test
	fun `H disabling auto does not block retry of already serialized queue work`() {
		val env = ReplayEnvironment()
		env.broadcaster.defaultResult = HiveRpc.BroadcastResult.NetworkFailure("offline")
		val (harness, target) = captured(env)
		harness.finalizeDirectly(target)
		assertEquals(1, env.retryQueue.size())
		val outcomes = harness.outcomes.toList()
		val commitsBeforeRetry = env.policy.automaticTransportCommitAttempts

		env.policy.autoScrobble = false
		env.broadcaster.defaultResult = HiveRpc.BroadcastResult.Success(
			"tx-queued",
			"replay-node",
			HiveRpc.BroadcastResult.Evidence.BLOCK,
		)
		FinalizationRuntime.flushQueue()

		assertEquals(outcomes, harness.outcomes)
		assertEquals(0, env.retryQueue.size())
		assertEquals(2, env.broadcaster.sent.size)
		assertEquals(commitsBeforeRetry, env.policy.automaticTransportCommitAttempts)
	}
}
