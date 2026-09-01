package com.rustedwax.core

/**
 * The narrow key/value surface exposed by a playback metadata provider.
 *
 * ## Why this exists
 *
 * The shared domain accepts a port instead of a framework-owned metadata type,
 * so every adapter and the reference implementation can use the same readers.
 *
 * A duplicate reader is the specific failure the parity gate is meant to catch.
 * If the Phase 0/1 reference had to re-implement `MetadataDump.textOrNull`, then
 * a comparison between reference and production would be comparing two readers
 * as well as two state machines, and a difference in either would look like the
 * other. Readers take this interface, each runtime adapts to it once, and both
 * sides run the *same* bodies.
 *
 * Deliberately narrow: this is the four operations production readers perform,
 * not a re-declaration of any runtime class.
 */
interface MetadataFields {

	/** The value at [key] as text, or null when unset or not text. */
	fun getString(key: String): String?

	/**
	 * The value at [key] as a number, or `0` when unset.
	 *
	 * Zero-for-unset is the existing provider behavior and is preserved rather than
	 * corrected: `MetadataDump` reports `0` as "unset", and a source that
	 * genuinely published zero is indistinguishable from one that published
	 * nothing. Changing that here would change what the dump says.
	 */
	fun getLong(key: String): Long

	/** Width and height of the bitmap at [key], or null when there is none. */
	fun bitmapDimensions(key: String): Pair<Int, Int>?

	/** Every key actually present, including non-standard ones. */
	fun keySet(): Set<String>
}
