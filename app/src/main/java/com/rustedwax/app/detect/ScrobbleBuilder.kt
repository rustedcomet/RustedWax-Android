package com.rustedwax.app.detect

import com.rustedwax.app.enrich.MusicBrainzVerifier
import com.rustedwax.app.enrich.VideoFacts
import com.rustedwax.hive.HiveScrobblePayload

object ScrobbleBuilder {

	/**
	 * The artist/track pair to *ask MusicBrainz about* — always the music
	 * reading, `Artist - Track` split and all.
	 *
	 * Public because the engine needs exactly this pair for the lookup, and the
	 * two computations must never drift apart. Note this is deliberately **not**
	 * the pair a `video` payload carries: asking MusicBrainz about the music
	 * reading is how a video-looking title is discovered to be a real recording,
	 * so the question has to be asked before the kind is known. See
	 * [creditsForKind] for what actually goes on-chain.
	 */
	fun creditsOf(session: SessionSnapshot, facts: VideoFacts? = null): Parsed? {
		val rawTitle = sourceTitle(session, facts) ?: return null
		val channel = sourceArtist(session, facts)
		// Only a source whose ARTIST field really names a performer may skip the
		// ladder. YouTube Music does; the YouTube app publishes the channel there
		// and must be parsed like any other title. See [SourceProfile].
		if (session.profile.trustsMetadataArtist && !session.title.isNullOrBlank()) {
			return Parsed(
				artist = session.artist?.trim()?.takeIf(String::isNotEmpty)
					?: facts?.originalArtist ?: facts?.author,
				track = session.title.trim(),
			)
		}
		val parsed = TitleParser.parse(rawTitle, channel)
		// A description credit beats a parsed one: on a cover it names the
		// original artist, which is the whole point of looking.
		return Parsed(
			artist = facts?.originalArtist ?: parsed.artist,
			track = facts?.originalTitle?.let { TitleParser.clean(it) } ?: parsed.track,
		)
	}

	fun creditsForKind(
		kind: String,
		session: SessionSnapshot,
		facts: VideoFacts? = null,
		mb: MusicBrainzVerifier.Match? = null,
		/** The resolver's canonical title, for a Short the screen never titled. */
		resolvedTitle: String? = null,
	): Parsed? {
		val rawTitle = sourceTitle(session, facts, resolvedTitle) ?: return null
		val channel = sourceArtist(session, facts)

		// A source whose ARTIST field names a real performer publishes better
		// credits than parsing could recover, so its values are preserved as-is.
		// This is YouTube Music, and it is *not* the YouTube app: §3.2.
		val nativeTitle = session.title?.takeIf(String::isNotBlank)
			?: resolvedTitle?.takeIf(String::isNotBlank)
		if (session.profile.trustsMetadataArtist && nativeTitle != null) {
			return Parsed(
				artist = session.artist?.trim()?.takeIf(String::isNotEmpty)
					?: TitleParser.cleanChannel(facts?.author),
				track = nativeTitle.trim(),
			)
		}

		if (kind != HiveScrobblePayload.KIND_SONG) {
			return Parsed(
				artist = TitleParser.cleanChannel(channel),
				track = TitleParser.clean(rawTitle).ifEmpty { rawTitle.trim() },
			)
		}

		val credits = creditsOf(session, facts) ?: return null
		// A MusicBrainz confirmation supplies the canonical spelling, so this
		// entry lines up with every other scrobble of the same recording — and
		// it lands the right way round when the title was `Track - Artist`.
		if (mb?.found != true) return credits
		return Parsed(
			artist = mb.artist ?: credits.artist,
			track = mb.title ?: credits.track,
		)
	}

