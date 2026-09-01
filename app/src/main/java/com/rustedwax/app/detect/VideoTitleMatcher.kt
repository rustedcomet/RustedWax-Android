package com.rustedwax.app.detect

import com.rustedwax.core.TextNormalizer
import java.text.Normalizer
import java.util.Locale

/**
 * Pure presentation-aware comparison for two observations of one video title.
 *
 * This does not resolve a title to a video and contains no media catalogue. It
 * only decides whether a page title can corroborate the MediaSession title for
 * an id already observed from the browser. [SessionProbe] still checks page
 * duration independently, and finalized resolution applies the same predicate
 * before any fetched facts may enter a payload.
 */
object VideoTitleMatcher {

	enum class Evidence {
		EXACT,
		STRONG_CONTAINMENT,
		WEAK_SHORT_CANONICAL_CORE,
		CONTRADICTION,
	}

	fun compare(first: String, second: String): Evidence {
		// A title does not need letters or digits to be exact. The 2026-08-14
		// field Short `🥰❤️` was byte-identical in the foreground proof,
		// resolved candidate and finalized snapshot, but both token lists below
		// were empty and therefore contradicted one another. Presentation
		// selectors change only whether an emoji is drawn as text or color, not
		// which title YouTube displayed; preserve every actual emoji scalar.
		if (samePresentation(first, second)) return Evidence.EXACT
		val left = tokens(first)
		val right = tokens(second)
		if (left.isEmpty() || right.isEmpty()) return Evidence.CONTRADICTION
		if (left == right) return Evidence.EXACT

		// One channel mention, spelled two ways. A title may embed an `@` mention,
		// and YouTube renders it as the handle in one place and the display name
		// in another — measured 2026-08-16 for `aZUbc6fCNDk`, whose watch page
		// says `@yingyangtwins5139` where its own MediaSession says
		// `@YING YANG TWINS`. Every other word of the title was byte-identical,
		// and the correct address-bar id was rejected for the difference.
		//
		// A mention names a channel, not the work, so it is presentation. This is
		// deliberately not fuzzy: the two titles must agree on everything before
		// the mention and everything after it, the disagreement must be one
		// contiguous run on each side, that run must begin at an `@` on both, and
		// the identical surrounding text must be substantial. `Cover by @alice`
		// against `Cover by @bob` shares two tokens and stays a contradiction.
		if (mentionOnlyDifference(first, second)) return Evidence.STRONG_CONTAINMENT

		val shorter = if (left.size <= right.size) left else right
		val longer = if (left.size <= right.size) right else left
		// Containment is whole-token and ordered. Two shared artist/promo words
		// in otherwise different adjacent songs are not enough; the complete
		// shorter identity structure must survive in the longer presentation.
		if (!containsSequence(longer, shorter)) return Evidence.CONTRADICTION
		if (shorter.size >= MIN_STRONG_CONTAINED_TOKENS) {
			return Evidence.STRONG_CONTAINMENT
		}

		// One/two-token containment is deliberately weaker. It is evidence only
		// when the short side is the work portion of a structurally parsed longer
		// title, not its artist/uploader prefix (`Bad Bunny` must not corroborate
		// `Bad Bunny - Another Song`). Callers apply the independent URL-generation
		// and duration restrictions before this rank may retain an id.
		val longerValue = if (left.size <= right.size) second else first
		val parsedWork = TitleParser.parse(longerValue, channel = null).track
		val work = tokens(TRAILING_FEATURE_CREDITS.replace(parsedWork, ""))
		if (shorter.size in 1..MAX_WEAK_TOKENS && shorter == work) {
			return Evidence.WEAK_SHORT_CANONICAL_CORE
		}
		// The canonical title as the *tail* of a longer presentation.
		//
		// Measured 2026-08-16 for `dE8D6WY6tQQ`: its watch page is titled
		// `Bounce` and its MediaSession publishes
		// `Ladii Rose ft Dej RoseGold Bounce (Official Video)`. The parsed-work
		// route above cannot see it, because `ft` there joins two *artists* and
		// [TRAILING_FEATURE_CREDITS] strips the work along with the credit,
		// leaving `Ladii Rose`. So the correct id was graded a contradiction and
		// filed as rejected, and the Mix queue's later independent resolution of
		// that same id was vetoed by it.
		//
		// Position is what separates this from the case the rank already guards:
		// in `Artist - Track` and `Artist ft Artist Track` the work is at the end,
		// so a short *suffix* is the canonical core, while a short *prefix* is the
		// uploader — `Bad Bunny` must still not corroborate
		// `Bad Bunny - Another Song`, and does not, because it is a prefix.
		//
		// This remains the weak rank deliberately. It never confirms an id on its
		// own; a caller still needs same-generation duration corroboration. What
		// it changes is that insufficiency stops being recorded as a
		// contradiction, so it no longer outranks stronger evidence arriving
		// later — the rule this file's callers already state for the other weak
		// shape.
		return if (shorter.size in 1..MAX_WEAK_TOKENS &&
			longer.takeLast(shorter.size) == shorter
		) {
			Evidence.WEAK_SHORT_CANONICAL_CORE
		} else {
			Evidence.CONTRADICTION
		}
	}

