package com.rustedwax.app.detect

import com.rustedwax.core.*
import com.rustedwax.core.PlaybackSourceCapabilities
import com.rustedwax.hive.HiveScrobblePayload

data class SourceProfile(
	/** The installed source package itself proves which service is playing. */
	val packageProvesSource: Boolean = false,

	val presentsForegroundShorts: Boolean = false,

	/** The source publishes separated music work and credit metadata. */
	val publishesDedicatedMusicMetadata: Boolean = false,

	/** The payload's `platform` field. */
	val platform: String,

	/**
	 * The session publishes an id that names the exact item, so the identity
	 * stack has nothing to prove. No YouTube surface does this — the native
	 * MediaSession carries no video id, which is why the resolver exists.
	 */
	val publishesExactTrackId: Boolean,

	/** A payload from this source is invalid without a canonical link. */
	val needsCanonicalUrl: Boolean,

	/** The shape that link must have, when one is required. */
	val canonicalUrlPattern: Regex?,

	/**
	 * Ads play inside the session and are not distinguishable from content by
	 * metadata alone, so a listen needs ad evidence before it can be trusted.
	 */
	val requiresAdEvidence: Boolean,

	/** Below this, nothing from this source is worth scoring. */
	val minDurationSeconds: Long,

	/**
	 * The MediaSession `ARTIST` field is source-published artist metadata rather
	 * than an uploader field. It remains best effort and is not a write gate.
	 * False sends credits through the parsing ladder instead — see §3.2 and
	 * [ScrobbleBuilder.creditsForKind].
	 */
	val trustsMetadataArtist: Boolean,
) {

	/** Whether this URL satisfies the source's own canonical-link invariant. */
	fun acceptsUrl(url: String?): Boolean {
		if (!needsCanonicalUrl) return true
		val pattern = canonicalUrlPattern ?: return true
		return url?.let(pattern::matches) == true
	}

	companion object {

		/**
		 * The invariant `HiveScrobblePayload` used to hardcode. It stays exactly
		 * as strict; it just stopped being the only shape the app can express.
		 */
		val YOUTUBE_WATCH_URL =
			Regex("""^https://www\.youtube\.com/watch\?v=[A-Za-z0-9_-]{11}$""")

		/**
		 * YouTube in a browser, and the native YouTube app.
		 *
		 * Both carry the channel where an artist belongs — in the browser because
		 * the media notification is built from page metadata, in the app because
		 * that is simply what it publishes.
		 */
		val YOUTUBE = SourceProfile(
			platform = HiveScrobblePayload.PLATFORM_YOUTUBE,
			publishesExactTrackId = false,
			needsCanonicalUrl = true,
			canonicalUrlPattern = YOUTUBE_WATCH_URL,
			requiresAdEvidence = true,
			minDurationSeconds = 0,
			trustsMetadataArtist = false,
		)

		/**
		 * The native YouTube Music app: a music player, publishing a real artist
		 * and a real album. Its metadata is better than anything parsing could
		 * recover from a title, so nothing here second-guesses it.
		 */
		val YOUTUBE_MUSIC = YOUTUBE.copy(
			packageProvesSource = true,
			publishesDedicatedMusicMetadata = true,
			trustsMetadataArtist = true,
		)

		/**
		 * The profile describing the package a session belongs to.
		 *
		 * Answered by that source's own adapter. The YouTube Music
		 * split used to be stated here and repeated as a package-specific check at
		 * call site; it is now declared once, by [YouTubeMusicAdapter], and read
		 * everywhere else. Two declarations of one capability is how the §3.2 artist
		 * bug survived a rule that already existed.
		 */
		fun forPackage(packageName: String): SourceProfile =
			SourceRegistry.forWatch(packageName, packageName).profile

		/**
		 * What the playback state machine may assume about a package's transport.
		 *
		 * The four questions the watch previously answered with a source-kind check,
		 * resolved once here so [PlaybackReducer] never sees a package name. This
		 * is the same argument as the rest of this file — a source declares what it
		 * does, and the shared logic reads the declaration — applied to the
		 * lifecycle half rather than the payload half.
		 *
		 * All four currently split browser from native app, because that is the
		 * only split the field evidence supports. Naming them separately is what
		 * lets a future source disagree on one of them without inheriting the other
		 * three.
		 *
		 * The answers are the adapters' own declarations rather than a
		 * second copy of them here. The replay harness and the device suite still
		 * call this, and calling it now reaches exactly the capabilities production
		 * gives that package — which is the property a second table cannot have.
		 */
		fun playbackCapabilitiesFor(packageName: String): PlaybackSourceCapabilities =
			SourceRegistry.forWatch(packageName, packageName).playbackCapabilities
	}
}
