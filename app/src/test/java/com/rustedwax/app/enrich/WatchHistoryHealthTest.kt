package com.rustedwax.app.enrich

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule the owner asked for: the history route must refuse outright when the
 * YouTube app is signed out, on a different account, or in incognito — not keep
 * fetching a page that can never describe this phone's playback.
 *
 * None of those three is separable from the feed, so the evidence is the miss
 * itself: a feed that reads fine and repeatedly does not contain what just
 * played. The two conditions that *are* separable — a dead session and a paused
 * history — are YouTube's own answer and refuse on the first sight of them.
 */
class WatchHistoryHealthTest {

	private val minute = 60_000L

	@Test
	fun `a working route runs`() {
		val health = WatchHistoryHealth()
		assertTrue(health.mayRun(0))
		assertNull(health.refusedBecause)
	}

	@Test
	fun `one miss is a video that has not landed yet, not a diagnosis`() {
		val health = WatchHistoryHealth()
		health.recordMiss(0)
		health.recordMiss(minute)
		assertNull(health.refusedBecause)
		assertTrue(health.mayRun(2 * minute))
	}

	@Test
	fun `three consecutive misses stop the route and name every possible cause`() {
		val health = WatchHistoryHealth()
		repeat(WatchHistoryHealth.MISSES_BEFORE_REFUSING) { health.recordMiss(it * minute) }
		val reason = health.refusedBecause
		assertNotNull(reason)
		assertTrue(reason!!.contains("signed out"))
		assertTrue(reason.contains("different account"))
		assertTrue(reason.contains("incognito"))
		assertFalse(health.mayRun(3 * minute))
	}

	/**
	 * Measured 2026-08-06. "See Every TIME Cover From 2025" was looked up three
	 * times in three minutes as the owner replayed it, each absence counted, and
	 * the route stood itself down on "the last 3 native tracks were not written"
	 * — a claim about one track. Two untitled Shorts at 62% and 77% were then
	 * refused inside the fifteen-minute pause that followed.
	 */
	@Test
	fun `one track missing repeatedly is one data point, not three`() {
		val health = WatchHistoryHealth()
		repeat(5) { health.recordMiss(it * minute, "see every time cover from 2025|time|208") }
		assertNull(health.refusedBecause)
		assertTrue(health.mayRun(5 * minute))

		// Different tracks going missing is the condition this exists for, and
		// it still diagnoses on the third one.
		health.recordMiss(6 * minute, "another track|channel|100")
		assertNull(health.refusedBecause)
		health.recordMiss(7 * minute, "a third track|channel|120")
		assertNotNull(health.refusedBecause)
	}

	/**
	 * The set is a set, not a one-deep memory.
	 *
	 * `consecutiveMisses` plus `lastMissKey` only rejected a repeat that arrived
	 * immediately after itself, so `A, B, A` was three tracks and so was
	 * `<empty feed>, A, <empty feed>`. Two things going missing while one of them
	 * is retried is two things, and a Shorts feed revisits the same items
	 * constantly.
	 */
	@Test
	fun `an interleaved repeat is not a third distinct track`() {
		val abA = WatchHistoryHealth()
		abA.recordMiss(0, "a|chan|10")
		abA.recordMiss(minute, "b|chan|20")
		abA.recordMiss(2 * minute, "a|chan|10")
		assertNull("A,B,A diagnosed three missing tracks", abA.refusedBecause)
		// A genuinely third track still trips it.
		abA.recordMiss(3 * minute, "c|chan|30")
		assertNotNull(abA.refusedBecause)

		val emptyAEmpty = WatchHistoryHealth()
		emptyAEmpty.recordUnavailable(WatchHistoryParser.Reason.EMPTY, "none", 0)
		emptyAEmpty.recordMiss(minute, "a|chan|10")
		emptyAEmpty.recordUnavailable(WatchHistoryParser.Reason.EMPTY, "none", 2 * minute)
		assertNull("EMPTY,A,EMPTY diagnosed three missing tracks", emptyAEmpty.refusedBecause)
		// EMPTY, A, B is still three distinct observations.
		emptyAEmpty.recordMiss(3 * minute, "b|chan|20")
		assertNotNull(emptyAEmpty.refusedBecause)
	}

