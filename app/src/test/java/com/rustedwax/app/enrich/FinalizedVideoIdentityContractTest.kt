package com.rustedwax.app.enrich

import com.rustedwax.youtube.identity.VideoResolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FinalizedVideoIdentityContractTest {

	@Test
	fun `a frozen exact id is authoritative without resolver uniqueness`() {
		val resolution = VideoResolution(
			videoId = "lir3dzYIhz0",
			source = "frozen address bar",
			uniquelyResolved = false,
		)

		assertEquals(
			FinalizedVideoIdentityAuthority.FROZEN_EXACT_ID,
			FinalizedVideoIdentityContract.authority("lir3dzYIhz0", resolution),
		)
	}

	@Test
	fun `one finalized lookup match is authoritative without a frozen id`() {
		val resolution = VideoResolution(
			videoId = "7i_2TJv96Wk",
			source = "title+channel+duration search",
			uniquelyResolved = true,
		)

		assertEquals(
			FinalizedVideoIdentityAuthority.UNIQUE_FINALIZED_LOOKUP,
			FinalizedVideoIdentityContract.authority(null, resolution),
		)
	}

	@Test
	fun `a plausible but non-unique lookup has no identity authority`() {
		val resolution = VideoResolution(
			videoId = "7i_2TJv96Wk",
			source = "plausible search card",
			uniquelyResolved = false,
		)

		assertNull(FinalizedVideoIdentityContract.authority(null, resolution))
		assertTrue(
			FinalizedVideoIdentityContract.refusalReason(null, resolution)
				.contains("without a unique finalized-track match"),
		)
	}

	@Test
	fun `a resolver cannot replace a different frozen id`() {
		val resolution = VideoResolution(
			videoId = "lir3dzYIhz0",
			source = "title+channel+duration search",
			uniquelyResolved = true,
		)

		assertNull(
			FinalizedVideoIdentityContract.authority("QuQW1vkDA1c", resolution),
		)
	}
}
