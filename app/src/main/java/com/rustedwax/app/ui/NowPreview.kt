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
