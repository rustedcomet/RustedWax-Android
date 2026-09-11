package com.rustedwax.app.enrich

import com.rustedwax.youtube.identity.VideoResolution
import com.rustedwax.youtube.identity.VideoResolutionAttempt
import com.rustedwax.youtube.identity.VideoResolutionFailure
import com.rustedwax.youtube.identity.PerformerCreditEvidence

import com.rustedwax.app.detect.TitleParser
import kotlin.math.abs

/**
 * Exact structured identity for native players that publish clean music fields
 * but omit the immutable YouTube video id.
 *
 * This is deliberately not a fuzzy title matcher. The canonical page title is
	 * parsed with the existing credit grammar, its work must equal the separated
	 * MediaSession work, the published artist metadata must agree with the candidate,
	 * and duration must agree. The resolver still requires exactly
	 * one fully fetched public page before this evidence can become authority.
 */
object NativeStructuredMusicMatcher {

	/**
	 * Attach descriptive artist-credit provenance at the resolver boundary.
	 *
	 * The work and complete credit must agree, and the canonical/listing owner
	 * must itself belong to that credit. This last condition is what distinguishes
	 * a credited artist's upload from a distributor page whose title merely names
	 * the artist. A collaboration may sit on one member's channel only when the
	 * title independently states the complete collaboration. This grade does not
	 * authorize or veto the verified identity or its eventual scrobble.
	 */
	fun withPerformerCreditEvidence(
		candidate: VideoResolution,
		nativeTitle: String,
		nativeArtist: String,
		durationSec: Long?,
		evidence: PerformerCreditEvidence,
	): VideoResolution {
		require(evidence != PerformerCreditEvidence.NONE) {
			"a resolver cannot positively attach NONE performer evidence"
		}
		return candidate.copy(
			performerCreditEvidence = if (
				completePerformerCreditAgrees(candidate, nativeTitle, nativeArtist, durationSec)
			) evidence else PerformerCreditEvidence.NONE,
		)
	}

	private fun completePerformerCreditAgrees(
		candidate: VideoResolution,
		nativeTitle: String,
		nativeArtist: String,
		durationSec: Long?,
	): Boolean {
		val candidateTitle = candidate.title?.takeIf(String::isNotBlank) ?: return false
		if (durationSec != null) {
			val candidateDuration = candidate.lengthSeconds ?: return false
			if (abs(candidateDuration - durationSec) > VideoIdResolver.DURATION_TOLERANCE_SEC) {
				return false
			}
		}
		val parsed = parse(candidateTitle, candidate.channel)
		val titleNamesCompleteArtist = SearchResultsParser.titleNamesArtistThenSession(
			nativeTitle, nativeArtist, candidateTitle,
		)
		if (titleKey(parsed.track) != titleKey(work(nativeTitle)) &&
			!worksAgree(candidateTitle, nativeTitle) && !titleNamesCompleteArtist
		) return false

		val exactOrParsedCompleteCredit = creditsAgree(parsed.credits, nativeArtist)
		val managedSpellingCredit = SearchResultsParser.shortTitleMatches(
			nativeTitle, candidateTitle,
		) && SearchResultsParser.ownerIsAuthoritativeArtistChannel(candidate.channel) &&
			SearchResultsParser.artistNameIsOneSpellingApart(nativeArtist, candidate.channel)
		if (!exactOrParsedCompleteCredit && !managedSpellingCredit) return false

		val owner = SearchResultsParser.channelKey(candidate.channel) ?: return false
		val credited = creditSet(nativeArtist)
		return owner in credited ||
			(titleNamesCompleteArtist && SearchResultsParser.channelIsSameArtistNamedDifferently(
				nativeArtist, candidate.channel,
			)) || managedSpellingCredit
	}

	fun couldDescribeTrack(
		candidateTitle: String?,
		candidateChannel: String?,
		nativeTitle: String,
		nativeArtist: String,
	): Boolean {
		val title = candidateTitle?.takeIf(String::isNotBlank) ?: return false
		val evidence = parse(title, candidateChannel)
		if (titleKey(evidence.track) != titleKey(work(nativeTitle))) return false
		return creditsAgree(evidence.credits, nativeArtist)
	}

	/** The separated works are the same, under the shared feature grammar. */
	fun worksAgree(candidateTitle: String, nativeTitle: String): Boolean =
		titleKey(work(candidateTitle)) == titleKey(work(nativeTitle))

