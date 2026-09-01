package com.rustedwax.app.enrich

import com.rustedwax.youtube.identity.VideoResolution
import com.rustedwax.youtube.identity.VideoResolutionAttempt
import com.rustedwax.youtube.identity.VideoResolutionFailure

import com.rustedwax.app.detect.TitleParser
import kotlin.math.abs

/**
 * Exact structured identity for native players that publish clean music fields
 * but omit the immutable YouTube video id.
 *
 * This is deliberately not a fuzzy title matcher. The canonical page title is
	 * parsed with the existing credit grammar, its work must equal the separated
	 * MediaSession work, the native artist must be one complete parsed or canonical
	 * author credit, and duration must agree. The resolver still requires exactly
	 * one fully fetched public page before this evidence can become authority.
 */
object NativeStructuredMusicMatcher {

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
				resolution = matches.single().copy(
					source = "structured native music title+artist+duration",
					uniquelyResolved = true,
					structuredNativeMusic = true,
				),
			)
			0 -> VideoResolutionAttempt(
				refusalReason = "no fully fetched candidate matched structured native " +
					"music title+artist+duration",
			)
			else -> VideoResolutionAttempt(
				refusalReason = "ambiguous identity — ${matches.size} uploads match structured " +
					"native music title+artist+duration " +
					"(${matches.joinToString { it.videoId }}); refusing every id",
				failure = VideoResolutionFailure.AMBIGUOUS,
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
		return worksAgree(candidateTitle, nativeTitle)
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
