package com.rustedwax.app.replay

import com.rustedwax.app.detect.EventLog
import com.rustedwax.app.enrich.VideoFacts
import com.rustedwax.youtube.identity.VideoResolution
import com.rustedwax.youtube.identity.VideoResolutionAttempt
import com.rustedwax.app.enrich.WatchHistoryHealth
import com.rustedwax.app.enrich.WatchHistoryParser
import com.rustedwax.app.scrobble.FinalizationOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The watch-history state that shadow finalization must be able to read but not
 * commit. Every scenario reaches the real [com.rustedwax.app.scrobble.FinalizationRuntime]
 * resolution path over one shared [ReplayEnvironment].
 */
class ShadowWatchHistoryIsolationReplayTest : ReplayScenarioTest() {

	private val ordinaryTitle = "A Production History Miss"
	private val ordinaryChannel = "Some Channel"
	private val ordinaryDurationMs = 240_000L

	private data class HistoryFingerprint(
		val health: String,
		val evidenceQueries: List<Pair<String, Boolean>>,
		val shortIdQueries: List<String?>,
		val shortIdForceRefreshes: List<Boolean>,
		val shortIdScopes: List<Boolean>,
		val shortCorroborations: Int,
		val feedReads: Int,
		val cacheWrites: Int,
	)

	private fun ReplayWatchHistory.fingerprint() = HistoryFingerprint(
		health = health.fingerprint(),
		evidenceQueries = evidenceQueries.toList(),
		shortIdQueries = shortIdQueries.toList(),
		shortIdForceRefreshes = shortIdForceRefreshes.toList(),
		shortIdScopes = shortIdScopes.toList(),
		shortCorroborations = shortCorroborations,
		feedReads = feedReads,
		cacheWrites = cacheWrites,
	)

	/** Read the real production breaker fields without asking [WatchHistoryHealth.mayRun]. */
	private fun WatchHistoryHealth.fingerprint(): String = listOf(
		"missKeys",
		"anonymousMisses",
		"mismatch",
		"mismatchAtMillis",
		"mismatchProbeMillis",
		"routeFault",
		"routeFaultAtMillis",
		"routeProbeMillis",
	).joinToString("|") { name ->
		val field = WatchHistoryHealth::class.java.getDeclaredField(name)
		field.isAccessible = true
		"$name=${field.get(this)}"
	}

	private fun environment(): ReplayEnvironment = ReplayEnvironment().also { env ->
		env.watchHistory.hasSession = true
		env.watchHistory.nowMillis = { env.clock.nowMillis() }
	}

	private fun ordinaryListen() = listOf(
		PlaybackEvent.SessionMetadata(
			title = ordinaryTitle,
			artist = ordinaryChannel,
			durationMs = ordinaryDurationMs,
		),
		PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
		PlaybackEvent.Advance(200_000),
		PlaybackEvent.Finalized("history-isolation field replay"),
	)

	private fun historyResolution(
		videoId: String = "histVideo01",
		title: String = ordinaryTitle,
		durationSeconds: Long = ordinaryDurationMs / 1_000,
	) = VideoResolutionAttempt(
		resolution = VideoResolution(
			videoId = videoId,
			source = "watch history",
			title = title,
			channel = ordinaryChannel,
			lengthSeconds = durationSeconds,
			uniquelyResolved = true,
			historyVerified = true,
		),
	)

	@Test
	fun `a shadow native miss leaves breaker query and cache state untouched`() {
		val env = environment()
		env.watchHistory.evidence = {
			VideoResolutionAttempt(refusalReason = "fresh history contained no matching row")
		}
		val harness = ReplayHarness(ReplaySource.NATIVE_YOUTUBE, env).also { it.shadow = true }
		val before = env.watchHistory.fingerprint()

		harness.feed(ordinaryListen())

		assertEquals(listOf("Refused"), harness.outcomeKinds)
		assertEquals("shadow committed watch-history state", before, env.watchHistory.fingerprint())
		assertEquals(emptyList<String>(), env.claims.claimed)
		assertEquals(0, env.retryQueue.size())
		assertEquals(emptyList<RecordingBroadcaster.Sent>(), env.broadcaster.sent)
	}

	@Test
	fun `shadow then live records one native miss and comparable terminal decisions`() {
		val env = environment()
		env.watchHistory.evidence = {
			VideoResolutionAttempt(refusalReason = "fresh history contained no matching row")
		}
		val shadow = ReplayHarness(ReplaySource.NATIVE_YOUTUBE, env).also { it.shadow = true }
		val before = env.watchHistory.fingerprint()
		shadow.feed(ordinaryListen())
		val afterShadow = env.watchHistory.fingerprint()

		env.clock.restore()
		val live = ReplayHarness(ReplaySource.NATIVE_YOUTUBE, env)
		live.feed(ordinaryListen())

		assertEquals("shadow changed shared history before live ran", before, afterShadow)
		assertEquals(shadow.outcomeKinds, live.outcomeKinds)
		assertEquals(
			(shadow.outcomes.single().outcome as FinalizationOutcome.Refused).reason,
			(live.outcomes.single().outcome as FinalizationOutcome.Refused).reason,
		)
		assertEquals(1, env.watchHistory.evidenceQueries.size)
		assertEquals(1, env.watchHistory.feedReads)
		assertEquals(1, env.watchHistory.cacheWrites)
		assertNull("one live miss must not trip a three-miss breaker", env.watchHistory.refusedBecause)
	}