	fun creditsAgree(candidateCredits: Collection<String>, nativeArtist: String): Boolean {
		val whole = SearchResultsParser.channelKey(nativeArtist) ?: return false
		if (candidateCredits.any { SearchResultsParser.channelKey(it) == whole }) return true
		val wanted = creditSet(nativeArtist)
		if (wanted.size < 2) return false
		// Any single credit that itself splits into the same set — the candidate
		// side may arrive joined too, as a `Topic` channel byline does.
		if (candidateCredits.any { creditSet(it) == wanted }) return true
		return candidateCredits
			.flatMap(::splitCredits)
			.mapNotNull(SearchResultsParser::channelKey)
			.toSet() == wanted
	}

	/**
	 * Exact complete-credit agreement for a structured catalog row.
	 *
	 * [creditsAgree] deliberately preserves the older watch-page rule where one
	 * parsed credit may corroborate a native artist. A Music catalog row is
	 * stronger evidence: when it links several artists, all of them are the row's
	 * published credit and the player must publish the same complete set. A single
	 * unlinked byline remains comparable as a whole first, preserving acts whose
	 * own name contains a separator.
	 */
	fun completeCreditsAgree(
		candidateCredits: Collection<String>,
		nativeArtist: String,
	): Boolean {
		val candidates = candidateCredits.map(String::trim).filter(String::isNotBlank).distinct()
		val whole = SearchResultsParser.channelKey(nativeArtist) ?: return false
		if (candidates.size == 1 && SearchResultsParser.channelKey(candidates.single()) == whole) {
			return true
		}
		val wanted = creditSet(nativeArtist).takeIf(Set<String>::isNotEmpty) ?: return false
		val found = candidates
			.flatMap(::splitCredits)
			.mapNotNull(SearchResultsParser::channelKey)
			.toSet()
		return found == wanted
	}

	/** One credit string as a normalized set of individual performers. */
	private fun creditSet(value: String): Set<String> = splitCredits(value)
		.mapNotNull(SearchResultsParser::channelKey)
		.toSet()

	/**
	 * Whether a catalog release names the same album the player published.
	 *
	 * Absent on either side is *not* a mismatch — the unfiltered catalog response
	 * carries no album at all, and YouTube Music leaves `ALBUM` unset on a single.
	 * This only ever rules a candidate out when both sides spoke and disagreed,
	 * which is exactly the duplicate-art-track case it exists for.
	 */
	fun albumsAgree(candidateAlbum: String?, nativeAlbum: String?): Boolean {
		val wanted = nativeAlbum?.takeIf(String::isNotBlank) ?: return true
		val found = candidateAlbum?.takeIf(String::isNotBlank) ?: return true
		return titleKey(found) == titleKey(wanted)
	}

	fun matches(
		candidate: VideoResolution,
		nativeTitle: String,
		nativeArtist: String,
		durationSec: Long,
	): Boolean {
		val candidateTitle = candidate.title?.takeIf(String::isNotBlank) ?: return false
		val candidateDuration = candidate.lengthSeconds ?: return false
		if (abs(candidateDuration - durationSec) > VideoIdResolver.DURATION_TOLERANCE_SEC) {
			return false
		}
		val evidence = parse(candidateTitle, candidate.channel)

		if (titleKey(evidence.track) != titleKey(work(nativeTitle)) &&
			!worksAgree(candidateTitle, nativeTitle)
		) return false
		return creditsAgree(evidence.credits, nativeArtist)
	}

	/**
	 * The same work at the same length, credited to *one* of [creditedArtists].
	 *
	 * A deliberately narrower companion to [matches], for the one place where the
	 * complete credit has already been proven elsewhere and a second source is
	 * only being asked not to contradict it. Empty [creditedArtists] never
	 * matches, so this can only ever be reached by a resolution that carried a
	 * catalog credit with it.
	 */
	fun matchesOneCredit(
		candidate: VideoResolution,
		nativeTitle: String,
		creditedArtists: List<String>,
		durationSec: Long,
	): Boolean {
		if (creditedArtists.isEmpty()) return false
		return creditedArtists.any { credit ->
			matches(candidate, nativeTitle, credit, durationSec)
		}
	}