	/**
	 * Whether a `song` payload actually knows who performed it.
	 *
	 * §3.2 step 4, and the reason restoring the ladder costs no scrobbles. When
	 * the ladder cannot establish an artist — no description credit, no
	 * `Artist - Track` split, no MusicBrainz confirmation — the honest answer is
	 * not to guess and not to refuse. It is to stop claiming the entry is a song.
	 * A `video` credited to its channel is true by construction: the channel *is*
	 * the uploader, and `creditsForKind` already credits every non-song kind that
	 * way.
	 *
	 * The measured corpus says this will be rare — 11 of 11 unique finalized
	 * YouTube-app titles carried an `Artist - Track` separator, because that is
	 * how uploaders title music. It is the floor under the rare case, not the
	 * common path.
	 */
	fun songCreditsAreKnown(
		session: SessionSnapshot,
		facts: VideoFacts? = null,
		mb: MusicBrainzVerifier.Match? = null,
	): Boolean {
		if (session.profile.trustsMetadataArtist) return true
		if (mb?.found == true && !mb.artist.isNullOrBlank()) return true
		if (!facts?.originalArtist.isNullOrBlank()) return true
		val rawTitle = sourceTitle(session, facts) ?: return false
		val parsed = TitleParser.parse(rawTitle, sourceArtist(session, facts))
		if (parsed.artist.isNullOrBlank()) return false
		// Every branch of the parser that could not work out who performed the
		// track hands back the channel plus the *whole* title — the no-separator
		// fallback, the quoted-work-with-unproven-credits case, and the pipe with
		// no channel agreement. A track that is the entire title is therefore the
		// parser saying it found no artist, whatever it put in the artist slot.
		//
		// Comparing the parsed artist against the channel instead would be wrong
		// in the common case: `Korn - Trash` on `KornVEVO` is genuinely Korn, and
		// an artist channel agreeing with its own uploads is what right looks
		// like, not a failure to parse.
		return parsed.track.trim() != TitleParser.clean(rawTitle).trim()
	}

	data class Parsed(val artist: String?, val track: String)

	fun effectiveDurationMs(session: SessionSnapshot, facts: VideoFacts? = null): Long? =
		session.durationMs ?: facts?.lengthSeconds?.times(1000)

	fun from(
		session: SessionSnapshot,
		facts: VideoFacts? = null,
		mb: MusicBrainzVerifier.Match? = null,
		videoId: String? = session.confirmed?.videoId,
		durationMs: Long? = effectiveDurationMs(session, facts),

		resolvedTitle: String? = null,
	): HiveScrobblePayload? {
		// The session itself may not have published a duration; `durationMs` is
		// the shared session-or-watch-page value used by rules and payload.
		if (!session.isYouTube) return null
		val verifiedVideoId = videoId
			?.takeIf { YOUTUBE_VIDEO_ID.matches(it) }
			?: return null
		// The screen's title, when there was one. A Short that went straight to
		// picture-in-picture never showed one, and that is no longer fatal: the
		// resolver proved the video on its own watch page and its canonical title
		// is the better name anyway.
		val sessionTitle = session.title?.takeIf { it.isNotBlank() }
			?: facts?.title?.takeIf { it.isNotBlank() }
			?: resolvedTitle?.takeIf { it.isNotBlank() }
			?: return null
		val duration = durationMs?.takeIf { it > 0 } ?: return null

		// Enrichment's title is the video's real one; the media session's can be
		// whatever the page chose to publish.
		val rawTitle = sourceTitle(session, facts, resolvedTitle) ?: sessionTitle
		val channel = sourceArtist(session, facts)

		val siteSaysMusic = when (val id = session.identity) {
			is YouTubeProbe.Identity.Confirmed -> id.isMusic && !session.profile.packageProvesSource
			is YouTubeProbe.Identity.SiteOnly -> id.isMusic && !session.profile.packageProvesSource
			else -> false
		}

		// Classified before parsing: cleaning strips "cover", "live" and
		// "lyrics", which are the strongest music signals there are.
		val kind = MusicClassifier.classify(
			rawTitle = rawTitle,
			channel = channel,
			durationMs = duration,
			siteSaysMusic = siteSaysMusic,
			enrichedCategory = facts?.category,
			isShort = session.hasShortSourceProof,
			musicbrainzMatch = mb?.found == true,
			autoGenerated = facts?.autoGenerated == true,
			recognisedByYouTubeMusic = facts?.recognisedByYouTubeMusic == true,
			nativeYouTubeMusic = session.profile.publishesDedicatedMusicMetadata,
			youtubeMusicPodcastEpisode =
				facts?.musicVideoType == "MUSIC_VIDEO_TYPE_PODCAST_EPISODE",
			nativeGenre = session.genre,
		)

		// §6.2. A film or an episode is neither a song nor a generic video, and
		// asked before the music/video split because the whole defect is films
		// arriving as songs. Returns null for everything that is not recognisably
		// a screen work, which is nearly everything.
		val screenWork = ScreenWorkClassifier.classify(
			rawTitle = rawTitle,
			durationMs = duration,
			enrichedCategory = facts?.category,
		)

		// §3.2 step 4. A song whose performer could not be established stops
		// claiming to be one rather than crediting the channel as an artist. The
		// listen is kept; only the claim about it weakens to what is provable.
		val effectiveKind = screenWork?.kind ?: if (
			kind.kind == HiveScrobblePayload.KIND_SONG &&
			!songCreditsAreKnown(session, facts, mb)
		) {
			HiveScrobblePayload.KIND_VIDEO
		} else {
			kind.kind
		}

		// Credits depend on the kind — a video is not split into artist/track.
		val credits = creditsForKind(effectiveKind, session, facts, mb, resolvedTitle) ?: return null

		return HiveScrobblePayload(
			kind = effectiveKind,
			title = credits.track,
			artist = credits.artist,
			// Songs only. `album` is release metadata: on a video it would either
			// be empty or, worse, whatever a description scrape found next to a
			// trailer's credits.
			album = if (effectiveKind == HiveScrobblePayload.KIND_SONG) {
				if (session.profile.packageProvesSource) {
					session.album?.trim()?.takeIf(String::isNotEmpty) ?: facts?.album
				} else {
					facts?.album
				}
			} else {
				null
			},
			timestamp = HiveScrobblePayload.isoTimestamp(session.trackStartedAtEpochSec),
			duration = HiveScrobblePayload.formatDuration(duration / 1000),
			// Recomputed against `duration` rather than read off the snapshot:
			// `SessionSnapshot.percentPlayed` divides by the *session's* duration,
			// which is null in exactly the case a watch-page length rescues — so
			// reading it there would omit `percent_played` from a payload whose
			// progress is perfectly well known. The automatic path overwrites this
			// per transaction; the manual button is what would have shown blank.
			percentPlayed = ((session.playedMs.toDouble() / duration) * 100)
				.toInt().coerceIn(0, 100),
			platform = "youtube",
			url = "https://www.youtube.com/watch?v=$verifiedVideoId",
		)
	}

