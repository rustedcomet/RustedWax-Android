package com.rustedwax.app.detect

import com.rustedwax.core.*
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionProbeDeviceTest {

	private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
	private val context: Context get() = instrumentation.targetContext

	private lateinit var probe: SessionProbe
	private val finalized = mutableListOf<SessionSnapshot>()
	private val sessions = mutableListOf<MediaSession>()

	private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

	/**
	 * Which packages the probe under test watches.
	 *
	 * Passed to the [SessionProbe] constructor, so it is per-instance and
	 * immutable — there is no global runtime state a shipped build could read, and
	 * `NativeSourceSwitches` is left exactly as production compiles it. A test
	 * cannot create a `MediaSession` under Brave's package name, and without one
	 * watchable package the real callback path is unreachable from the device.
	 *
	 * The production answer is kept for every other package, so a real target is
	 * still accepted and a real non-target still refused; only this app's own
	 * package is added.
	 *
	 * **What it cannot buy.** It changes which packages are *watched*, not what a
	 * package *is*. These sessions still resolve to browser capabilities through
	 * `SourceProfile`, so native exact-ID carry, stopped-replacement grace and
	 * picture-in-picture inference are not exercised by anything in this file.
	 */
	private fun watchable(packageName: String): Boolean =
		packageName == context.packageName || NativeSourceSwitches.acceptsPackage(packageName)

	/** Let the main looper drain whatever the platform has queued. */
	private fun settle(millis: Long = 400) {
		Thread.sleep(millis)
		onMain {}
	}

	private fun waitFor(what: String, timeoutMs: Long = 8_000, predicate: () -> Boolean) {
		val deadline = System.currentTimeMillis() + timeoutMs
		while (System.currentTimeMillis() < deadline) {
			if (predicate()) return
			Thread.sleep(100)
			onMain {}
		}
		throw AssertionError("timed out waiting for $what")
	}

	private fun newSession(tag: String): MediaSession {
		lateinit var created: MediaSession
		onMain {
			created = MediaSession(context, tag).also {
				it.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS)
				it.isActive = true
			}
			sessions += created
		}
		return created
	}

	private fun MediaSession.publish(
		title: String? = null,
		artist: String? = null,
		durationMs: Long? = null,
		mediaId: String? = null,
	) = onMain {
		setMetadata(
			MediaMetadata.Builder().apply {
				title?.let { putString(MediaMetadata.METADATA_KEY_TITLE, it) }
				artist?.let { putString(MediaMetadata.METADATA_KEY_ARTIST, it) }
				durationMs?.let { putLong(MediaMetadata.METADATA_KEY_DURATION, it) }
				mediaId?.let { putString(MediaMetadata.METADATA_KEY_MEDIA_ID, it) }
			}.build(),
		)
	}

	/**
	 * Retire a session the way the platform notices soonest.
	 *
	 * `release()` alone leaves the record active long enough for the probe to
	 * re-adopt it; clearing `isActive` first is what takes it out of
	 * `getActiveSessions` promptly.
	 */
	private fun MediaSession.retire() = onMain {
		runCatching { isActive = false }
		runCatching { release() }
	}

	private fun MediaSession.transport(state: Int, positionMs: Long, speed: Float = 1f) = onMain {
		setPlaybackState(PlaybackState.Builder().setState(state, positionMs, speed).build())
	}

	private fun requireNotificationAccess() {
		val enabled = android.provider.Settings.Secure.getString(
			context.contentResolver,
			"enabled_notification_listeners",
		).orEmpty()
		assertTrue(
			"RustedWax does not hold notification access on this device, so no media " +
				"session can be read and nothing below can run. Grant it on the device: " +
				"Settings → Apps → Special access → Notification access → RustedWax. " +
				"(Reinstalling the app clears this grant.) Current holders: $enabled",
			enabled.contains(context.packageName),
		)
	}

	@Before
	fun setUp() {
		requireNotificationAccess()
		onMain {
			NativeSourceSwitches.configureForReplay(
				NativeSourceSwitches.Config(
					youTubeScrobbling = true,
					youtubeEnabled = true,
					youtubeMusicEnabled = true,
				),
			)
			RunScopedEvidence.clearAll(includeCarriedProgress = true)
			EventLog.applyPolicy(true)
			EventLog.clear()

			probe = SessionProbe(context, ::watchable)
			probe.onTrackFinalized = { finalized += it }
			probe.start()
		}
		settle()
		// A `MediaSession` released by the previous test can still be in the
		// platform's active list for a moment, and a probe that adopted it would
		// be watching two sessions for one package — which is a real production
		// state with real consequences (`MediaSessionAdEvidence.conflict`, a carry
		// claimed by the wrong watch) and not the one any test below is about.
		// Measured: this is what made `progress_carries_across_a_real_session_
		// replacement` fail in a full run and pass on its own.
		waitForQuiet()
		finalized.clear()
	}

	/**
	 * Wait until no session of ours is adopted, and *stays* unadopted.
	 *
	 * A single "nothing is published" reading is not enough. The platform's
	 * active-session callback lags a release by a few hundred milliseconds, so the
	 * previous test's session can be absent at the moment of the check and adopted
	 * immediately afterwards — measured 12:10:07 on this device, where the probe
	 * registered on two of our sessions inside half a second and the test that
	 * followed watched two listens instead of one.
	 */
	private fun waitForQuiet(quietForMs: Long = 1_200) {
		val deadline = System.currentTimeMillis() + 20_000
		var quietSince = 0L
		while (System.currentTimeMillis() < deadline) {
			if (published() == null) {
				if (quietSince == 0L) quietSince = System.currentTimeMillis()
				if (System.currentTimeMillis() - quietSince >= quietForMs) return
			} else {
				quietSince = 0L
			}
			Thread.sleep(100)
			onMain {}
		}
		throw AssertionError(
			"a previous test's MediaSession is still being adopted: " +
				probe.sessions.value.map { it.title },
		)
	}

	/*
	 * The diagnostic log is deliberately not used as a duplicate signal here.
	 *
	 * Two production behaviours make it undependable on a real device, and both
	 * are correct: `Watch.freezeAndReport` writes its `[finalize]` line only when
	 * `mayRecordIdentifyingDetail()` allows it, and §4.1 forbids an unproven
	 * source from naming itself in an exportable log — measured as zero lines
	 * every run for this app's own package. And `RustedWaxListenerService`
	 * re-applies the *user's* logging setting on every reconnect, where
	 * `EventLog.applyPolicy(false)` erases what was already written. A test that
	 * asserted on the log would be asserting on the owner's settings.
	 *
	 * The duplicate assertions below therefore read the two surfaces this suite
	 * genuinely owns: what reached `onTrackFinalized`, and what `SessionProbe`
	 * publishes for the UI. The log is compared line for line where it is
	 * deterministic — `ProbeParityTest`, across all 40 scenarios.
	 */

	/**
	 * The finalized listen for one particular track.
	 *
	 * Keyed by title rather than by arrival order: a stray session adopted from a
	 * neighbouring test would make `finalized.first()` a different listen, and the
	 * resulting failure would describe the wrong thing.
	 */
	private fun listenFor(title: String): SessionSnapshot {
		waitFor("a finalized listen for \"$title\"") { finalized.any { it.title == title } }
		return finalized.first { it.title == title }
	}

	@After
	fun tearDown() {

		onMain {
			sessions.forEach {
				runCatching { it.isActive = false }
				runCatching { it.release() }
			}
			sessions.clear()
		}
		settle()
		onMain {
			runCatching { probe.stop(finalizeTracks = false) }
			RunScopedEvidence.clearAll(includeCarriedProgress = true)
			EventLog.clear()
			EventLog.applyPolicy(false)
		}
		finalized.clear()
	}

	/**
	 * The snapshot this probe holds for one of our sessions.
	 *
	 * Scoped to a title rather than to the package. The instrumentation process
	 * outlives every test method, and a `MediaSession` released by a neighbouring
	 * test can still be listed as active for a moment afterwards — measured on this
	 * device as a probe registering two controllers for this package inside half a
	 * second. A wait that accepted "any session of ours" would then be satisfied by
	 * the wrong one, and the assertion that followed would describe a listen the
	 * test never created.
	 */
	private fun published(title: String? = null): SessionSnapshot? =
		probe.sessions.value.firstOrNull {
			it.packageName == context.packageName && (title == null || it.title == title)
		}

	// ── the probe attaches to the real platform at all ─────────────────────

	/**
	 * Notification access is genuinely granted and the listener really registers.
	 *
	 * `SessionProbe.start` catches `SecurityException` and records it in [error];
	 * a suite that ran with the permission missing would silently observe nothing
	 * and report every other test as trivially green.
	 */
	@Test
	fun the_probe_starts_against_the_real_media_session_manager() {
		assertNull("SessionProbe.start reported an error: ${probe.error.value}", probe.error.value)

		val session = newSession("attach")
		session.publish(title = "Attached", durationMs = 120_000)
		waitFor("the probe to adopt a real MediaSession") { published("Attached") != null }

		assertEquals("Attached", published("Attached")?.title)
	}

	/** The `sessions` StateFlow is what the Now card renders. */
	@Test
	fun ui_state_is_published_from_real_callbacks() {
		val session = newSession("ui")
		session.publish(title = "On The Card", durationMs = 100_000)
		waitFor("a published snapshot") { published("On The Card") != null }

		session.transport(PlaybackState.STATE_PLAYING, 0)
		waitFor("the card to show playing") { published("On The Card")?.isPlaying == true }

		session.transport(PlaybackState.STATE_PAUSED, 3_000)
		waitFor("the card to show paused") { published("On The Card")?.isPlaying == false }

		assertEquals("PAUSED", published("On The Card")?.playbackState)
	}

	// ── measurement through the real boundary ──────────────────────────────

	/**
	 * The measurement the JVM gate asserts, taken on a real clock.
	 *
	 * Three seconds of real playing time, ended by a real metadata change. If the
	 * production translation dropped `elapsedRealtimeMs`, published the wrong
	 * transport, or installed the incoming bundle before finalizing, this is
	 * where it shows.
	 */
	@Test
	fun a_real_track_change_finalizes_the_outgoing_listen_with_what_it_played() {
		val session = newSession("measure")
		session.publish(title = "First Track", artist = "Someone", durationMs = 100_000)
		session.transport(PlaybackState.STATE_PLAYING, 0)
		waitFor("playback to be observed") { published("First Track")?.isPlaying == true }

		Thread.sleep(3_000)
		session.publish(title = "Second Track", durationMs = 100_000)
		val listen = listenFor("First Track")
		assertEquals("the finalized listen is not the outgoing track", "First Track", listen.title)
		assertEquals("Someone", listen.artist)
		assertEquals(100_000L, listen.durationMs)
		assertTrue(
			"played ${listen.playedMs}ms for a ~3s real listen",
			listen.playedMs in 2_500..4_500,
		)
		assertNotNull("no instance token was stamped", listen.trackInstanceToken)
	}

	/** A paused transport stops the real clock, not just the virtual one. */
	@Test
	fun a_paused_listen_does_not_accrue_real_time() {
		val session = newSession("pause")
		session.publish(title = "Paused Track", durationMs = 100_000)
		session.transport(PlaybackState.STATE_PLAYING, 0)
		waitFor("playback to be observed") { published("Paused Track")?.isPlaying == true }

		Thread.sleep(1_500)
		session.transport(PlaybackState.STATE_PAUSED, 1_500)
		waitFor("the pause to be observed") { published("Paused Track")?.isPlaying == false }
		Thread.sleep(3_000)

		session.publish(title = "Next", durationMs = 100_000)
		val listen = listenFor("Paused Track")

		assertTrue(
			"played ${listen.playedMs}ms — the paused seconds were counted",
			listen.playedMs in 1_000..2_600,
		)
	}

	/** Rate scaling, through the real `PlaybackState.getPlaybackSpeed`. */
	@Test
	fun accelerated_playback_scales_measured_content_time() {
		val session = newSession("rate")
		session.publish(title = "Fast Track", durationMs = 600_000)
		session.transport(PlaybackState.STATE_PLAYING, 0, speed = 2f)
		waitFor("playback to be observed") { published("Fast Track")?.isPlaying == true }

		Thread.sleep(2_000)
		session.publish(title = "Next", durationMs = 100_000)
		val listen = listenFor("Fast Track")

		assertTrue(
			"played ${listen.playedMs}ms for 2s of real time at 2x",
			listen.playedMs in 3_400..5_200,
		)
	}

	// ── finalization causes ────────────────────────────────────────────────

	/**
	 * `STOPPED` ends a browser listen immediately — no replacement grace, because
	 * these sessions carry browser capabilities.
	 */
	@Test
	fun a_stopped_transport_finalizes_the_listen() {
		val session = newSession("stop")
		session.publish(title = "Stopped Track", durationMs = 100_000)
		session.transport(PlaybackState.STATE_PLAYING, 0)
		waitFor("playback to be observed") { published("Stopped Track")?.isPlaying == true }

		Thread.sleep(1_500)
		session.transport(PlaybackState.STATE_STOPPED, 1_500)
		assertEquals("Stopped Track", listenFor("Stopped Track").title)
	}

	@Test
	fun a_real_session_replacement_produces_exactly_one_combined_listen() {
		val first = newSession("carry-1")
		first.publish(title = "Carried Track", durationMs = 240_000, mediaId = "aaaaaaaaaaa")
		first.transport(PlaybackState.STATE_PLAYING, 0)
		waitFor("playback to be observed") { published("Carried Track")?.isPlaying == true }
		val originalToken = requireNotNull(published("Carried Track")).trackInstanceToken
		assertNotNull("session A had no instance identity", originalToken)

		Thread.sleep(3_000)
		first.retire()
		settle()
		assertTrue(
			"the destroyed session finalized instead of parking its progress",
			finalized.isEmpty(),
		)
		assertTrue(
			"the teardown parked nothing for a replacement to claim",
			TrackProgressCarry.hasPackage(context.packageName),
		)

		val second = newSession("carry-2")
		second.publish(title = "Carried Track", durationMs = 240_000, mediaId = "aaaaaaaaaaa")
		second.transport(PlaybackState.STATE_PLAYING, 3_000)
		waitFor("the replacement to claim the parked progress") {
			!TrackProgressCarry.hasPackage(context.packageName)
		}
		val replacementToken = requireNotNull(published("Carried Track")).trackInstanceToken
		assertEquals(
			"session B did not inherit session A's listen identity",
			originalToken,
			replacementToken,
		)

		// B plays on, then ends the listen with a real track change.
		Thread.sleep(2_000)
		second.publish(title = "Something Else", durationMs = 100_000, mediaId = "bbbbbbbbbbb")
		val listen = listenFor("Carried Track")

		assertEquals(
			"one continuous listen produced more than one finalization: " +
				finalized.joinToString {
					"${it.title}/${it.playedMs}ms/token=${it.trackInstanceToken}"
				},
			1,
			finalized.count { it.title == "Carried Track" },
		)
		assertTrue(
			"played ${listen.playedMs}ms — the replacement did not carry A's 3s forward",
			listen.playedMs >= 4_400,
		)
		assertEquals(
			"the finalized listen did not keep the identity shared by sessions A and B",
			originalToken,
			listen.trackInstanceToken,
		)

		// ---- the delayed-duplicate half -------------------------------------
		//
		// Everything above could hold and a second listen still arrive later, from
		// the replacement grace, the continuation timer or the idle deadline. So
		// the state is frozen here and re-read after all of them have had their
		// chance to fire.
		val finalizedThen = finalized.map { it.title to it.playedMs }
		val publishedThen = probe.sessions.value
			.filter { it.packageName == context.packageName }
			.map { it.title }

		// Past the idle deadline for the outgoing item (duration + 30s grace is far
		// out, but the *incoming* 100s item's deadline and the stopped/continuation
		// graces are all inside this window).
		Thread.sleep(35_000)
		onMain {}

		assertEquals(
			"a second finalization arrived after the graces expired",
			finalizedThen,
			finalized.map { it.title to it.playedMs },
		)
		assertEquals(
			"a duplicate session row appeared in the UI state after the graces expired",
			publishedThen,
			probe.sessions.value
				.filter { it.packageName == context.packageName }
				.map { it.title },
		)
	}

	/*
	 * Payload, retry-queue and UI-history duplication are deliberately **not**
	 * asserted here.
	 *
	 * `FinalizeTrackUseCase` is not wired to this probe, so reading its state would
	 * report "unchanged" whatever happened — a vacuous assertion dressed as a
	 * strong one. Wiring the real engine to a real device would mean a test that
	 * can write to an immutable chain with the owner's key, which is not a
	 * trade this suite makes.
	 *
	 * Those surfaces are asserted where finalization genuinely runs, against
	 * recording ports: `ProbeEndToEndParityTest`, which compares payload bytes and
	 * order, dedup claims, duplicate attempts, queue contents and depth, and both
	 * UI histories, on both implementations. What this test adds is the half that
	 * cannot be reached there — that the real platform hands the engine exactly one
	 * listen for one recreated session.
	 */

	@Test
	fun two_live_sessions_from_one_package_are_both_watched() {
		val tabOne = newSession("tab-1")
		tabOne.publish(title = "Tab One", durationMs = 240_000, mediaId = "aaaaaaaaaaa")
		tabOne.transport(PlaybackState.STATE_PLAYING, 0)
		waitFor("the first tab to be adopted") { published("Tab One") != null }

		val tabTwo = newSession("tab-2")
		tabTwo.publish(title = "Tab Two", durationMs = 240_000, mediaId = "bbbbbbbbbbb")
		tabTwo.transport(PlaybackState.STATE_PLAYING, 0)
		waitFor("the second tab to be adopted") { published("Tab Two") != null }

		val ours = probe.sessions.value.filter { it.packageName == context.packageName }
		assertEquals(
			"the tombstone rule collapsed two live sessions: ${ours.map { it.title }}",
			2,
			ours.size,
		)
		assertEquals(
			"two live tabs were not kept apart",
			setOf("Tab One", "Tab Two"),
			ours.mapNotNull { it.title }.toSet(),
		)
	}

	/**
	 * A direct `probe.stop()` / `probe.start()` cycle — **not** a listener rebind.
	 *
	 * Named for what it does. This calls the probe's own lifecycle methods; it
	 * never executes `RustedWaxListenerService.onListenerDisconnected` or
	 * `onListenerConnected`, so it proves that a restarted probe re-attaches to a
	 * live session and nothing about the service wiring around it. The real
	 * service boundary is `ListenerServiceRebindTest`, which asks the platform to
	 * recycle the binding.
	 *
	 * Kept because it is fast and it does cover the probe half: the system takes
	 * the listener down mid-playback, and what is still playing must be picked up
	 * again rather than lost or double-counted.
	 */
	@Test
	fun a_direct_probe_stop_start_cycle_reattaches_to_a_live_session() {
		val session = newSession("rebuild")
		session.publish(title = "Across A Rebuild", durationMs = 400_000, mediaId = "aaaaaaaaaaa")
		session.transport(PlaybackState.STATE_PLAYING, 0)
		waitFor("playback to be observed") { published("Across A Rebuild")?.isPlaying == true }
		Thread.sleep(1_500)

		onMain { probe.stop(finalizeTracks = true) }
		settle()
		onMain { probe.start() }
		waitFor("the probe to re-adopt the live session") { published("Across A Rebuild") != null }

		assertEquals("Across A Rebuild", published("Across A Rebuild")?.title)
		session.transport(PlaybackState.STATE_PLAYING, 1_500)
		Thread.sleep(1_500)
		session.publish(title = "Next", durationMs = 100_000, mediaId = "bbbbbbbbbbb")
		waitFor("a listen to finalize after the rebuild") {
			finalized.any { it.title == "Across A Rebuild" }
		}
	}

	/**
	 * A user Stop must not write to an immutable chain on its way out — including
	 * from a timer armed before it.
	 *
	 * This is the defect the old/new parity gate found and
	 * `SessionProbe.AndroidSessionBinding.dispose` now cancels. Re-checked here on the real
	 * `Handler`, because the JVM version proves the cancel happens and this proves
	 * the platform timer it cancels is the one that would otherwise fire.
	 */
	@Test
	fun a_user_stop_leaves_no_timer_that_can_finalize_afterwards() {
		val session = newSession("user-stop")
		// A short item, so the idle deadline is a few seconds out rather than
		// minutes: (duration - played) + 30s grace.
		session.publish(title = "Discarded", durationMs = 1_000, mediaId = "aaaaaaaaaaa")
		session.transport(PlaybackState.STATE_PLAYING, 0)
		waitFor("playback to be observed") { published("Discarded")?.isPlaying == true }
		Thread.sleep(1_000)

		onMain { probe.stop(finalizeTracks = false) }
		// Past the deadline the listen had armed.
		Thread.sleep(32_000)
		onMain {}

		assertEquals(
			"a discarded listen was scored by a timer that outlived the Stop",
			emptyList<String>(),
			finalized.map { it.title },
		)
	}

	@Test
	fun a_silent_listen_past_its_own_length_is_ended_by_the_real_timer() {
		val session = newSession("idle")
		session.publish(title = "Runs Out", durationMs = 2_000)
		session.transport(PlaybackState.STATE_PLAYING, 0)
		waitFor("playback to be observed") { published("Runs Out")?.isPlaying == true }

		// Deadline is (2s remaining) + 30s grace; nothing further is published.
		waitFor("the idle deadline to end the listen", timeoutMs = 45_000) {
			finalized.isNotEmpty()
		}

		val listen = listenFor("Runs Out")
		assertTrue(
			"played ${listen.playedMs}ms — the deadline did not stop at length plus grace",
			listen.playedMs in 30_000..36_000,
		)
	}

	/**
	 * A parked continuation nothing claims, expiring on the **real wall clock**.
	 *
	 * `TrackProgressCarry.expire` compares `System.currentTimeMillis()` against
	 * the entry's own timestamp, so no virtual clock can reach it — the JVM gate
	 * records that as a named limitation
	 * (`ProbeParityTest.known limitation - the continuation deadline is
	 * wall-clock on both sides`). This is where it is actually proven. The
	 * position is kept below `TrackProgressCarry.RESUME_MIN_POSITION_MS` so the
	 * one-minute TTL applies rather than the fifteen-minute resumed one.
	 */
	@Test
	fun an_unclaimed_continuation_expires_on_the_real_clock() {
		val session = newSession("expire")
		session.publish(title = "Abandoned", durationMs = 1_800_000, mediaId = "aaaaaaaaaaa")
		session.transport(PlaybackState.STATE_PLAYING, 0)
		waitFor("playback to be observed") { published("Abandoned")?.isPlaying == true }

		Thread.sleep(3_000)
		session.retire()
		settle()
		assertTrue("the teardown finalized instead of parking", finalized.isEmpty())

		waitFor(
			"the continuation to expire on the real clock",
			timeoutMs = TimeUnit.SECONDS.toMillis(90),
		) { finalized.isNotEmpty() }

		val listen = listenFor("Abandoned")
		assertTrue(
			"played ${listen.playedMs}ms — the parked total was not scored",
			listen.playedMs in 2_500..4_500,
		)
	}

	/**
	 * The run-scoped evidence boundary, in one process — **not** a process death.
	 *
	 * Named for what it does. `RunScopedEvidence.clearAll` is called and another
	 * `SessionProbe` is constructed in the same process; nothing dies. That is the
	 * boundary the *code* crosses on a Stop and on a restart, and it is worth
	 * asserting that nothing leaks across it, but it is not evidence about process
	 * death.
	 *
	 * A real process death cannot be an in-process JUnit test: killing this app
	 * takes the runner with it. `tools/device/process-restart.sh` orchestrates that
	 * from the host with non-destructive `am` steps, and its result is reported
	 * separately rather than claimed here.
	 */
	@Test
	fun the_run_scoped_evidence_boundary_leaks_nothing_in_process() {
		val session = newSession("restart")
		session.publish(title = "Before The Restart", durationMs = 400_000, mediaId = "aaaaaaaaaaa")
		session.transport(PlaybackState.STATE_PLAYING, 0)
		waitFor("playback to be observed") { published("Before The Restart")?.isPlaying == true }
		Thread.sleep(2_000)

		// The boundary: the user's Stop path, which discards in-flight work, then
		// everything run-scoped goes, then a different probe object attaches.
		onMain {
			probe.stop(finalizeTracks = false)
			RunScopedEvidence.clearAll(includeCarriedProgress = true)
			probe = SessionProbe(context, ::watchable)
			probe.onTrackFinalized = { finalized += it }
			probe.start()
		}
		waitFor("the new probe to adopt the live session") { published("Before The Restart") != null }

		assertTrue(
			"a listen survived the run-scoped boundary: ${finalized.map { it.title }}",
			finalized.isEmpty(),
		)
		// Not exactly zero: the session is still PLAYING, so the replacement probe
		// starts its clock the instant it attaches and a few real milliseconds are
		// already legitimately its own. What must not appear is the two seconds
		// from before the boundary — measured at 4ms on the device, against the
		// ~2000ms that would mean the carry leaked.
		val carriedOver = published("Before The Restart")?.playedMs ?: -1
		assertTrue(
			"the rebuilt probe inherited $carriedOver ms from before the boundary",
			carriedOver in 0..500,
		)
	}

	// ── what this suite is and is not measuring ────────────────────────────

	/**
	 * The accommodation, asserted rather than assumed.
	 *
	 * These sessions are watched under this app's own package, which is not a
	 * native YouTube package, so the probe applies **browser** capabilities. Any
	 * reader of this suite's results needs that to be a measured fact rather than
	 * a claim in a comment — and if a future change made the test package native,
	 * several assertions above would be testing a different branch than their
	 * names say.
	 */
	@Test
	fun browser_capabilities_are_what_this_suite_exercises() {
		val capabilities = SourceProfile.playbackCapabilitiesFor(context.packageName)
		assertEquals(false, capabilities.republishesShorterDurations)
		assertEquals(false, capabilities.requiresExactIdToCarryProgress)
		assertEquals(false, capabilities.usesStoppedReplacementGrace)
		assertEquals(false, capabilities.supportsPictureInPictureInference)
		assertEquals(false, SourceRegistry.packageProvesSource(context.packageName))
	}

	/**
	 * The seam is per-probe, and it widens nothing globally.
	 *
	 * The shipped decision function is untouched: it still refuses this package.
	 * What the suite passes is a constructor argument on one instance, and a
	 * probe built the production way still answers the production way.
	 */
	@Test
	fun the_source_seam_is_per_probe_and_not_global() {
		assertEquals(
			"the shipped decision function watches this package",
			false,
			NativeSourceSwitches.acceptsPackage(context.packageName),
		)
		assertEquals(
			"the shipped decision function stopped accepting a real target",
			true,
			NativeSourceSwitches.acceptsPackage(YouTubeProbe.YOUTUBE_PACKAGE),
		)
		assertEquals("the test predicate does not accept this package", true, watchable(context.packageName))
		assertEquals(
			"the test predicate stopped deferring to production elsewhere",
			false,
			watchable("com.example.not.a.source"),
		)
	}
}
