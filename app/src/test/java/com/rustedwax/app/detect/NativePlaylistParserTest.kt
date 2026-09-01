package com.rustedwax.app.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativePlaylistParserTest {

	private val pkg = YouTubeProbe.YOUTUBE_PACKAGE

	private fun node(
		id: String? = null,
		text: String? = null,
		description: String? = null,
		visible: Boolean = true,
		children: List<NativeShortNode> = emptyList(),
	) = NativeShortNode(
		packageName = pkg,
		resourceId = id?.let { "com.google.android.youtube:id/$it" },
		text = text,
		contentDescription = description,
		visible = visible,
		children = children,
	)

	private fun tree(
		vararg containers: NativeShortNode,
		packageName: String? = pkg,
		exceeded: Boolean = false,
		watchScreen: Boolean = true,
	) = NativePlaylistCapture(
		packageName = packageName,
		watchScreenPresent = watchScreen,
		containers = containers.toList(),
		exceededCaptureBudget = exceeded,
	)

	/** Measured collapsed bar: non-breaking spaces and a bullet around `61/120`. */
	private fun collapsedBar(
		name: String = "Reggaeton 2016,17,18",
		position: String = " • 61/120",
		positionDescription: String? = "61 out of 120",
	) = node(
		children = listOf(
			node(id = "playlist_name", text = name),
			node(id = "position", text = position, description = positionDescription),
			node(id = "chevron", description = "show playlist videos"),
		),
	)

	/** Measured expanded panel header: name arrives as a generic `title`. */
	private fun expandedHeader() = node(
		children = listOf(
			node(id = "title", text = "Reggaeton 2016,17,18"),
			node(id = "position", text = " • 1/120"),
			node(id = "subtitle", text = "Jhonny Gutierrez"),
		),
	)

	@Test
	fun `collapsed playlist bar parses name and position`() {
		assertEquals(
			NativePlaylistParser.Result.Context("Reggaeton 2016,17,18", null, 61, 120),
			NativePlaylistParser.parse(tree(collapsedBar())),
		)
	}

	@Test
	fun `expanded panel header parses name owner and position`() {
		assertEquals(
			NativePlaylistParser.Result.Context("Reggaeton 2016,17,18", "Jhonny Gutierrez", 1, 120),
			NativePlaylistParser.parse(tree(expandedHeader())),
		)
	}

	@Test
	fun `position is read from the content description when the text lacks it`() {
		val bar = node(
			children = listOf(
				node(id = "playlist_name", text = "Reggaeton 2016,17,18"),
				node(id = "position", description = "61 out of 120"),
			),
		)
		assertEquals(
			NativePlaylistParser.Result.Context("Reggaeton 2016,17,18", null, 61, 120),
			NativePlaylistParser.parse(tree(bar)),
		)
	}

	@Test
	fun `a queue row title is never read as the playlist name`() {
		// Each anchor is captured as its own container, so the row's `title`
		// cannot reach the header's position node.
		val queueRow = node(
			id = "playlist_panel_video_item",
			children = listOf(
				node(id = "title", text = "Quiero Repetir"),
				node(id = "channel", text = "Ozuna"),
				node(id = "video_info", text = "110M views • 8 years ago"),
			),
		)
		val result = NativePlaylistParser.parse(tree(expandedHeader(), queueRow))
		assertEquals(
			NativePlaylistParser.Result.Context("Reggaeton 2016,17,18", "Jhonny Gutierrez", 1, 120),
			result,
		)
	}

	@Test
	fun `an ordinary video with no playlist bar reports NoPlaylist`() {
		val watch = node(
			id = "watch_player",
			children = listOf(
				// Measured: autoplay up-next exists without any playlist.
				node(id = "next_video_title", text = "Tiemblan"),
				node(description = "like this video along with 173,005 other people"),
			),
		)
		val result = NativePlaylistParser.parse(tree(watch))
		assertTrue(result is NativePlaylistParser.Result.NoPlaylist)
	}

	@Test
	fun `an ad break that removes the bar reports NoPlaylist not Unobservable`() {
		assertTrue(NativePlaylistParser.parse(tree()) is NativePlaylistParser.Result.NoPlaylist)
	}

	@Test
	fun `a foreign or unreadable root is Unobservable`() {
		assertTrue(
			NativePlaylistParser.parse(tree(collapsedBar(), packageName = "com.android.chrome"))
				is NativePlaylistParser.Result.Unobservable,
		)
		assertTrue(
			NativePlaylistParser.parse(tree(collapsedBar(), packageName = null))
				is NativePlaylistParser.Result.Unobservable,
		)
		assertTrue(
			NativePlaylistParser.parse(tree(collapsedBar(), exceeded = true))
				is NativePlaylistParser.Result.Unobservable,
		)
	}

	@Test
	fun `malformed or out of range positions refuse`() {
		listOf("", " • /120", " • 0/120", " • 121/120", " • 61/0", "not a position").forEach { bad ->
			val result = NativePlaylistParser.parse(
				tree(collapsedBar(position = bad, positionDescription = bad)),
			)
			assertTrue(
				"expected refusal for \"$bad\" but got $result",
				result is NativePlaylistParser.Result.NoPlaylist,
			)
		}
	}

	@Test
	fun `a blank or oversized name refuses`() {
		assertTrue(
			NativePlaylistParser.parse(tree(collapsedBar(name = "   ")))
				is NativePlaylistParser.Result.NoPlaylist,
		)
		assertTrue(
			NativePlaylistParser.parse(tree(collapsedBar(name = "x".repeat(400))))
				is NativePlaylistParser.Result.NoPlaylist,
		)
	}

	@Test
	fun `two disagreeing bars refuse rather than pick one`() {
		val other = node(
			children = listOf(
				node(id = "playlist_name", text = "Something Else"),
				node(id = "position", text = " • 3/9"),
			),
		)
		assertTrue(
			NativePlaylistParser.parse(tree(collapsedBar(), other))
				is NativePlaylistParser.Result.NoPlaylist,
		)
	}

	@Test
	fun `the miniplayer is Unobservable, never NoPlaylist`() {
		// Measured: watch_player and player_view survive into the miniplayer, so
		// only watch_panel separates it from a real watch screen. Getting this
		// wrong dropped a good latch after 103s in the field.
		assertTrue(
			NativePlaylistParser.parse(tree(watchScreen = false))
				is NativePlaylistParser.Result.Unobservable,
		)
	}

	@Test
	fun `an invisible bar is not read`() {
		val hidden = NativeShortNode(
			packageName = pkg,
			children = listOf(
				node(id = "playlist_name", text = "Reggaeton 2016,17,18", visible = false),
				node(id = "position", text = " • 61/120", visible = false),
			),
		)
		assertTrue(
			NativePlaylistParser.parse(tree(hidden)) is NativePlaylistParser.Result.NoPlaylist,
		)
	}
}
