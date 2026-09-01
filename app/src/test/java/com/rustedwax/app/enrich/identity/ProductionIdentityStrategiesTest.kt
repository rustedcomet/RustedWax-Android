package com.rustedwax.app.enrich.identity

import com.rustedwax.core.ItemIdentity
import com.rustedwax.app.detect.YouTubeProbe
import com.rustedwax.identity.api.IdentityOutcomeKind
import com.rustedwax.identity.api.IdentityStrategyId
import com.rustedwax.identity.api.IdentityStrategyOutcome
import com.rustedwax.youtube.identity.VideoResolution
import com.rustedwax.youtube.identity.ProductionIdentityContext
import com.rustedwax.youtube.identity.ProductionIdentityStrategies
import com.rustedwax.youtube.identity.VideoIdentityRepositoryAttempt
import com.rustedwax.youtube.identity.YouTubeIdentityContext
import com.rustedwax.youtube.identity.YouTubeIdentityStrategyRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProductionIdentityStrategiesTest {

	private data class ForeignIdentity(
		override val source: String,
		override val sourceItemId: String?,
		override val canonicalLink: String?,
		override val isSourceProven: Boolean,
	) : ItemIdentity

	private fun noMatch(route: String) = VideoIdentityRepositoryAttempt(
		kind = IdentityOutcomeKind.NO_MATCH,
		diagnostic = "$route did not match",
	)

	private fun youTubeContext(repository: YouTubeIdentityStrategyRepository) =
		ProductionIdentityContext(
			publishedIdentity = YouTubeProbe.Identity.SiteOnly(
				host = "youtube.com",
				isMusic = false,
				source = "test",
			),
			youTube = YouTubeIdentityContext,
			repository = repository,
		)

	@Test
	fun `production factory exposes the exact phase six order`() {
		assertEquals(
			listOf(
				IdentityStrategyId.SOURCE_PUBLISHED,
				IdentityStrategyId.FROZEN_EXACT_ID,
				IdentityStrategyId.RUN_LOCAL_CANDIDATE,
				IdentityStrategyId.CONSECUTIVE_PLAYLIST,
				IdentityStrategyId.OBSERVED_PLAYLIST,
				IdentityStrategyId.WATCH_HISTORY,
				IdentityStrategyId.STRUCTURED_NATIVE_MUSIC,
				IdentityStrategyId.SEARCH,
			),
			ProductionIdentityStrategies.ids,
		)
	}

	@Test
	fun `foreign published identity resolves without entering a YouTube repository`() = runBlocking {
		val calls = mutableListOf<String>()
		val repository = object : YouTubeIdentityStrategyRepository {
			override suspend fun frozenExactId(): VideoIdentityRepositoryAttempt =
				unexpected("frozen")

			override suspend fun runLocalCandidate(): VideoIdentityRepositoryAttempt =
				unexpected("run-local")

			override suspend fun consecutivePlaylist(): VideoIdentityRepositoryAttempt =
				unexpected("consecutive")

			override suspend fun observedPlaylist(): VideoIdentityRepositoryAttempt =
				unexpected("observed")

			override suspend fun watchHistory(): VideoIdentityRepositoryAttempt =
				unexpected("history")

			override suspend fun structuredNativeMusic(): VideoIdentityRepositoryAttempt =
				unexpected("structured")

			override suspend fun search(): VideoIdentityRepositoryAttempt =
				unexpected("search")

			private fun unexpected(route: String): Nothing {
				calls += route
				error("foreign identity entered $route")
			}
		}
		val context = ProductionIdentityContext(
			publishedIdentity = ForeignIdentity(
				source = "ExampleMusic",
				sourceItemId = "example:track:4417",
				canonicalLink = "https://example.fm/tracks/4417",
				isSourceProven = true,
			),
			youTube = null,
			repository = repository,
		)

		val result = ProductionIdentityStrategies.chain().resolve(context)
		val resolved = result.outcome as IdentityStrategyOutcome.Resolved

		assertEquals("example:track:4417", resolved.identity.sourceItemId)
		assertEquals("https://example.fm/tracks/4417", resolved.identity.canonicalLink)
		assertEquals(emptyList<String>(), calls)
		assertEquals(1, result.transitions.size)
		assertEquals(IdentityStrategyId.SOURCE_PUBLISHED, result.transitions.single().strategy)
	}

	@Test
	fun `production control flow never parses diagnostic wording`() {
		val root = generateSequence(File(checkNotNull(System.getProperty("user.dir")))) { it.parentFile }
			.first { File(it, "app/src/main/java").isDirectory }
		val production = File(root, "app/src/main/java")
			.walkTopDown()
			.filter { it.isFile && it.extension == "kt" }
			.joinToString("\n") { it.readText() }

		assertTrue(
			"FinalizationRuntime must execute the production typed strategy chain",
			production.contains("ProductionIdentityStrategies.chain()"),
		)
		assertTrue(
			"diagnostic text must never decide identity control flow",
			!production.contains("refusalReason?.startsWith") &&
				!production.contains("refusalReason.startsWith") &&
				!production.contains("diagnostic.message.startsWith") &&
				!production.contains("refusalReason.orEmpty().contains") &&
				!production.contains("refusalReason?.contains(\"ambiguous identity") &&
				!production.contains("refusalReason?.contains(\"contradiction"),
		)
	}

	@Test
	fun `frozen exact id has priority over every candidate repository`() = runBlocking {
		val calls = mutableListOf<String>()
		val frozen = VideoResolution(
			videoId = "frozenIdAaa",
			source = "frozen address bar",
			uniquelyResolved = false,
		)
		val repository = object : YouTubeIdentityStrategyRepository {
			override suspend fun frozenExactId(): VideoIdentityRepositoryAttempt {
				calls += "frozen"
				return VideoIdentityRepositoryAttempt(
					resolution = frozen,
					kind = IdentityOutcomeKind.RESOLVED,
					diagnostic = frozen.source,
				)
			}

			override suspend fun runLocalCandidate() = unexpected("run-local")
			override suspend fun consecutivePlaylist() = unexpected("consecutive")
			override suspend fun observedPlaylist() = unexpected("observed")
			override suspend fun watchHistory() = unexpected("history")
			override suspend fun structuredNativeMusic() = unexpected("structured")
			override suspend fun search() = unexpected("search")

			private fun unexpected(route: String): Nothing =
				error("$route must not run after frozen exact identity")
		}

		val result = ProductionIdentityStrategies.chain().resolve(youTubeContext(repository))
		val resolved = result.outcome as IdentityStrategyOutcome.Resolved

		assertEquals("frozenIdAaa", resolved.identity.sourceItemId)
		assertEquals(listOf("frozen"), calls)
		assertEquals(
			listOf(IdentityStrategyId.SOURCE_PUBLISHED, IdentityStrategyId.FROZEN_EXACT_ID),
			result.transitions.map { it.strategy },
		)
	}

	@Test
	fun `structured native music precedes ordinary search in the typed chain`() = runBlocking {
		val calls = mutableListOf<String>()
		val structured = VideoResolution(
			videoId = "structured1",
			source = "structured native music title+artist+duration",
			uniquelyResolved = true,
			structuredNativeMusic = true,
		)
		val repository = object : YouTubeIdentityStrategyRepository {
			override suspend fun frozenExactId() = noMatch("frozen")
			override suspend fun runLocalCandidate() = noMatch("run-local")
			override suspend fun consecutivePlaylist() = noMatch("consecutive")
			override suspend fun observedPlaylist() = noMatch("observed")
			override suspend fun watchHistory() = noMatch("history")
			override suspend fun structuredNativeMusic(): VideoIdentityRepositoryAttempt {
				calls += "structured"
				return VideoIdentityRepositoryAttempt(
					resolution = structured,
					kind = IdentityOutcomeKind.RESOLVED,
					diagnostic = structured.source,
				)
			}
			override suspend fun search(): VideoIdentityRepositoryAttempt =
				error("ordinary search must not run after structured identity resolved")
		}

		val result = ProductionIdentityStrategies.chain().resolve(youTubeContext(repository))

		assertEquals("structured1", (result.outcome as IdentityStrategyOutcome.Resolved).identity.sourceItemId)
		assertEquals(listOf("structured"), calls)
		assertEquals(
			IdentityStrategyId.STRUCTURED_NATIVE_MUSIC,
			result.transitions.last().strategy,
		)
	}
}
