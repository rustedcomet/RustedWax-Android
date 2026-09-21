package com.rustedwax.app.architecture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The History card's v1.1 presentation, and the one thing it must not leak.
 *
 * Deliberately narrow: spacing, colours and typography are free to move, and
 * nothing here mentions them. What is pinned is the handful of decisions that
 * would be silently undone by a later edit — the count coming from the summary
 * the card already has, the conversation control being the globe, and the
 * transaction id being absent from the screen while still present everywhere
 * it is actually useful.
 */
class HistoryCardPresentationWiringTest {

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

	/**
	 * The transaction id is removed from the card, and from nowhere else.
	 *
	 * This is the assertion worth having twice over: a later "tidy up" that
	 * reads the first half of this rule and not the second would take the id
	 * out of the log line that is the only way to find a scrobble on chain.
	 */
	@Test
	fun `the transaction id leaves the card but not the app`() {
		assertFalse(
			"the History card must not draw a transaction id at a reader who is " +
				"not auditing a chain",
			screen.contains(Regex(""""tx \$""")),
		)
		val runtime = text("app/src/main/java/com/rustedwax/app/scrobble/FinalizationRuntime.kt")
		assertTrue(
			"but the engine must still log it — removing it there would leave a " +
				"confirmed scrobble with nothing to look it up by",
			runtime.contains(Regex("""tx \$\{result\.txId}""")),
		)
	}

	/**
	 * One count, from the summary the card already computed.
	 *
	 * A second source — asking the thread, or counting rows — would be a number
	 * that can disagree with the one the card was already showing.
	 */
	@Test
	fun `the Comments count comes from the card's existing summary`() {
		assertTrue(
			"the count must be read from the preview the card already holds",
			screen.contains(Regex("""threads\.preview\(it\)\?\.total""")),
		)
		assertTrue(
			"bare while nobody has answered, counted once somebody has",
			screen.contains(Regex(""""Comments \(\${'$'}replies\)"[\s\S]{0,20}"Comments"""")),
		)
	}

	/**
	 * Snap keeps the icon it has always had; Comments carries none.
	 *
	 * The mockup gives the conversation control its label and its count and
	 * nothing else, and an icon there competes with the number — which is the
	 * part the reader is actually looking for.
	 */
	@Test
	fun `Snap keeps its icon and Comments has none`() {
		assertTrue(
			"Snap draws the mark from the Snaps mockup",
			screen.contains(
				Regex("""threads\.openComposer\(record\.eventId\)[\s\S]{0,300}?icon = WaxIcons\.SpeechBubble"""),
			),
		)
		assertFalse(
			"the conversation control is label and count only",
			screen.contains(
				Regex("""threads\.open\(it, published\)[\s\S]{0,240}?icon = WaxIcons"""),
			),
		)
	}
}
