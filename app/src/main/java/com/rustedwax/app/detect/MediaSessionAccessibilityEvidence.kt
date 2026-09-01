package com.rustedwax.app.detect

import java.util.concurrent.atomic.AtomicLong

/**
 * Successful visible-YouTube-root coverage bound to one MediaSession track.
 *
 * Coverage says only that the bounded accessibility scan ran for this track.
 * A null [Scan.adSignal] is not an organic verdict. Positive labels continue
 * through [AdEvidence] or [MediaSessionAdEvidence] as an independent veto.
 */
object MediaSessionAccessibilityEvidence {

	data class Scan(
		val packageName: String,
		val host: String?,
		val rootVisible: Boolean,
		val urlGeneration: Long? = null,
		val videoId: String? = null,
		val isShort: Boolean = false,
		val adSignal: String? = null,
		val atMillis: Long = System.currentTimeMillis(),
	)

	data class Coverage(
		val instance: MediaSessionAdEvidence.TrackInstance,
		val atMillis: Long,
		val urlGeneration: Long?,
		val videoId: String?,
		val lifecycleEpoch: Long = 0,
	)

	enum class Freshness { OUTAGE, RECOVERED }

	data class FreshnessTransition(
		val packageName: String,
		val freshness: Freshness,
		val atMillis: Long,
	)

	private data class ActiveState(
		val instance: MediaSessionAdEvidence.TrackInstance,
		val establishedAtMillis: Long,
		var coverage: Coverage? = null,
		var lastSuccessfulRootAtMillis: Long? = null,
		var outage: Boolean = false,
	)

	/** Refresh keeps this renewed; an old scan cannot bless later screen-off autoplay. */
	const val COVERAGE_FRESH_MS = 30_000L

	/** Three missed five-second refresh windows before one explicit transition. */
	const val OUTAGE_AFTER_MS = 15_000L

	private val activeByPackage = mutableMapOf<String, MutableMap<Long, ActiveState>>()
	private val lifecycleEpochByPackage = mutableMapOf<String, Long>()
	private val nextLifecycleEpoch = AtomicLong(1)

	fun offer(scan: Scan) {
		if (!isSuccessfulYouTubeScan(scan)) return
	}

	@Synchronized
	fun activate(
		instance: MediaSessionAdEvidence.TrackInstance,
		establishedAtMillis: Long,
	) {
		if (!validInstance(instance) || establishedAtMillis <= 0) return
		val states = activeByPackage.getOrPut(instance.packageName) { mutableMapOf() }
		val prior = states[instance.token]
		states[instance.token] = if (prior != null && sameInstance(prior.instance, instance)) {
			prior.copy(instance = instance)
		} else {
			ActiveState(instance, establishedAtMillis)
		}
	}

	@Synchronized
	/**
	 * @param unambiguous the package has exactly one active track, so this scan
	 * cannot belong to anything else.
	 * @param namedThisInstance the scan carried a video id and it is the one this
	 * instance is playing. A browser legitimately keeps YouTube playing in one tab
	 * while the user browses another, and refusing every scan whenever a second
	 * tab exists starved the *playing* listen of coverage and ad evidence for as
	 * long as any other tab stayed open. A scan that names its own video attributes
	 * itself; elimination is no longer the only way to be sure.
	 */
	fun observe(
		scan: Scan,
		instance: MediaSessionAdEvidence.TrackInstance,
		unambiguous: Boolean,
		namedThisInstance: Boolean = false,
	): Coverage? {
		if (!(unambiguous || namedThisInstance) || !isSuccessfulYouTubeScan(scan) ||
			scan.packageName != instance.packageName
		) return null
		val states = activeByPackage[instance.packageName] ?: return null
		if (states.size != 1 && !namedThisInstance) return null
		val state = states[instance.token]
			?.takeIf { sameInstance(it.instance, instance) }
			?: return null
		if (scan.atMillis < state.establishedAtMillis) return null
		val coverage = Coverage(
			instance = instance,
			atMillis = scan.atMillis,
			urlGeneration = scan.urlGeneration?.takeIf { it > 0 },
			videoId = scan.videoId,
			lifecycleEpoch = lifecycleEpochFor(instance.packageName),
		)
		state.coverage = coverage
		state.lastSuccessfulRootAtMillis = scan.atMillis
		return coverage
	}

