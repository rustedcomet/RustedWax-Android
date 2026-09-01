package com.rustedwax.app.scrobble

/**
 * Completion ownership for asynchronous video-facts prefetches.
 *
 * Several watches may ask for the same id while one fetch is in flight. Every
 * requester gets the one terminal result, but a result from before [reset] can
 * never complete a request registered after that lifecycle boundary.
 */
internal class PrefetchCompletionRegistry {

	class Launch internal constructor(val videoId: String, internal val generation: Long)

	data class Registration(
		val launch: Launch? = null,
		val completed: Boolean? = null,
	)

	private var generation = 0L
	private val inFlight = mutableSetOf<String>()
	private val outcomes = mutableMapOf<String, Boolean>()
	private val waiters = mutableMapOf<String, MutableList<(Boolean) -> Unit>>()

	@Synchronized
	fun register(videoId: String, completion: ((Boolean) -> Unit)?): Registration {
		outcomes[videoId]?.let { return Registration(completed = it) }
		completion?.let { waiters.getOrPut(videoId) { mutableListOf() } += it }
		if (!inFlight.add(videoId)) return Registration()
		return Registration(launch = Launch(videoId, generation))
	}

	/** Return callbacks to invoke outside this object's monitor. */
	@Synchronized
	fun complete(launch: Launch, available: Boolean): List<(Boolean) -> Unit> {
		if (launch.generation != generation || !inFlight.remove(launch.videoId)) return emptyList()
		outcomes[launch.videoId] = available
		return waiters.remove(launch.videoId)?.toList().orEmpty()
	}

	@Synchronized
	fun reset() {
		generation++
		inFlight.clear()
		outcomes.clear()
		waiters.clear()
	}
}
