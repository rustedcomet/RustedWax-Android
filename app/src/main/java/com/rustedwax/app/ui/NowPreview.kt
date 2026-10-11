package com.rustedwax.app.ui

import com.rustedwax.app.detect.ScrobbleBuilder
import com.rustedwax.app.detect.SessionSnapshot
import com.rustedwax.app.enrich.MusicBrainzVerifier
import com.rustedwax.app.enrich.VideoFacts
import com.rustedwax.hive.HiveScrobblePayload

/**
 * Cache-only payload observation for the Now card.
 *
 * A native resolver can establish a unique immutable id while the source still
 * publishes only a site-level MediaSession identity. That id deliberately does
 * not replace the session identity: finalization re-verifies its route before
 * dispatch. The diagnostic preview may nevertheless show what the existing
 * builder produces from that exact id and its already-cached facts.
 */
internal data class NowPreview(
	val videoId: String?,
	val facts: VideoFacts?,
	val musicMatch: MusicBrainzVerifier.Match?,
	val payload: HiveScrobblePayload?,
	val kindReason: String?,
) {
	companion object {
		fun from(
			session: SessionSnapshot,
			cachedFacts: (String) -> VideoFacts?,
			cachedMusicMatch: (SessionSnapshot, VideoFacts?) -> MusicBrainzVerifier.Match?,
		): NowPreview {
			// A source-published exact identity keeps precedence. The native
			// pre-resolution is observation authority only; it is not copied into
			// SessionSnapshot.identity and cannot make the manual path broadcast.
			val videoId = session.confirmed?.videoId
				?: session.resolverContext.preResolvedNativeVideoId
			val facts = videoId?.let(cachedFacts)
			val musicMatch = cachedMusicMatch(session, facts)
			return NowPreview(
				videoId = videoId,
				facts = facts,
				musicMatch = musicMatch,
				payload = ScrobbleBuilder.from(
					session = session,
					facts = facts,
					mb = musicMatch,
					videoId = videoId,
				),
				kindReason = ScrobbleBuilder.kindReason(session, facts, musicMatch),
			)
		}
	}
}

/**
 * Whether a player-published picture may stand in for [session]'s thumbnail.
 *
 * The frame must have arrived in a bundle naming the title this card shows. The
 * listen key already keeps it on the right card; this keeps a bundle the player
 * sent mid-switch — a browser's `YouTube` teardown, the next title before its
 * art — from painting a picture the card's own text does not describe. Display
 * only: the answer never reaches identity, finalization or Hive.
 */
internal fun playerArtworkMatches(session: SessionSnapshot, frameTitle: String): Boolean {
	val shown = session.title?.trim()?.takeIf { it.isNotEmpty() } ?: return false
	return shown == frameTitle.trim()
}
