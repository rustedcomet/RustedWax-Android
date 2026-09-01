package com.rustedwax.core
import java.text.Normalizer
import java.util.Locale

/**
 * One answer to "are these the same string", in the two forms that question
 * actually has.
 *
 * ## The bug this exists for
 *
 * Three components had three answers. `DedupLedger.keyFor` lowercased and
 * normalized nothing; `TrackIdentity` used NFKC; `OwnerHandle` used NFC as of
 * v0.9.13. A dedup key then read `…|@eduardaarebouçass|496153`, so the cedilla
 * that `OwnerHandle` composed and `DedupLedger` did not could produce two keys
 * for one listen — and a duplicate on a ledger that cannot be edited. That is
 * `<redacted-private-path>` §3.6, and it stopped being hypothetical the moment a
 * handle entered the key.
 *
 * ## Why this is two functions and not one
 *
 * "Just use NFKC everywhere" is the obvious merge and it is wrong. NFKC folds
 * *compatibility* variants together: `ﬁ` becomes `fi`, `Ⅳ` becomes `IV`,
 * full-width `Ａ` becomes `A`. For a title that is exactly right — those are the
 * same text typed differently, and a listen should not dedup twice because an
 * uploader used a ligature.
 *
 * For an **identity** it is dangerous in the other direction. Two genuinely
 * different handles that differ only by a compatibility variant would collide
 * and one creator's listens would be credited to the other. Identity may only
 * use canonical equivalence — NFC merges sequences that *are* the same
 * character and never merges two that are not.
 *
 * So: [presentation] for anything a human reads, [identity] for anything that
 * names a thing. The split is deliberate and load-bearing.
 *
 * ## Invisible characters
 *
 * §3.5. `Artist[U+200B] - Song` fails to split on a title that looks perfectly
 * correct on screen, because a zero-width space sits where the parser expects a
 * word boundary. The same class of assumption already cost every non-ASCII
 * creator their listens once, when the owner-handle pattern was ASCII-only.
 *
 * The primitives are ported from `metadata-filter` (MIT), which solves this for
 * the upstream extension.
 */
object TextNormalizer {

	/**
	 * Characters that occupy no space and mean nothing here.
	 *
	 * Zero-width space/non-joiner/joiner, the BOM used as a zero-width no-break
	 * space, the bidirectional marks and embeddings that RTL titles carry, and
	 * the soft hyphen. All of them survive a copy-paste into a video title and
	 * none of them should affect whether two titles are the same.
	 */
	private val ZERO_WIDTH = Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u2064\\uFEFF\\u00AD]")

	/** Every space Unicode offers, folded to the one the parsers expect. */
	private val UNICODE_SPACES = Regex("[\\u00A0\\u1680\\u2000-\\u200A\\u202F\\u205F\\u3000]")

	private val SMART_SINGLE = Regex("[\\u2018\\u2019\\u201A\\u201B\\u2032\\u00B4`]")
	private val SMART_DOUBLE = Regex("[\\u201C\\u201D\\u201E\\u201F\\u2033\\u00AB\\u00BB]")

	private val COLLAPSE_SPACES = Regex("\\s{2,}")

	/**
	 * Strip what is invisible and fold what is merely typed differently.
	 *
	 * Applied before any parsing or comparison. Deliberately does **not** change
	 * case or drop punctuation — this makes a string well-formed, it does not
	 * decide what it means.
	 */
	fun clean(value: String): String {
		var out = ZERO_WIDTH.replace(value, "")
		out = UNICODE_SPACES.replace(out, " ")
		out = SMART_SINGLE.replace(out, "'")
		out = SMART_DOUBLE.replace(out, "\"")
		out = decodeHtmlEntities(out)
		return COLLAPSE_SPACES.replace(out, " ").trim()
	}

	/**
	 * A comparison key for text a human reads — titles, artists, albums.
	 *
	 * NFKC, because a ligature and its letters are the same title.
	 */
	fun presentation(value: String?): String {
		val cleaned = clean(value.orEmpty())
		if (cleaned.isEmpty()) return ""
		return Normalizer.normalize(cleaned, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
	}

	/**
	 * A comparison key for something that *names* a thing — an owner handle, a
	 * video id, anything two of which must never be confused.
	 *
	 * NFC only. Composing `c` + combining cedilla into `ç` is safe because they
	 * are the same character; folding `Ａ` into `A` is not, because they may be
	 * two different names.
	 */
	fun identity(value: String?): String {
		val cleaned = clean(value.orEmpty())
		if (cleaned.isEmpty()) return ""
		return Normalizer.normalize(cleaned, Normalizer.Form.NFC)
	}

	/**
	 * The handful of entities encountered in published titles.
	 *
	 * Not a general HTML parser and must not become one: this runs on a media
	 * session title, not on markup. Numeric forms are included because the watch
	 * page uses them for characters the page's own encoding cannot carry.
	 */
	private fun decodeHtmlEntities(value: String): String {
		if (!value.contains('&')) return value
		var out = value
		NAMED_ENTITIES.forEach { (name, replacement) -> out = out.replace(name, replacement) }
		out = NUMERIC_ENTITY.replace(out) { m ->
			val text = m.groupValues[1]
			val code = if (text.startsWith("x") || text.startsWith("X")) {
				text.drop(1).toIntOrNull(16)
			} else {
				text.toIntOrNull()
			}
			// An out-of-range or unparseable reference is left exactly as written;
			// a title containing a literal "&#99999999;" is not ours to rewrite.
			if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else m.value
		}
		// Last, so "&amp;quot;" decodes to the literal "&quot;" rather than a
		// quote — the same order a browser uses.
		return out.replace("&amp;", "&")
	}

	private val NUMERIC_ENTITY = Regex("&#(x?[0-9A-Fa-f]+);")

	private val NAMED_ENTITIES = listOf(
		"&quot;" to "\"",
		"&apos;" to "'",
		"&#39;" to "'",
		"&lt;" to "<",
		"&gt;" to ">",
		"&nbsp;" to " ",
	)
}
