package com.rustedwax.app.detect

import com.rustedwax.core.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExclusivePlaybackArbitrationTest {

	/** Exact device shape at 20:09:48 on 2026-08-14. */
	@Test
	fun `native YouTube progress ends a proven positionless Brave listen`() {
		val brave = browser(
			key = "brave-hoyoverse",
			packageName = "com.brave.browser",
			sourceProven = true,
			rawPositionMs = null,
		)
		val native = native(rawPositionMs = 3_343)

		assertEquals(
			setOf(brave.key),
			ExclusivePlaybackArbitration.staleHostWatchesToFinalize(
				native, listOf(brave, native),
			),
		)
	}

	@Test
	fun `takeover refuses weak or legitimately concurrent shapes`() {
		val native = native(rawPositionMs = 3_343)
		val cases = listOf(
			browser("readable", rawPositionMs = 12_000),
			browser("unproven", sourceProven = false),
			browser("paused", transport = TransportState.PAUSED),
			browser("finished", live = false),
			browser("same-package", packageName = native.packageName),
		)

		assertTrue(
			ExclusivePlaybackArbitration.staleHostWatchesToFinalize(
				native, cases + native,
			).isEmpty(),
		)
	}

	@Test
	fun `a paused or positionless first-party session is not takeover proof`() {
		val brave = browser("brave")
		assertTrue(
			ExclusivePlaybackArbitration.staleHostWatchesToFinalize(
				native(rawPositionMs = 3_343).copy(transport = TransportState.PAUSED),
				listOf(brave),
			).isEmpty(),
		)
		assertTrue(
			ExclusivePlaybackArbitration.staleHostWatchesToFinalize(
				native(rawPositionMs = null),
				listOf(brave),
			).isEmpty(),
		)
	}

	private fun browser(
		key: String,
		packageName: String = "com.brave.browser",
		live: Boolean = true,
		sourceProven: Boolean = true,
		transport: TransportState = TransportState.PLAYING,
		rawPositionMs: Long? = null,
	) = ConcurrentPlaybackCandidate(
		key = key,
		packageName = packageName,
		live = live,
		sourceProven = sourceProven,
		packageProvesSource = false,
		publishesHostScreenEvidence = true,
		transport = transport,
		rawPositionMs = rawPositionMs,
		metadataUsable = true,
	)

	private fun native(rawPositionMs: Long?) = ConcurrentPlaybackCandidate(
		key = "native",
		packageName = YouTubeProbe.YOUTUBE_PACKAGE,
		live = true,
		sourceProven = true,
		packageProvesSource = true,
		publishesHostScreenEvidence = false,
		transport = TransportState.PLAYING,
		rawPositionMs = rawPositionMs,
		metadataUsable = true,
	)
}
