package com.rustedwax.app.enrich

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FactsCacheCodecTest {

	@Test
	fun `owner handle round trips with current facts schema`() {
		val facts = VideoFacts(
			videoId = "s8ZQSxuKPb0",
			title = "The Mask",
			author = "Sona Darus",
			ownerHandle = "@sonadarus",
			lengthSeconds = 158,
			watchPageResolved = true,
		)
		assertEquals(facts, FactsCache.decode(facts.videoId, FactsCache.encode(facts)))
	}

	@Test
	fun `current explicit missing handle remains valid absence`() {
		val facts = VideoFacts(videoId = "abcdefghijk", watchPageResolved = true)
		assertEquals(facts, FactsCache.decode(facts.videoId, FactsCache.encode(facts)))
	}

	@Test
	fun `legacy cache cannot silently become owner-handle proof`() {
		assertNull(
			FactsCache.decode(
				"s8ZQSxuKPb0",
				"""{"title":"The Mask","watchPageResolved":true,"lengthSeconds":158}""",
			),
		)
		assertNull(FactsCache.decode("s8ZQSxuKPb0", """{"title":"The Mask"}"""))
	}
}
