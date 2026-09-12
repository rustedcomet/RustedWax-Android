package com.rustedwax.app.detect

import com.rustedwax.core.PlayerAdSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeWatchAdParserTest {

	private fun node(
		id: String,
		text: String? = null,
		description: String? = null,
		visible: Boolean = true,
	) = NativeShortNode(
		packageName = YouTubeProbe.YOUTUBE_PACKAGE,
		resourceId = "com.google.android.youtube:id/$id",
		text = text,
		contentDescription = description,
		visible = visible,
	)

	private fun capture(
		vararg controls: NativeShortNode,
		watchPlayerVisible: Boolean = true,
		otherPlayerSurfaceVisible: Boolean = false,
		packageName: String? = YouTubeProbe.YOUTUBE_PACKAGE,
	) = NativeWatchAdCapture(
		packageName = packageName,
		watchPlayerVisible = watchPlayerVisible,
		otherPlayerSurfaceVisible = otherPlayerSurfaceVisible,
		adControlNodes = controls.toList(),
	)

	/** Exact player control IDs paired with their supported advertisement labels. */
	@Test
	fun `each measured watch-player ad control reads as a visible ad`() {
		listOf(
			node("ad_progress_text", text = "Sponsored", description = "Sponsored My Ad Center") to "Sponsored",
			node("skip_ad_button_container", description = "Skip ad") to "Skip ad",
			node("player_learn_more_button", text = "Visit advertiser") to "Visit advertiser",
		).forEach { (control, label) ->
			val reading = NativeWatchAdParser.parse(capture(control))
			assertEquals(PlayerAdSurface.VISIBLE, reading.surface)
			assertEquals(label, reading.signal)
		}
	}

	@Test
	fun `the playing watch player with no ad control drawn is observed absent`() {
		val reading = NativeWatchAdParser.parse(
			capture(node("ad_progress_text", text = "Sponsored", visible = false)),
		)
		assertEquals(PlayerAdSurface.ABSENT, reading.surface)
		assertNull(reading.signal)
	}

	@Test
	fun `an exact label on any other view is not the player's state`() {
		val reading = NativeWatchAdParser.parse(
			capture(
				node("promoted_card_badge", text = "Sponsored"),
				node("title", text = "Skip ad"),
			),
		)
		assertEquals(PlayerAdSurface.ABSENT, reading.surface)
	}

	@Test
	fun `a drawn ad control without an exact label proves nothing either way`() {
		listOf("Ad · 0:12", "Ad blockers, explained", "Skip in 5").forEach { label ->
			val reading = NativeWatchAdParser.parse(capture(node("ad_progress_text", text = label)))
			assertEquals(label, PlayerAdSurface.UNOBSERVED, reading.surface)
		}
		assertEquals(
			PlayerAdSurface.UNOBSERVED,
			NativeWatchAdParser.parse(capture(node("skip_ad_button_container"))).surface,
		)
	}

	@Test
	fun `absence is never claimed where the full watch player was not in sight`() {
		assertEquals(
			PlayerAdSurface.UNOBSERVED,
			NativeWatchAdParser.parse(capture(watchPlayerVisible = false)).surface,
		)
		assertEquals(
			PlayerAdSurface.UNOBSERVED,
			NativeWatchAdParser.parse(capture(otherPlayerSurfaceVisible = true)).surface,
		)
		assertEquals(
			PlayerAdSurface.UNOBSERVED,
			NativeWatchAdParser.parse(capture(packageName = "com.android.chrome")).surface,
		)
	}

	@Test
	fun `a label the minimized player still draws is still a visible ad`() {
		val reading = NativeWatchAdParser.parse(
			capture(
				node("ad_progress_text", text = "Sponsored"),
				watchPlayerVisible = false,
				otherPlayerSurfaceVisible = true,
			),
		)
		assertEquals(PlayerAdSurface.VISIBLE, reading.surface)
	}

	@Test
	fun `nothing is read from another package whatever it draws`() {
		val reading = NativeWatchAdParser.parse(
			capture(node("ad_progress_text", text = "Sponsored"), packageName = null),
		)
		assertEquals(PlayerAdSurface.UNOBSERVED, reading.surface)
	}
}
