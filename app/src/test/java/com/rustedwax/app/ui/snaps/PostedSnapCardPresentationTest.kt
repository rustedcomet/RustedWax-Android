package com.rustedwax.app.ui.snaps

import com.rustedwax.app.snaps.PostedSnap
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The posted card must show the whole Snap — asserted against the composable
 * itself, not against the layer underneath it.
 *
 * Everything else about the text is already covered: extraction is proven by
 * `PostedSnapBodyTest` and restoration by `PostedSnapStateTest`, including a
 * 200-cluster emoji Snap. None of that would notice a `maxLines = 8` added to
 * the one `Text` that actually draws the body, which is precisely how the
 * original defect shipped — the data was perfect and the screen cut it.
 *
 * This project has no Compose UI or semantics testing available (no
 * `ui-test-junit4`, no Robolectric, no Espresso beyond the runner), and the
 * instrumented suite deliberately pulls in no UI-automation library. Adding one
 * for a single assertion is not a trade this change gets to make. So this
 * follows the pattern the repository already uses for exactly this problem —
 * `RecompositionScopeWiringTest` and the other architecture tests read the
 * production source and assert on its shape.
 *
 * ## What is inspected
 *
 * An earlier revision of this test looked only at the final `Text(...)`
 * argument list, which a truncation applied *earlier* in the same expression
 * walks straight past:
 *
 * ```
 * posted.userText.take(1000).takeIf { it.isNotEmpty() }?.let { Text(it) }
 * ```
 *
 * `Text(it)` is spotless there and the Snap is still cut. So what is checked is
 * the whole **presentation path**: everything from the `posted.userText` that
 * supplies the body, through every link of the call chain carrying it, into the
 * `Text` call that draws it — plus whatever the source is wrapped in on the way
 * *into* that chain, which is where `shorten(posted.userText)` would hide.
 *
 * It stays narrow. It says nothing about the handle or the age beside it, which
 * are header chrome and remain free to be single ellipsized lines, and it does
 * not try to police Kotlin in general — only the concrete path this one string
 * travels.
 */
class PostedSnapCardPresentationTest {

	private val root: File by lazy {
		generateSequence(File(checkNotNull(System.getProperty("user.dir"))).absoluteFile) {
			it.parentFile
		}.firstOrNull { File(it, "settings.gradle.kts").isFile }
			?: error("repository root was not found")
	}

	private fun text(path: String): String = File(root, path).let {
		assertTrue("production source missing: $path", it.isFile)
		it.readText()
	}

	/**
	 * Comments come out before anything is matched.
	 *
	 * The card's own comment explains that it carries no `maxLines` and no
	 * ellipsis, and a test that searched the raw file would find those words in
	 * the sentence saying they are absent.
	 */
	private fun stripComments(source: String): String = source
		.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
		.lines()
		.joinToString("\n") { it.substringBefore("//") }

	private val card: String get() = stripComments(text(CARD))

	private val pureLayer: String get() =
		stripComments(text("app/src/main/java/com/rustedwax/app/snaps/PostedSnap.kt"))

	/**
	 * The one path the Snap body travels, cut out of the card's source.
	 *
	 * @param wrappedBy the call `posted.userText` is an argument to, if any —
	 *  null when it stands on its own, which is the only correct answer.
	 * @param chain everything between the body source and the `Text(` that draws
	 *  it: the links that carry the string.
	 * @param arguments the `Text` call's own argument list.
	 * @param whole source and chain and call together, for a single sweep that
	 *  no lambda body or local `val` inside the path can hide from.
	 */
	private data class Path(
		val wrappedBy: String?,
		val chain: String,
		val arguments: String,
		val whole: String,
	)

	private fun path(): Path {
		val source = card
		val anchor = source.indexOf(BODY_SOURCE)
		assertTrue(
			"$CARD no longer mentions `$BODY_SOURCE`. If the card was restructured, " +
				"this test has to be re-pointed at whatever now draws the Snap body — " +
				"it must not simply be deleted.",
			anchor >= 0,
		)
		val call = source.indexOf("Text(", anchor)
		assertTrue("no Text( call follows `$BODY_SOURCE` in $CARD", call >= 0)

		val openParen = call + "Text".length
		val closeParen = matching(source, openParen)
		val arguments = source.substring(openParen + 1, closeParen)
		assertFalse(
			"this test matched the handle's Text rather than the Snap body's; " +
				"re-point it at the call that renders the body",
			arguments.contains("@\$author"),
		)

		return Path(
			wrappedBy = wrapperAround(source, anchor),
			chain = source.substring(anchor + BODY_SOURCE.length, call),
			arguments = arguments,
			whole = source.substring(anchor, closeParen + 1),
		)
	}