	@Synchronized
	fun current(
		instance: MediaSessionAdEvidence.TrackInstance,
		expectedUrlGeneration: Long? = null,
		now: Long = System.currentTimeMillis(),
	): Coverage? {
		val state = stateFor(instance) ?: return null
		val coverage = state.coverage ?: return null
		if (now < coverage.atMillis || now - coverage.atMillis > COVERAGE_FRESH_MS) {
			state.coverage = null
			return null
		}
		if (expectedUrlGeneration != null && coverage.urlGeneration != null &&
			coverage.urlGeneration != expectedUrlGeneration
		) {
			state.coverage = null
			return null
		}
		return coverage
	}

	/** Restore only the same package/token/signature inside the freshness bound. */
	@Synchronized
	fun restore(
		instance: MediaSessionAdEvidence.TrackInstance,
		coverage: Coverage,
		now: Long = System.currentTimeMillis(),
	): Boolean {
		if (!sameInstance(instance, coverage.instance) || !isLifecycleCurrent(coverage) ||
			now < coverage.atMillis || now - coverage.atMillis > COVERAGE_FRESH_MS
		) return false
		val state = stateFor(instance) ?: return false
		state.coverage = coverage.copy(instance = instance)
		state.lastSuccessfulRootAtMillis = coverage.atMillis
		return true
	}

	/**
	 * One bounded-refresh result. Null means no visible target YouTube root.
	 * The first prolonged miss emits OUTAGE; later retries remain silent until a
	 * successful root scan emits RECOVERED.
	 */
	@Synchronized
	fun noteRefreshResult(
		successfulPackageName: String?,
		now: Long = System.currentTimeMillis(),
	): List<FreshnessTransition> {
		val transitions = mutableListOf<FreshnessTransition>()
		for ((packageName, states) in activeByPackage) {
			if (states.isEmpty()) continue
			if (packageName == successfulPackageName) {
				states.values.forEach { state ->
					state.lastSuccessfulRootAtMillis = now
					if (state.outage) {
						state.outage = false
						transitions += FreshnessTransition(packageName, Freshness.RECOVERED, now)
					}
				}
				continue
			}
			val reference = states.values.maxOfOrNull {
				it.lastSuccessfulRootAtMillis ?: it.establishedAtMillis
			} ?: continue
			if (now - reference >= OUTAGE_AFTER_MS && states.values.none { it.outage }) {
				states.values.forEach { it.outage = true }
				transitions += FreshnessTransition(packageName, Freshness.OUTAGE, now)
			}
		}
		return transitions.distinctBy { it.packageName to it.freshness }
	}

	@Synchronized
	fun deactivate(instance: MediaSessionAdEvidence.TrackInstance) {
		val states = activeByPackage[instance.packageName] ?: return
		val state = states[instance.token] ?: return
		if (sameInstance(state.instance, instance)) states.remove(instance.token)
		if (states.isEmpty()) activeByPackage.remove(instance.packageName)
	}

	@Synchronized
	fun clearPackage(packageName: String) {
		activeByPackage.remove(packageName)
		lifecycleEpochByPackage[packageName] = nextLifecycleEpoch.getAndIncrement()
	}

	@Synchronized
	fun clearAll() {
		activeByPackage.clear()
		lifecycleEpochByPackage.clear()
	}

	/** How many track instances this store holds coverage state for. See
	 * [MediaSessionAdEvidence.trackedPackages] for why an observable store matters. */
	@Synchronized
	fun trackedInstances(): Int = activeByPackage.values.sumOf { it.size }

	@Synchronized
	fun isLifecycleCurrent(coverage: Coverage): Boolean =
		coverage.lifecycleEpoch > 0 &&
			coverage.lifecycleEpoch == lifecycleEpochFor(coverage.instance.packageName)

	private fun isSuccessfulYouTubeScan(scan: Scan): Boolean =
		scan.rootVisible &&
			scan.packageName in YouTubeProbe.TARGET_PACKAGES &&
			YouTubeProbe.isYouTubeHost(scan.host)

	private fun validInstance(instance: MediaSessionAdEvidence.TrackInstance): Boolean =
		instance.packageName in YouTubeProbe.TARGET_PACKAGES &&
			instance.token > 0 && instance.signature.isUsable

	private fun lifecycleEpochFor(packageName: String): Long =
		lifecycleEpochByPackage.getOrPut(packageName) { nextLifecycleEpoch.getAndIncrement() }

	private fun stateFor(instance: MediaSessionAdEvidence.TrackInstance): ActiveState? =
		activeByPackage[instance.packageName]
			?.get(instance.token)
			?.takeIf { sameInstance(it.instance, instance) }

	private fun sameInstance(
		first: MediaSessionAdEvidence.TrackInstance,
		second: MediaSessionAdEvidence.TrackInstance,
	): Boolean = first.packageName == second.packageName &&
		first.token == second.token &&
		first.signature.sameTrackAs(second.signature)
}
