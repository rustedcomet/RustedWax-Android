package com.rustedwax.app.enrich

/**
 * Picks the one watch-history entry that is certainly the track that just
 * played, or refuses. Pure — no network, no Android, no credentials.
 *
 * ## History supplies a candidate, never a verdict
 *
 * The feed says what this account watched, not what *this phone* is playing
 * right now. Another device signed into the same account writes into the same
 * list; so does the user skipping forward three videos while a track finalizes.
 * So an entry is accepted only when it uniquely matches the available frozen
 * MediaSession fields. With duration that is the strict three-field search gate
 * in [SearchResultsParser.identityMatches]. When Chromium omitted duration it
 * is exact title+channel, or one exact-title candidate whose own canonical page
 * must confirm the channel later. Two recent survivors always refuse.
 *
 * ## Why a small window rather than only the newest entry
 *
 * The plan (§11.2 item 0d) says to take the most recent entry. Position turns
 * out to be the wrong thing to lean on, in both directions: the id is asked for
 * twice — once mid-track to establish the carry authority, once at finalization
 * — and by the second of those the user may already be two videos further on,
 * so the track that just ended is no longer at the top. Meanwhile "newest"
 * confers no safety of its own; the corroboration does all of that work.
 *
 * A bounded window with a mandatory unique match is therefore strictly stronger
 * than trusting position 0: it refuses when two recent entries are
 * indistinguishable, which "take the newest" would silently resolve. The
 * position that matched is reported so the field log can answer whether the
 * newest entry really is the current track (§11.2 item 0g) rather than assuming
 * it.
 */
object WatchHistoryMatcher {

	/**
	 * How far back a track that just finished may be. Four videos of slack
	 * covers a user skipping ahead during a finalize; beyond that the feed is
	 * describing a different listening session and must not answer for this one.
	 */
	/**
	 * How far down the feed an ordinary entry may be found.
	 *
	 * Was 5, on the reasoning that position in the ordinary-entry list *is*
	 * recency. Autoplay breaks that: measured 2026-08-06, "Nicki Minaj - Red Ruby
	 * Da Sleeze" resolved at entry 0 during playback and had been pushed past the
	 * window by the time it finalized, because YouTube had auto-advanced through
	 * several more videos in between. The lookup that decides the scrobble is the
	 * one at finalize, and that is exactly when the track is furthest down.
	 *
	 * Widening the haystack relaxes nothing: the match still requires exact title,
	 * channel and duration agreement, so a bigger window can only find the *same*
	 * video further down, never a different one. This is the same correction
	 * already made for Shorts, which search the whole feed for the same reason.
	 */
	const val RECENT_WINDOW = 30

	sealed interface Verdict {
		enum class RefusalKind {
			ABSENT,
			NO_MATCH,
			AMBIGUOUS,
			CONTRADICTION,
		}

		data class Candidate(
			val entry: WatchHistoryParser.Entry,
			/** 0 is the newest entry in the feed. Diagnostic only. */
			val positionFromNewest: Int,
		) : Verdict

		data class Refused(
			val reason: String,
			val kind: RefusalKind = RefusalKind.NO_MATCH,
		) : Verdict
	}

	fun candidate(
		entries: List<WatchHistoryParser.Entry>,
		title: String,
		channel: String?,
		durationSec: Long?,
	): Verdict {
		if (entries.isEmpty()) {
			return Verdict.Refused(
				"watch history carried no entries",
				Verdict.RefusalKind.ABSENT,
			)
		}

		val window = entries.take(RECENT_WINDOW)
		val matches = if (durationSec != null && durationSec > 0) {
			SearchResultsParser.identityMatches(
				window.map(::asCandidate), title, channel, durationSec,
			).distinctBy { it.videoId }
		} else {
			if (channel.isNullOrBlank()) {
				return Verdict.Refused(
					"the finalized duration and channel were both unknown, so history " +
						"could not establish identity from the title alone",
				)
			}
			// Chromium sometimes omits duration entirely after RustedWax reconnects.
			// The account history still names the exact upload the phone played. Use
			// all available finalized fields and keep the same single-id boundary:
			// prefer exact title+channel, otherwise an exact-title singleton that the
			// candidate's own watch page must later confirm against the session
			// channel. Two same-title history rows remain ambiguous.
			val wantedTitle = SearchResultsParser.titleKey(title)
			val titleMatches = window.filter {
				SearchResultsParser.titleKey(it.title) == wantedTitle
			}.distinctBy { it.videoId }
			val wantedChannel = SearchResultsParser.channelKey(channel)
			val channelMatches = wantedChannel?.let { wanted ->
				titleMatches.filter {
					SearchResultsParser.channelKey(it.channel) == wanted
				}
			}.orEmpty()
			(channelMatches.ifEmpty { titleMatches }).map(::asCandidate)
		}

		return when (matches.size) {
			1 -> {
				val hit = matches.single()
				Verdict.Candidate(
					entry = window.first { it.videoId == hit.videoId },
					positionFromNewest = window.indexOfFirst { it.videoId == hit.videoId },
				)
			}

			0 -> Verdict.Refused(
				"none of the ${window.size} most recent watch-history entries match " +
					"\"$title\" / \"${channel.orEmpty()}\"" +
					durationSec?.let { " (${it}s)" }.orEmpty(),
				Verdict.RefusalKind.ABSENT,
			)

			else -> Verdict.Refused(
				"ambiguous identity — ${matches.size} recent watch-history entries match " + (
					if (durationSec != null && durationSec > 0) {
						"title+channel+duration (${matches.joinToString { it.videoId }}); refusing every id"
					} else {
						"the available finalized title/channel evidence " +
							"(${matches.joinToString { it.videoId }}); refusing every id"
					}),
				Verdict.RefusalKind.AMBIGUOUS,
			)
		}
	}

	/**
	 * Whether an id resolved earlier in this track is still the one the feed
	 * corroborates.
	 *
	 * The carry authority is re-derived at finalization rather than trusted from
	 * memory (`<redacted-private-path>` §9.4), and for this route that
	 * means the same feed has to still name the same video. A different answer
	 * is a refusal, not a correction: two ids for one listen means neither was
	 * proven.
	 */
	fun revalidate(
		entries: List<WatchHistoryParser.Entry>,
		videoId: String,
		title: String,
		channel: String?,
		durationSec: Long?,
	): Verdict = when (val verdict = candidate(entries, title, channel, durationSec)) {
		is Verdict.Candidate ->
			if (verdict.entry.videoId == videoId) {
				verdict
			} else {
				Verdict.Refused(
					"watch history now names ${verdict.entry.videoId} for \"$title\", " +
						"not the carried $videoId",
					Verdict.RefusalKind.CONTRADICTION,
				)
			}

		is Verdict.Refused -> verdict
	}

	/**
	 * "The track is simply not in the feed", as opposed to an ambiguous or
	 * unusable one. The distinction decides two things — whether to re-read
	 * before believing it, and whether it counts toward the "your YouTube app is
	 * on another account" diagnosis — so it is one predicate, not two string
	 * checks at the call sites.
	 */
	fun isAbsence(verdict: Verdict): Boolean =
		verdict is Verdict.Refused && verdict.kind == Verdict.RefusalKind.ABSENT

	private fun asCandidate(entry: WatchHistoryParser.Entry) = SearchResultsParser.Candidate(
		videoId = entry.videoId,
		title = entry.title,
		channel = entry.channel,
		lengthSeconds = entry.lengthSeconds,
	)
}
