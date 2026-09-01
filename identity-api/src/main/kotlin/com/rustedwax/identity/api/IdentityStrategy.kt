package com.rustedwax.identity.api

/** A source-neutral identity that one strategy resolved. */
interface ResolvedItemIdentity {
	val sourceItemId: String
	val canonicalLink: String?
	val provenance: String
}

/** Stable production route identifiers. Source-specific execution lives downstream. */
enum class IdentityStrategyId {
	SOURCE_PUBLISHED,
	FROZEN_EXACT_ID,
	RUN_LOCAL_CANDIDATE,
	CONSECUTIVE_PLAYLIST,
	OBSERVED_PLAYLIST,
	WATCH_HISTORY,
	STRUCTURED_NATIVE_MUSIC,
	SEARCH,
	;

	companion object {
		val productionOrder: List<IdentityStrategyId> = entries
	}
}

enum class IdentityOutcomeKind {
	RESOLVED,
	NOT_APPLICABLE,
	NO_MATCH,
	AMBIGUOUS,
	CONTRADICTION,
	TEMPORARY_FAILURE,
}

data class IdentityDiagnostic(val message: String)

sealed interface IdentityStrategyOutcome {
	val kind: IdentityOutcomeKind
	val diagnostic: IdentityDiagnostic

	data class Resolved(
		val identity: ResolvedItemIdentity,
		override val diagnostic: IdentityDiagnostic = IdentityDiagnostic(identity.provenance),
	) : IdentityStrategyOutcome {
		override val kind = IdentityOutcomeKind.RESOLVED
	}

	data class NotApplicable(val reason: String) : IdentityStrategyOutcome {
		override val kind = IdentityOutcomeKind.NOT_APPLICABLE
		override val diagnostic = IdentityDiagnostic(reason)
	}

	data class NoMatch(val reason: String) : IdentityStrategyOutcome {
		override val kind = IdentityOutcomeKind.NO_MATCH
		override val diagnostic = IdentityDiagnostic(reason)
	}

	data class Ambiguous(val reason: String) : IdentityStrategyOutcome {
		override val kind = IdentityOutcomeKind.AMBIGUOUS
		override val diagnostic = IdentityDiagnostic(reason)
	}

	data class Contradiction(val reason: String) : IdentityStrategyOutcome {
		override val kind = IdentityOutcomeKind.CONTRADICTION
		override val diagnostic = IdentityDiagnostic(reason)
	}

	data class TemporaryFailure(val reason: String) : IdentityStrategyOutcome {
		override val kind = IdentityOutcomeKind.TEMPORARY_FAILURE
		override val diagnostic = IdentityDiagnostic(reason)
	}
}

fun interface IdentityResolutionStrategy<C> {
	val id: IdentityStrategyId
		get() = error("a strategy must publish its stable id")

	suspend fun resolve(context: C): IdentityStrategyOutcome
}

enum class IdentityChainDecision { CONTINUE, STOP }

class IdentityChainPolicy private constructor(
	val matrix: Map<Pair<IdentityStrategyId, IdentityOutcomeKind>, IdentityChainDecision>,
) {
	init {
		val expected = IdentityStrategyId.entries.flatMap { strategy ->
			IdentityOutcomeKind.entries.map { outcome -> strategy to outcome }
		}.toSet()
		require(matrix.keys == expected) {
			"identity chain policy must define every strategy/outcome pair"
		}
	}

	fun decision(strategy: IdentityStrategyId, outcome: IdentityOutcomeKind): IdentityChainDecision =
		checkNotNull(matrix[strategy to outcome])

	companion object {
		fun default(): IdentityChainPolicy = IdentityChainPolicy(buildMap {
			IdentityStrategyId.entries.forEach { strategy ->
				IdentityOutcomeKind.entries.forEach { outcome ->
					put(strategy to outcome, when (outcome) {
						IdentityOutcomeKind.RESOLVED,
						IdentityOutcomeKind.AMBIGUOUS,
						IdentityOutcomeKind.CONTRADICTION,
						-> IdentityChainDecision.STOP

						IdentityOutcomeKind.NOT_APPLICABLE,
						IdentityOutcomeKind.NO_MATCH,
						IdentityOutcomeKind.TEMPORARY_FAILURE,
						-> IdentityChainDecision.CONTINUE
					})
				}
			}
		})
	}
}

data class IdentityChainTransition(
	val strategy: IdentityStrategyId,
	val outcome: IdentityOutcomeKind,
	val decision: IdentityChainDecision,
)

data class IdentityChainResult(
	val outcome: IdentityStrategyOutcome,
	val transitions: List<IdentityChainTransition>,
)

class IdentityStrategyChain<C>(
	private val strategies: List<IdentityResolutionStrategy<C>>,
	private val policy: IdentityChainPolicy,
) {
	init {
		require(strategies.map { it.id }.distinct().size == strategies.size) {
			"an identity strategy may appear only once in a chain"
		}
	}

	suspend fun resolve(context: C): IdentityChainResult {
		val transitions = mutableListOf<IdentityChainTransition>()
		var last: IdentityStrategyOutcome =
			IdentityStrategyOutcome.NotApplicable("no identity strategy applied")
		for (strategy in strategies) {
			val outcome = strategy.resolve(context)
			val decision = policy.decision(strategy.id, outcome.kind)
			transitions += IdentityChainTransition(strategy.id, outcome.kind, decision)
			if (outcome !is IdentityStrategyOutcome.NotApplicable ||
				last is IdentityStrategyOutcome.NotApplicable
			) last = outcome
			if (decision == IdentityChainDecision.STOP) return IdentityChainResult(outcome, transitions)
		}
		return IdentityChainResult(last, transitions)
	}
}
