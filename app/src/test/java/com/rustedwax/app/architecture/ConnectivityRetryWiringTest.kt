package com.rustedwax.app.architecture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Production-wiring gate for reconnect-driven queue retry.
 *
 * The behaviour tests run the trigger against the engine directly; what they
 * cannot show is that anything on a phone ever calls it. Before this change
 * `flushQueue()` had three callers — the activity's `onStart`, the manual retry
 * button, and the listener connecting — and the composition contained no
 * `ConnectivityManager` at all, so a queued scrobble waited for a person. This
 * asserts the fourth caller exists, is bound to a real service lifecycle, and
 * did not arrive as a polling loop.
 */
class ConnectivityRetryWiringTest {

	private fun source(path: String): String {
		val file = java.io.File("src/main/java/com/rustedwax/app/$path")
		assertTrue("production source missing at ${file.absolutePath}", file.isFile)
		return file.readText()
	}

	/**
	 * The same file with its comments removed.
	 *
	 * The negative assertions below are about what the trigger *does*. Its own
	 * KDoc names `BroadcastQueue.due()`, `recordFailure` and `MAX_ATTEMPTS` —
	 * explaining which policy it defers to is the opposite of owning one — so a
	 * plain substring scan would read the explanation as the offence.
	 */
	private fun code(path: String): String = source(path)
		.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
		.lines()
		.joinToString("\n") { it.substringBefore("//") }

	@Test
	fun `a connectivity callback reaches the queue drain`() {
		val trigger = source("scrobble/ConnectivityRetryTrigger.kt")

		assertTrue(
			"the trigger does not observe the platform's connectivity",
			"ConnectivityManager.NetworkCallback" in trigger && "registerNetworkCallback" in trigger,
		)
		assertTrue("a returning network does not reach the drain", "FinalizationRuntime.flushQueue()" in trigger)
		assertTrue(
			"the request accepts a network that has not been validated",
			"NET_CAPABILITY_VALIDATED" in trigger,
		)
	}

	/**
	 * Both teardown callbacks release it, not just the one that is easy to reach.
	 *
	 * `onListenerDisconnected` and `Service.onDestroy` are separate platform
	 * callbacks and neither is promised to imply the other, so a service instance
	 * destroyed without a disconnect used to leave the callback registered for the
	 * life of the process, with a replacement instance adding another. This is a
	 * wiring assertion — that both bodies reach the release — because a JVM test
	 * cannot drive a real `NotificationListenerService` lifecycle. The behaviour
	 * those bodies depend on (release is idempotent, a second registration
	 * replaces the first, releasing what was never registered is a no-op) is
	 * exercised for real in `ConnectivityRetryLifetimeTest`.
	 */
	@Test
	fun `both service teardown boundaries release the callback`() {
		val service = code("detect/RustedWaxListenerService.kt")

		assertTrue(
			"the listener service does not register the connectivity trigger",
			"ConnectivityRetryTrigger()" in service && "register(applicationContext)" in service,
		)
		assertTrue(
			"the service has no onDestroy at all",
			"override fun onDestroy()" in service,
		)
		fun bodyOf(signature: String): String {
			assertTrue("$signature is missing", signature in service)
			return service.substringAfter(signature).substringBefore("\n\t}")
		}
		assertTrue(
			"onListenerDisconnected does not release the connectivity callback",
			"releaseConnectivityRetry()" in bodyOf("override fun onListenerDisconnected()"),
		)
		assertTrue(
			"onDestroy does not release the connectivity callback",
			"releaseConnectivityRetry()" in bodyOf("override fun onDestroy()"),
		)
		assertTrue(
			"a reconnect does not replace the previous registration",
			"releaseConnectivityRetry()" in bodyOf("override fun onListenerConnected()"),
		)
	}

	/**
	 * A queued send that the chain accepted is retired through the fail-closed
	 * path, not through the removal whose failure was the duplicate-write risk.
	 */
	@Test
	fun `accepted queued sends are settled rather than merely removed`() {
		val runtime = code("scrobble/FinalizationRuntime.kt")
		val drain = runtime.substringAfter("private fun retryQueuedPayloads()")
			.substringBefore("private fun handleQueuedFailure")

		assertTrue("a successful queued send is not settled", "queue.settle(entry)" in drain)
		assertFalse(
			"an accepted queued send still relies on a removal that can fail",
			"queue.remove(entry.id)" in drain.substringBefore("BroadcastResult.Rejected"),
		)
	}

	/**
	 * A trigger, not a retry policy.
	 *
	 * `BroadcastQueue` owns backoff and the attempt ceiling. The whole risk of
	 * adding an automatic caller is that it grows its own opinion about when an
	 * entry is owed, so the trigger is held to reaching `flushQueue` and nothing
	 * else — no queue entries, no attempt counts, no scheduler.
	 */
	@Test
	fun `reconnect retry adds no second retry policy and no polling`() {
		val trigger = code("scrobble/ConnectivityRetryTrigger.kt")

		assertFalse("the trigger re-finalizes a listen", "finalizeTrack" in trigger)
		assertFalse("the trigger inspects queue entries", "BroadcastQueue.Entry" in trigger)
		assertFalse("the trigger reimplements backoff", "recordFailure(" in trigger)
		assertFalse("the trigger decides for itself what is due", ".due(" in trigger)
		assertFalse("the trigger polls on a timer", "scheduleAtFixedRate" in trigger)
		assertFalse("the trigger polls on a timer", "WorkManager" in trigger)
		assertFalse("the trigger sleeps in a loop", "while (true)" in trigger)
	}
}
