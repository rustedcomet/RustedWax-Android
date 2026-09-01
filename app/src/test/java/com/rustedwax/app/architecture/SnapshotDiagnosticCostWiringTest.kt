package com.rustedwax.app.architecture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotDiagnosticCostWiringTest {

	private val root: File by lazy {
		generateSequence(File(checkNotNull(System.getProperty("user.dir"))).absoluteFile) {
			it.parentFile
		}.firstOrNull { File(it, "settings.gradle.kts").isFile }
			?: error("repository root was not found")
	}

	private fun text(path: String): String = File(root, path).let { file ->
		assertTrue("production source missing: $path", file.isFile)
		file.readText()
	}

	private fun stripComments(source: String): String = source
		.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
		.lines()
		.joinToString("\n") { it.substringBefore("//") }

	private val probe: String get() = stripComments(
		text("app/src/main/java/com/rustedwax/app/detect/SessionProbe.kt"),
	)

	/** The one assignment that builds the field, with its surrounding expression. */
	private val metadataLinesAssignment: String
		get() {
			val matches = Regex("""metadataLines\s*=\s*[^\n]*""").findAll(probe)
				.map { it.value.trim() }
				.toList()
			assertEquals(
				"SessionProbe should assign metadataLines exactly once; found $matches",
				1,
				matches.size,
			)
			return matches.single()
		}

	@Test
	fun `the snapshot builds the metadata dump only for a finalized track`() {
		assertTrue(
			"snapshot() builds MetadataDump.dump unconditionally, so the diagnostic " +
				"dump is paid on every publish tick as well as at finalization — the " +
				"main-thread cost regression. Assignment was: " +
				metadataLinesAssignment,
			metadataLinesAssignment.contains("finalizedTrack"),
		)
	}

	/**
	 * The negative control: skipping it live must not mean skipping it at all.
	 * `FinalizedTrack.rawLines` is the exported evidence for a finished listen.
	 */
	@Test
	fun `a finalized snapshot still builds the metadata dump`() {
		assertTrue(
			"the finalized path no longer builds a dump, so FinalizedTrack.rawLines " +
				"would be empty for every listen. Assignment was: $metadataLinesAssignment",
			metadataLinesAssignment.contains("MetadataDump.dump"),
		)
	}

	/**
	 * The other half of the boundary: the change must not have been made by
	 * deleting the field or its consumer.
	 */
	@Test
	fun `the finalized track still carries the dump to its consumer`() {
		val finalized = stripComments(
			text("app/src/main/java/com/rustedwax/app/detect/FinalizedTrack.kt"),
		)
		assertTrue(
			"FinalizedTrack no longer reads snapshot.metadataLines",
			finalized.contains("snapshot.metadataLines"),
		)
	}

	/**
	 * The tick path is what made this expensive. If `publish` ever starts asking
	 * for a finalized snapshot, the cost returns under a different name.
	 */
	@Test
	fun `publish builds only live snapshots`() {
		val publish = Regex(
			"""private fun publish\(\)\s*\{.*?\n\t\}""",
			RegexOption.DOT_MATCHES_ALL,
		).find(probe)?.value ?: error("publish() was not found in SessionProbe")

		assertTrue(
			"publish() now requests a finalized snapshot, which reintroduces the " +
				"per-tick dump this rule exists to stop",
			!publish.contains("finalizedTrack"),
		)
	}
}
