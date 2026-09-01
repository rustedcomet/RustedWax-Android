package com.rustedwax.youtube.identity

import com.rustedwax.core.ItemIdentity
import com.rustedwax.identity.api.IdentityChainPolicy
import com.rustedwax.identity.api.IdentityDiagnostic
import com.rustedwax.identity.api.IdentityOutcomeKind
import com.rustedwax.identity.api.IdentityResolutionStrategy
import com.rustedwax.identity.api.IdentityStrategyChain
import com.rustedwax.identity.api.IdentityStrategyId
import com.rustedwax.identity.api.IdentityStrategyOutcome
import com.rustedwax.identity.api.ResolvedItemIdentity

data object YouTubeIdentityContext

data class ProductionIdentityContext(
	val publishedIdentity: ItemIdentity,
	val youTube: YouTubeIdentityContext?,
	val repository: YouTubeIdentityStrategyRepository,
)

data class VideoIdentityRepositoryAttempt(
	val resolution: VideoResolution? = null,
	val kind: IdentityOutcomeKind = IdentityOutcomeKind.NO_MATCH,
	val diagnostic: String,
) {
	init {
		require((resolution != null) == (kind == IdentityOutcomeKind.RESOLVED)) {
			"a repository resolution and its typed kind must agree"
		}
	}

	fun toOutcome(): IdentityStrategyOutcome = when (kind) {
		IdentityOutcomeKind.RESOLVED ->
			IdentityStrategyOutcome.Resolved(checkNotNull(resolution), IdentityDiagnostic(diagnostic))
		IdentityOutcomeKind.NOT_APPLICABLE -> IdentityStrategyOutcome.NotApplicable(diagnostic)
		IdentityOutcomeKind.NO_MATCH -> IdentityStrategyOutcome.NoMatch(diagnostic)
		IdentityOutcomeKind.AMBIGUOUS -> IdentityStrategyOutcome.Ambiguous(diagnostic)
		IdentityOutcomeKind.CONTRADICTION -> IdentityStrategyOutcome.Contradiction(diagnostic)
		IdentityOutcomeKind.TEMPORARY_FAILURE -> IdentityStrategyOutcome.TemporaryFailure(diagnostic)
	}
}

interface YouTubeIdentityStrategyRepository {
	suspend fun frozenExactId(): VideoIdentityRepositoryAttempt
	suspend fun runLocalCandidate(): VideoIdentityRepositoryAttempt
	suspend fun consecutivePlaylist(): VideoIdentityRepositoryAttempt
	suspend fun observedPlaylist(): VideoIdentityRepositoryAttempt
	suspend fun watchHistory(): VideoIdentityRepositoryAttempt
	suspend fun structuredNativeMusic(): VideoIdentityRepositoryAttempt
	suspend fun search(): VideoIdentityRepositoryAttempt
}

private data class PublishedResolvedIdentity(
	override val sourceItemId: String,
	override val canonicalLink: String?,
	override val provenance: String,
) : ResolvedItemIdentity

class SourcePublishedIdentityStrategy : IdentityResolutionStrategy<ProductionIdentityContext> {
	override val id = IdentityStrategyId.SOURCE_PUBLISHED

	override suspend fun resolve(context: ProductionIdentityContext): IdentityStrategyOutcome {
		if (context.youTube != null) {
			return IdentityStrategyOutcome.NotApplicable("source-specific identity uses corroborated routes")
		}
		val identity = context.publishedIdentity
		if (!identity.isSourceProven) {
			return IdentityStrategyOutcome.NotApplicable("the source was not proven")
		}
		val itemId = identity.sourceItemId?.takeIf(String::isNotBlank)
			?: return IdentityStrategyOutcome.NoMatch("the source published no stable item id")
		return IdentityStrategyOutcome.Resolved(PublishedResolvedIdentity(
			sourceItemId = itemId,
			canonicalLink = identity.canonicalLink,
			provenance = "source-published ${identity.source}",
		))
	}
}

internal abstract class YouTubeRepositoryStrategy(
	final override val id: IdentityStrategyId,
) : IdentityResolutionStrategy<ProductionIdentityContext> {
	final override suspend fun resolve(context: ProductionIdentityContext): IdentityStrategyOutcome {
		if (context.youTube == null) {
			return IdentityStrategyOutcome.NotApplicable("not a source-specific identity request")
		}
		return attempt(context.repository).toOutcome()
	}

	protected abstract suspend fun attempt(
		repository: YouTubeIdentityStrategyRepository,
	): VideoIdentityRepositoryAttempt
}

internal class FrozenExactIdStrategy : YouTubeRepositoryStrategy(IdentityStrategyId.FROZEN_EXACT_ID) {
	override suspend fun attempt(repository: YouTubeIdentityStrategyRepository) = repository.frozenExactId()
}

internal class RunLocalCandidateStrategy :
	YouTubeRepositoryStrategy(IdentityStrategyId.RUN_LOCAL_CANDIDATE) {
	override suspend fun attempt(repository: YouTubeIdentityStrategyRepository) = repository.runLocalCandidate()
}

internal class ConsecutivePlaylistStrategy :
	YouTubeRepositoryStrategy(IdentityStrategyId.CONSECUTIVE_PLAYLIST) {
	override suspend fun attempt(repository: YouTubeIdentityStrategyRepository) = repository.consecutivePlaylist()
}

internal class ObservedPlaylistStrategy :
	YouTubeRepositoryStrategy(IdentityStrategyId.OBSERVED_PLAYLIST) {
	override suspend fun attempt(repository: YouTubeIdentityStrategyRepository) = repository.observedPlaylist()
}

internal class WatchHistoryStrategy : YouTubeRepositoryStrategy(IdentityStrategyId.WATCH_HISTORY) {
	override suspend fun attempt(repository: YouTubeIdentityStrategyRepository) = repository.watchHistory()
}

internal class StructuredNativeMusicStrategy :
	YouTubeRepositoryStrategy(IdentityStrategyId.STRUCTURED_NATIVE_MUSIC) {
	override suspend fun attempt(repository: YouTubeIdentityStrategyRepository) = repository.structuredNativeMusic()
}

internal class SearchStrategy : YouTubeRepositoryStrategy(IdentityStrategyId.SEARCH) {
	override suspend fun attempt(repository: YouTubeIdentityStrategyRepository) = repository.search()
}

object ProductionIdentityStrategies {
	val ids: List<IdentityStrategyId> = IdentityStrategyId.productionOrder

	fun chain(): IdentityStrategyChain<ProductionIdentityContext> = IdentityStrategyChain(
		strategies = listOf(
			SourcePublishedIdentityStrategy(),
			FrozenExactIdStrategy(),
			RunLocalCandidateStrategy(),
			ConsecutivePlaylistStrategy(),
			ObservedPlaylistStrategy(),
			WatchHistoryStrategy(),
			StructuredNativeMusicStrategy(),
			SearchStrategy(),
		),
		policy = IdentityChainPolicy.default(),
	)
}
