package com.rustedwax.core

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
