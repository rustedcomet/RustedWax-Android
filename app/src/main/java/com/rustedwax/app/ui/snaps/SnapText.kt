package com.rustedwax.app.ui.snaps

/**
 * The 200-character rule, counted the way the person typing it counts.
 *
 * A Snap's limit is a promise about what the user can see in the box, so the
 * unit has to be the thing they see. `String.length` is UTF-16 code units,
 * which makes one waving-hand-with-skin-tone worth four characters and a flag
 * worth two — a limit that spends a fifth of itself on a single emoji reads as
 * a bug, not a rule. So this walks grapheme clusters instead.
 *
 * `java.text.BreakIterator` would also do it, but its answer depends on which
 * ICU the runtime happens to carry: the JVM that runs these tests and the
 * Android device that runs the app do not agree about newer emoji. A rule the
 * user is held to should not change between a test and a phone, so the cluster
 * rules this feature needs are spelled out here and behave identically in both.
 *
 * Pure Kotlin on purpose — no Compose, no Android, no playback. This is the one
 * piece of Snaps with real logic in it, so it is the piece that stays testable.
 */
internal object SnapText {

	/** What RustedWax itself creates. Other Hive frontends are not bound by it. */
	const val LIMIT = 200

	/**
	 * User-visible characters, counting one emoji as one.
	 *
	 * Handles the sequences an ordinary keyboard actually produces: skin tones,
	 * ZWJ families, flags, keycaps, variation selectors and combining accents.
	 */
	fun count(text: String): Int {
		var clusters = 0
		var i = 0
		while (i < text.length) {
			val start = i
			val first = text.codePointAt(i)
			i += Character.charCount(first)

			// CRLF is one break, not two.
			if (first == '\r'.code && i < text.length && text[i] == '\n') {
				i += 1
				clusters += 1
				continue
			}

			// A flag is exactly two regional indicators. A third starts a new one.
			if (isRegionalIndicator(first) && i < text.length) {
				val next = text.codePointAt(i)
				if (isRegionalIndicator(next)) i += Character.charCount(next)
			}

			// Whether this cluster is currently built on an emoji. A joiner only
			// welds two *pictures* together; between letters it joins nothing.
			var pictographicBase = isPictographic(first)

			// Everything that hangs off the character before it.
			while (i < text.length) {
				val cp = text.codePointAt(i)
				val joins = when {
					isExtending(cp) -> true
					// A ZWJ always attaches to the cluster it follows — it is
					// invisible, so it can never be a character of its own.
					// Whether it also pulls in what comes *after* it is decided
					// below, and is not the same question.
					cp == ZWJ -> true
					else -> false
				}
				if (!joins) break
				i += Character.charCount(cp)
				if (cp == ZWJ && i < text.length) {
					val joined = text.codePointAt(i)
					// UAX #29 GB11: a joiner merges the next character into this
					// cluster only when an emoji is being joined to an emoji.
					// Treating every `x ZWJ y` as one grapheme let arbitrary
					// letters be chained into a single "character", so 400
					// visible letters could report 200/200 and post a Snap twice
					// the length of the limit. A joiner between two letters is
					// not a ligature — both letters are still on screen.
					if (pictographicBase && isPictographic(joined)) {
						i += Character.charCount(joined)
						// The emoji just welded on becomes the base the next
						// joiner is judged against, so 👨‍👩‍👧‍👦 stays one cluster.
						pictographicBase = true
					} else {
						// Not a picture-to-picture weld: the joiner is consumed
						// as part of this cluster, and `joined` is left to start
						// the next one.
						break
					}
				}
			}

			if (i == start) i += 1 // defensive: never stall on malformed input
			clusters += 1
		}
		return clusters
	}

	/**
	 * Whether this draft could be posted at all.
	 *
	 * Whitespace is not a Snap, an emoji on its own is, and exactly [LIMIT] is
	 * inside the limit rather than over it.
	 */
	fun isValid(text: String): Boolean = hasVisible(text) && count(text) <= LIMIT

	/** True once the draft has spent its allowance and Post has to go dead. */
	fun isOverflowing(text: String): Boolean = count(text) > LIMIT

	/** How far past the limit, as a positive number. Zero while inside it. */
	fun overflow(text: String): Int = (count(text) - LIMIT).coerceAtLeast(0)

	/**
	 * The counter under the box: `0/200` while there is room, and the plain
	 * negative amount once there is not — `-1`, `-12`, `-53`.
	 */
	fun counterLabel(text: String): String {
		val n = count(text)
		return if (n > LIMIT) "-${n - LIMIT}" else "$n/$LIMIT"
	}

	/**
	 * Whether anything would actually show on screen.
	 *
	 * Spaces and newlines do not count, and neither do the invisible joiners and
	 * selectors that only exist to modify a neighbour — a draft made of nothing
	 * but those has no visible character in it, whatever its length says.
	 */
	fun hasVisible(text: String): Boolean {
		var i = 0
		while (i < text.length) {
			val cp = text.codePointAt(i)
			i += Character.charCount(cp)
			if (Character.isWhitespace(cp)) continue
			if (cp == ZWJ || isExtending(cp) || isInvisibleFormat(cp)) continue
			return true
		}
		return false
	}

	private const val ZWJ = 0x200D

	private fun isRegionalIndicator(cp: Int) = cp in 0x1F1E6..0x1F1FF

