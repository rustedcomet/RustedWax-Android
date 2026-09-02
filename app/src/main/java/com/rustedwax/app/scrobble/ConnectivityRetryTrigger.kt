package com.rustedwax.app.scrobble

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import com.rustedwax.app.detect.EventLog
import java.util.concurrent.atomic.AtomicLong

/**
 * Drains due queued scrobbles when usable connectivity comes back.
 *
 * A scrobble that finalized while the phone was offline is parked in
 * [BroadcastQueue] and, before this existed, stayed there: the only three
 * callers of `FinalizationRuntime.flushQueue` were the activity's `onStart`,
 * the manual retry button, and the listener service connecting. All three are
 * *user* or *system* events, so a listen earned in airplane mode sat due and
 * untouched until somebody happened to open the app — measured, owed, and
 * invisible until then.
 *
 * The trigger is deliberately the smallest one that closes that gap:
 *
 * - **One signal.** A `NetworkCallback` for a network that already carries
 *   `NET_CAPABILITY_INTERNET` *and* `NET_CAPABILITY_VALIDATED`, so "the radio
 *   came up" is not mistaken for "the internet is reachable". No polling
 *   timer, no periodic job, nothing that runs while the queue is empty.
 * - **No new retry policy.** It calls the same `flushQueue()` the UI calls.
 *   `BroadcastQueue.due()` still decides what is owed, `recordFailure` still
 *   doubles the backoff, and `MAX_ATTEMPTS` still drops an entry. Connectivity
 *   returning is permission to *ask* the queue, never permission to bypass it:
 *   an entry backing off is skipped here exactly as it is skipped everywhere.
 * - **Quiet period.** A single reconnect can deliver several `onAvailable`
 *   callbacks — Wi-Fi and cellular validating within a second of each other,
 *   or a flapping link. [QUIET_PERIOD_MS] collapses those into one drain so a
 *   bad link cannot turn into a broadcast loop.
 * - **Nothing to drain, nothing to do.** With an empty queue the signal is
 *   dropped before the posting key is touched.
 *
 * Bound to the notification listener, which is the longest-lived component the
 * engine already has and the one that owns the probe. Registered on connect and
 * released on *both* of the service's teardown boundaries — `onListenerDisconnected`
 * and `onDestroy` — because they are separate platform callbacks and neither is
 * promised to imply the other. Release is idempotent, so a disconnect followed by
 * a destroy, a destroy with no disconnect, or a reconnect that replaces a live
 * registration all end with exactly one callback registered or none.
 *
 * What this is **not** is a background scheduler. A `NetworkCallback` is a
 * process-lifetime observation: while the process is frozen or dead it delivers
 * nothing, and Android may hand over a deferred callback once the process is
 * runnable again. So this makes a due entry retry *without the user opening the
 * app*, which is what was missing; it does not promise a retry at any particular
 * moment. The queue stays persisted and `onListenerConnected` and the activity's
 * `onStart` remain the other two ways it gets drained.
 */
class ConnectivityRetryTrigger internal constructor(
	private val queueDepth: () -> Int,
	private val flush: () -> Unit,
	private val nowMs: () -> Long,
	private val quietPeriodMs: Long = QUIET_PERIOD_MS,
) {

	constructor() : this(
		queueDepth = { FinalizationRuntime.queueSize.value },
		flush = { FinalizationRuntime.flushQueue() },
		// Monotonic: a quiet period must not be reopened or extended by the user
		// changing the wall clock or by an NTP correction on reconnect.
		nowMs = { SystemClock.elapsedRealtime() },
	)

	private val lastDrainAtMs = AtomicLong(Long.MIN_VALUE)

	/**
	 * How a usable network is watched for.
	 *
	 * Production is [ConnectivityManager]; the seam exists because the lifetime
	 * rules — release is idempotent, a second registration replaces the first,
	 * releasing something never registered is a no-op — are the part that has to
	 * be *proved*, and a JVM test cannot construct a `Context` to prove them
	 * against the real one.
	 */
	internal fun interface NetworkWatch {
		/** Begin watching. Returns how to stop, or null if watching is unavailable. */
		fun start(onUsableNetwork: () -> Unit): (() -> Unit)?
	}

	/** Non-null exactly while a watch is live. Guarded by this object's monitor. */
	private var stopWatching: (() -> Unit)? = null

	/**
	 * A network that can reach the internet became available.
	 *
	 * Returns whether this signal actually dispatched a drain, which is what the
	 * unit tests assert on: "reconnect drains the queue" and "reconnect does not
	 * hammer the queue" are the same method answering twice.
	 */
	internal fun onUsableNetwork(): Boolean {
		if (queueDepth() <= 0) return false
		val now = nowMs()
		val previous = lastDrainAtMs.get()
		if (previous != Long.MIN_VALUE && now - previous < quietPeriodMs) return false
		if (!lastDrainAtMs.compareAndSet(previous, now)) return false
		EventLog.append(
			"queue",
			"connectivity returned — retrying scrobbles whose backoff has elapsed",
		)
		flush()
		return true
	}

	/** Start listening. Idempotent: a second call replaces the first watch. */
	fun register(context: Context) = registerWith(androidWatch(context.applicationContext))

	@Synchronized
	internal fun registerWith(watch: NetworkWatch) {
		unregister()
		stopWatching = watch.start { onUsableNetwork() }
	}

	/**
	 * Release the watch. Safe to call any number of times, in any order, from
	 * either of the service's teardown callbacks.
	 */
	@Synchronized
	fun unregister() {
		val stop = stopWatching ?: return
		// Cleared before the call, so a throwing stop still leaves this object in
		// the released state rather than holding a handle it can never let go of.
		stopWatching = null
		// Releasing a callback the framework has already dropped throws.
		runCatching { stop() }
	}

	/** Whether a watch is currently held. Lifetime assertions read this. */
	internal val isWatching: Boolean
		@Synchronized get() = stopWatching != null

	private fun androidWatch(context: Context) = NetworkWatch { onUsableNetwork ->
		val connectivity = context
			.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
			?: run {
				EventLog.append("queue", "no connectivity service — queued scrobbles retry on app open only")
				return@NetworkWatch null
			}
		val request = NetworkRequest.Builder()
			.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
			// Validated, not merely connected. A captive portal or a radio that
			// has associated but carries no route would otherwise spend a retry
			// attempt and start the backoff clock for nothing.
			.addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
			.build()
		val listener = object : ConnectivityManager.NetworkCallback() {
			override fun onAvailable(network: Network) {
				onUsableNetwork()
			}
		}
		// A revoked ACCESS_NETWORK_STATE, or a system that refuses the
		// registration, must not take the listener service down with it: the
		// queue simply keeps its previous UI-driven behaviour.
		runCatching { connectivity.registerNetworkCallback(request, listener) }
			.map { { connectivity.unregisterNetworkCallback(listener) } }
			.getOrElse {
				EventLog.append(
					"queue",
					"connectivity retry could not be registered: ${it.message}",
				)
				null
			}
	}

	private companion object {
		/**
		 * Shortest gap between two connectivity-driven drains.
		 *
		 * One reconnect, one drain. Long enough to swallow the burst of
		 * callbacks a single reconnect produces, far shorter than
		 * `BroadcastQueue.BASE_BACKOFF_MS`, so it never becomes the thing that
		 * decides when an entry is retried — the queue's own backoff does.
		 */
		const val QUIET_PERIOD_MS = 30_000L
	}
}
