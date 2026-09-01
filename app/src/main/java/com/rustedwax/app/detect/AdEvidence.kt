package com.rustedwax.app.detect

import java.util.concurrent.ConcurrentHashMap

object AdEvidence {

	data class Evidence(
		val packageName: String,
		val videoId: String,
		val signal: String,
		val urlGeneration: Long = 0,
		val atMillis: Long = System.currentTimeMillis(),
	)

	private data class Instance(
		val videoId: String,
		val generation: Long,
	)

	private data class State(
		val instance: Instance,
		val provisional: Evidence? = null,
		val accepted: Evidence? = null,
	)

	private const val FRESH_MS = 30_000L
	private val byPackage = ConcurrentHashMap<String, State>()
	private val establishedSession = ConcurrentHashMap<String, Instance>()

	/** A different URL/track instance invalidates every package-level label. */
	fun onUrlObserved(packageName: String, videoId: String?, generation: Long) {
		val id = videoId?.takeIf(VIDEO_ID::matches)
		val instance = id?.let { Instance(it, generation) }
		val previous = byPackage[packageName]
		if (previous != null && previous.instance != instance) byPackage.remove(packageName)
		val established = establishedSession[packageName]
		if (established != null && established != instance) establishedSession.remove(packageName)
	}

	/**
	 * Record that the active MediaSession already agreed with this URL instance.
	 * This never retroactively accepts a provisional label: ordering is the
	 * evidence that distinguishes a stable ad from the log-14 transition frame.
	 */
	fun markSessionEstablished(packageName: String, videoId: String, generation: Long) {
		if (generation <= 0 || !VIDEO_ID.matches(videoId)) return
		establishedSession[packageName] = Instance(videoId, generation)
	}

	/** Observe one literal label. Returns it only once it is an accepted veto. */
	fun observe(evidence: Evidence): Evidence? {
		if (!VIDEO_ID.matches(evidence.videoId) || evidence.signal.isBlank() ||
			evidence.urlGeneration <= 0
		) return null

		val instance = Instance(evidence.videoId, evidence.urlGeneration)
		val prior = byPackage[evidence.packageName]
			?.takeIf { it.instance == instance }
		val accepted = prior?.accepted
		if (accepted?.signal == evidence.signal) {
			val refreshed = evidence.copy(atMillis = evidence.atMillis)
			byPackage[evidence.packageName] = State(instance, accepted = refreshed)
			return refreshed
		}

		val sessionWasEstablished = establishedSession[evidence.packageName] == instance
		val repeated = prior?.provisional?.signal == evidence.signal
		if (sessionWasEstablished || repeated) {
			byPackage[evidence.packageName] = State(instance, accepted = evidence)
			log(
				"ad",
				"${evidence.packageName} → accepted explicit ad evidence for " +
					"${evidence.videoId} generation ${evidence.urlGeneration} " +
					"(\"${evidence.signal}\")",
			)
			return evidence
		}

		byPackage[evidence.packageName] = State(instance, provisional = evidence)
		log(
			"ad",
			"${evidence.packageName} → provisional ad label for ${evidence.videoId} " +
				"generation ${evidence.urlGeneration} (\"${evidence.signal}\")",
		)
		return null
	}

	/** A disappeared label cannot survive to poison the next accessibility frame. */
	fun labelAbsent(packageName: String, videoId: String?, generation: Long) {
		val state = byPackage[packageName] ?: return
		if (state.instance.generation == generation && state.instance.videoId == videoId) {
			byPackage.remove(packageName, state)
		}
	}

	fun get(
		packageName: String,
		videoId: String,
		urlGeneration: Long? = null,
		now: Long = System.currentTimeMillis(),
	): Evidence? = byPackage[packageName]
		?.accepted
		?.takeIf {
			it.videoId == videoId &&
				(urlGeneration == null || it.urlGeneration == urlGeneration) &&
				now - it.atMillis <= FRESH_MS
		}

	fun clear(packageName: String) {
		byPackage.remove(packageName)
		establishedSession.remove(packageName)
	}

	fun clearAll() {
		byPackage.clear()
		establishedSession.clear()
	}

	private fun log(tag: String, message: String) {
		// Pure JVM regression tests do not provide android.util.Log. The device
		// path still records every state transition normally.
		runCatching { EventLog.append(tag, message) }
	}

	private val VIDEO_ID = Regex("""[A-Za-z0-9_-]{11}""")
}
