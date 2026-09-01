package com.rustedwax.app.detect

import com.rustedwax.core.*
import com.rustedwax.core.PlaybackSourceCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The deadline that ends a listen whose source has gone silent.
 *
 * Every case here was previously unreachable from a test, because the timer was
 * a `handler.postDelayed` inside a private inner class over `MediaController`.
 * Two of them are defects that survived exactly because of that: a listen that
 * was already playing when its watcher was built never armed a deadline, and a
 * length that arrived after the listen started never replaced the ceiling it was
 * armed with.
 *
 * The scheduler is a fake, so "the deadline fired" is a thing the test does
 * rather than a thing it waits for. The reducer is real: what an expiry *means*
 * is `PlaybackReducer`'s decision, and asserting it against anything else would
 * be asserting a second implementation.
 */
class IdleFinalizationTest {

	/** A scheduler that records rather than schedules. */
	private class FakeScheduler : IdleFinalization.Scheduler {
		data class Scheduled(val delayMs: Long, val action: () -> Unit)

		val scheduled = mutableListOf<Scheduled>()

		override fun postDelayed(delayMs: Long, action: () -> Unit) {
			scheduled += Scheduled(delayMs, action)
		}

		val delays: List<Long> get() = scheduled.map { it.delayMs }

		/** Run everything armed so far, oldest first, as a real handler would. */
		fun fireAll() = scheduled.toList().forEach { it.action() }

		fun fireLast() = scheduled.last().action()
	}

	private val browser = PlaybackSourceCapabilities(
		republishesShorterDurations = false,
		requiresExactIdToCarryProgress = false,
		usesStoppedReplacementGrace = false,
		supportsPictureInPictureInference = false,
	)

	private val identity = TrackIdentity("Never Gonna Give You Up", "Rick Astley", null, null)

	/** The world the coordinator reads, all of it writable by the test. */
	private class World(
		var listen: ListenState,
		var elapsedRealtimeMs: Long = 0,
		var durationMs: Long? = null,
	) {
		val deadlines = mutableListOf<Pair<Long?, Long>>()
	}

	private fun coordinator(world: World, scheduler: FakeScheduler) = IdleFinalization(
		scheduler = scheduler,
		listenState = { world.listen },
		elapsedRealtimeMs = { world.elapsedRealtimeMs },
		durationMs = { world.durationMs },
		onDeadline = { durationMs, elapsedRealtimeMs ->
			world.deadlines += durationMs to elapsedRealtimeMs
		},
	)

	private fun playingListen(playedMs: Long = 0, sinceElapsedMs: Long? = 0) = ListenState(
		trackIdentity = identity,
		instanceToken = 1,
		instanceEstablishedAtMillis = 1_700_000_000_000,
		startedAtEpochSec = 1_700_000_000,
		transport = TransportState.PLAYING,
		playingSinceElapsedMs = sinceElapsedMs,
		playedMs = playedMs,
		everPublishedMetadata = true,
	)

	// ── arming ─────────────────────────────────────────────────────────────

	/**
	 * The first defect, as the case that produced it.
	 *
	 * A watcher built around a session that is already playing receives no
	 * callback — nothing has changed yet — so a coordinator that only armed on
	 * the reduce path armed nothing. Every listener-service rebuild and every
	 * process start with a video already going landed here, which is precisely
	 * the unbounded listen the deadline exists to prevent.
	 */
	@Test
	fun `a listen that was already playing at construction is armed straight away`() {
		val scheduler = FakeScheduler()
		val world = World(playingListen(), durationMs = 213_000)

		coordinator(world, scheduler).rearm()

		assertEquals(1, scheduler.scheduled.size)
		assertEquals(213_000 + PlaybackReducer.IDLE_FINALIZE_GRACE_MS, scheduler.delays.single())
	}

	@Test
	fun `no length from anywhere falls back to the silence ceiling`() {
		// Brave listed DURATION among the *unset* keys for an entire listen. "No
		// length" cannot mean "no deadline", or the one source that most needs
		// bounding is the one that never gets it.
		val scheduler = FakeScheduler()
		val world = World(playingListen(), durationMs = null)

		coordinator(world, scheduler).rearm()

		assertEquals(
			PlaybackReducer.IDLE_FINALIZE_MAX_SILENCE_MS,
			scheduler.delays.single(),
		)
	}