	/**
	 * Unicode's `Extended_Pictographic`: is this code point a picture?
	 *
	 * Only the joiner rule asks, and it asks one narrow question — may these two
	 * things be welded into a single glyph. Answered from the real property
	 * ranges below by binary search, not by which block a character lives in.
	 */
	private fun isPictographic(cp: Int): Boolean {
		var lo = 0
		var hi = EXTENDED_PICTOGRAPHIC.size / 2 - 1
		while (lo <= hi) {
			val mid = (lo + hi) ushr 1
			val start = EXTENDED_PICTOGRAPHIC[mid * 2]
			val end = EXTENDED_PICTOGRAPHIC[mid * 2 + 1]
			when {
				cp < start -> hi = mid - 1
				cp > end -> lo = mid + 1
				else -> return true
			}
		}
		return false
	}

	/**
	 * Unicode's `Extended_Pictographic` ranges, as `[start, end]` pairs, ascending.
	 *
	 * Transcribed from `emoji-data.txt` rather than approximated by block, because
	 * "the block contains emoji" is not the same claim as "this character is an
	 * emoji". Arrows are the case that proved it: `U+2194..U+2199` and
	 * `U+21A9..U+21AA` are pictographic, while the plain `←` `↑` `→` `↓` beside
	 * them are ordinary symbols. Accepting the whole `2190..21FF` block welded
	 * `←` to `→` through a joiner and reopened the very bypass the joiner rule was
	 * added to close.
	 *
	 * Deliberately excludes the regional indicators `U+1F1E6..U+1F1FF`, which are
	 * not Extended_Pictographic; flags are counted by their own pair rule above.
	 *
	 * A missing range costs one extra character in a count. A wrong range costs
	 * the limit, so anything uncertain is left out.
	 */
	private val EXTENDED_PICTOGRAPHIC = intArrayOf(
		0x00A9, 0x00A9, 0x00AE, 0x00AE, 0x203C, 0x203C, 0x2049, 0x2049,
		0x2122, 0x2122, 0x2139, 0x2139, 0x2194, 0x2199, 0x21A9, 0x21AA,
		0x231A, 0x231B, 0x2328, 0x2328, 0x2388, 0x2388, 0x23CF, 0x23CF,
		0x23E9, 0x23F3, 0x23F8, 0x23FA, 0x24C2, 0x24C2, 0x25AA, 0x25AB,
		0x25B6, 0x25B6, 0x25C0, 0x25C0, 0x25FB, 0x25FE, 0x2600, 0x2605,
		0x2607, 0x2612, 0x2614, 0x2685, 0x2690, 0x2705, 0x2708, 0x2712,
		0x2714, 0x2714, 0x2716, 0x2716, 0x271D, 0x271D, 0x2721, 0x2721,
		0x2728, 0x2728, 0x2733, 0x2734, 0x2744, 0x2744, 0x2747, 0x2747,
		0x274C, 0x274C, 0x274E, 0x274E, 0x2753, 0x2755, 0x2757, 0x2757,
		0x2763, 0x2767, 0x2795, 0x2797, 0x27A1, 0x27A1, 0x27B0, 0x27B0,
		0x27BF, 0x27BF, 0x2934, 0x2935, 0x2B05, 0x2B07, 0x2B1B, 0x2B1C,
		0x2B50, 0x2B50, 0x2B55, 0x2B55, 0x3030, 0x3030, 0x303D, 0x303D,
		0x3297, 0x3297, 0x3299, 0x3299,
		0x1F000, 0x1F0FF, 0x1F10D, 0x1F10F, 0x1F12F, 0x1F12F, 0x1F16C, 0x1F171,
		0x1F17E, 0x1F17F, 0x1F18E, 0x1F18E, 0x1F191, 0x1F19A, 0x1F1AD, 0x1F1E5,
		0x1F201, 0x1F20F, 0x1F21A, 0x1F21A, 0x1F22F, 0x1F22F, 0x1F232, 0x1F23A,
		0x1F23C, 0x1F23F, 0x1F249, 0x1F3FA, 0x1F400, 0x1F53D, 0x1F546, 0x1F64F,
		0x1F680, 0x1F6FF, 0x1F774, 0x1F77F, 0x1F7D5, 0x1F7FF, 0x1F80C, 0x1F80F,
		0x1F848, 0x1F84F, 0x1F85A, 0x1F85F, 0x1F888, 0x1F88F, 0x1F8AE, 0x1F8FF,
		0x1F90C, 0x1F93A, 0x1F93C, 0x1F945, 0x1F947, 0x1FAFF, 0x1FC00, 0x1FFFD,
	)

	/** Marks and modifiers that attach to the character before them. */
	private fun isExtending(cp: Int): Boolean = when {
		cp in 0xFE00..0xFE0F -> true // variation selectors (incl. emoji-style FE0F)
		cp in 0x1F3FB..0x1F3FF -> true // skin tone modifiers
		cp in 0x0300..0x036F -> true // combining diacritics
		cp in 0x1AB0..0x1AFF -> true
		cp in 0x1DC0..0x1DFF -> true
		cp in 0x20D0..0x20FF -> true // includes the keycap U+20E3
		cp in 0xFE20..0xFE2F -> true
		cp in 0xE0020..0xE007F -> true // tag characters, for subdivision flags
		else -> Character.getType(cp).let {
			it == Character.NON_SPACING_MARK.toInt() ||
				it == Character.ENCLOSING_MARK.toInt() ||
				it == Character.COMBINING_SPACING_MARK.toInt()
		}
	}

	private fun isInvisibleFormat(cp: Int) = when (cp) {
		0x200B, 0x200C, 0x200E, 0x200F, 0xFEFF -> true
		else -> false
	}
}