	/**
	 * Why the kind came out the way it did, for the diagnostics card. Uses the
	 * same inputs as [from], so the preview and the broadcast can't disagree —
	 * an earlier version dropped `siteSaysMusic` and `isShort` here, and the
	 * card showed a different verdict than the one that went on-chain.
	 */
	fun kindReason(
		session: SessionSnapshot,
		facts: VideoFacts? = null,
		mb: MusicBrainzVerifier.Match? = null,
	): String? {
		val title = sourceTitle(session, facts) ?: return null
		val siteSaysMusic = when (val id = session.identity) {
			is YouTubeProbe.Identity.Confirmed -> id.isMusic && !session.profile.packageProvesSource
			is YouTubeProbe.Identity.SiteOnly -> id.isMusic && !session.profile.packageProvesSource
			else -> false
		}
		return MusicClassifier.classify(
			rawTitle = title,
			channel = sourceArtist(session, facts),
			durationMs = effectiveDurationMs(session, facts),
			siteSaysMusic = siteSaysMusic,
			enrichedCategory = facts?.category,
			isShort = session.hasShortSourceProof,
			musicbrainzMatch = mb?.found == true,
			autoGenerated = facts?.autoGenerated == true,
			recognisedByYouTubeMusic = facts?.recognisedByYouTubeMusic == true,
			nativeYouTubeMusic = session.profile.publishesDedicatedMusicMetadata,
			youtubeMusicPodcastEpisode =
				facts?.musicVideoType == "MUSIC_VIDEO_TYPE_PODCAST_EPISODE",
			nativeGenre = session.genre,
		).reason
	}

	private fun sourceTitle(
		session: SessionSnapshot,
		facts: VideoFacts?,
		resolvedTitle: String? = null,
	): String? = if (session.profile.packageProvesSource) {
		session.title ?: facts?.title ?: resolvedTitle
	} else {
		facts?.title ?: session.title ?: resolvedTitle
	}

	private fun sourceArtist(session: SessionSnapshot, facts: VideoFacts?): String? =
		if (session.profile.packageProvesSource) session.artist ?: facts?.author else facts?.author ?: session.artist

	private val YOUTUBE_VIDEO_ID = Regex("""^[A-Za-z0-9_-]{11}$""")
}
