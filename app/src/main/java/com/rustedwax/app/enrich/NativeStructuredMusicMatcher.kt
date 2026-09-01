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

	/**
	 * Whether a candidate's credits describe the same performers as one native
	 * MediaSession artist string.
	 *
	 * Two ways to agree:
	 *
	 *  1. **One credit equals the whole string.** This is the original rule,
	 *     carried over verbatim so nothing that resolved before stops resolving.
	 *     It is also what keeps an artist whose own name contains a separator
	 *     working — the field log has `Capleton & Derrick Sound` as a single act,
	 *     and splitting that would be the mirror-image bug. Note it is deliberately
	 *     permissive in one direction: a solo `Vybz Kartel` still matches a row
	 *     credited `["Vybz Kartel", "Ishawna"]`, exactly as before. Duration and
	 *     — now — album are what rule that out downstream.
	 *  2. **The split credit sets are equal.** This is the new case, and the one
	 *     the fix exists for. YouTube Music publishes a collaboration as one
	 *     joined string (`"Walshy Fire, Lizi & Mr. Vegas"`) while the catalog
	 *     exposes the same credit as structured parts
	 *     (`["Mr. Vegas", "Lizi", "Walshy Fire"]`). Rule 1 can never fire for
	 *     those — `"mrvegas"` is not `"walshyfirelizimrvegas"` — so before this,
	 *     *every* collaboration was refused at the filter and the listen was lost.
	 *     Comparing normalized sets is order-independent, because the catalog
	 *     routinely lists a collaboration in a different order than the player
	 *     does, and it still requires the complete credit on both sides: a
	 *     different collaboration on the same work does not match.
	 */
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
		// The credit grammar splits an `Artist - Track` page title, which is right
		// for a watch page and wrong for a catalog row whose title is already a
		// bare work. Measured 2026-08-23: `Tan Tuddy - Raw` reduced to `Raw` on
		// one side and stayed whole on the other, so a card that had already
		// resolved was then refused at finalization. Raw agreement between the two
		// titles is strictly stronger evidence than the split, so it is accepted
		// as an alternative — never as a replacement.
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

	/**
	 * One recording that YouTube's own catalog publishes more than once.
	 *
	 * ## Why this is not an ambiguity
	 *
	 * The ambiguity gate exists to stop the app choosing between two *different*
	 * videos. It assumes that two ids mean two candidate works. On auto-generated
	 * `- Topic` artist channels that assumption fails: a label or distributor can
	 * ingest the same master twice, and YouTube keeps both. Measured 2026-08-22,
	 * from the day's refusals — every one of these pairs is a single recording:
	 *
	 * ```
	 * Happy Pum Pum      -1J5knicUsw / HTc6UDnLy2s  Vybz Kartel - Topic  213 s
	 * Pretty Position    0lifXEmihs0 / JeTgdzD72Ic  Vybz Kartel - Topic  141 s
	 * Don't Cry          1LyZvC9nbYQ / WX7c6N9UjDk  Mavado - Topic       142 s
	 * A Snitch's Eulogy  5xnlV108EzI / M1ZH6zSnnUE  Mavado - Topic        82 s
	 * Amazing Grace      N0a9SYSaV4M / SjaEZej8gnA  Mavado - Topic       202 s
	 * ```
	 *
	 * They differ only in view count and upload date. No further evidence exists
	 * that could ever separate them, because there is nothing to separate — so
	 * refusing is not "fail-closed until we learn more", it is permanent loss of a
	 * listen whose recording is fully identified.
	 *
	 * ## Why the test is stricter than the one it rescues
	 *
	 * Duration must be **exactly** equal, not within [VideoIdResolver.DURATION_TOLERANCE_SEC].
	 * Two masters of one song that genuinely differ — a radio edit, a remaster —
	 * differ by at least a second, and those must keep refusing. The uploader must
	 * be the same canonical channel, so two labels' separate uploads are still an
	 * ambiguity. The work must be the same under the existing credit grammar.
	 *
	 * The caller has additionally proven the complete artist credit and the
	 * published album agree, which is why this is only reached from the YouTube
	 * Music catalog route and not from ordinary search.
	 *
	 * The representative is chosen by lowest id purely for determinism. Every
	 * candidate is the same recording, so the choice decides which URL is written,
	 * never which song was scrobbled.
	 */
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
	 * A page that describes the same work at the same length, whatever it calls
	 * the artist.
	 *
	 * ## Why the artist name is deliberately not read here
	 *
	 * This is only ever asked of a page fetched for an id that a YouTube Music
	 * catalog row already bound to the finalized work and the **complete** artist
	 * credit — and bound it through linked artist *entities*, not string matching.
	 * The page is a second opinion on whether that id is the right video, and the
	 * one thing it cannot reliably contribute is the artist's name: an art track
	 * lives on an auto-generated `- Topic` channel whose name is frequently a
	 * different alias of the same act.
	 *
	 * Measured 2026-08-23, from an overnight run — every one of these is a correct
	 * id that the name test rejected:
	 *
	 * ```
	 * Who Dem        catalog "Lexxus"       page "Mr. Lexx - Topic"
	 * Some Bwoy      catalog "Tommy Lee Sparta"  player "Tommy Lee"
	 * ```
	 *
	 * So the page is asked the two questions it can answer from its own
	 * `videoDetails`: is this the same work, and is it the same length. A wrong id
	 * still fails both. The credit remains proven — by the catalog row, which is
	 * the stronger source for it.
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

	/**
	 * One id from a set of ingests of a single recording, or null.
	 *
	 * ## Why exact-equal lengths were not enough
	 *
	 * The first version of this rule required every candidate to report the same
	 * `lengthSeconds`, which was true of the pairs it was built from. Measured
	 * 2026-08-22, playing the Mavado album straight through, `Weh Dem A Do` shows
	 * it is not true in general — three ingests on one `- Topic` channel, one
	 * album, one master, trimmed differently:
	 *
	 * ```
	 * ZV_VhN5g9hk  169 s  2 784 082 views
	 * RJYPUKFnzs8  167 s     20 554 views
	 * BIns5PQC7ck  166 s    656 226 views
	 * ```
	 *
	 * A 100 %-played listen was refused because none of the three agreed with the
	 * others to the second.
	 *
	 * ## What replaced it, and why it is not just a looser tolerance
	 *
	 * The player publishes its own length for the item it is actually playing —
	 * `169000 ms` here, which names `ZV_VhN5g9hk` and nothing else. That is a
	 * *discriminator*, not a relaxation: widening the equality test would have
	 * collapsed the three into an arbitrary pick, while asking which upload the
	 * player's own duration names picks the right one and picks it for a reason.
	 *
	 * [playerSeconds] must be **rounded** from milliseconds, not truncated.
	 * `168925 ms` truncates to 168, which matches none of the three and is the
	 * whole reason this looked unresolvable; it rounds to 169, which is exact.
	 *
	 * Only when the player's length still cannot separate them — every ingest the
	 * same length, as in the `Amazing Grace` pair — does the deterministic
	 * [sameRecording] representative apply.
	 */
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

	/**
	 * Preserve the work while exposing every explicit trailing feature credit.
	 *
	 * Stripping only **one** marker made the same recording reduce to two
	 * different works depending on how each surface wrote the credit. Measured
	 * 2026-08-23:
	 *
	 * ```
	 * player: "Can't Take Wi Life Ft. Di Genius (feat. Di Genius)"
	 *           strip the parenthetical -> "Can't Take Wi Life Ft. Di Genius"
	 * page:   "Can't Take Wi Life Ft. Di Genius"
	 *           strip the bare marker   -> "Can't Take Wi Life"
	 * ```
	 *
	 * One pass each, two different answers, and a fully played listen refused.
	 * Repeating until neither shape matches makes the reduction independent of how
	 * many markers a surface happened to write. Bounded so a pathological title
	 * cannot spin.
	 */
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
			// A parenthesised credit that is not at the end. YouTube Music and the
			// watch page order the parts differently for the same recording —
			// measured 2026-08-23, the player wrote
			// `Dancehall Frequency [Wavz] (feat. Jahnaton & 808 Delavega)` and the
			// page wrote `Dancehall Frequency (feat. Jahnaton, 808 Delavega) [Wavz]`.
			// Removing the group wherever it sits makes the reduction independent
			// of that ordering. Only an explicit feature group is removed; every
			// other parenthetical — `(Live)`, `(Remastered)` — is part of the work
			// and is left alone.
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
