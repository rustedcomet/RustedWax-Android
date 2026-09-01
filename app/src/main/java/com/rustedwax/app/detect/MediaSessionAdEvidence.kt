package com.rustedwax.app.detect

import com.rustedwax.core.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Exact YouTube accessibility-label evidence for ordinary MediaSession tracks.
 *
 * Unlike [AdEvidence], this store never accepts a video id or URL generation:
 * an ordinary watch URL names the organic content even while a pre-roll or
 * mid-roll advert owns the MediaSession. Evidence is instead keyed by package
 * plus a unique track token and semantic metadata signature. The token travels
 * with [TrackProgressCarry] through a Chrome session recreation.
 */
object MediaSessionAdEvidence {

	data class TrackInstance(
		val packageName: String,
		val token: Long,
		val signature: TrackIdentity,
	)

	/** A literal label observation; null means the label disappeared. */
	data class Observation(
		val packageName: String,
		val signal: String?,
		val atMillis: Long = System.currentTimeMillis(),
	)

	data class Evidence(
		val instance: TrackInstance,
		val signal: String,
		val atMillis: Long,
	)

	private data class State(
		val instance: TrackInstance,
		val provisional: Observation? = null,
		val accepted: Evidence? = null,
	)

	const val PROVISIONAL_TTL_MS = 30_000L

	private val byPackage = ConcurrentHashMap<String, State>()
	private val nextToken = AtomicLong(1)

	fun nextTrackToken(): Long = nextToken.getAndIncrement()

	fun offer(observation: Observation) {
		if (observation.signal == null) {
			labelAbsent(observation.packageName)
		}
	}

	/**
	 * Bind one observation to the one instance selected by SessionProbe.
	 *
	 * A first observation is accepted only if that exact instance was already
	 * established before the accessibility frame. Otherwise the same literal
	 * signal must be observed again for the same token/signature.
	 */
	fun observe(
		observation: Observation,
		instance: TrackInstance,
		instanceEstablishedBeforeObservation: Boolean,
		unambiguous: Boolean,
	): Evidence? {
		if (observation.packageName.isBlank() ||
			observation.packageName != instance.packageName ||
			instance.token <= 0 || !instance.signature.isUsable
		) return null
		val signal = observation.signal?.trim()?.takeIf(String::isNotEmpty)
		if (signal == null) {
			labelAbsent(observation.packageName)
			return null
		}
		if (!unambiguous) {
			conflict(observation.packageName)
			return null
		}

		pruneProvisional(observation.packageName, observation.atMillis)
		val prior = byPackage[observation.packageName]
			?.takeIf { sameInstance(it.instance, instance) }
		if (prior == null) byPackage.remove(observation.packageName)

		prior?.accepted?.takeIf { it.signal == signal }?.let {
			val refreshed = it.copy(atMillis = observation.atMillis)
			byPackage[observation.packageName] = State(instance, accepted = refreshed)
			return refreshed
		}

		val repeated = prior?.provisional?.signal == signal
		if (instanceEstablishedBeforeObservation || repeated) {
			val accepted = Evidence(instance, signal, observation.atMillis)
			byPackage[observation.packageName] = State(instance, accepted = accepted)
			return accepted
		}

		byPackage[observation.packageName] = State(instance, provisional = observation)
		return null
	}

	fun accepted(
		instance: TrackInstance,
		now: Long = System.currentTimeMillis(),
	): Evidence? {
		pruneProvisional(instance.packageName, now)
		return byPackage[instance.packageName]
			?.takeIf { sameInstance(it.instance, instance) }
			?.accepted
	}

	/** Restore already accepted evidence carried through session recreation. */
	fun restoreAccepted(instance: TrackInstance, signal: String, atMillis: Long) {
		if (instance.packageName.isBlank() || instance.token <= 0 ||
			!instance.signature.isUsable || signal.isBlank()
		) return
		byPackage[instance.packageName] = State(
			instance = instance,
			accepted = Evidence(instance, signal, atMillis),
		)
	}

	/** Disappearance clears only provisional evidence; an accepted flag is track state. */
	fun labelAbsent(packageName: String) {
		val state = byPackage[packageName] ?: return
		if (state.provisional != null) byPackage.remove(packageName, state)
	}

	/** Multiple possible sessions make package-to-track binding unknowable. */
	fun conflict(packageName: String) {
		val state = byPackage[packageName] ?: return
		if (state.provisional != null) byPackage.remove(packageName, state)
	}

	fun clearInstance(instance: TrackInstance) {
		val state = byPackage[instance.packageName] ?: return
		if (sameInstance(state.instance, instance)) byPackage.remove(instance.packageName, state)
	}

	fun clearPackage(packageName: String) {
		byPackage.remove(packageName)
	}

	fun clearAll() = byPackage.clear()

	/**
	 * Which packages this store currently holds anything for.
	 *
	 * Read-only, and it exists so a caller can assert that a run left the store
	 * alone. `<redacted-private-path>` §2 is about evidence with no single owner;
	 * being able to *observe* the store is the cheapest half of fixing that.
	 */
	fun trackedPackages(): Set<String> = byPackage.keys.toSet()

	private fun pruneProvisional(packageName: String, now: Long) {
		val state = byPackage[packageName] ?: return
		val provisional = state.provisional ?: return
		if (now - provisional.atMillis > PROVISIONAL_TTL_MS) {
			byPackage.remove(packageName, state)
		}
	}

	private fun sameInstance(first: TrackInstance, second: TrackInstance): Boolean =
		first.packageName == second.packageName &&
			first.token == second.token &&
			first.signature.sameTrackAs(second.signature)
}
