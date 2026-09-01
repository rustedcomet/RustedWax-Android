package com.rustedwax.app.detect

/**
 * Refuses transient cross-page accessibility snapshots.
 *
 * YouTube can briefly publish an outgoing footer beside an incoming seekbar.
 * A complete structural parse is therefore necessary but not sufficient at an
 * identity boundary: the identity-bearing fields must remain identical across
 * a short observation interval. Position is deliberately excluded from the
 * key because it is expected to advance.
 */
class NativeShortStabilizer {

	sealed interface Decision {
		data class Waiting(val reason: String) : Decision
		data class Accepted(val result: NativeShortParser.Result) : Decision
	}

	private data class Candidate(
		val key: Key,
		val firstSeenAtMillis: Long,
		val latestResult: NativeShortParser.Result,
		val accepted: Boolean = false,
	)

	private sealed interface Key {
		data class Organic(
			val title: String?,
			val ownerHandle: String,
			val totalSeconds: Long,
		) : Key

		data class Ad(
			val signal: String,
			val title: String?,
			val totalSeconds: Long,
		) : Key
	}

	private var candidate: Candidate? = null

	@Synchronized
	fun observe(result: NativeShortParser.Result, observedAtMillis: Long): Decision {
		val key = when (result) {
			is NativeShortParser.Result.Organic -> Key.Organic(
				result.title,
				result.ownerHandle,
				result.totalSeconds,
			)
			// Stabilized on the two fields it has. Its length is not known here
			// at all, so it cannot take part in the key — which is the point:
			// the same Short must not re-key itself when the seekbar appears or
			// disappears mid-viewing.
			is NativeShortParser.Result.OrganicUnmeasured -> Key.Organic(
				result.title,
				result.ownerHandle,
				totalSeconds = 0,
			)
			// Carries no identity, so it may continue only an identity that this
			// stabilizer has already accepted. A Shorts scroll/navigation event resets
			// the candidate before the incoming tree is read; accepting an unnamed
			// seekbar in that gap splices the incoming duration onto the outgoing
			// owner. Measured 2026-08-15 in the exact Home → Short field sequence:
			// @lfgbae was finalized as 62s of the incoming @barefoot_surf Short's
			// 108-second duration. Wait for the incoming footer instead.
			is NativeShortParser.Result.OrganicUnnamed -> {
				val prior = candidate
				val organic = prior?.key as? Key.Organic
					?: return Decision.Waiting(
						"foreground Short footer is absent before the current identity is stable",
					)
				if (prior.accepted) {
					if (organic.totalSeconds != 0L && organic.totalSeconds != result.totalSeconds) {
						return Decision.Waiting(
							"foreground Short footer is absent before the current identity is stable",
						)
					}
					return Decision.Accepted(result)
				}
				// The footer is not coming back while the gesture lasts. Holding a
				// Short to play it at 2x strips the title and owner handle for as
				// long as the finger is down and leaves the seekbar readable, so a
				// Short whose identity had not finished stabilizing when the hold
				// began received only unnamed frames and could never be acquired at
				// all. Measured 2026-08-16 on the Galaxy A36: of four Shorts played
				// back to back at 2x, one was never acquired and one finalized at
				// 1s of 20s, both refused below threshold.
				//
				// An unnamed frame carries no identity and still cannot supply one.
				// What it can do is corroborate that the Short whose footer was just
				// read is still the one on screen: it must publish exactly the
				// length that footer came with — deliberately stricter than the
				// accepted case above, which tolerates an unknown length — and the
				// navigation/scroll reset that guards the accepted case guards this
				// one identically, because it clears the candidate outright.
				if (organic.totalSeconds == 0L || organic.totalSeconds != result.totalSeconds ||
					observedAtMillis < prior.firstSeenAtMillis ||
					observedAtMillis - prior.firstSeenAtMillis < STABILITY_MS
				) {
					return Decision.Waiting(
						"foreground Short footer is absent before the current identity is stable",
					)
				}
				candidate = prior.copy(accepted = true)
				return Decision.Accepted(prior.latestResult)
			}
			is NativeShortParser.Result.Ad -> Key.Ad(
				result.signal,
				result.title,
				result.totalSeconds,
			)
			is NativeShortParser.Result.Invalid -> {
				reset()
				return Decision.Accepted(result)
			}
		}
		val prior = candidate
		if (prior == null || prior.key != key || observedAtMillis < prior.firstSeenAtMillis) {
			candidate = Candidate(key, observedAtMillis, result)
			return Decision.Waiting("foreground Short identity is stabilizing across accessibility frames")
		}
		if (!prior.accepted && observedAtMillis - prior.firstSeenAtMillis < STABILITY_MS) {
			candidate = prior.copy(latestResult = result)
			return Decision.Waiting("foreground Short identity is stabilizing across accessibility frames")
		}
		candidate = prior.copy(latestResult = result, accepted = true)
		return Decision.Accepted(result)
	}

	/**
	 * Complete the one transition whose second stabilizing frame cannot exist.
	 *
	 * A user may open a fully identified Short and send it to Android PiP before
	 * YouTube publishes another accessibility frame. The first frame is retained
	 * here instead of being weakened or accepted immediately. A later, independent
	 * audio + visible-pinned-window observation may promote it only while the
	 * transition is fresh. Ads and identity-less seekbars are never candidates.
	 * Exact-id recovery still has to corroborate the resulting title, owner and
	 * duration before a row or transaction can be produced.
	 */
	@Synchronized
	fun promotePendingOrganicForImmediatePip(observedAtMillis: Long): NativeShortParser.Result? {
		val pending = candidate ?: return null
		if (pending.accepted || observedAtMillis < pending.firstSeenAtMillis ||
			observedAtMillis - pending.firstSeenAtMillis > IMMEDIATE_PIP_HANDOVER_MS
		) return null
		val promotable = when (pending.latestResult) {
			is NativeShortParser.Result.Organic,
			is NativeShortParser.Result.OrganicUnmeasured,
			-> true
			is NativeShortParser.Result.OrganicUnnamed,
			is NativeShortParser.Result.Ad,
			is NativeShortParser.Result.Invalid,
			-> false
		}
		if (!promotable) return null
		candidate = pending.copy(accepted = true)
		return pending.latestResult
	}

	@Synchronized
	fun reset() {
		candidate = null
	}

	companion object {
		const val STABILITY_MS = 750L
		const val IMMEDIATE_PIP_HANDOVER_MS = 4_000L
	}
}
