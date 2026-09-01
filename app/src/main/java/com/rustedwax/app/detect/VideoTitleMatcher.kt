package com.rustedwax.app.detect

import com.rustedwax.core.TextNormalizer
import java.text.Normalizer
import java.util.Locale

object VideoTitleMatcher {

	enum class Evidence {
		EXACT,
		STRONG_CONTAINMENT,
		WEAK_SHORT_CANONICAL_CORE,
		CONTRADICTION,
	}

	fun compare(first: String, second: String): Evidence {

		if (samePresentation(first, second)) return Evidence.EXACT
		val left = tokens(first)
		val right = tokens(second)
		if (left.isEmpty() || right.isEmpty()) return Evidence.CONTRADICTION
		if (left == right) return Evidence.EXACT

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

		return if (shorter.size in 1..MAX_WEAK_TOKENS &&
			longer.takeLast(shorter.size) == shorter
		) {
			Evidence.WEAK_SHORT_CANONICAL_CORE
		} else {
			Evidence.CONTRADICTION
		}
	}

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

	private const val MAX_MENTION_RUN_TOKENS = 3
	private const val MAX_WEAK_TOKENS = 2
	private val EMOJI_PRESENTATION_SELECTOR = Regex("[\\uFE0E\\uFE0F]")
	private val TRAILING_FEATURE_CREDITS = Regex(
		"""\s+\b(?:ft|feat(?:uring)?)\.?\s+.+$""",
		RegexOption.IGNORE_CASE,
	)
}
