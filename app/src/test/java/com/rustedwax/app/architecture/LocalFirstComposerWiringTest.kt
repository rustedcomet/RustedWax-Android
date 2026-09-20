package com.rustedwax.app.architecture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The half of local-first posting that lives in Compose.
 *
 * The controllers are covered by their own tests: those prove the composer is
 * *told* to close at the durable intent, that the reply is in the thread before
 * a node is asked, and that nothing is destroyed before Hive confirms. What no
 * controller test can see is the wiring on the other end of those callbacks —
 * which lambda closes the box, which one throws the draft away, and whether a
 * card that has just been posted still wears a "Draft" badge.
 *
 * Read from source deliberately. A Compose test would need an instrumentation
 * host for what is, in the end, four decisions about which call sits in which
 * lambda; these assert exactly those and nothing about how the screen looks.
 */
class LocalFirstComposerWiringTest {

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

	private fun stripComments(source: String): String = source
		.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
		.lines()
		.joinToString("\n") { it.substringBefore("//") }

	private val screen: String get() =
		stripComments(text("app/src/main/java/com/rustedwax/app/ui/MainScreen.kt"))

	private val sheet: String get() =
		stripComments(text("app/src/main/java/com/rustedwax/app/ui/snaps/SnapThreadSheet.kt"))

	// ── the root Snap composer ─────────────────────────────────────────

	@Test
	fun `the Snap composer closes on the durable intent`() {
		assertTrue(
			"the box must collapse from onStaged — the callback that fires at the " +
				"durable intent, before any network call. Closing it from " +
				"onPublished is the original bug: the card appears instantly and " +
				"the text box sits open above it for the whole background pipeline",
			screen.contains(Regex("""onStaged\s*=\s*\{\s*snaps\.collapse\(\)\s*}""")),
		)
	}

	@Test
	fun `the draft is destroyed only once Hive has confirmed`() {
		assertTrue(
			"snaps.discard must stay inside onPublished. The draft is the only " +
				"copy of the words that is not tied to the attempt, so it may not " +
				"be thrown away on an optimistic boundary",
			screen.contains(
				Regex("""onPublished\s*=\s*\{[^}]*snaps\.discard\(key\)""", RegexOption.DOT_MATCHES_ALL),
			),
		)
		assertFalse(
			"collapse must not discard: closing a composer and destroying a draft " +
				"are different acts, and only the second waits for the chain",
			screen.contains(Regex("""onStaged\s*=\s*\{[^}]*discard""")),
		)
	}

	/**
	 * Retry is a success path too, and the screen wires it as one.
	 *
	 * The A12 run posted offline, tapped Retry once the network was back, and
	 * published exactly once — leaving the draft on disk, because this call
	 * site passed no success callback at all. Pinned here because the callback
	 * is invisible in behaviour until a Snap is actually retried.
	 */
	@Test
	fun `a retried Snap discards its draft on confirmation`() {
		assertTrue(
			"Retry must be given the same onPublished discard the first attempt gets",
			screen.contains(
				Regex(
					"""posts\.retry\(key, record\.eventId\)\s*\{[^}]*snaps\.discard\(key\)""",
					RegexOption.DOT_MATCHES_ALL,
				),
			),
		)
	}

	@Test
	fun `neither composer narrates a wait`() {
		assertFalse(
			"a \"Posting…\" label puts the network back on the visible path; the " +
				"box is gone before it could be read",
			screen.contains("Posting…"),
		)
		assertFalse(
			"same for replies: the composer closes on the durable intent and the " +
				"reply is already in the thread",
			sheet.contains("Sending…"),
		)
	}

	@Test
	fun `a card that exists never wears a Draft badge`() {
		assertTrue(
			"the draft stays on disk until the chain confirms, which is a detail " +
				"of recovery — a \"Draft\" badge beside a Snap the user just posted " +
				"says the opposite of what happened",
			screen.contains(Regex("""val hasCard = published != null""")) &&
				screen.contains(Regex("""if \(!open && !hasCard && draft\.isNotEmpty\(\)\)""")),
		)
	}

	// ── the reply composer ─────────────────────────────────────────────

	@Test
	fun `an unfinished reply is offered a finish, and an ambiguous one is not`() {
		assertTrue(
			"an interrupted reply was never built, so no transaction exists and " +
				"nothing can be duplicated by finishing it",
			sheet.contains(Regex("""status is SnapPostStatus\.Interrupted""")) &&
				sheet.contains("Finish reply"),
		)
		assertTrue(
			"an ambiguous reply may only be read: sending again could duplicate a " +
				"live comment",
			sheet.contains(Regex("""status is SnapPostStatus\.Uncertain""")) &&
				sheet.contains("Check again"),
		)
		assertFalse(
			"and it is never offered a resend",
			sheet.contains(Regex("""SnapPostStatus\.Uncertain[^}]*threads\.send\(""")),
		)
	}
}
