package com.rustedwax.app.detect

import android.media.MediaMetadata
import com.rustedwax.core.MetadataFields

/**
 * The one place an `android.media.MediaMetadata` becomes source-neutral.
 *
 * Every production reader now takes [MetadataFields]; this adapter is what the
 * Android media path hands them. Keeping the conversion in a single named class
 * is the point — a second conversion site would be a second chance to disagree
 * about what "unset" means.
 */
internal class AndroidMetadataFields(
	/** The bundle itself, for the one display-only reader of its pictures. */
	internal val metadata: MediaMetadata,
) : MetadataFields {

	override fun getString(key: String): String? = metadata.getString(key)

	override fun getLong(key: String): Long = metadata.getLong(key)

	override fun bitmapDimensions(key: String): Pair<Int, Int>? =
		runCatching { metadata.getBitmap(key) }.getOrNull()?.let { it.width to it.height }

	override fun keySet(): Set<String> = metadata.keySet()
}

/** This metadata as the source-neutral surface the readers consume. */
internal fun MediaMetadata.asFields(): MetadataFields = AndroidMetadataFields(this)
