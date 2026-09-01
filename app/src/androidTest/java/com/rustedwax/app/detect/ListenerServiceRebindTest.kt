package com.rustedwax.app.detect

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the real listener service built, in production, before this test ran.
 *
 * ## The boundary, and how much of it is reachable in-process
 *
 * `SessionProbeDeviceTest` has a fast component test that calls `probe.stop()`
 * and `probe.start()` directly. That is *not* this boundary: it never executes
 * `RustedWaxListenerService.onListenerConnected`, so it proves nothing about the
 * wiring that callback rebuilds. The audit called that out, and the component
 * test has been renamed to say what it does.
 *
 * The disconnect/reconnect half cannot be driven from inside this process.
 * `NotificationListenerService.requestRebind` is documented for use *after*
 * `requestUnbind()`, and measured here it is a no-op while the listener is still
 * bound: 20 s of polling produced no unbind, no reconnect and no new probe. The
 * platform log for that run shows the only rebind of the day arriving when the
 * instrumentation process itself died — which is the point: the event is a
 * process/binding boundary, and a test living inside that process cannot
 * provoke it and survive to assert on it.
 *
 * That half is therefore orchestrated from the host by
 * `tools/device/process-restart.sh`, with `am force-stop`, and its result is
 * reported separately rather than claimed here.
 *
 * ## What this file does assert
 *
 * `ProbeHolder` is only ever written by `RustedWaxListenerService.startProbe`.
 * So a probe present here, carrying its five engine callbacks, is direct
 * evidence that the production `onListenerConnected` path ran on this device and
 * built what it is supposed to build. That is a real production-path assertion;
 * it is simply not the *reconnect* assertion, and it does not pretend to be.
 *
 * ## Deliberately no MediaSession
 *
 * The service wires the **real** `FinalizeTrackUseCase` through `FinalizationRuntime`,
 * the path holding the owner's posting key. A test that made a listen finalize
 * through it could write to an immutable chain. Nothing here publishes playback.
 */
@RunWith(AndroidJUnit4::class)
class ListenerServiceRebindTest {

	private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
	private val context: Context get() = instrumentation.targetContext

	private fun waitFor(what: String, timeoutMs: Long = 20_000, predicate: () -> Boolean) {
		val deadline = System.currentTimeMillis() + timeoutMs
		while (System.currentTimeMillis() < deadline) {
			if (predicate()) return
			Thread.sleep(200)
		}
		throw AssertionError("timed out waiting for $what")
	}

	/**
	 * Without the grant the system never binds the service, so there is no
	 * reconnect to observe and every assertion below would be about nothing.
	 */
	@Before
	fun requireNotificationAccess() {
		val enabled = android.provider.Settings.Secure.getString(
			context.contentResolver,
			"enabled_notification_listeners",
		).orEmpty()
		assertTrue(
			"RustedWax does not hold notification access, so the listener service is " +
				"never bound and this boundary cannot be exercised. Grant it on the " +
				"device: Settings → Apps → Special access → Notification access → " +
				"RustedWax. Current holders: $enabled",
			enabled.contains(context.packageName),
		)
	}

	/**
	 * The service ran its connect path and published a probe.
	 *
	 * Nothing else writes [ProbeHolder]. A non-null value is `startProbe` having
	 * executed inside `onListenerConnected` on this device.
	 */
	@Test
	fun the_listener_service_published_a_probe() {
		waitFor("the listener service to publish a probe") { ProbeHolder.current != null }
		assertNotNull("the listener service never published a probe", ProbeHolder.current)
	}

	/**
	 * The published probe is wired to the engine, not merely constructed.
	 *
	 * `startProbe` attaches five callbacks. A probe with none of them would be
	 * present and broken: it would observe playback and tell nobody. Asserting the
	 * wiring is what makes "the service rebuilt everything" checkable without
	 * making anything finalize.
	 */
	@Test
	fun the_published_probe_is_wired_to_the_engine() {
		waitFor("the listener service to publish a probe") { ProbeHolder.current != null }
		val probe = requireNotNull(ProbeHolder.current)

		assertNotNull("onTrackFinalized was not attached", probe.onTrackFinalized)
		assertNotNull("onVideoConfirmed was not attached", probe.onVideoConfirmed)
		assertNotNull("knownVideoFor was not attached", probe.knownVideoFor)
		assertNotNull("onPackageTornDown was not attached", probe.onPackageTornDown)
		assertNotNull(
			"onNativeIdentityRequested was not attached",
			probe.onNativeIdentityRequested,
		)
	}

	/**
	 * The service's own probe reports no error, so its `MediaSessionManager`
	 * registration succeeded with the real grant.
	 */
	@Test
	fun the_published_probe_attached_to_the_media_session_manager() {
		waitFor("the listener service to publish a probe") { ProbeHolder.current != null }
		val probe = requireNotNull(ProbeHolder.current)
		assertTrue(
			"the production probe reported: ${probe.error.value}",
			probe.error.value == null,
		)
	}
}