	fun select(
		candidates: List<VideoResolution>,
		nativeTitle: String,
		nativeArtist: String,
		durationSec: Long,
	): VideoResolutionAttempt {
		val matches = candidates.filter { candidate ->
			matches(candidate, nativeTitle, nativeArtist, durationSec)
		}.distinctBy(VideoResolution::videoId)
		return when (matches.size) {
			1 -> VideoResolutionAttempt(
				resolution = withPerformerCreditEvidence(
					candidate = matches.single().copy(
						source = "structured native music title+artist+duration",
						uniquelyResolved = true,
						structuredNativeMusic = true,
						// [matches] required this row's own length to agree with the
						// length the player was publishing, so the surface is pinned.
						presentationDurationCorroborated = true,
					),
					nativeTitle = nativeTitle,
					nativeArtist = nativeArtist,
					durationSec = durationSec,
					evidence = PerformerCreditEvidence.CANONICAL_PAGE_COMPLETE_CREDIT,
				),
			)
			0 -> VideoResolutionAttempt(
				refusalReason = "no fully fetched candidate matched structured native " +
					"music title+artist+duration",
			)
			else -> soleAuthoritativeOwner(matches, nativeArtist)?.let { authoritative ->
				VideoResolutionAttempt(
					resolution = withPerformerCreditEvidence(
						candidate = authoritative.copy(
						source = "structured native music title+artist+duration on the " +
							"artist's own channel",
						uniquelyResolved = true,
						structuredNativeMusic = true,
						// Every member of this set passed [matches], so the chosen
						// one's length is the length the player published.
						presentationDurationCorroborated = true,
					),
						nativeTitle = nativeTitle,
						nativeArtist = nativeArtist,
						durationSec = durationSec,
						evidence = PerformerCreditEvidence.CANONICAL_PAGE_COMPLETE_CREDIT,
					),
				)
			} ?: VideoResolutionAttempt(
				refusalReason = "ambiguous identity — ${matches.size} uploads match structured " +
					"native music title+artist+duration " +
					"(${matches.joinToString { it.videoId }}); refusing every id",
				failure = VideoResolutionFailure.AMBIGUOUS,
			)
		}
	}

	/**
	 * Choose one identity candidate among otherwise equal uploads.
	 *
	 * Regression: work, published credit and length can leave two pages standing
	 * for one recording—an artist-managed upload and a separate repost. The
	 * duplicate-family rules decline this set and are
	 * right to: they answer "is this one upload ingested twice", and a different
	 * uploader means it is not. That question simply is not the one this set poses.
	 *
	 * The question it does pose has an answer YouTube itself supplies.
	 * [SearchResultsParser.ownerIsAuthoritativeArtistChannel] already reads the two
	 * channel forms YouTube generates for a rights holder — `… - Topic` and
	 * `…VEVO` — and deliberately refuses the ones anyone can claim (`Official`,
	 * `Music`, `TV`, `Records`). When exactly one survivor sits on such a channel,
	 * the ambiguity is not between two candidate works; it is between the artist's
	 * publication and someone else's copy of it, and the artist's own is the
	 * recording this listen played.
	 *
	 * Deliberately narrow. Every candidate has already passed [matches], so nothing
	 * about the work, the credit or the length is being relaxed. Uniqueness carries
	 * the whole licence: none authoritative and several authoritative both leave the
	 * ambiguity exactly as it was. Nothing here reads views, categories, upload
	 * dates, descriptions, result order or any similarity score.
	 */
	private fun soleAuthoritativeOwner(
		matches: List<VideoResolution>,
		nativeArtist: String,
	): VideoResolution? {
		val authoritative = matches
			.filter { SearchResultsParser.ownerIsAuthoritativeArtistChannel(it.channel) }
			.singleOrNull() ?: return null
		val credited = creditSet(nativeArtist)
		// The suffix proves only that YouTube manages this channel on behalf of
		// *some* artist. It does not prove that artist is the one this session
		// credited: a foreign VEVO/Topic page can still carry a title whose parsed
		// byline names the listened artist. The winner itself must therefore belong
		// to the complete session credit before its marker can break any tie.
		if (SearchResultsParser.channelKey(authoritative.channel) !in credited) return null
		// Everyone else must be a stranger. Two of the artist's *own* publications
		// are a different question with a different answer: `Ella Y Yo` leaves an
		// official audio on `Aventura` beside a `- Topic` live take, both of them
		// the rights holder's, and preferring the marked one would be choosing a
		// live recording over a studio one on the strength of a channel suffix.
		// This tie-break is only ever about the artist's upload against somebody
		// else's copy of it, so an upload sitting on the credited act's own name —
		// compared exactly, by the same [SearchResultsParser.channelKey] every
		// route uses — leaves the ambiguity exactly as it was.
		return authoritative.takeIf {
			matches.none { candidate ->
				candidate.videoId != authoritative.videoId &&
					SearchResultsParser.channelKey(candidate.channel) in credited
			}
		}
	}