	/**
	 * The second defect.
	 *
	 * The precise deadline needs the item's length, and where the session
	 * publishes none it comes from the video's own cached page — fetched
	 * asynchronously. Nothing told the watcher when that landed, so the listen
	 * kept the blunt ceiling it was armed with. The owning Watch's prefetch
	 * completion is what now calls this, and this is what it has to do.
	 */
	@Test
	fun `a length that arrives late replaces the ceiling with a precise deadline`() {
		val scheduler = FakeScheduler()
		val world = World(playingListen(), durationMs = null)
		val idle = coordinator(world, scheduler)

		idle.rearm()
		assertEquals(PlaybackReducer.IDLE_FINALIZE_MAX_SILENCE_MS, scheduler.delays.single())

		// The page resolves; the cache now answers.
		world.durationMs = 213_000
		world.elapsedRealtimeMs = 10_000
		idle.rearm()

		assertEquals(
			listOf(
				PlaybackReducer.IDLE_FINALIZE_MAX_SILENCE_MS,
				213_000 - 10_000 + PlaybackReducer.IDLE_FINALIZE_GRACE_MS,
			),
			scheduler.delays,
		)
	}

	@Test
	fun `a paused listen arms nothing, and re-arming after a pause drops the deadline`() {
		val scheduler = FakeScheduler()
		val world = World(playingListen(), durationMs = 213_000)
		val idle = coordinator(world, scheduler)

		idle.rearm()
		assertTrue(idle.armed)

		// A pause is not an ending — a paused track is usually resumed — but it is
		// also not a source going silent while claiming to play, so nothing is owed.
		world.listen = world.listen.copy(
			transport = TransportState.PAUSED,
			playingSinceElapsedMs = null,
		)
		idle.rearm()

		assertFalse(idle.armed)
		assertEquals("no second deadline was scheduled", 1, scheduler.scheduled.size)
	}

	@Test
	fun `a metadata refresh pushes the deadline out`() {
		val scheduler = FakeScheduler()
		val world = World(playingListen(), durationMs = 213_000)
		val idle = coordinator(world, scheduler)

		idle.rearm()
		// 100 s later the source publishes something. The listen has consumed 100 s
		// of its 213 s, so what is left to wait for is the remainder plus the grace.
		world.elapsedRealtimeMs = 100_000
		idle.rearm()

		assertEquals(
			listOf(
				213_000 + PlaybackReducer.IDLE_FINALIZE_GRACE_MS,
				113_000 + PlaybackReducer.IDLE_FINALIZE_GRACE_MS,
			),
			scheduler.delays,
		)
	}

	// ── expiry ─────────────────────────────────────────────────────────────

	@Test
	fun `a superseded deadline does nothing when it fires`() {
		// The stale-timer case. A handler cannot un-post, so an expiry that has
		// been overtaken has to notice and drop itself — otherwise every re-arm
		// leaves a live timer behind and a long listen ends the moment the first
		// one matures.
		val scheduler = FakeScheduler()
		val world = World(playingListen(), durationMs = 213_000)
		val idle = coordinator(world, scheduler)

		idle.rearm()
		world.elapsedRealtimeMs = 50_000
		idle.rearm()

		scheduler.scheduled.first().action()
		assertTrue("the superseded timer must be inert", world.deadlines.isEmpty())

		scheduler.scheduled.last().action()
		assertEquals(1, world.deadlines.size)
	}

	@Test
	fun `cancelling drops a pending deadline without arming another`() {
		val scheduler = FakeScheduler()
		val world = World(playingListen(), durationMs = 213_000)
		val idle = coordinator(world, scheduler)

		idle.rearm()
		idle.cancel()
		scheduler.fireAll()

		assertFalse(idle.armed)
		assertTrue(world.deadlines.isEmpty())
	}

	@Test
	fun `an expiry re-reads the length rather than spending the one it was armed with`() {
		// Armed on the ceiling, fired after the page landed: the reducer has to be
		// told what is known *now*, or it compares played time against nothing and
		// takes the silence branch for a listen whose length is perfectly well known.
		val scheduler = FakeScheduler()
		val world = World(playingListen(), durationMs = null)
		val idle = coordinator(world, scheduler)

		idle.rearm()
		world.durationMs = 213_000
		world.elapsedRealtimeMs = 900_000
		scheduler.fireLast()

		assertEquals(listOf<Pair<Long?, Long>>(213_000L to 900_000L), world.deadlines)
	}

	// ── what an expiry means, decided by the real reducer ──────────────────

