package com.rustedwax.app.detect

import com.rustedwax.core.*
import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The installed native YouTube app, through the production probe.
 *
 * ## Why this is different from every other device test here
 *
 * `SessionProbeDeviceTest` and `SurfaceLifecycleDeviceTest` build their own
 * `SessionProbe` with a test predicate, because a test cannot create a
 * `MediaSession` under someone else's package. That buys Android callback
 * translation under **browser** capabilities and nothing more, and both files say
 * so.
 *
 * This file uses **no seam at all**. It reads `ProbeHolder.current` — the probe
 * `RustedWaxListenerService.startProbe` built, with the shipped
 * `NativeSourceSwitches::acceptsPackage` predicate — and observes what it makes
 * of the real `com.google.android.youtube` session created by the installed
 * YouTube app. So the chain under test is the shipped one end to end:
 *
 * ```text
 * installed YouTube app -> real MediaSession -> platform
 *   -> RustedWaxListenerService's SessionProbe -> Watch callbacks
 *   -> PlaybackReducer -> SessionSnapshot -> ProbeHolder (what the UI renders)
 * ```
 *
 * These sessions genuinely carry native capabilities: `republishesShorterDurations`,
 * `requiresExactIdToCarryProgress`, `usesStoppedReplacementGrace` and
 * `supportsPictureInPictureInference` are all true for this package, which is the
 * half no other device test in this suite reaches.
 *
 * ## Preconditions this test refuses to fake
 *
	 * It needs the notification grant and a native YouTube session that is actually
	 * playing. Each is asserted with the exact action required; none is simulated.
	 * Accessibility/PiP is deliberately absent: instrumentation force-stops the
	 * target package on this API-31 device and destroys that precondition. The two
	 * PiP gates are host-side scripts that never start instrumentation.
 *
	 * `tools/device/native-source.sh` sets the playback up from the host and runs
	 * this non-accessibility class.
 */
@RunWith(AndroidJUnit4::class)
class NativeSourceDeviceTest {

	private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
	private val context: Context get() = instrumentation.targetContext
	private val youtube = YouTubeProbe.YOUTUBE_PACKAGE
	private lateinit var productionProbe: SessionProbe
	private var productionFinalizer: ((SessionSnapshot) -> Unit)? = null
	private var productionNativeResolver:
		((SessionSnapshot, (SessionProbe.NativeResolvedIdentity?) -> Unit) -> Unit)? = null

	private companion object {
		/** Confirmed stable ordinary native-video fixture. */
		const val NATIVE_VIDEO = "tSi6Dn1H36Y"
	}

	private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

	/** The production probe, refreshed the way `MainActivity` refreshes it. */
	private fun nativeSnapshot(): SessionSnapshot? {
		if (!::productionProbe.isInitialized) return null
		onMain { productionProbe.tick() }
		return productionProbe.sessions.value.firstOrNull { it.packageName == youtube }
	}

	private fun waitFor(what: String, timeoutMs: Long = 20_000, predicate: () -> Boolean) {
		val deadline = System.currentTimeMillis() + timeoutMs
		while (System.currentTimeMillis() < deadline) {
			if (predicate()) return
			Thread.sleep(250)
		}
		throw AssertionError("timed out waiting for $what")
	}

	private fun secureSetting(key: String): String =
		android.provider.Settings.Secure.getString(context.contentResolver, key).orEmpty()

	@Before
	fun requireRealNativePlayback() {
		assertTrue(
			"RustedWax does not hold notification access. Grant it: Settings → Apps → " +
				"Special access → Notification access → RustedWax.",
			secureSetting("enabled_notification_listeners").contains(context.packageName),
		)
		// The listener service binds a moment after the process starts, so this is
		// a wait rather than an immediate read. A null that never resolves means
		// monitoring is off rather than that the service is slow.
		waitFor(
			"the listener service to publish a probe — if this never happens, " +
				"monitoring is off; turn RustedWax monitoring on in the app",
		) { ProbeHolder.current != null }
		productionProbe = requireNotNull(ProbeHolder.current)
		productionFinalizer = productionProbe.onTrackFinalized
		productionNativeResolver = productionProbe.onNativeIdentityRequested
		assertNotNull("the listener-built probe had no engine finalizer", productionFinalizer)
		assertNotNull(
			"the listener-built probe had no native identity resolver",
			productionNativeResolver,
		)
		// The fixture must exercise the listener-built production probe without
		// handing a test listen to the live engine. Early native-ID resolution and
		// finalization can both query watch history and legitimately rotate the
		// encrypted YouTube session. That mutates account state even with Hive
		// posting disabled and makes this device gate credential-destructive.
		onMain {
			productionProbe.onTrackFinalized = {}
			productionProbe.onNativeIdentityRequested = { _, callback -> callback(null) }
		}
		// Each test re-establishes playback for itself. The picture-in-picture test
		// fronts the launcher, which stops the video, so a suite run would otherwise
		// leave whichever test ran next with no native session — a failure about
		// test order rather than about production.
		if (nativeSnapshot()?.isPlaying != true) startNativePlayback()
		waitFor(
			"the production probe to see a native YouTube session playing — " +
				"is the YouTube app able to play $NATIVE_VIDEO on this device?",
		) { nativeSnapshot()?.isPlaying == true }
	}

