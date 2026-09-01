package com.rustedwax.app.detect

import com.rustedwax.core.*
import com.rustedwax.core.SourceSessionId
import com.rustedwax.core.TrackInstanceId
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 5 ownership and isolation contract for the production evidence owner. */
class EvidenceCoordinatorTest {

	private val brave = SourceSessionId("com.brave.browser", null)
	private val native1 = SourceSessionId(YouTubeProbe.YOUTUBE_PACKAGE, 1)
	private val native2 = SourceSessionId(YouTubeProbe.YOUTUBE_PACKAGE, 2)

	private fun hint(title: String) = NotificationHints.Hint(
		host = "youtube.com",
		subText = "youtube.com",
		title = title,
		text = "Uploader",
		atMillis = 1_000,
	)

	private fun url(raw: String, videoId: String? = null) = UrlEvidence.Evidence(
		host = "youtube.com",
		videoId = videoId,
		raw = raw,
		atMillis = 1_000,
	)

	@Test
	fun `one explicit lifecycle rejects writes outside the active run`() {
		val evidence = EvidenceCoordinator()
		assertFalse(evidence.putNotification(brave, hint("before start")))

		evidence.start()
		assertTrue(evidence.putNotification(brave, hint("active")))
		assertEquals("active", evidence.notificationFor(brave, "active", null, false)?.title)

		evidence.stop()
		assertNull(evidence.notificationFor(brave, "active", null, false))
		assertFalse(evidence.putNotification(brave, hint("after stop")))
	}

	@Test
	fun `reset starts a fresh generation and stale asynchronous work cannot enter it`() {
		val oldRun = EvidenceCoordinator().also(EvidenceCoordinator::start)
		oldRun.putNotification(brave, hint("old"))
		oldRun.stop()

		val newRun = EvidenceCoordinator().also(EvidenceCoordinator::start)
		assertTrue(newRun.runGeneration > oldRun.runGeneration)
		assertFalse(oldRun.putNotification(brave, hint("late old callback")))
		assertNull(newRun.notificationFor(brave, "late old callback", null, false))

		val before = newRun.runGeneration
		newRun.reset()
		assertTrue(newRun.runGeneration > before)
		assertNull(newRun.notificationFor(brave, "old", null, false))
	}

	@Test
	fun `the same package in two source epochs is independently isolated`() {
		val evidence = EvidenceCoordinator().also(EvidenceCoordinator::start)
		evidence.putNotification(native1, hint("epoch one"))
		evidence.putNotification(native2, hint("epoch two"))

		assertEquals("epoch one", evidence.notificationFor(native1, "epoch one", null, false)?.title)
		assertEquals("epoch two", evidence.notificationFor(native2, "epoch two", null, false)?.title)

		evidence.resetSource(native1)
		assertNull(evidence.notificationFor(native1, "epoch one", null, false))
		assertEquals("epoch two", evidence.notificationFor(native2, "epoch two", null, false)?.title)
	}

	@Test
	fun `two track instances in one source never share accepted evidence`() {
		val evidence = EvidenceCoordinator().also(EvidenceCoordinator::start)
		val first = TrackInstanceId(brave.packageName, 11)
		val second = TrackInstanceId(brave.packageName, 12)

		evidence.bindTrack(brave, first, establishedAtMillis = 100)
		evidence.bindTrack(brave, second, establishedAtMillis = 100)
		evidence.acceptTrackAd(brave, first, "Sponsored", atMillis = 200)

		assertEquals("Sponsored", evidence.trackAd(brave, first, nowMillis = 201))
		assertNull(evidence.trackAd(brave, second, nowMillis = 201))
	}

	@Test
	fun `clearing URL state cannot clear unrelated track or source evidence`() {
		val evidence = EvidenceCoordinator().also(EvidenceCoordinator::start)
		val track = TrackInstanceId(brave.packageName, 21)
		evidence.putNotification(brave, hint("survives"))
		evidence.putUrl(brave, url("https://youtube.com/watch?v=dQw4w9WgXcQ", "dQw4w9WgXcQ"))
		evidence.bindTrack(brave, track, establishedAtMillis = 100)
		evidence.acceptTrackAd(brave, track, "Sponsored", atMillis = 200)

		evidence.clearUrl(brave)

		assertNull(evidence.urlFor(brave, nowMillis = 201))
		assertEquals("survives", evidence.notificationFor(brave, "survives", null, false)?.title)
		assertEquals("Sponsored", evidence.trackAd(brave, track, nowMillis = 201))
	}

