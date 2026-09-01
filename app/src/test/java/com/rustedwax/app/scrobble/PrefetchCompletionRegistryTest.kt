package com.rustedwax.app.scrobble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PrefetchCompletionRegistryTest {

	private val video = "dQw4w9WgXcQ"

	@Test
	fun `duplicate in-flight requests share one launch and both complete once`() {
		val registry = PrefetchCompletionRegistry()
		val firstResults = mutableListOf<Boolean>()
		val secondResults = mutableListOf<Boolean>()

		val first = registry.register(video) { firstResults += it }
		val second = registry.register(video) { secondResults += it }

		assertNotNull(first.launch)
		assertNull(second.launch)
		registry.complete(first.launch!!, available = true).forEach { it(true) }
		assertEquals(listOf(true), firstResults)
		assertEquals(listOf(true), secondResults)
		assertEquals(
			true,
			registry.register(video, completion = null).completed,
		)
	}

	@Test
	fun `failure is terminal for the run and is reported without a second launch`() {
		val registry = PrefetchCompletionRegistry()
		val results = mutableListOf<Boolean>()
		val launch = registry.register(video) { results += it }.launch!!

		registry.complete(launch, available = false).forEach { it(false) }
		val duplicate = registry.register(video) { results += it }
		duplicate.completed?.let { results += it }

		assertNull(duplicate.launch)
		assertEquals(listOf(false, false), results)
	}

	@Test
	fun `completion from before reset cannot satisfy a restarted request`() {
		val registry = PrefetchCompletionRegistry()
		val staleResults = mutableListOf<Boolean>()
		val restartedResults = mutableListOf<Boolean>()
		val stale = registry.register(video) { staleResults += it }.launch!!

		registry.reset()
		val restarted = registry.register(video) { restartedResults += it }.launch!!

		registry.complete(stale, available = true).forEach { it(true) }
		assertEquals(emptyList<Boolean>(), staleResults)
		assertEquals(emptyList<Boolean>(), restartedResults)

		registry.complete(restarted, available = true).forEach { it(true) }
		assertEquals(listOf(true), restartedResults)
	}
}
