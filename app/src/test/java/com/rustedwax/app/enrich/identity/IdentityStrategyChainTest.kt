package com.rustedwax.app.enrich.identity

import com.rustedwax.identity.api.IdentityChainDecision
import com.rustedwax.identity.api.IdentityChainPolicy
import com.rustedwax.identity.api.IdentityOutcomeKind
import com.rustedwax.identity.api.IdentityResolutionStrategy
import com.rustedwax.identity.api.IdentityStrategyChain
import com.rustedwax.identity.api.IdentityStrategyId
import com.rustedwax.identity.api.IdentityStrategyOutcome
import com.rustedwax.identity.api.ResolvedItemIdentity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class IdentityStrategyChainTest {

	private data class Context(val marker: String = "context")

	private data class Identity(
		override val sourceItemId: String,
		override val canonicalLink: String?,
		override val provenance: String,
	) : ResolvedItemIdentity

	private class ScriptedStrategy(
		override val id: IdentityStrategyId,
		private val outcome: IdentityStrategyOutcome,
		private val calls: MutableList<IdentityStrategyId>,
	) : IdentityResolutionStrategy<Context> {
		override suspend fun resolve(context: Context): IdentityStrategyOutcome {
			calls += id
			return outcome
		}
	}

	@Test
	fun `default policy explicitly covers every strategy and sealed outcome`() {
		val policy = IdentityChainPolicy.default()
		assertEquals(
			IdentityStrategyId.entries.size * IdentityOutcomeKind.entries.size,
			policy.matrix.size,
		)
		IdentityStrategyId.entries.forEach { strategy ->
			IdentityOutcomeKind.entries.forEach { outcome ->
				val expected = when (outcome) {
					IdentityOutcomeKind.RESOLVED,
					IdentityOutcomeKind.AMBIGUOUS,
					IdentityOutcomeKind.CONTRADICTION,
					-> IdentityChainDecision.STOP

					IdentityOutcomeKind.NOT_APPLICABLE,
					IdentityOutcomeKind.NO_MATCH,
					IdentityOutcomeKind.TEMPORARY_FAILURE,
					-> IdentityChainDecision.CONTINUE
				}
				assertEquals("$strategy / $outcome", expected, policy.decision(strategy, outcome))
			}
		}
	}

	@Test
	fun `all six sealed outcomes publish their typed discriminator`() {
		val resolvedIdentity = Identity("stable-item", "https://source.test/stable-item", "fixture")
		val outcomes = listOf(
			IdentityStrategyOutcome.Resolved(resolvedIdentity),
			IdentityStrategyOutcome.NotApplicable("not applicable"),
			IdentityStrategyOutcome.NoMatch("no match"),
			IdentityStrategyOutcome.Ambiguous("ambiguous"),
			IdentityStrategyOutcome.Contradiction("contradiction"),
			IdentityStrategyOutcome.TemporaryFailure("temporary"),
		)

		assertEquals(IdentityOutcomeKind.entries, outcomes.map { it.kind })
		assertSame(
			resolvedIdentity,
			(outcomes.single { it.kind == IdentityOutcomeKind.RESOLVED } as
				IdentityStrategyOutcome.Resolved).identity,
		)
	}

	@Test
	fun `production order is exact and no match plus temporary failure fall through`() = runBlocking {
		val calls = mutableListOf<IdentityStrategyId>()
		val resolved = IdentityStrategyOutcome.Resolved(
			Identity(
				sourceItemId = "FcYm_6kR3Eg",
				canonicalLink = "https://www.youtube.com/watch?v=FcYm_6kR3Eg",
				provenance = "ordinary search",
			),
		)
		val outcomes = mapOf(
			IdentityStrategyId.SOURCE_PUBLISHED to IdentityStrategyOutcome.NotApplicable("not published"),
			IdentityStrategyId.FROZEN_EXACT_ID to IdentityStrategyOutcome.NotApplicable("no frozen id"),
			IdentityStrategyId.RUN_LOCAL_CANDIDATE to IdentityStrategyOutcome.NoMatch("cache miss"),
			IdentityStrategyId.CONSECUTIVE_PLAYLIST to
				IdentityStrategyOutcome.TemporaryFailure("playlist discovery offline"),
			IdentityStrategyId.OBSERVED_PLAYLIST to IdentityStrategyOutcome.NoMatch("queue miss"),
			IdentityStrategyId.WATCH_HISTORY to IdentityStrategyOutcome.NotApplicable("disconnected"),
			IdentityStrategyId.STRUCTURED_NATIVE_MUSIC to
				IdentityStrategyOutcome.NoMatch("structured miss"),
			IdentityStrategyId.SEARCH to resolved,
		)
		val chain = IdentityStrategyChain(
			IdentityStrategyId.productionOrder.map { id ->
				ScriptedStrategy(id, checkNotNull(outcomes[id]), calls)
			},
			IdentityChainPolicy.default(),
		)

		val result = chain.resolve(Context())

		assertSame(resolved, result.outcome)
		assertEquals(IdentityStrategyId.productionOrder, calls)
	}

	@Test
	fun `ambiguity stops before every lower priority strategy`() = runBlocking {
		val calls = mutableListOf<IdentityStrategyId>()
		val ambiguity = IdentityStrategyOutcome.Ambiguous("two run-local candidates")
		val chain = IdentityStrategyChain(
			listOf(
				ScriptedStrategy(
					IdentityStrategyId.SOURCE_PUBLISHED,
					IdentityStrategyOutcome.NotApplicable("not published"),
					calls,
				),
				ScriptedStrategy(
					IdentityStrategyId.FROZEN_EXACT_ID,
					IdentityStrategyOutcome.NotApplicable("no frozen id"),
					calls,
				),
				ScriptedStrategy(IdentityStrategyId.RUN_LOCAL_CANDIDATE, ambiguity, calls),
				ScriptedStrategy(
					IdentityStrategyId.CONSECUTIVE_PLAYLIST,
					IdentityStrategyOutcome.Resolved(
						Identity("wrong-lower-priority", null, "must not run"),
					),
					calls,
				),
			),
			IdentityChainPolicy.default(),
		)

		val result = chain.resolve(Context())

		assertSame(ambiguity, result.outcome)
		assertEquals(
			listOf(
				IdentityStrategyId.SOURCE_PUBLISHED,
				IdentityStrategyId.FROZEN_EXACT_ID,
				IdentityStrategyId.RUN_LOCAL_CANDIDATE,
			),
			calls,
		)
	}

	@Test
	fun `contradiction stops before every lower priority strategy`() = runBlocking {
		val calls = mutableListOf<IdentityStrategyId>()
		val contradiction = IdentityStrategyOutcome.Contradiction("stable id contradicted")
		val chain = IdentityStrategyChain(
			listOf(
				ScriptedStrategy(
					IdentityStrategyId.SOURCE_PUBLISHED,
					IdentityStrategyOutcome.NotApplicable("not published"),
					calls,
				),
				ScriptedStrategy(IdentityStrategyId.FROZEN_EXACT_ID, contradiction, calls),
				ScriptedStrategy(
					IdentityStrategyId.RUN_LOCAL_CANDIDATE,
					IdentityStrategyOutcome.Resolved(
						Identity("wrong-lower-priority", null, "must not run"),
					),
					calls,
				),
			),
			IdentityChainPolicy.default(),
		)

		val result = chain.resolve(Context())

		assertSame(contradiction, result.outcome)
		assertEquals(
			listOf(
				IdentityStrategyId.SOURCE_PUBLISHED,
				IdentityStrategyId.FROZEN_EXACT_ID,
			),
			calls,
		)
	}

	@Test
	fun `resolved identity preserves stable id and canonical link exactly`() = runBlocking {
		val identity = Identity(
			sourceItemId = "spotify:track:4cOdK2wGLETKBW3PvgPWqT",
			canonicalLink = "https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT",
			provenance = "source-published",
		)
		val chain = IdentityStrategyChain(
			listOf(
				ScriptedStrategy(
					IdentityStrategyId.SOURCE_PUBLISHED,
					IdentityStrategyOutcome.Resolved(identity),
					mutableListOf(),
				),
			),
			IdentityChainPolicy.default(),
		)

		val result = chain.resolve(Context()).outcome as IdentityStrategyOutcome.Resolved

		assertEquals("spotify:track:4cOdK2wGLETKBW3PvgPWqT", result.identity.sourceItemId)
		assertEquals(
			"https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT",
			result.identity.canonicalLink,
		)
	}
}