	/**
	 * A browser lookup is not evidence about the *native* app's account, so the
	 * miss-count diagnosis neither blocks it nor is spent by it.
	 */
	@Test
	fun `the miss-count diagnosis does not gate or get probed by browser lookups`() {
		val health = WatchHistoryHealth()
		repeat(3) { health.recordMiss(0, "gone $it|chan|10") }
		assertNotNull(health.refusedBecause)

		// A browser read runs, and asking does not move the native probe.
		assertTrue(health.mayRun(minute, accountEvidence = false))
		assertTrue(health.mayRun(2 * minute, accountEvidence = false))
		assertFalse(
			"a browser read postponed the native probe",
			health.mayRun(WatchHistoryHealth.RETRY_INTERVAL_MS - 1),
		)
		assertTrue(health.mayRun(WatchHistoryHealth.RETRY_INTERVAL_MS))
	}

	/** A fault YouTube stated is about the session, so it stops every route. */
	@Test
	fun `a declared fault fails closed for native and browser before the interval`() {
		listOf(
			WatchHistoryParser.Reason.SIGNED_OUT,
			WatchHistoryParser.Reason.HISTORY_PAUSED,
			WatchHistoryParser.Reason.MARKUP_CHANGED,
		).forEach { reason ->
			val health = WatchHistoryHealth()
			health.recordUnavailable(reason, "detail", 0)
			assertNotNull(health.refusedBecause)
			assertFalse(
				"$reason let a browser lookup through",
				health.mayRun(minute, accountEvidence = false),
			)
			assertFalse(
				"$reason let a native lookup through",
				health.mayRun(minute, accountEvidence = true),
			)
		}
	}

	/**
	 * `<redacted-private-path>` §5. A browser-only user who fixes a recoverable session
	 * fault must not stay blocked: nothing they can play is native evidence, so
	 * if only native playback could make the recovery probe there would be no
	 * way back short of an app restart.
	 */
	@Test
	fun `either source may make the due declared-fault probe, and a healthy feed clears it`() {
		val health = WatchHistoryHealth()
		health.recordUnavailable(WatchHistoryParser.Reason.SIGNED_OUT, "detail", 0)

		assertFalse(health.mayRun(WatchHistoryHealth.RETRY_INTERVAL_MS - 1, accountEvidence = false))
		// The browser gets the probe, because it asked first.
		assertTrue(health.mayRun(WatchHistoryHealth.RETRY_INTERVAL_MS, accountEvidence = false))
		assertFalse(
			"the declared-fault probe was not one probe",
			health.mayRun(WatchHistoryHealth.RETRY_INTERVAL_MS + 1, accountEvidence = true),
		)

		// A fetched and parsed feed is the route working, whoever fetched it.
		health.recordRouteHealthy()
		assertNull(health.refusedBecause)
		assertTrue(health.mayRun(WatchHistoryHealth.RETRY_INTERVAL_MS + 2, accountEvidence = false))
	}

	@Test
	fun `a failed declared-fault probe waits another whole interval`() {
		val health = WatchHistoryHealth()
		health.recordUnavailable(WatchHistoryParser.Reason.HISTORY_PAUSED, "paused", 0)
		assertTrue(health.mayRun(WatchHistoryHealth.RETRY_INTERVAL_MS, accountEvidence = false))
		// Still faulted: the probe found the same thing and said so again.
		health.recordUnavailable(
			WatchHistoryParser.Reason.HISTORY_PAUSED,
			"paused",
			WatchHistoryHealth.RETRY_INTERVAL_MS,
		)
		assertNotNull(health.refusedBecause)
		assertFalse(health.mayRun(2 * WatchHistoryHealth.RETRY_INTERVAL_MS - 1))
		assertTrue(health.mayRun(2 * WatchHistoryHealth.RETRY_INTERVAL_MS))
	}

	/**
	 * Clearing the global route fault says the *route* works. It says nothing
	 * about whether this phone's playback is in this account, so the native
	 * mismatch run has to survive it untouched.
	 */
	@Test
	fun `declared-fault recovery does not erase native mismatch evidence`() {
		val health = WatchHistoryHealth()
		health.recordMiss(0, "a|chan|100")
		health.recordMiss(minute, "b|chan|200")
		health.recordUnavailable(WatchHistoryParser.Reason.SIGNED_OUT, "detail", 2 * minute)

		health.recordRouteHealthy()
		assertNull("the route fault survived a healthy feed", health.refusedBecause)

		// The two earlier native misses are still on the run: a third trips.
		health.recordMiss(3 * minute, "c|chan|300")
		assertNotNull(
			"a browser route recovery silently cleared native mismatch evidence",
			health.refusedBecause,
		)
	}