	/** The index of the `)` matching the `(` at [openParen]. */
	private fun matching(source: String, openParen: Int): Int {
		var depth = 0
		for (i in openParen until source.length) {
			when (source[i]) {
				'(' -> depth += 1
				')' -> {
					depth -= 1
					if (depth == 0) return i
				}
			}
		}
		throw AssertionError("unbalanced Text( in $CARD")
	}

	/**
	 * The name of the call `posted.userText` sits inside, or null.
	 *
	 * Reads *backwards* from the body source, because a wrapper is the one
	 * transformation that happens before the text this test otherwise scans:
	 * in `shorten(posted.userText).let { Text(it) }` every character after the
	 * body source is innocent.
	 */
	private fun wrapperAround(source: String, anchor: Int): String? {
		var i = anchor - 1
		while (i >= 0 && source[i].isWhitespace()) i -= 1
		if (i < 0 || source[i] != '(') return null

		var start = i - 1
		while (start >= 0 && (source[start].isLetterOrDigit() || source[start] in "_.")) {
			start -= 1
		}
		return source.substring(start + 1, i).ifBlank { "an unnamed call" }
	}

	/**
	 * The names invoked on the carrier chain itself.
	 *
	 * Depth-tracked, so what happens *inside* a lambda or an argument list is not
	 * mistaken for a link in the chain — `takeIf { it.isNotEmpty() }` calls
	 * `isNotEmpty` on the value without the chain ever handing it on.
	 */
	private fun chainLinks(chain: String): List<String> {
		val links = mutableListOf<String>()
		var depth = 0
		var i = 0
		while (i < chain.length) {
			when (val c = chain[i]) {
				'(', '{', '[' -> depth += 1
				')', '}', ']' -> depth -= 1
				'.' -> if (depth <= 0) {
					var end = i + 1
					while (end < chain.length && (chain[end].isLetterOrDigit() || chain[end] == '_')) {
						end += 1
					}
					if (end > i + 1) {
						links += chain.substring(i + 1, end)
						i = end - 1
					}
				}
				else -> Unit
			}
			i += 1
		}
		return links
	}

	// ---- the body Text's own arguments -------------------------------------

	/**
	 * `maxLines` on the body hides whole lines of a Snap that the composer
	 * accepted. Twenty short lines is a legal draft.
	 */
	@Test
	fun `the Snap body is drawn with no line limit`() {
		assertFalse(
			"the Snap body's Text has a maxLines again — a legal Snap of more than " +
				"that many lines would be silently cut off on the card",
			path().arguments.contains("maxLines"),
		)
	}

	/** An ellipsis is the visible half of the same defect. */
	@Test
	fun `the Snap body is drawn with no overflow clipping`() {
		val arguments = path().arguments
		listOf("overflow", "TextOverflow", "Ellipsis", "Clip").forEach {
			assertFalse(
				"the Snap body's Text specifies `$it`; the body must not be clipped",
				arguments.contains(it),
			)
		}
	}

	/**
	 * The first argument has to be the body itself, not a function of it — a call
	 * is the one thing a bare reference cannot contain.
	 */
	@Test
	fun `the Snap body reaches Text unmodified`() {
		val rendered = firstArgument(path().arguments)

		assertFalse(
			"the Snap body is passed through a call (`$rendered`) instead of being " +
				"rendered as it is; nothing may shorten or rewrite it here",
			rendered.contains("("),
		)
	}

	/** The first positional argument — the thing actually being rendered. */
	private fun firstArgument(arguments: String): String {
		var depth = 0
		arguments.forEachIndexed { i, c ->
			when (c) {
				'(', '[', '{' -> depth += 1
				')', ']', '}' -> depth -= 1
				',' -> if (depth == 0) return arguments.substring(0, i).trim()
				else -> Unit
			}
		}
		return arguments.trim()
	}

	// ---- everything upstream of it -----------------------------------------

