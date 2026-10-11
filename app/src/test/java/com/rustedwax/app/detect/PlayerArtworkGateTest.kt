package com.rustedwax.app.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which player-published pictures the Now card may draw, and for which listen. */
class PlayerArtworkGateTest {

	private fun browser() = PlayerArtworkGate<String>(rejectInherited = true, same = { a, b -> a == b })
	private fun native() = PlayerArtworkGate<String>(rejectInherited = false, same = { a, b -> a == b })

	@Test
	fun `a picture is accepted for the listen and title of its own bundle`() {
		val accepted = browser().observe(token = 1, title = "Honey Bun", image = "frame-a")
		assertEquals(PlayerArtworkGate.Accepted("frame-a", "Honey Bun"), accepted)
	}

	@Test
	fun `no picture or no title is no artwork`() {
		val gate = browser()
		assertNull(gate.observe(token = 1, title = "Honey Bun", image = null))
		assertNull(gate.observe(token = 1, title = " ", image = "frame-a"))
		assertNull(gate.observe(token = 1, title = null, image = "frame-a"))
	}

	@Test
	fun `a browser's previous-listen picture is refused for the next listen until it changes`() {
		val gate = browser()
		gate.observe(token = 1, title = "Honey Bun", image = "frame-a")

		// Next video's title announced while the page still holds the old frame.
		assertNull(gate.observe(token = 2, title = "Slippery", image = "frame-a"))
		assertNull(gate.observe(token = 2, title = "Slippery", image = "frame-a"))
		assertEquals(
			PlayerArtworkGate.Accepted("frame-b", "Slippery"),
			gate.observe(token = 2, title = "Slippery", image = "frame-b"),
		)
	}

	@Test
	fun `a stale picture stays refused across any number of switches until a fresh one arrives`() {
		val gate = browser()
		gate.observe(token = 1, title = "A", image = "frame-a")

		// B, C and D are announced while the page still holds A's frame.
		assertNull(gate.observe(token = 2, title = "B", image = "frame-a"))
		assertNull(gate.observe(token = 3, title = "C", image = "frame-a"))
		assertNull(gate.observe(token = 4, title = "D", image = "frame-a"))

		// D's own frame finally lands.
		assertEquals(
			PlayerArtworkGate.Accepted("frame-d", "D"),
			gate.observe(token = 4, title = "D", image = "frame-d"),
		)
	}

	@Test
	fun `a freshly accepted picture becomes the reference for the next switches`() {
		val gate = browser()
		gate.observe(token = 1, title = "A", image = "frame-a")
		assertNull(gate.observe(token = 2, title = "B", image = "frame-a"))
		gate.observe(token = 2, title = "B", image = "frame-b")

		// Now B's frame is the one a lagging page would still hold.
		assertNull(gate.observe(token = 3, title = "C", image = "frame-b"))
		assertNull(gate.observe(token = 4, title = "D", image = "frame-b"))
		assertEquals(
			PlayerArtworkGate.Accepted("frame-d", "D"),
			gate.observe(token = 4, title = "D", image = "frame-d"),
		)
	}

	@Test
	fun `a listen keeps its own picture across repeated bundles`() {
		val gate = browser()
		gate.observe(token = 1, title = "A", image = "frame-a")
		gate.observe(token = 2, title = "B", image = "frame-b")
		assertEquals(
			PlayerArtworkGate.Accepted("frame-b", "B"),
			gate.observe(token = 2, title = "B", image = "frame-b"),
		)
	}

	@Test
	fun `a native album cover shared by consecutive tracks stays visible`() {
		val gate = native()
		gate.observe(token = 1, title = "Track 1", image = "album-cover")
		listOf(2L to "Track 2", 3L to "Track 3", 4L to "Track 4").forEach { (token, title) ->
			assertEquals(
				PlayerArtworkGate.Accepted("album-cover", title),
				gate.observe(token = token, title = title, image = "album-cover"),
			)
		}
	}

	@Test
	fun `the first listen inherits nothing`() {
		assertEquals(
			PlayerArtworkGate.Accepted("frame-a", "First"),
			browser().observe(token = 7, title = "First", image = "frame-a"),
		)
	}
}
