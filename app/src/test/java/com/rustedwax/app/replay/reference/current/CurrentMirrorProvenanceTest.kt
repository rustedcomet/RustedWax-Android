package com.rustedwax.app.replay.reference.current

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mirror is the shipping implementation, and this is what makes that
 * checkable.
 *
 * The current side must remain identical to whatever ships now. A mirror that
 * lagged production by one edit would let a regression
 * land in `detect/SessionProbe.kt` while the parity suite went on comparing the
 * version that did not have it.
 *
 * So the comparison is against the live file rather than a resource copy, and it
 * runs on every build. Hand-editing the mirror to make a parity test pass fails
 * here first, which is the only property that matters.
 */
class CurrentMirrorProvenanceTest {

	/** Unit tests run with the module directory as their working directory. */
	private fun read(path: String): String {
		val file = java.io.File(path)
		assertTrue("not found at ${file.absolutePath}", file.isFile)
		return file.readText()
	}

	private fun production() = read("src/main/java/com/rustedwax/app/detect/SessionProbe.kt")

	private fun mirror() =
		read("src/test/java/com/rustedwax/app/replay/reference/current/SessionProbe.kt")

	private fun carryProduction() =
		read("src/main/java/com/rustedwax/app/detect/TrackProgressCarry.kt")

	private fun carryMirror() =
		read("src/test/java/com/rustedwax/app/replay/reference/current/TrackProgressCarry.kt")

	/**
	 * Both sides are cut at the same landmark — the file's first KDoc block,
	 * which is the first line of its body in each case. Anchoring on a line number
	 * would make this a statement about the header's length instead.
	 */
	private fun bodyOf(text: String): List<String> {
		val lines = text.lines()
		val start = lines.indexOfFirst { it.startsWith("/**") }
		assertTrue("no declaration found — header not recognised", start > 0)
		return lines.drop(start)
	}

	@Test
	fun `mirror body is byte-identical to the shipping SessionProbe`() {
		assertEquals(
			"the parity mirror has drifted from detect/SessionProbe.kt — " +
				"re-run tools/phase03/generate-current-mirror.sh",
			bodyOf(production()),
			bodyOf(mirror()),
		)
	}

	@Test
	fun `carry mirror body is byte-identical to shipping TrackProgressCarry`() {
		fun body(text: String): List<String> {
			val lines = text.lines()
			val start = lines.indexOfFirst { it.startsWith("object TrackProgressCarry") }
			assertTrue("TrackProgressCarry declaration not found", start > 0)
			return lines.drop(start)
		}
		assertEquals(
			"the current mirror's carry implementation drifted from production",
			body(carryProduction()),
			body(carryMirror()),
		)
	}

	@Test
	fun `mirror header only moves the package and swaps the android imports`() {
		val header = mirror().lines().takeWhile { !it.startsWith("/**") }
		assertEquals(
			"package com.rustedwax.app.replay.reference.current",
			header.first(),
		)
		assertFalse(
			"an android import survived the transform",
			header.any { it.startsWith("import android.") },
		)
		val standIns = header.filter {
			it.startsWith("import com.rustedwax.app.replay.reference.phase01.")
		}
		assertEquals(
			"expected the nine platform stand-ins, virtual wall clock and two shadowed helpers",
			12,
			standIns.size,
		)
	}

	/**
	 * The body comparison is only meaningful if it can fail.
	 *
	 * [bodyOf] cuts at the first KDoc block; a cut that produced an empty list on
	 * both sides would make the test above pass against any file at all.
	 */
	@Test
	fun `body extraction retains the implementation it claims to compare`() {
		val body = bodyOf(mirror())
		assertTrue("mirror body implausibly short: ${body.size} lines", body.size > 2_000)
		assertTrue(
			"the extracted body is not the Android session registry",
			body.any { it.contains("private inner class AndroidSessionBinding(") },
		)
		assertTrue(
			"the extracted body does not delegate to the reducer driver",
			body.any { it.contains("private val driver = MediaSessionDriver(") },
		)
	}

	/**
	 * The mirror is a test fixture and must never be shipped.
	 *
	 * Two `SessionProbe`s in one application would be a live hazard; the package
	 * move is what prevents it, and the source set is what proves it.
	 */
	@Test
	fun `the mirror lives only in the test source set`() {
		assertFalse(
			"a mirror exists under src/main",
			java.io.File("src/main/java/com/rustedwax/app/replay").exists(),
		)
	}
}
