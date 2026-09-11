package com.rustedwax.app.detect

import com.rustedwax.core.MetadataFields
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
	 * Representative source shape: YouTube publishes numeric extras
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