	@Test
	fun `an expired deadline on a listen that consumed its length ends it`() {
		val reducer = PlaybackReducer(browser)
		val scheduler = FakeScheduler()
		val world = World(playingListen(sinceElapsedMs = 0), durationMs = 213_000)
		val idle = IdleFinalization(
			scheduler = scheduler,
			listenState = { world.listen },
			elapsedRealtimeMs = { world.elapsedRealtimeMs },
			durationMs = { world.durationMs },
			onDeadline = { durationMs, elapsedRealtimeMs ->
				world.listen = reducer.reduce(
					world.listen,
					PlaybackInput.IdleDeadlineReached(durationMs, elapsedRealtimeMs),
				).let { transition ->
					world.deadlines += durationMs to elapsedRealtimeMs
					assertTrue(
						"the listen ran out and nothing said so",
						transition.effects.any { it is PlaybackEffect.Finalize },
					)
					transition.state
				}
			},
		)

		idle.rearm()
		world.elapsedRealtimeMs = 250_000
		scheduler.fireLast()

		assertEquals(1, world.deadlines.size)
	}

	/**
	 * The finalized-timer case.
	 *
	 * A track can end for its own reasons — a track change, a navigation — after
	 * a deadline was armed and before it matures. The `Watch` that armed it is
	 * still alive, so the timer still fires; the listen it was about is over. The
	 * reducer refuses, and it is the reducer that has to, because the finalized
	 * latch is what stops one track scoring twice.
	 */
	@Test
	fun `an expired deadline on a listen that already finalized changes nothing`() {
		val reducer = PlaybackReducer(browser)
		val scheduler = FakeScheduler()
		val world = World(playingListen(), durationMs = 213_000)
		val idle = coordinator(world, scheduler)

		idle.rearm()
		world.listen = world.listen.copy(finalized = true)
		world.elapsedRealtimeMs = 250_000
		scheduler.fireLast()

		assertEquals(1, world.deadlines.size)
		val transition = reducer.reduce(
			world.listen,
			PlaybackInput.IdleDeadlineReached(213_000, 250_000),
		)
		assertEquals(world.listen, transition.state)
		assertTrue(transition.effects.isEmpty())
		assertTrue(transition.before.isEmpty())
	}

	@Test
	fun `a deadline that matures early leaves a listen still in progress alone`() {
		// A rate change or a seek can push the arithmetic; the reducer re-checks
		// that the item's length is genuinely used up before ending anything.
		val reducer = PlaybackReducer(browser)
		val transition = reducer.reduce(
			playingListen(sinceElapsedMs = 0),
			PlaybackInput.IdleDeadlineReached(durationMs = 213_000, elapsedRealtimeMs = 100_000),
		)

		assertTrue(transition.effects.isEmpty())
	}

	// ── several tabs at once ───────────────────────────────────────────────

	/**
	 * One deadline per listen, and no crosstalk.
	 *
	 * `Watch` owns one of these each, so two browser tabs have two independent
	 * timers. A shared token would let the second tab's arming cancel the first
	 * tab's deadline, which is the unbounded listen again — this time invisible,
	 * because a deadline *was* armed.
	 */
	@Test
	fun `two listens keep separate deadlines`() {
		val scheduler = FakeScheduler()
		val first = World(playingListen(), durationMs = 213_000)
		val second = World(playingListen(), durationMs = 60_000)
		val firstIdle = coordinator(first, scheduler)
		val secondIdle = coordinator(second, scheduler)

		firstIdle.rearm()
		secondIdle.rearm()
		// The second tab publishes something; only its own deadline moves.
		second.elapsedRealtimeMs = 10_000
		secondIdle.rearm()

		scheduler.fireAll()

		assertEquals("the first tab's deadline still fires", 1, first.deadlines.size)
		assertEquals("the second tab fires once, on its newest deadline", 1, second.deadlines.size)
		assertEquals(10_000L, second.deadlines.single().second)
	}

	@Test
	fun `a listen the foreground Shorts route owns arms nothing`() {
		val scheduler = FakeScheduler()
		val world = World(
			playingListen().copy(suppressedByForegroundShort = true),
			durationMs = 60_000,
		)

		coordinator(world, scheduler).rearm()

		assertTrue(scheduler.scheduled.isEmpty())
	}

	@Test
	fun `a browser naming only its tab arms nothing`() {
		val scheduler = FakeScheduler()
		val world = World(
			playingListen().copy(describingTabOnly = true),
			durationMs = 213_000,
		)

		coordinator(world, scheduler).rearm()

		assertTrue(scheduler.scheduled.isEmpty())
		assertNull(
			world.listen.copy(describingTabOnly = true)
				.idleFinalizeDelayMs(elapsedRealtimeMs = 0, durationMs = 213_000),
		)
	}
}
