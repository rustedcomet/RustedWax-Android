package com.rustedwax.app.architecture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the Like count is drawn, and where it deliberately is not.
 *
 * The controller's own tests decide what the number *is*; these decide that
 * the screen asks for it in the two places a comment can appear, and that the
 * one place the user cannot Like — their own comment — shows the number
 * without offering an action. That last one is the part worth pinning: an
 * interactive or filled heart on your own Snap would claim you voted for
 * yourself, which Hive refuses and RustedWax has never done.
 *
 * Read from source for the same reason as the composer wiring: these are
 * decisions about which value goes into which call, and a Compose test would
 * need an instrumentation host to assert four lines of argument passing.
 */
class SnapLikeCountWiringTest {

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

	@Test
	fun `the heart is drawn with the count the controller computed`() {
		assertTrue(
			"the number beside a heart must come from likeCount, which is what " +
				"applies the optimistic +1 without double-counting — not from the " +
				"raw chain figure",
			sheet.contains(
				Regex("""count = likes\.likeCount\(target, viewerVote, likeCount\)"""),
			),
		)
	}

	@Test
	fun `both a reply and the root Snap supply a count`() {
		assertTrue(
			"a reply's count comes off the reply it is drawing",
			sheet.contains(Regex("""likeCount = reply\.positiveLikeCount""")),
		)
		assertTrue(
			"and the root Snap's comes off the thread, which kept it when the " +
				"builder dropped the root row",
			sheet.contains(Regex("""likeCount = threads\.thread\(root\)\?\.rootLikeCount \?: 0""")),
		)
	}

	@Test
	fun `your own comment shows the count and no Like action`() {
		assertTrue(
			"the branch with no heart must still draw the social count",
			sheet.contains(
				Regex("""if \(likes\.showsHeart\(author\)\)[\s\S]*?else \{[\s\S]*?SocialLikeCount\(likeCount\)"""),
			),
		)
		val signal = sheet.substringAfter("private fun SocialLikeCount(").substringBefore("\n}")
		assertFalse("it must not be a button", signal.contains("Button") || signal.contains("onClick"))
		assertFalse(
			"and never a filled heart, which would claim you liked yourself",
			signal.contains("HeartFilled"),
		)
		assertTrue("nothing is drawn at zero", signal.contains("if (count <= 0) return"))
	}

	@Test
	fun `a zero count draws no number anywhere`() {
		val heart = sheet.substringAfter("private fun LikeHeart(").substringBefore("\n}")
		assertTrue(
			"the count beside a live heart is conditional too",
			heart.contains(Regex("""if \(count > 0\)""")),
		)
	}

	/**
	 * The count is presentation, and the write path never consults it.
	 *
	 * `like()` is the only thing that can cast a vote; if the aggregate ever
	 * reached it, a number assembled from rows this app deliberately does not
	 * fail closed on would be influencing an irreversible write.
	 */
	@Test
	fun `the Like write path never reads the aggregate`() {
		val liking = stripComments(
			text("app/src/main/java/com/rustedwax/app/ui/snaps/SnapLiking.kt"),
		)
		val castPath = liking.substringAfter("fun like(").substringBefore("fun recheck(")

		assertFalse("like() must not consult a count", castPath.contains("likeCount"))
		assertFalse(castPath.contains("positiveLikeCount"))
		assertFalse(
			"and the count must not be able to send anything",
			liking.substringAfter("fun likeCount(").substringBefore("\n\t}")
				.contains(Regex("""port\(\)|scope\.launch|broadcast|prepare""")),
		)
	}
}