	@After
	fun releaseRealNativePlayback() {
		if (!::productionProbe.isInitialized) return
		// End only the installed fixture application; force-stop preserves its
		// data and grants. Keep the inert test finalizer installed until Android has
		// delivered the controller removal, then discard any continuation parked by
		// that removal before restoring the listener's original engine callback.
		ParcelFileDescriptor.AutoCloseInputStream(
			instrumentation.uiAutomation.executeShellCommand("am force-stop $youtube"),
		).use { it.readBytes() }
		waitFor("the native fixture MediaSession to leave the production probe") {
			nativeSnapshot() == null
		}
		onMain {
			RunScopedEvidence.clearAll(includeCarriedProgress = true)
			productionProbe.onTrackFinalized = productionFinalizer
			productionProbe.onNativeIdentityRequested = productionNativeResolver
		}
	}

	/** Ask the installed YouTube app to play, the same way the host script does. */
	private fun startNativePlayback() {
		context.startActivity(
			Intent(Intent.ACTION_VIEW, android.net.Uri.parse(
				"https://www.youtube.com/watch?v=$NATIVE_VIDEO",
			)).setPackage(youtube).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
		)
		Thread.sleep(9_000)
	}

	/**
	 * The shipped probe adopted the real native session, with native capabilities.
	 *
	 * This is the assertion no other device test in this suite can make: the
	 * package is genuinely `com.google.android.youtube`, so the capability answers
	 * are the native ones rather than the browser defaults a test-owned session
	 * would get.
	 */
	@Test
	fun the_production_probe_watches_the_installed_youtube_app() {
		val snapshot = requireNotNull(nativeSnapshot())

		assertEquals(youtube, snapshot.packageName)
		assertTrue("the native session was not treated as native", snapshot.profile.packageProvesSource)

		val capabilities = SourceProfile.playbackCapabilitiesFor(snapshot.packageName)
		assertTrue(
			"native capabilities were not applied to the installed app",
			capabilities.requiresExactIdToCarryProgress &&
				capabilities.usesStoppedReplacementGrace &&
				capabilities.supportsPictureInPictureInference &&
				capabilities.republishesShorterDurations,
		)
	}

	/**
	 * The reducer is measuring the installed app's playback.
	 *
	 * Sampled within **one track instance** rather than across two readings six
	 * seconds apart. The installed app is a real application: it can advance to the
	 * next item, loop, or cut to an interstitial at any moment, and each of those
	 * legitimately starts a new listen at zero — measured here as 8,492 ms
	 * dropping to 1,130 ms between two naive reads. Comparing totals across that
	 * boundary tests YouTube's playlist behaviour, not this reducer.
	 *
	 * So the sampler follows `trackInstanceToken`, rebaselines whenever the listen
	 * changes, and asserts that some single listen measurably advanced. A path
	 * that adopted the session but never fed the reducer never advances any
	 * instance and fails.
	 */
	@Test
	fun the_production_reducer_measures_the_installed_app() {
		var token: Long? = null
		var baseline = 0L
		var best = 0L

		val deadline = System.currentTimeMillis() + 25_000
		while (System.currentTimeMillis() < deadline && best < 3_000) {
			val now = nativeSnapshot()
			if (now != null && now.isPlaying) {
				if (now.trackInstanceToken != token) {
					token = now.trackInstanceToken
					baseline = now.playedMs
				} else {
					best = maxOf(best, now.playedMs - baseline)
				}
			}
			Thread.sleep(500)
		}

		assertNotNull("no native listen was ever observed playing", token)
		assertTrue(
			"no single native listen advanced measurably; best delta was ${best}ms",
			best >= 3_000,
		)
	}

}