	/**
	 * Whether a page has the player's length and an owner matching one listed act.
	 *
	 * The half of [matches] that survives a title the two catalogues spell
	 * differently. Nothing is loosened in the comparison itself: the length is the
	 * same tolerance every route uses, and the owner must reduce to one credit
	 * under the same [SearchResultsParser.channelKey] — which is what makes
	 * `Kabaka Pyramid Music` and `Kabaka Pyramid` one name, and `KSKZik` not.
	 *
	 * Never an identity route or a write gate of its own. This retained predicate
	 * grades descriptive credit provenance only; a false result cannot refuse or
	 * degrade a verified listen.
	 */
	fun ownedByOneCreditedArtist(
		candidate: VideoResolution,
		nativeArtist: String,
		durationSec: Long,
	): Boolean {
		val length = candidate.lengthSeconds ?: return false
		if (abs(length - durationSec) > VideoIdResolver.DURATION_TOLERANCE_SEC) return false
		val owner = SearchResultsParser.channelKey(candidate.channel) ?: return false
		val credited = creditSet(nativeArtist)
		if (credited.isEmpty()) return false
		if (owner in credited) return true
		return splitCredits(nativeArtist).any { creditedArtist ->
			SearchResultsParser.channelIsSameArtistNamedDifferently(
				creditedArtist, candidate.channel,
			)
		}
	}

	fun sameRecording(candidates: List<VideoResolution>): VideoResolution? {
		if (candidates.size < 2) return null
		val first = candidates.first()
		val uploader = SearchResultsParser.channelKey(first.channel) ?: return null
		val work = first.title?.let { titleKey(work(it)) }?.takeIf(String::isNotEmpty) ?: return null
		val length = first.lengthSeconds ?: return null
		val identical = candidates.all { candidate ->
			SearchResultsParser.channelKey(candidate.channel) == uploader &&
				candidate.title?.let { titleKey(work(it)) } == work &&
				candidate.lengthSeconds == length
		}
		if (!identical) return null
		return candidates.minByOrNull(VideoResolution::videoId)
	}

	/**
	 * The page names the player's work, at the player's length.
	 *
	 * Reads the candidate title with the credit grammar [matches] already uses,
	 * then falls back to the whole-title comparison. Both readings, in that order,
	 * because a canonical page ordinarily publishes `<Artist> - <Work>` while the
	 * player publishes the bare work: `El Preso` against
	 * `Fruko Y Sus Tesos - El Preso` is one work under the parse and two strings
	 * under [worksAgree]. Asking only the second made this — the *weaker*
	 * corroboration, reached after [matches] has already declined — stricter about
	 * titles than [matches] itself, so a row whose work and complete credit the
	 * player matched exactly could not be corroborated by its own page and the
	 * listen resolved to nothing at all.
	 *
	 * Only the work is read this way. Who performed it is not decided here and
	 * never was: the caller grades that separately, and a page title naming an
	 * artist on a different channel still carries no additional credit provenance.
	 *
	 * Three readings, in order of how much they assume: the whole title, the
	 * oriented parse, and — last — the title's own opening run
	 * ([SearchResultsParser.titleLeadsWithSessionWork]). The third exists because
	 * the parser deliberately declines to orient a title it cannot prove, keeping
	 * the whole string as the track; a page that plainly opens with the work then
	 * corroborated nothing, and the listen it belonged to resolved to no id at all.
	 */
	fun corroboratesWorkAndLength(
		candidate: VideoResolution,
		nativeTitle: String,
		durationSec: Long,
	): Boolean {
		val candidateTitle = candidate.title?.takeIf(String::isNotBlank) ?: return false
		val candidateDuration = candidate.lengthSeconds ?: return false
		if (abs(candidateDuration - durationSec) > VideoIdResolver.DURATION_TOLERANCE_SEC) {
			return false
		}
		if (worksAgree(candidateTitle, nativeTitle)) return true
		val nativeWork = work(nativeTitle)
		if (titleKey(parse(candidateTitle, candidate.channel).track) == titleKey(nativeWork)) {
			return true
		}
		return SearchResultsParser.titleLeadsWithSessionWork(candidateTitle, nativeWork)
	}

	/**
	 * The widest length spread a set of one recording's ingests may show.
	 *
	 * Every candidate has already passed [matches], so each is within
	 * [VideoIdResolver.DURATION_TOLERANCE_SEC] of the player and the spread is
	 * bounded at twice that. This states the narrower bound directly.
	 */
	private const val DUPLICATE_FAMILY_SPREAD_SEC = 5L

