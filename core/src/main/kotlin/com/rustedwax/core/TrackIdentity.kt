package com.rustedwax.core

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/**
 * The semantic identity of one transport session track.
 *
 * page-backed source republishes otherwise identical metadata while refining duration.
 * Duration is therefore evidence about a track, not an exact component of its
 * identity. Title, artist and album remain the stable identity fields; a
 * missing duration becoming known or a bounded rounding drift refines the
 * observation without ending the track.
 */
data class TrackIdentity(
	val title: String?,
	val artist: String?,
	val album: String?,
	val durationMs: Long?,
	/** Exact source item id when a native transport session publishes one. */
	val sourceItemId: String? = null,
	/** Source-declared work shared by alternate presentations of one item. */
	val logicalWork: String? = null,
	/** Non-empty source presentation class; only different classes may alias. */
	val logicalVariant: String? = null,
) {
	private val titleKey = normalize(title)
	private val artistKey = normalize(artist)
	private val albumKey = normalize(album)
	private val logicalWorkKey = normalize(logicalWork)
	private val logicalVariantKey = normalize(logicalVariant)
	// source video ids are case-sensitive; unlike presentation metadata this
	// value must never be case-folded.
	private val sourceItemKey = sourceItemId?.trim().orEmpty()
	val hasExactSourceItemId: Boolean get() = sourceItemKey.isNotEmpty()

	/** Stable carry key; duration is validated separately by [sameTrackAs]. */
	val semanticKey: String = if (logicalWorkKey.isNotEmpty()) {
		listOf(logicalWorkKey, artistKey).joinToString("|")
	} else {
		listOf(titleKey, artistKey, albumKey).joinToString("|")
	}

	val isUsable: Boolean get() = titleKey.isNotEmpty()

	/**
	 * Same presentation metadata, no exact id on either side, but durations that
	 * cannot describe one continuous item. Native source emitted this shape for
	 * short transition/ad phases while the organic song metadata was already
	 * installed. The caller must stop ordinary measurement until the presentation
	 * boundary is resolved; this predicate itself never labels the fragment an ad.
	 */
	fun isExactIdlessMaterialDurationReplacement(other: TrackIdentity): Boolean {
		if (!isUsable || !other.isUsable ||
			sourceItemKey.isNotEmpty() || other.sourceItemKey.isNotEmpty() ||
			!sameWorkAs(other) ||
			(artistKey.isNotEmpty() && other.artistKey.isNotEmpty() && artistKey != other.artistKey) ||
			!albumsCompatible(other)
		) return false
		val left = durationMs.validDuration() ?: return false
		val right = other.durationMs.validDuration() ?: return false
		return abs(left - right) > DURATION_REFINEMENT_TOLERANCE_MS
	}

	fun sameTrackAs(other: TrackIdentity): Boolean {
		if (!sameWorkAs(other)) return false

		if (!albumsCompatible(other)) return false
		// An artist nobody published is silence, exactly like a missing duration
		// — not a second opinion about who the uploader is. page-backed source answers the
		// field with the page's origin whenever a source watch page has not set
		// one, [BrowserTabMetadata] reads that back as absence, and without this
		// the channel arriving late or dropping out mid-track ended the listen
		// and started a duplicate. Two *published* names that differ still do.
		if (artistKey.isNotEmpty() && other.artistKey.isNotEmpty() &&
			artistKey != other.artistKey
		) {
			return false
		}
		if (sourceItemKey.isNotEmpty() && other.sourceItemKey.isNotEmpty() &&
			sourceItemKey != other.sourceItemKey
		) {
			return false
		}
		val left = durationMs.validDuration()
		val right = other.durationMs.validDuration()
		return left == null || right == null || abs(left - right) <= DURATION_REFINEMENT_TOLERANCE_MS
	}

	private fun sameWorkAs(other: TrackIdentity): Boolean =
		titleKey == other.titleKey ||
			(logicalWorkKey.isNotEmpty() && other.logicalWorkKey.isNotEmpty() &&
				logicalWorkKey == other.logicalWorkKey &&
				logicalVariantKey.isNotEmpty() && other.logicalVariantKey.isNotEmpty() &&
				logicalVariantKey != other.logicalVariantKey)

	private fun albumsCompatible(other: TrackIdentity): Boolean =
		albumKey == other.albumKey ||
			(logicalWorkKey.isNotEmpty() && other.logicalWorkKey.isNotEmpty() &&
				logicalWorkKey == other.logicalWorkKey &&
				(albumKey.isEmpty() || other.albumKey.isEmpty()))

	/**
	 * Keep the first concrete duration as the comparison baseline. If the first
	 * observation omitted duration, the first known value establishes it.
	 */
	fun refinedWith(other: TrackIdentity): TrackIdentity = when {
		!sameTrackAs(other) -> other
		durationMs.validDuration() == null && other.durationMs.validDuration() != null ||
			sourceItemKey.isEmpty() && other.sourceItemKey.isNotEmpty() ||
			// The name a page published once is the one this track has, however
			// many later bundles omit it.
			artistKey.isEmpty() && other.artistKey.isNotEmpty() ||
			albumKey.isEmpty() && other.albumKey.isNotEmpty() -> copy(
			durationMs = durationMs.validDuration() ?: other.durationMs,
			sourceItemId = sourceItemId?.takeIf(String::isNotBlank) ?: other.sourceItemId,
			artist = artist?.takeIf { artistKey.isNotEmpty() } ?: other.artist,
			album = album?.takeIf { albumKey.isNotEmpty() } ?: other.album,
		)
		else -> this
	}

	companion object {
		const val DURATION_REFINEMENT_TOLERANCE_MS = 2_000L

		private fun normalize(value: String?): String =
			TextNormalizer.presentation(value).replace(Regex("""\s+"""), " ").trim()

		private fun Long?.validDuration(): Long? = this?.takeIf { it > 0 }
	}
}
