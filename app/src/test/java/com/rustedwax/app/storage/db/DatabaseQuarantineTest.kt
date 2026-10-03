package com.rustedwax.app.storage.db

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The quarantine sequence against a real temporary directory: every byte is
 * kept, names are kept, and a move that cannot finish leaves the set whole.
 */
class DatabaseQuarantineTest {

	private lateinit var dir: File
	private lateinit var db: File

	@Before
	fun setUp() {
		dir = Files.createTempDirectory("rw-quarantine").toFile()
		db = File(dir, "rustedwax.db")
	}

	@After
	fun tearDown() {
		dir.deleteRecursively()
	}

	private fun write(file: File, text: String): ByteArray =
		text.toByteArray().also(file::writeBytes)

	/** Every file under [dir], relative path to contents. */
	private fun snapshot(): Map<String, List<Byte>> =
		dir.walkTopDown().filter { it.isFile }
			.associate { it.relativeTo(dir).path to it.readBytes().toList() }

	@Test
	fun `nothing to move when there is no database`() {
		assertEquals(DatabaseQuarantine.Outcome.NothingToMove, DatabaseQuarantine.quarantine(db, 1L))
		assertEquals(emptyList<File>(), DatabaseQuarantine.existing(db))
	}

	@Test
	fun `the whole set moves into one directory with names and bytes intact`() {
		val main = write(db, "corrupt main")
		val wal = write(File(dir, "rustedwax.db-wal"), "wal frames")
		val shm = write(File(dir, "rustedwax.db-shm"), "shm")
		val unrelated = write(File(dir, "other.db"), "someone else")

		val outcome = DatabaseQuarantine.quarantine(db, 1234L)

		outcome as DatabaseQuarantine.Outcome.Quarantined
		assertEquals("rustedwax.db.corrupt-1234", outcome.directory.name)
		assertEquals(listOf("rustedwax.db", "rustedwax.db-wal", "rustedwax.db-shm"), outcome.moved)
		assertArrayEquals(main, File(outcome.directory, "rustedwax.db").readBytes())
		assertArrayEquals(wal, File(outcome.directory, "rustedwax.db-wal").readBytes())
		assertArrayEquals(shm, File(outcome.directory, "rustedwax.db-shm").readBytes())
		assertFalse(db.exists())
		assertFalse(File(dir, "rustedwax.db-wal").exists())
		assertArrayEquals("unrelated files are not touched", unrelated, File(dir, "other.db").readBytes())
		assertEquals(listOf(outcome.directory), DatabaseQuarantine.existing(db))
	}

	@Test
	fun `a second quarantine in the same millisecond gets its own directory`() {
		write(db, "first")
		val first = DatabaseQuarantine.quarantine(db, 7L) as DatabaseQuarantine.Outcome.Quarantined
		write(db, "second")
		val second = DatabaseQuarantine.quarantine(db, 7L) as DatabaseQuarantine.Outcome.Quarantined
		assertEquals("rustedwax.db.corrupt-7-1", second.directory.name)
		assertEquals("first", File(first.directory, "rustedwax.db").readText())
		assertEquals("second", File(second.directory, "rustedwax.db").readText())
	}

	@Test
	fun `a main file that cannot move leaves everything in place`() {
		write(db, "main")
		write(File(dir, "rustedwax.db-wal"), "wal")
		val before = snapshot()

		val outcome = DatabaseQuarantine.quarantine(db, 1L) { _, _ -> false }

		assertTrue(outcome is DatabaseQuarantine.Outcome.Refused)
		assertEquals(before, snapshot())
		assertEquals("no empty quarantine directory left behind", emptyList<File>(), DatabaseQuarantine.existing(db))
	}

	@Test
	fun `a companion that cannot move puts the main file back`() {
		write(db, "main")
		write(File(dir, "rustedwax.db-wal"), "wal")
		write(File(dir, "rustedwax.db-shm"), "shm")
		val before = snapshot()

		val outcome = DatabaseQuarantine.quarantine(db, 1L) { from, to ->
			!from.name.endsWith("-shm") && from.renameTo(to)
		}

		assertTrue(outcome is DatabaseQuarantine.Outcome.Refused)
		assertEquals(before, snapshot())
		assertEquals(emptyList<File>(), DatabaseQuarantine.existing(db))
	}

	@Test
	fun `a set that cannot be put back is reported as stranded, with nothing deleted`() {
		write(db, "main")
		write(File(dir, "rustedwax.db-wal"), "wal")
		val before = snapshot().values.map { it }.toSet()

		val outcome = DatabaseQuarantine.quarantine(db, 1L) { from, to ->
			// The main file goes in and cannot come back out; the log never moves.
			from.parentFile == dir && !from.name.endsWith("-wal") && from.renameTo(to)
		}

		outcome as DatabaseQuarantine.Outcome.Stranded
		assertEquals(listOf("rustedwax.db"), outcome.inQuarantine)
		assertEquals("every byte still exists somewhere", before, snapshot().values.toSet())
		assertTrue(File(dir, "rustedwax.db-wal").exists())
	}
}