	/**
	 * Nothing anywhere on the path may shorten the Snap.
	 *
	 * One sweep over the whole path — source, chain, lambda bodies, the `Text`
	 * call — for the operations that cut a string. This is what catches a
	 * truncation that hides behind an innocent-looking `Text(it)`, whether it sits
	 * in the chain (`.take(1000).takeIf { … }`), inside a lambda, or in a local
	 * `val` declared on the way.
	 *
	 * Matched with the opening parenthesis attached, so `takeIf` — which returns
	 * its receiver unchanged and is exactly how the card decides whether there is
	 * anything to draw — is not mistaken for `take(`.
	 */
	@Test
	fun `nothing on the path truncates the Snap body`() {
		val whole = path().whole
		listOf(
			"take", "takeLast", "drop", "dropLast", "slice", "substring",
			"subSequence", "chunked", "windowed", "trim", "trimEnd", "trimStart",
			"replace", "removeRange", "removeSuffix", "removePrefix", "ellipsize",
		).forEach {
			assertFalse(
				"`.$it(` appears on the Snap body's presentation path; the body must " +
					"reach the screen exactly as it was published",
				whole.contains(Regex("""\.\s*$it\s*\(""")),
			)
		}
		listOf("forDisplay", "shorten", "truncate", "abbreviate", "clip").forEach {
			assertFalse(
				"`$it(` appears on the Snap body's presentation path",
				whole.contains(Regex("""\b$it\s*\(""")),
			)
		}
	}

	/**
	 * And the chain carrying it may only contain links that preserve it.
	 *
	 * The allowlist is the counterpart to the sweep above: that one names the
	 * ways a string is known to be cut, this one refuses anything it does not
	 * recognise, so a truncation spelled some way nobody has thought of still has
	 * to get past a name check. What is allowed is emptiness and null handling —
	 * `takeIf`, `let` and their relatives hand the value on untouched or not at
	 * all, which is the card deciding *whether* to draw, never *how much*.
	 */
	@Test
	fun `only value-preserving links carry the Snap body`() {
		val links = chainLinks(path().chain)

		assertTrue(
			"the Snap body no longer reaches Text through a recognisable chain; " +
				"re-point this test rather than weakening it",
			links.isNotEmpty(),
		)
		links.forEach {
			assertTrue(
				"`.$it` carries the Snap body into its Text call, and this test " +
					"cannot vouch that it preserves the string. If it genuinely does, " +
					"add it to the allowlist deliberately: $VALUE_PRESERVING",
				it in VALUE_PRESERVING,
			)
		}
	}

	/**
	 * Nor may the body be wrapped on its way into that chain.
	 *
	 * The blind spot a chain scan has by itself: in `shorten(posted.userText)` the
	 * cut happens before any of the text the other tests read.
	 */
	@Test
	fun `the Snap body is not wrapped in a helper call`() {
		assertNull(
			"the Snap body is passed into a call before it is drawn; nothing may " +
				"stand between `$BODY_SOURCE` and the Text that renders it",
			path().wrappedBy,
		)
	}

	/**
	 * The helper that used to do the truncating is gone and must stay gone.
	 *
	 * Checked at its source rather than only on the path, so it cannot come back
	 * as something the card is later tempted to use.
	 */
	@Test
	fun `no display-truncation helper exists to be reintroduced`() {
		listOf("forDisplay", "MAX_DISPLAY_CHARS").forEach {
			assertFalse(
				"`$it` is back in PostedSnap.kt; the Snap body has no display limit",
				pureLayer.contains(it),
			)
			assertFalse("`$it` is being used by $CARD", card.contains(it))
		}
	}

	/**
	 * What the card is handed, stated as data.
	 *
	 * The two shapes the old limit destroyed: far more than eight lines, and a
	 * Snap at the top of the 200-cluster rule whose UTF-16 length is an order of
	 * magnitude larger. Both arrive at the composable whole; the tests above are
	 * what stop the composable cutting them.
	 */
	@Test
	fun `the card is handed the whole Snap`() {
		val lines = (1..20).joinToString("\n") { "line $it" }
		val emoji = "👨‍👩‍👧‍👦".repeat(200)

		listOf(lines, emoji, "   padded   ", "\n\nedged\n\n").forEach { body ->
			val posted = PostedSnap(
				author = "alice",
				permlink = "rustedwax-snap-1000-aaaaaa",
				userText = body,
				createdAtEpochSec = 1_000L,
				fromChain = false,
			)
			assertEquals(body, posted.userText)
		}
		assertTrue("a legal Snap is far longer than the old limit", emoji.length > 2_000)
	}

	private companion object {
		const val CARD = "app/src/main/java/com/rustedwax/app/ui/snaps/PostedSnapCard.kt"

		/** How the body reaches the card. The anchor everything here hangs on. */
		const val BODY_SOURCE = "posted.userText"

		/**
		 * Chain links that hand the string on unchanged, or not at all.
		 *
		 * Deliberately short. Every one of these either returns its receiver
		 * untouched or returns null — none of them can produce a *different*
		 * string, which is the only property this test needs from them.
		 */
		val VALUE_PRESERVING = setOf("takeIf", "takeUnless", "let", "also", "run", "apply")
	}
}
