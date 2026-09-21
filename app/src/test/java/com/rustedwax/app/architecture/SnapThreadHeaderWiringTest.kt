package com.rustedwax.app.architecture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two Snaps v1.1 Stage 1 decisions that a later change could quietly undo.
 *
 * Both are about the *shell* rather than about anything the conversation says,
 * and neither is a detail of how the header happens to look — a spacing or a
 * typography change is free to move anything this test does not mention.
 *
 * Read from source for the reason the rest of this package is: these are
 * decisions about a composable's shape, this project has no Compose UI or
 * semantics testing available, and adding an instrumentation host for two
 * assertions is not a trade this change gets to make. See
 * `PostedSnapCardPresentationTest` for the same reasoning at more length.
 */
class SnapThreadHeaderWiringTest {

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

	private val sheet: String get() =
		stripComments(text("app/src/main/java/com/rustedwax/app/ui/snaps/SnapThreadSheet.kt"))

	/**
	 * The sheet's height may not be decided by how much has been said.
	 *
	 * Two different versions of that mistake have shipped into this file: a
	 * written-down 560dp ceiling, and then a column that simply wrapped its
	 * content. The first cut a busy thread off on a tall phone; the second drew
	 * a stub for a quiet one and left the composer stranded in mid-screen. A
	 * definite fraction of the screen fixes both and is what keeps History
	 * visible above the sheet.
	 */
	@Test
	fun `the sheet takes a definite height rather than its content's`() {
		assertFalse(
			"a heightIn(max = …) ceiling is what cut long threads off",
			sheet.contains("heightIn("),
		)
		assertTrue(
			"the sheet's content must claim a definite fraction of the screen, " +
				"so the header and the composer have somewhere to stay and only " +
				"the conversation between them scrolls",
			sheet.contains(Regex("""fillMaxHeight\(0\.\d+f\)""")),
		)
	}

	/**
	 * The header is chrome, not a second History card.
	 *
	 * §7 is explicit that the sheet must not duplicate large History-card
	 * metadata, and the card is still on screen above the sheet. An earlier
	 * draft drew the video's thumbnail here and pushed the conversation down for
	 * it; the media is named in text instead.
	 */
	@Test
	fun `the header names the media without drawing it`() {
		assertFalse(
			"no thumbnail in the sheet header: the History card above it is " +
				"already showing one, and this is the space the conversation needs",
			sheet.contains("VideoThumbnail"),
		)
		assertTrue(
			"the sheet still says what it is",
			sheet.contains("\"Comments\""),
		)
	}

	/**
	 * Send captures the target, then reports the slot — in that order.
	 *
	 * The band that says "Replying to @…" is sheet-local state, and the defect
	 * this pins was that nothing ever retired it: after a targeted reply the box
	 * stayed aimed, so the *next* message would have been parented on the
	 * previous comment instead of the root Snap. Wrong parent, never a
	 * duplicate — but wrong permanently, on chain.
	 *
	 * Order is the whole assertion. `threads.send` closes over the target it was
	 * handed; reporting the slot afterwards cannot reparent anything, whereas
	 * clearing the aim first would hand the next composition a different target.
	 */
	@Test
	fun `send hands the target to the controller before reporting the slot`() {
		assertTrue(
			"send must be called with the target this bar was composed with, and " +
				"only then report which slot was sent",
			sheet.contains(
				Regex("""threads\.send\(root, target\)\s*onSent\(key\)"""),
			),
		)
	}

	/**
	 * The aim is dropped at the durable boundary, never on the tap.
	 *
	 * A draft is keyed by the comment it answers. Re-aiming the box at the root
	 * before the attempt is recorded would file the user's words under a comment
	 * the box no longer points at, so a staging failure would read as losing
	 * them. `Optimistic` is one local write after the tap and before any
	 * network — immediate, and safe.
	 */
	@Test
	fun `the replying-to band is retired on a staged status, not on the tap`() {
		assertTrue(
			"the aim is dropped only once the slot reports a staged status",
			sheet.contains(
				Regex(
					"""is SnapPostStatus\.Optimistic, is SnapPostStatus\.Posted ->[\s\S]{0,120}?replyTarget = null""",
				),
			),
		)
		assertTrue(
			"and an outcome that recorded nothing leaves the aim alone, so the " +
				"words come back under the comment they were written for",
			sheet.contains(
				Regex("""is SnapPostStatus\.Failed,[\s\S]{0,160}?-> sentKey = null"""),
			),
		)
	}

	/**
	 * The header has to survive knowing nothing about the media.
	 *
	 * A thread reached from the bell or from an Android notification has no
	 * History row behind it — those routes open a conversation whose card may
	 * have fallen off the list entirely — so the media is genuinely absent
	 * rather than merely late. A non-null parameter here would force the call
	 * site to invent one.
	 */
	@Test
	fun `the thread sheet takes its media context as optional`() {
		assertTrue(
			"SnapThreadSheet must accept a null media context so the bell and " +
				"notification routes, which have no History record, keep working",
			sheet.contains(Regex("""media:\s*SnapThreadMedia\?""")),
		)
	}
}
