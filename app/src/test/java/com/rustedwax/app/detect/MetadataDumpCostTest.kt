package com.rustedwax.app.detect

import com.rustedwax.core.MetadataFields
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What one metadata dump costs, and why it may not be paid once a second.
 *
 * `MetadataDump.dump` is a diagnostic renderer: it reports what a source
 * published, including the keys it did *not*, so it necessarily probes every
 * standard key and every non-standard extra. That is correct for a dump and
 * ruinous on a hot path.
 *
 * Two of those probes are far more expensive than they look on this side of the
 * `MetadataFields` port:
 *
 *  - asking a **non-text** key for text. `android.os.BaseBundle.getCharSequence`
 *    answers a type mismatch by building a `ClassCastException` and writing its
 *    **whole stack trace** to logcat. Measured on the field device on 2026-08-26
 *    with YouTube Music playing: 1,532 `W/Bundle` stack-trace lines in twelve
 *    seconds, 99% of everything the process logged, all on the main thread.
 *  - asking for a **bitmap**. `MediaMetadata.getBitmap` marshals album artwork
 *    across Binder; YouTube Music publishes artwork and the YouTube app does not,
 *    which is most of why one of them lagged and the other did not.
 *
 * These tests pin the cost rather than trying to remove it. The repair is that a
 * live snapshot stops asking — see
 * [com.rustedwax.app.architecture.SnapshotDiagnosticCostWiringTest].
 */
class MetadataDumpCostTest {

	/** Records every probe, so a caller's real cost is countable. */
	private class CountingFields(
		private val text: Map<String, String> = emptyMap(),
		private val longs: Map<String, Long> = emptyMap(),
		private val bitmaps: Map<String, Pair<Int, Int>> = emptyMap(),
		private val extraKeys: Set<String> = emptySet(),
	) : MetadataFields {

		val textProbes = mutableListOf<String>()
		val longProbes = mutableListOf<String>()
		val bitmapProbes = mutableListOf<String>()

		override fun getString(key: String): String? {
			textProbes += key
			return text[key]
		}

		override fun getLong(key: String): Long {
			longProbes += key
			return longs[key] ?: 0L
		}

		override fun bitmapDimensions(key: String): Pair<Int, Int>? {
			bitmapProbes += key
			return bitmaps[key]
		}

		override fun keySet(): Set<String> = text.keys + longs.keys + bitmaps.keys + extraKeys
	}

	/**
	 * The exact shape measured on the device: YouTube publishes numeric extras
	 * under its own namespace, and `dump` asks each of them for text first.
	 */
	private fun youTubeMusicShaped() = CountingFields(
		text = mapOf(
			"android.media.metadata.TITLE" to "Pull Up to Mi Bumper",
			"android.media.metadata.ARTIST" to "Konshens & J Capri",
		),
		longs = mapOf(
			"android.media.metadata.DURATION" to 151_000L,
			"com.google.android.youtube.MEDIA_METADATA_VIDEO_HEIGHT_PX" to 1080L,
			"com.google.android.youtube.MEDIA_METADATA_VIDEO_WIDTH_PX" to 1920L,
		),
		bitmaps = mapOf("android.media.metadata.ART" to (544 to 544)),
	)

	@Test
	fun `a dump asks every non-text extra for text, which is the expensive probe`() {
		val fields = youTubeMusicShaped()
		MetadataDump.dump(fields)

		val numericExtras = listOf(
			"com.google.android.youtube.MEDIA_METADATA_VIDEO_HEIGHT_PX",
			"com.google.android.youtube.MEDIA_METADATA_VIDEO_WIDTH_PX",
		)
		numericExtras.forEach { key ->
			assertTrue(
				"a dump did not probe $key as text, so this fixture no longer " +
					"reproduces the field cost it was written for",
				fields.textProbes.contains(key),
			)
		}
	}

	@Test
	fun `a dump marshals every artwork key`() {
		val fields = youTubeMusicShaped()
		MetadataDump.dump(fields)

		assertEquals(
			"artwork probes changed; the bitmap cost this measures is no longer here",
			listOf(
				"android.media.metadata.ART",
				"android.media.metadata.ALBUM_ART",
				"android.media.metadata.DISPLAY_ICON",
			),
			fields.bitmapProbes,
		)
	}

	/**
	 * The number that matters, characterized exactly rather than bounded.
	 *
	 * 17 standard text keys, 5 numeric keys, 3 artwork keys, then both a text and
	 * a numeric probe for each of the two non-standard extras: 29 probes for one
	 * ordinary music bundle. Every one is paid on whichever thread built the
	 * snapshot, and before 2026-08-26 that was the main thread, once per second,
	 * per session.
	 *
	 * If this count changes the cost changed, which is exactly what a reader of
	 * this file needs to be told.
	 */
	@Test
	fun `one dump of an ordinary music bundle costs twenty-nine probes`() {
		val fields = youTubeMusicShaped()
		MetadataDump.dump(fields)

		assertEquals("standard text keys", 17, fields.textProbes.size - 2)
		assertEquals("standard numeric keys", 5, fields.longProbes.size - 2)
		assertEquals("artwork keys", 3, fields.bitmapProbes.size)

		val total = fields.textProbes.size + fields.longProbes.size + fields.bitmapProbes.size
		assertEquals(
			"one dump now costs $total probes rather than 29; if it has genuinely " +
				"become cheap, the wiring rule that stops calling it per tick can be " +
				"revisited",
			29,
			total,
		)
	}

	/** The control: nothing to dump costs nothing. */
	@Test
	fun `no metadata costs no probes`() {
		assertEquals(listOf("<no metadata>"), MetadataDump.dump(null))
	}
}