	@Test
	fun `a shadow recovery lookup cannot consume the live recovery probe`() {
		val env = environment()
		env.watchHistory.declareFault(WatchHistoryParser.Reason.SIGNED_OUT)
		env.watchHistory.feedHealthy = false
		env.watchHistory.feedFault = WatchHistoryParser.Reason.SIGNED_OUT
		env.clock.advance(WatchHistoryHealth.RETRY_INTERVAL_MS)
		val before = env.watchHistory.fingerprint()
		val shadow = ReplayHarness(ReplaySource.NATIVE_YOUTUBE, env).also { it.shadow = true }

		shadow.feed(ordinaryListen())

		assertEquals("shadow spent or moved the due recovery probe", before, env.watchHistory.fingerprint())
		val live = ReplayHarness(ReplaySource.NATIVE_YOUTUBE, env)
		live.feed(ordinaryListen())
		assertEquals("the live lookup never received its recovery probe", 1, env.watchHistory.feedReads)
		assertNotNull(env.watchHistory.refusedBecause)
	}

	@Test
	fun `a healthy feed and native hit seen in shadow clear neither breaker state`() {
		val env = environment()
		val now = env.clock.nowMillis()
		repeat(WatchHistoryHealth.MISSES_BEFORE_REFUSING) { i ->
			env.watchHistory.health.recordMiss(now, "missing-$i|channel|240")
		}
		env.watchHistory.declareFault(WatchHistoryParser.Reason.HISTORY_PAUSED, atMillis = now)
		env.clock.advance(WatchHistoryHealth.RETRY_INTERVAL_MS)
		env.watchHistory.evidence = { historyResolution() }
		env.facts.put(
			VideoFacts(
				videoId = "histVideo01",
				title = ordinaryTitle,
				author = ordinaryChannel,
				lengthSeconds = 240,
				category = "Music",
				watchPageResolved = true,
				isUnlisted = false,
			),
		)
		val before = env.watchHistory.fingerprint()
		val shadow = ReplayHarness(ReplaySource.NATIVE_YOUTUBE, env).also { it.shadow = true }

		shadow.feed(ordinaryListen())

		assertEquals(listOf("Eligible"), shadow.outcomeKinds)
		assertEquals("shadow cleared route or account breaker state", before, env.watchHistory.fingerprint())
		assertNotNull(env.watchHistory.refusedBecause)
	}

	@Test
	fun `a shadow Shorts corroboration cannot clear the native mismatch`() {
		val env = environment()
		val now = env.clock.nowMillis()
		repeat(WatchHistoryHealth.MISSES_BEFORE_REFUSING) { i ->
			env.watchHistory.health.recordMiss(now, "missing-$i|channel|60")
		}
		env.clock.advance(WatchHistoryHealth.RETRY_INTERVAL_MS)
		val shortId = "shortHist01"
		val shortTitle = "A Corroborated Short"
		env.watchHistory.shortIds = { listOf(shortId) }
		env.identity.verifiedCandidates = { ids ->
			if (ids == listOf(shortId)) {
				VideoResolutionAttempt(
					resolution = VideoResolution(
						videoId = shortId,
						source = "watch history",
						title = shortTitle,
						channel = "Shorts Creator",
						ownerHandle = "@shortscreator",
						lengthSeconds = 60,
						uniquelyResolved = true,
						historyVerified = true,
					),
				)
			} else {
				VideoResolutionAttempt(refusalReason = "unexpected candidates")
			}
		}
		env.facts.put(
			VideoFacts(
				videoId = shortId,
				title = shortTitle,
				author = "Shorts Creator",
				ownerHandle = "@shortscreator",
				lengthSeconds = 60,
				category = "Entertainment",
				watchPageResolved = true,
				isUnlisted = false,
			),
		)
		val before = env.watchHistory.fingerprint()
		val shadow = ReplayHarness(ReplaySource.NATIVE_YOUTUBE, env).also { it.shadow = true }

		shadow.feed(
			PlaybackEvent.ForegroundShortObserved(
				title = shortTitle,
				ownerHandle = "@shortscreator",
				durationMs = 60_000,
			),
			PlaybackEvent.PlaybackStateChanged(playing = true),
			PlaybackEvent.Advance(55_000),
			PlaybackEvent.Finalized("shadow Shorts corroboration"),
		)

		assertEquals(listOf("Eligible"), shadow.outcomeKinds)
		assertEquals("shadow Shorts corroboration changed live breaker state", before, env.watchHistory.fingerprint())
		assertNotNull(env.watchHistory.refusedBecause)
	}

	@Test
	fun `native-history shadow writes no EventLog broadcast queue or retained claim`() {
		val env = environment()
		env.watchHistory.evidence = { historyResolution() }
		env.facts.put(
			VideoFacts(
				videoId = "histVideo01",
				title = ordinaryTitle,
				author = ordinaryChannel,
				lengthSeconds = 240,
				category = "Music",
				watchPageResolved = true,
				isUnlisted = false,
			),
		)
		val shadow = ReplayHarness(ReplaySource.NATIVE_YOUTUBE, env).also { it.shadow = true }
		try {
			EventLog.applyPolicy(true)
			shadow.feed(ordinaryListen().dropLast(1))
			EventLog.clear()
			shadow.feed(listOf(ordinaryListen().last()))

			assertEquals(listOf("Eligible"), shadow.outcomeKinds)
			assertEquals(emptyList<String>(), EventLog.lines.value)
			assertEquals(emptyList<RecordingBroadcaster.Sent>(), env.broadcaster.sent)
			assertEquals(0, env.retryQueue.size())
			assertEquals(emptyList<String>(), env.claims.claimed)
			assertEquals(emptyList<String>(), env.claims.duplicateAttempts)
		} finally {
			EventLog.applyPolicy(false)
			EventLog.clear()
		}
	}
}
