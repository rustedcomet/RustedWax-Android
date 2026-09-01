package com.rustedwax.core

/**
 * What the source of a listen is, as facts rather than as a package name.
 *
 * The playback reducer knows events, measurement, and lifecycle, but not source
 * names. Code downstream asks *what this source can do* rather than *which app
 * it is*.
 *
 * The capability fields are copied from `SourceProfile`, which already made the
 * argument for reading a declaration instead of branching on a package, and
 * which cannot itself live here because it also owns transport payload naming.
 *
 * [packageName] is retained deliberately. It is the *identity* of the source,
 * which an adapter, the dedup key and the log all legitimately need; the
 * boundary rule is that decisions read the capabilities, not that the name is a
 * secret.
 */
data class SourceDescriptor(
	val packageName: String,
	/** What a person calls this app. Display only. */
	val appLabel: String,
	/** How the log names where the listen came from. Display only. */
	val originName: String,
	/** A first-party app rather than a page inside a browser. */
	val packageProvesSource: Boolean,
	/** This source is currently opted in, so its listens may be scored. */
	val isWatched: Boolean,
	/**
	 * Address-bar, tab, browser-notification and browser-accessibility evidence
	 * are available for this source.
	 *
	 * False for every native app — not because the services were switched off,
	 * but because a page container says nothing about another source that is
	 * doing. Keeping "the user granted the services" and "this source has such
	 * evidence at all" as one field is what lets the reducer read the capability
	 * before accepting page-container evidence.
	 */
	val hasBrowserEvidence: Boolean,
	/**
	 * The session's `ARTIST` field names a performer rather than an uploader.
	 * This is source-declared rather than inferred from a package name.
	 */
	val trustsMetadataArtist: Boolean,
) {
	init {
		require(packageName.isNotBlank()) { "a source must name its package" }
		// A page container says nothing about another source, and the reducer reads
		// this field instead of testing a source name. The two
		// disagreeing would put address-bar and tab evidence in reach of a native
		// listen, which is the misattribution the capability split exists to stop.
		require(!(packageProvesSource && hasBrowserEvidence)) {
			"$packageName is a native app and cannot have browser evidence"
		}
	}
}