	/**
	 * [mentionOnlyDifference] with the differing run additionally bounded to the
	 * length of a channel name, for callers where this is the *primary* title
	 * test rather than corroboration for an id already observed elsewhere.
	 *
	 * The anchoring in [mentionOnlyDifference] fixes where the disagreement
	 * starts but not where it stops, so a run beginning at an `@` may extend
	 * across the rest of the title:
	 * `… Feat. @SexyyRed - Different Song Entirely (Official Video)` differs from
	 * `… Feat. @Sexyy Red - Slut Me Out Remix (Official Video)` in one run that
	 * starts at a mention on both sides, and the uncapped rule calls that
	 * presentation. Corroborating an address-bar id that way is bounded by the
	 * id's own provenance; *selecting* a search result that way is not, and the
	 * two songs above are a plausible pair of uploads by one channel.
	 *
	 * [MAX_MENTION_RUN_TOKENS] is what a channel name occupies:
	 * `@yingyangtwins5139` against `@YING YANG TWINS` (measured 2026-08-16) is
	 * the longest observed, at three. The 2026-08-17 run's three losses are one
	 * and two.
	 */
	fun mentionSpellingOnly(first: String, second: String): Boolean =
		mentionOnlyDifference(first, second, maxRunTokens = MAX_MENTION_RUN_TOKENS)

	/**
	 * Whether two titles differ only in how one embedded `@` mention is spelled.
	 *
	 * Anchored on both sides: the differing run must be one contiguous block that
	 * *starts at an `@`* in each title, and everything around it must be
	 * token-identical and at least [MIN_STRONG_CONTAINED_TOKENS] long. A title
	 * that merely happens to contain a mention is not enough.
	 */
	private fun mentionOnlyDifference(
		first: String,
		second: String,
		maxRunTokens: Int = Int.MAX_VALUE,
	): Boolean {
		val left = markedTokens(first)
		val right = markedTokens(second)
		if (left.isEmpty() || right.isEmpty()) return false
		val maxPrefix = minOf(left.size, right.size)
		var prefix = 0
		while (prefix < maxPrefix && left[prefix].value == right[prefix].value) prefix++
		var suffix = 0
		while (suffix < maxPrefix - prefix &&
			left[left.size - 1 - suffix].value == right[right.size - 1 - suffix].value
		) {
			suffix++
		}
		if (prefix + suffix < MIN_STRONG_CONTAINED_TOKENS) return false
		val leftMiddle = left.subList(prefix, left.size - suffix)
		val rightMiddle = right.subList(prefix, right.size - suffix)
		if (leftMiddle.isEmpty() || rightMiddle.isEmpty()) return false
		if (leftMiddle.size > maxRunTokens || rightMiddle.size > maxRunTokens) return false
		return leftMiddle.first().mention && rightMiddle.first().mention
	}

	/** One token, and whether the title wrote an `@` immediately before it. */
	private data class Marked(val value: String, val mention: Boolean)

	private fun markedTokens(value: String): List<Marked> =
		normalized(TitleParser.presentationCore(value))
			// Everything the plain tokenizer separates on, except the mention
			// marker, which is the one punctuation character that carries meaning
			// here rather than being noise.
			.replace(Regex("""[^\p{L}\p{N}@]+"""), " ")
			.replace(Regex("""\s+"""), " ")
			.trim()
			.split(' ')
			.filter { it.isNotBlank() && it != "@" }
			.map { Marked(it.removePrefix("@"), it.startsWith('@')) }
			.filter { it.value.isNotBlank() }

	/** Non-empty exact equality for human-visible title presentations. */
	fun samePresentation(first: String, second: String): Boolean {
		val left = presentationKey(first)
		return left.isNotEmpty() && left == presentationKey(second)
	}

	private fun presentationKey(value: String): String =
		TextNormalizer.presentation(value).replace(EMOJI_PRESENTATION_SELECTOR, "")

	private fun tokens(value: String): List<String> =
		normalized(TitleParser.presentationCore(value))
			.replace(Regex("""[^\p{L}\p{N}]+"""), " ")
			.replace(Regex("""\s+"""), " ")
			.trim()
			.split(' ')
			.filter(String::isNotBlank)

	/** Case, accents and compatibility forms folded; punctuation left in place. */
	private fun normalized(value: String): String =
		Normalizer.normalize(value, Normalizer.Form.NFKD)
			.lowercase(Locale.ROOT)
			.replace(Regex("""\p{M}+"""), "")

	private fun containsSequence(longer: List<String>, shorter: List<String>): Boolean {
		if (shorter.size > longer.size) return false
		return (0..(longer.size - shorter.size)).any { start ->
			longer.subList(start, start + shorter.size) == shorter
		}
	}

	private const val MIN_STRONG_CONTAINED_TOKENS = 3

	/** Longest observed channel name inside a mention run — `@YING YANG TWINS`. */
	private const val MAX_MENTION_RUN_TOKENS = 3
	private const val MAX_WEAK_TOKENS = 2
	private val EMOJI_PRESENTATION_SELECTOR = Regex("[\\uFE0E\\uFE0F]")
	private val TRAILING_FEATURE_CREDITS = Regex(
		"""\s+\b(?:ft|feat(?:uring)?)\.?\s+.+$""",
		RegexOption.IGNORE_CASE,
	)
}