	@Test
	fun `one source is serialized while different sources retain independent generations`() {
		val evidence = EvidenceCoordinator().also(EvidenceCoordinator::start)
		val pool = Executors.newFixedThreadPool(8)
		try {
			val braveWrites = (1..100).map { i ->
				Callable {
					val id = i.toString().padStart(11, '0')
					evidence.putUrl(brave, url("https://youtube.com/watch?v=$id", id))
				}
			}
			val nativeWrites = (1..100).map { i ->
				Callable {
					val id = i.toString().padStart(10, '0') + "n"
					evidence.putUrl(native1, url("https://youtube.com/watch?v=$id", id))
				}
			}
			pool.invokeAll(braveWrites + nativeWrites).forEach { it.get() }
		} finally {
			pool.shutdownNow()
		}

		assertEquals(100, evidence.urlGeneration(brave))
		assertEquals(100, evidence.urlGeneration(native1))
	}

	@Test
	fun `an injected adapter never falls back to stale singleton evidence`() {
		val evidence = EvidenceCoordinator().also(EvidenceCoordinator::start)
		NotificationHints.put(
			brave.packageName,
			hint("stale process global").copy(atMillis = System.currentTimeMillis()),
		)
		try {
			val adapter = BrowserYouTubeAdapter(brave.packageName, "Brave", evidence)
			assertNull(adapter.hostNotificationHint(fields = null, soleSession = true))

			evidence.putNotification(
				brave,
				hint("owned by this run").copy(atMillis = System.currentTimeMillis()),
			)
			assertEquals(
				"owned by this run",
				adapter.hostNotificationHint(fields = null, soleSession = true)?.title,
			)
		} finally {
			NotificationHints.clearAll()
		}
	}

	@Test
	fun `ad and coverage state is bound to one source session and track instance`() {
		val evidence = EvidenceCoordinator().also(EvidenceCoordinator::start)
		val signature = TrackIdentity("Song", "Artist", null, 180_000)
		val first = MediaSessionAdEvidence.TrackInstance(brave.packageName, 31, signature)
		val second = MediaSessionAdEvidence.TrackInstance(brave.packageName, 32, signature)
		evidence.bindTrack(brave, first, establishedAtMillis = 1_000)
		evidence.bindTrack(brave, second, establishedAtMillis = 1_000)

		val accepted = evidence.observeTrackAd(
			brave,
			MediaSessionAdEvidence.Observation(brave.packageName, "Sponsored", 1_100),
			first,
			instanceEstablishedBeforeObservation = true,
			unambiguous = true,
		)
		assertEquals(31L, accepted?.instance?.token)
		assertNull(
			evidence.observeTrackAd(
				brave,
				MediaSessionAdEvidence.Observation(brave.packageName, "Sponsored", 1_101),
				second,
				instanceEstablishedBeforeObservation = false,
				unambiguous = false,
			),
		)

		val scan = MediaSessionAccessibilityEvidence.Scan(
			packageName = brave.packageName,
			host = "youtube.com",
			rootVisible = true,
			videoId = "dQw4w9WgXcQ",
			atMillis = 1_200,
		)
		assertNotNull(evidence.observeCoverage(brave, scan, first, false, true))
		assertNotNull(evidence.currentCoverage(brave, first, nowMillis = 1_201))
		assertNull(evidence.currentCoverage(brave, second, nowMillis = 1_201))
	}

	@Test
	fun `native playlist state cannot cross a source epoch`() {
		val evidence = EvidenceCoordinator().also(EvidenceCoordinator::start)
		evidence.observeNativePlaylist(
			native1,
			NativePlaylistParser.Result.Context("Epoch one list", "Owner", 1, 10),
			1_000,
		)

		assertEquals("Epoch one list", evidence.nativePlaylist(native1)?.playlistName)
		assertNull(evidence.nativePlaylist(native2))
		evidence.resetSource(native1)
		assertNull(evidence.nativePlaylist(native1))
	}
}
