package com.rustedwax.core

/**
 * What the session said this track *is*, after the presentation freeze.
 *
 * Descriptive fields only. Length lives in [PlaybackMeasurement] because it is
 * the denominator of the threshold rather than a description of the item, and
 * splitting it that way is what keeps "how the track was named" separable from
 * "how much of it was played" — two of the eight concepts
 * `<redacted-private-path>` §5 says `SessionSnapshot` currently fuses.
 *
 * These are the values already frozen by `finalizedPresentation`, so a teardown
 * bundle that replaces the channel with the site's own host, or omits fields
 * entirely, cannot erase what this track established while it was playing.
 *
 * [rawLines] is the metadata dump the diagnostics card shows. It is carried so
 * the decomposition is lossless rather than because any decision reads it; a
 * decision that parsed it would be re-deriving facts the typed fields already
 * hold.
 */
data class TrackMetadata(
	val title: String?,
	val artist: String?,
	val album: String?,
	/** Structured metadata that can explicitly name episodic context. */
	val genre: String? = null,
	val rawLines: List<String> = emptyList(),
) {
	init {
		// Present-but-blank is the one state these fields must never be in.
		// `isNamed` reads blankness while every other caller reads nullity, so a
		// blank title is a track that is simultaneously named and unnamed depending
		// on who is asking. `MetadataDump.textOrNull` already returns null for a
		// blank bundle value; this is what keeps a later constructor honest.
		require(title?.isBlank() != true) { "a blank title must be null" }
		require(artist?.isBlank() != true) { "a blank artist must be null" }
		require(album?.isBlank() != true) { "a blank album must be null" }
		require(genre?.isBlank() != true) { "a blank genre must be null" }
	}

	/** Enough was published to name the item at all. */
	val isNamed: Boolean get() = !title.isNullOrBlank()
}
