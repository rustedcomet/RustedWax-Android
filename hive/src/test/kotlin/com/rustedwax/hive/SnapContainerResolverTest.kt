package com.rustedwax.hive

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Container recognition, against fixtures shaped like the live `bridge` response. */
class SnapContainerResolverTest {

	private fun post(author: String, permlink: String, created: String = "2026-09-17T12:36:00") =
		JSONObject().put("author", author).put("permlink", permlink).put("created", created)

	private fun resolver(
		posts: List<JSONObject>,
		record: MutableList<Triple<String, String, Int>> = mutableListOf(),
	) = SnapContainerResolver { account, sort, limit ->
		record += Triple(account, sort, limit)
		posts
	}

	@Test
	fun `resolves the newest container`() {
		val result = resolver(
			listOf(
				post("peak.snaps", "snap-container-1789648560"),
				post("peak.snaps", "snap-container-1789583040"),
			),
		).resolve()
		val container = (result as SnapContainerResolver.Result.Resolved).container
		assertEquals("peak.snaps", container.author)
		assertEquals("snap-container-1789648560", container.permlink)
	}

	/**
	 * The sort is load-bearing. `blog` answers with an empty list for this
	 * account, so asking the wrong way looks exactly like a missing container.
	 */
	@Test
	fun `asks for the sort that actually returns containers`() {
		val calls = mutableListOf<Triple<String, String, Int>>()
		resolver(listOf(post("peak.snaps", "snap-container-1")), calls).resolve()
		assertEquals("peak.snaps", calls.single().first)
		assertEquals("posts", calls.single().second)
		assertEquals("posts", SnapContainerResolver.CONTAINER_SORT)
	}

	/**
	 * A prefix test alone was too loose — `snap-container-announcement` passed
	 * it. The permlink must be the prefix followed by digits and nothing else.
	 */
	@Test
	fun `refuses malformed container permlinks`() {
		listOf(
			"snap-container-",
			"snap-container-announcement",
			"snap-container-123abc",
			"snap-container-12.3",
			"snap-container-12 ",
			"xsnap-container-123",
		).forEach { bad ->
			assertTrue(
				"must refuse '$bad'",
				resolver(listOf(post("peak.snaps", bad))).resolve()
					is SnapContainerResolver.Result.Unavailable,
			)
		}
		assertTrue(SnapContainerResolver.CONTAINER_PERMLINK.matches("snap-container-1789648560"))
	}

	/** A malformed newest post must not stop a valid older container being used. */
	@Test
	fun `falls back past a malformed container`() {
		val result = resolver(
			listOf(
				post("peak.snaps", "snap-container-announcement"),
				post("peak.snaps", "snap-container-1789583040"),
			),
		).resolve()
		assertEquals(
			"snap-container-1789583040",
			(result as SnapContainerResolver.Result.Resolved).container.permlink,
		)
	}

	/** Never parent a permanent public comment on something unrecognised. */
	@Test
	fun `skips posts that are not containers`() {
		val result = resolver(
			listOf(
				post("peak.snaps", "some-announcement-post"),
				post("peak.snaps", "snap-container-1789583040"),
			),
		).resolve()
		assertEquals(
			"snap-container-1789583040",
			(result as SnapContainerResolver.Result.Resolved).container.permlink,
		)
	}

	@Test
	fun `refuses a container from the wrong author`() {
		val result = resolver(listOf(post("impostor", "snap-container-1789648560"))).resolve()
		assertTrue(result is SnapContainerResolver.Result.Unavailable)
	}

	@Test
	fun `reports unavailable rather than inventing a container`() {
		assertTrue(resolver(emptyList()).resolve() is SnapContainerResolver.Result.Unavailable)
		val threw = SnapContainerResolver { _, _, _ -> error("node down") }.resolve()
		assertTrue(threw is SnapContainerResolver.Result.Unavailable)
		assertTrue((threw as SnapContainerResolver.Result.Unavailable).reason.contains("node down"))
	}

	/** No fixture container may ever be baked into the app. */
	@Test
	fun `hardcodes no particular container`() {
		assertEquals("snap-container-", SnapContainerResolver.SNAP_CONTAINER_PREFIX)
		assertEquals("peak.snaps", SnapContainerResolver.SNAP_CONTAINER_ACCOUNT)
	}
}
