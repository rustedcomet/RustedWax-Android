package com.rustedwax.app.enrich

import com.rustedwax.youtube.identity.VideoResolutionFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The predecessor-playlist budget is a memory bound, not an identity verdict.
 *
 * It is spent before any candidate playlist is fetched, so no two ids have been
 * compared when it trips. Typing that refusal AMBIGUOUS stopped the identity
 * chain and vetoed the later exact routes; NOT_APPLICABLE skips this strategy
 * and lets them run.
 */
class PredecessorPlaylistBudgetClassificationTest {

	private val resolver = VideoIdResolver()

	@Test
	fun `a discovery set above the budget skips the route instead of claiming ambiguity`() {
		val refusal = checkNotNull(resolver.predecessorPlaylistBudgetRefusal(20)) {
			"twenty lists is above the bounded budget and must refuse"
		}
		assertEquals(VideoResolutionFailure.NOT_APPLICABLE, refusal.failure)
		assertNull("a budget refusal must never hand back an unverified id", refusal.resolution)
		assertTrue(
			"the refusal must say the budget stopped it, not the identity: " +
				"${refusal.refusalReason}",
			checkNotNull(refusal.refusalReason).contains("budget"),
		)
	}

	@Test
	fun `exactly the budget and below still runs the ordinary playlist inference`() {
		assertNull(
			"a candidate set at the budget must be inspected, not skipped",
			resolver.predecessorPlaylistBudgetRefusal(
				VideoIdResolver.MAX_PREDECESSOR_PLAYLIST_CANDIDATES,
			),
		)
		assertNull(
			"a candidate set below the budget must be inspected, not skipped",
			resolver.predecessorPlaylistBudgetRefusal(1),
		)
	}

	@Test
	fun `the fix did not widen the bound it reclassifies`() {
		assertTrue(
			"the bound this reclassifies must not be widened",
			VideoIdResolver.MAX_PREDECESSOR_PLAYLIST_CANDIDATES <= 8,
		)
		assertEquals(
			"one list over the bound is already too many",
			VideoResolutionFailure.NOT_APPLICABLE,
			resolver.predecessorPlaylistBudgetRefusal(
				VideoIdResolver.MAX_PREDECESSOR_PLAYLIST_CANDIDATES + 1,
			)?.failure,
		)
	}
}