	fun oneOfDuplicateFamily(
		candidates: List<VideoResolution>,
		playerSeconds: Long?,
	): VideoResolution? {
		if (candidates.size < 2) return null
		val first = candidates.first()
		val uploader = SearchResultsParser.channelKey(first.channel) ?: return null
		val work = first.title?.let { titleKey(work(it)) }?.takeIf(String::isNotEmpty) ?: return null
		val lengths = candidates.mapNotNull(VideoResolution::lengthSeconds)
		if (lengths.size != candidates.size) return null
		// One artist channel, one work. A different uploader is two labels'
		// separate uploads; a different work is never a duplicate.
		val family = candidates.all { candidate ->
			SearchResultsParser.channelKey(candidate.channel) == uploader &&
				candidate.title?.let { titleKey(work(it)) } == work
		}
		if (!family) return null
		if ((lengths.max() - lengths.min()) > DUPLICATE_FAMILY_SPREAD_SEC) return null
		if (playerSeconds != null) {
			val named = candidates.filter { it.lengthSeconds == playerSeconds }
			if (named.size == 1) return named.single()
		}
		return sameRecording(candidates)
	}

	/**
	 * [oneOfDuplicateFamily] over the candidates that pass the ordinary structured
	 * test.
	 *
	 * Re-runs the same predicate [select] uses so the collapse can only ever be
	 * applied to a set [select] itself called ambiguous.
	 */
	fun sameRecordingAmong(
		candidates: List<VideoResolution>,
		nativeTitle: String,
		nativeArtist: String,
		durationSec: Long,
		playerSeconds: Long? = null,
	): VideoResolution? = oneOfDuplicateFamily(
		candidates.filter { matches(it, nativeTitle, nativeArtist, durationSec) }
			.distinctBy(VideoResolution::videoId),
		playerSeconds,
	)

	private data class Evidence(val track: String, val credits: Set<String>)

	private fun parse(title: String, channel: String?): Evidence {
		val parsed = TitleParser.parse(title, channel)
		val feature = featureSuffix(parsed.track)
		val credits = linkedSetOf<String>()
		// The canonical author/byline is independent structural evidence. Keep it
		// even when the presentation title parses to a broader collaboration such
		// as `Natti Natasha ❌ Ozuna`: exact author agreement must not disappear
		// merely because the title also carries credits.
		channel?.trim()?.takeIf(String::isNotBlank)?.let(credits::add)
		parsed.artist?.let { artist ->
			credits += artist.trim()
			credits += splitCredits(artist)
		}
		feature.second.forEach { featured ->
			credits += featured.trim()
			credits += splitCredits(featured)
		}
		return Evidence(feature.first, credits.filter(String::isNotBlank).toSet())
	}

	/** Apply the same explicit feature grammar to native and canonical works. */
	private fun work(value: String): String = featureSuffix(value).first

	private fun featureSuffix(track: String): Pair<String, List<String>> {
		var work = track.trim()
		val featured = mutableListOf<String>()
		repeat(MAX_FEATURE_MARKERS) {
			PARENTHETICAL_FEATURE.matchEntire(work)?.let { match ->
				work = match.groupValues[1].trim()
				featured += match.groupValues[2].trim()
				return@repeat
			}
			BARE_FEATURE.matchEntire(work)?.let { match ->
				work = match.groupValues[1].trim()
				featured += match.groupValues[2].trim()
				return@repeat
			}

			EMBEDDED_FEATURE.find(work)?.let { match ->
				work = work.removeRange(match.range).replace(WHITESPACE, " ").trim()
				featured += match.groupValues[1].trim()
				return@repeat
			}
			return work to featured
		}
		return work to featured
	}

	/** More trailing credits than any real title carries. */
	private const val MAX_FEATURE_MARKERS = 4

	private fun splitCredits(value: String): List<String> = value
		.split(CREDIT_SEPARATOR)
		.map(String::trim)
		.filter(String::isNotBlank)

	private fun titleKey(value: String): String = SearchResultsParser.titleKey(value)

	private val PARENTHETICAL_FEATURE = Regex(
		"""^(.+?)\s*\(\s*(?:ft|feat|featuring)\.?\s+([^)]+)\)\s*$""",
		RegexOption.IGNORE_CASE,
	)
	private val EMBEDDED_FEATURE = Regex(
		"""\(\s*(?:ft|feat|featuring)\.?\s+([^)]+)\)""",
		RegexOption.IGNORE_CASE,
	)
	private val WHITESPACE = Regex("""\s+""")
	private val BARE_FEATURE = Regex(
		"""^(.+?)\s+(?:ft|feat|featuring)\.?\s+(.+?)\s*$""",
		RegexOption.IGNORE_CASE,
	)
	private val CREDIT_SEPARATOR = Regex(
		"""\s*(?:,|&|\band\b|\bx\b|×|/)\s*""",
		RegexOption.IGNORE_CASE,
	)
}