	/** An EMPTY feed seen by a browser lookup is not native evidence. */
	@Test
	fun `an empty feed seen by a browser lookup adds no native evidence`() {
		val health = WatchHistoryHealth()
		repeat(5) {
			health.recordUnavailable(
				WatchHistoryParser.Reason.EMPTY,
				"none",
				it * minute,
				accountEvidence = false,
			)
		}
		assertNull(health.refusedBecause)
		health.recordMiss(6 * minute, "a|chan|10")
		health.recordMiss(7 * minute, "b|chan|20")
		assertNull("browser-seen empty feeds became native evidence", health.refusedBecause)
	}

	@Test
	fun `a track landing in the feed clears the refusal`() {
		val health = WatchHistoryHealth()
		repeat(3) { health.recordMiss(it * minute) }
		health.recordHit()
		assertNull(health.refusedBecause)
		assertTrue(health.mayRun(3 * minute))
	}

	/** A verdict that could never be revisited would need an app restart to clear. */
	@Test
	fun `a refused route re-probes once per retry interval`() {
		val health = WatchHistoryHealth()
		repeat(3) { health.recordMiss(0) }
		assertFalse(health.mayRun(WatchHistoryHealth.RETRY_INTERVAL_MS - 1))
		assertTrue(health.mayRun(WatchHistoryHealth.RETRY_INTERVAL_MS))
		// One probe, not a reopened gate.
		assertFalse(health.mayRun(WatchHistoryHealth.RETRY_INTERVAL_MS + 1))
	}

	@Test
	fun `a dead session refuses immediately and says to sign in again`() {
		val health = WatchHistoryHealth()
		health.recordUnavailable(
			WatchHistoryParser.Reason.SIGNED_OUT,
			"responseContext.loggedOut",
			0,
		)
		assertTrue(health.refusedBecause!!.contains("no longer signed in"))
	}

	@Test
	fun `a paused history refuses immediately and says where to fix it`() {
		val health = WatchHistoryHealth()
		health.recordUnavailable(WatchHistoryParser.Reason.HISTORY_PAUSED, "paused", 0)
		assertTrue(health.refusedBecause!!.contains("paused"))
		assertTrue(health.refusedBecause!!.contains("YouTube settings"))
	}

	/**
	 * An empty feed on a live session is the same evidence a miss is — and it
	 * obeys the same same-observation rule, which it used to slip past.
	 *
	 * It passed no track key at all, so the `trackKey == lastMissKey` guard could
	 * never fire and three reads of one empty feed were three data points about
	 * one fact. Reading the same feed twice is not two accounts' worth of
	 * evidence, so repeated emptiness is one, and the diagnosis still needs two
	 * other distinct observations before it stands the route down.
	 */
	@Test
	fun `repeated empty feeds are one observation, not three`() {
		val health = WatchHistoryHealth()
		repeat(5) {
			health.recordUnavailable(WatchHistoryParser.Reason.EMPTY, "no entries", it * minute)
		}
		assertNull(health.refusedBecause)
		assertTrue(health.mayRun(5 * minute))

		// It is still real evidence: it just has to be joined by other, different
		// evidence before it means anything about the account.
		health.recordMiss(6 * minute, "a real track|channel|100")
		assertNull(health.refusedBecause)
		health.recordMiss(7 * minute, "another real track|channel|120")
		assertNotNull(health.refusedBecause)
	}

	@Test
	fun `a network failure says nothing about the account`() {
		val health = WatchHistoryHealth()
		repeat(10) { health.recordFetchFailure() }
		assertNull(health.refusedBecause)
	}

	@Test
	fun `a monitoring boundary starts the diagnosis over`() {
		val health = WatchHistoryHealth()
		repeat(3) { health.recordMiss(0) }
		health.reset()
		assertNull(health.refusedBecause)
		assertTrue(health.mayRun(0))
	}
}
