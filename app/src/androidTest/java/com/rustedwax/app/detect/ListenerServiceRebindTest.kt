package com.rustedwax.app.detect

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

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
