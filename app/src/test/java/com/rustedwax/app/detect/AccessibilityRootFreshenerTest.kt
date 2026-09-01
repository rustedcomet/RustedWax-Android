package com.rustedwax.app.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccessibilityRootFreshenerTest {

	@Test
	fun `cache is cleared before obtaining and refreshing the root`() {
		val calls = mutableListOf<String>()
		val root = AccessibilityRootFreshener.acquire(
			clearCache = { calls += "clear" },
			obtain = { calls += "obtain"; "current" },
			refresh = { calls += "refresh:$it"; true },
			recycle = { calls += "recycle:$it" },
		)

		assertEquals("current", root)
		assertEquals(listOf("clear", "obtain", "refresh:current"), calls)
	}

	@Test
	fun `a stale outgoing root is recycled and cannot become the incoming capture`() {
		val calls = mutableListOf<String>()
		val roots = ArrayDeque(listOf("outgoing-footer", "incoming-footer"))
		val root = AccessibilityRootFreshener.acquire(
			clearCache = { calls += "clear" },
			obtain = { calls += "obtain"; roots.removeFirstOrNull() },
			refresh = {
				calls += "refresh:$it"
				it == "incoming-footer"
			},
			recycle = { calls += "recycle:$it" },
		)

		assertEquals("incoming-footer", root)
		assertEquals(
			listOf(
				"clear", "obtain", "refresh:outgoing-footer", "recycle:outgoing-footer",
				"clear", "obtain", "refresh:incoming-footer",
			),
			calls,
		)
	}

	@Test
	fun `two unrefreshable roots fail closed and both are recycled`() {
		val recycled = mutableListOf<String>()
		var next = 0
		val root = AccessibilityRootFreshener.acquire(
			clearCache = {},
			obtain = { "stale-${++next}" },
			refresh = { false },
			recycle =(recycled::add),
		)

		assertNull(root)
		assertEquals(listOf("stale-1", "stale-2"), recycled)
	}
}
