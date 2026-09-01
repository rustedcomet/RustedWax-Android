package com.rustedwax.app.detect

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Retention where it actually has to hold: the file the Export button sends and
 * the list the Log destination draws, across a restart.
 *
 * The whole point of the bound is that it survives the process ending. A prune
 * that only ran while the app happened to be open would leave the 22 MB file
 * measured on the field device exactly where it was.
 */
class EventLogRetentionTest {

	private lateinit var file: File
	private var now = 1_787_000_000_000L
	private val hour = 60 * 60 * 1000L
	private val stamp = SimpleDateFormat(LogRetention.STAMP_PATTERN, Locale.US)

	private fun at(agoMillis: Long, body: String) =
		"${stamp.format(Date(now - agoMillis))}  $body"

	@Before
	fun setUp() {
		file = File.createTempFile("rustedwax-log", ".txt")
		file.writeText("")
	}

	@After
	fun tearDown() {
		EventLog.attach(null, enabled = false)
		file.delete()
	}

	private fun start(enabled: Boolean = true) = EventLog.attach(file, enabled) { now }

	@Test
	fun `an enabled log writes what it is told`() {
		start()
		EventLog.append("probe", "hello")
		assertTrue(EventLog.lines.value.any { it.endsWith("[probe] hello") })
		assertTrue(file.readText().contains("[probe] hello"))
	}

	/** Every retained line carries the date, or its age cannot be established. */
	@Test
	fun `written lines are dated as well as timed`() {
		start()
		EventLog.append("probe", "hello")
		val line = EventLog.lines.value.last { it.contains("[probe] hello") }
		assertEquals(
			stamp.format(Date(now)).take("yyyy-MM-dd".length),
			line.take("yyyy-MM-dd".length),
		)
	}

	@Test
	fun `starting up prunes what the last run left behind`() {
		file.writeText(
			listOf(
				at(20 * hour, "[old] two nights ago"),
				at(13 * hour, "[old] just outside the window"),
				at(2 * hour, "[recent] inside the window"),
			).joinToString("\n", postfix = "\n"),
		)
		start()

		val retained = file.readLines().filter { it.isNotBlank() }
		assertFalse("an expired line survived startup", retained.any { it.contains("[old]") })
		assertTrue("a retained line was lost", retained.any { it.contains("[recent]") })
		assertTrue(
			"the UI does not show the retained line",
			EventLog.lines.value.any { it.contains("[recent]") },
		)
		assertFalse(
			"the UI shows an expired line",
			EventLog.lines.value.any { it.contains("[old]") },
		)
	}

	/**
	 * The exact shape of the field device's 240,631-line file: no dates at all,
	 * because they were written before the stamp carried one.
	 */
	@Test
	fun `a legacy undated file is pruned safely rather than kept or crashed on`() {
		file.writeText(
			listOf(
				"21:10:46.534  [health] browser evidence dropped",
				"21:10:46.536  [health] shorts evidence dropped",
			).joinToString("\n", postfix = "\n"),
		)
		start()
		assertFalse(file.readText().contains("[health]"))
		assertFalse(EventLog.lines.value.any { it.contains("[health]") })
	}

	@Test
	fun `what a run retains is what the next run starts from`() {
		start()
		EventLog.append("probe", "first run")
		now += 13 * hour
		EventLog.attach(null, enabled = false)

		start()
		assertFalse(
			"a line older than the window came back after a restart",
			EventLog.lines.value.any { it.contains("first run") },
		)
		assertFalse(file.readText().contains("first run"))
	}

	@Test
	fun `a line still inside the window survives a restart`() {
		start()
		EventLog.append("probe", "first run")
		now += 2 * hour
		EventLog.attach(null, enabled = false)

		start()
		assertTrue(
			"a retained line was lost across the restart",
			EventLog.lines.value.any { it.contains("first run") },
		)
		assertTrue(file.readText().contains("first run"))
	}

	/**
	 * Pruning on every append would put a whole-file rewrite in the path of a
	 * line written several times a second. It runs on a bound instead — and the
	 * bound has to actually fire, or the cap is decorative.
	 */
	@Test
	fun `a long run is pruned without being asked`() {
		start()
		repeat(EventLog.PRUNE_EVERY_LINES + 20) { index ->
			now += 60_000L
			EventLog.append("probe", "entry $index")
		}
		val bytes = file.length()
		assertTrue(
			"the file grew to $bytes bytes with no prune",
			bytes <= LogRetention.MAX_BYTES,
		)
		assertTrue(
			"nothing at all was retained",
			EventLog.lines.value.any { it.contains("entry ${EventLog.PRUNE_EVERY_LINES + 19}") },
		)
	}

	/**
	 * The exact state measured on the field device on 2026-08-26: a log file that
	 * is already sitting on the byte ceiling when the process starts.
	 *
	 * Every line is dated an hour ago, so only the byte cap binds and the twelve
	 * hour window has no say in what this test is measuring.
	 */
	private fun fillToCeiling() {
		val body = "[probe] " + "x".repeat(100)
		val width = at(hour, "$body 0").length + 1
		val count = (LogRetention.MAX_BYTES / width).toInt() + 8
		file.writeText(
			(0 until count).joinToString("\n", postfix = "\n") { at(hour - it, "$body $it") },
		)
	}

	/**
	 * A prune that trims to exactly the ceiling leaves the next line over it, so
	 * the ceiling re-arms on every append and the whole file is read, reparsed and
	 * rewritten *per line* — several times a second while something is playing,
	 * on whichever thread wrote the line.
	 *
	 * That thread is usually the main one: this app is a single process, so the
	 * notification listener and the accessibility service deliver on the same
	 * looper the UI draws on. Measured on the field device on 2026-08-26 with the
	 * log at 524,263 bytes across 4,497 lines, 74 of 78 lines written during a
	 * playback start came from the main thread, and the app skipped 302 frames —
	 * five seconds of an unswipeable, unscrollable UI, with playback progress
	 * arriving late and jumping.
	 */
	@Test
	fun `a log already at the byte ceiling does not re-prune for every single line`() {
		fillToCeiling()
		start()

		val oldest = EventLog.lines.value.first()
		val before = EventLog.lines.value.size
		// Twenty lines is around 2.8 KiB: nowhere near the 200-line count, the
		// 64 KiB slack or the five-minute interval, so nothing here is allowed to
		// prune. A trim that stopped at the ceiling re-arms it within a line or
		// two and then evicts on every one of them.
		repeat(APPENDS_WELL_INSIDE_THE_CADENCE) { EventLog.append("probe", "entry $it") }

		assertEquals(
			"$APPENDS_WELL_INSIDE_THE_CADENCE appends inside every cadence evicted " +
				"the oldest retained line, so the whole file was re-pruned per line " +
				"— the main-thread stall that froze the UI for 302 frames on " +
				"2026-08-26",
			oldest,
			EventLog.lines.value.first(),
		)
		assertTrue(
			"the retained window did not grow, so the ceiling is still re-arming",
			EventLog.lines.value.size > before,
		)
	}

	private companion object {
		/** Comfortably inside all three prune cadences, so none of them may fire. */
		const val APPENDS_WELL_INSIDE_THE_CADENCE = 20
	}

	/**
	 * The negative control for the test above: reclaiming headroom must not turn
	 * into not pruning. The cadence still has to fire, and the export promise
	 * still has to hold, on a log that starts at the ceiling.
	 */
	@Test
	fun `a log at the ceiling still prunes on the documented cadence`() {
		fillToCeiling()
		start()

		val oldest = EventLog.lines.value.first()
		repeat(EventLog.PRUNE_EVERY_LINES) { EventLog.append("probe", "entry $it") }

		assertNotEquals(
			"the cadence never fired, so the cap is decorative",
			oldest,
			EventLog.lines.value.first(),
		)
		assertTrue(
			"the export file reached ${file.length()} bytes against the " +
				"${LogRetention.MAX_BYTES}-byte promise",
			file.length() <= LogRetention.MAX_BYTES,
		)
	}

	@Test
	fun `an entry older than the window is gone from the export file`() {
		start()
		EventLog.append("probe", "ancient")
		now += 13 * hour
		repeat(EventLog.PRUNE_EVERY_LINES + 1) { EventLog.append("probe", "modern $it") }
		assertFalse("an expired entry is still exportable", file.readText().contains("ancient"))
	}

	@Test
	fun `a disabled log still writes and retains nothing`() {
		file.writeText(at(hour, "[recent] inside the window") + "\n")
		start(enabled = false)
		EventLog.append("probe", "should not appear")
		assertEquals(emptyList<String>(), EventLog.lines.value)
		assertEquals("", file.readText())
	}

	@Test
	fun `turning logging off erases the log even inside shadow suppression`() {
		start()
		EventLog.append("probe", "sensitive viewing evidence")
		assertTrue(file.readText().contains("sensitive viewing evidence"))

		EventLog.withWritesSuppressed {
			EventLog.applyPolicy(enabled = false)
		}

		assertEquals(emptyList<String>(), EventLog.lines.value)
		assertEquals("", file.readText())
	}

	@Test
	fun `the export file never exceeds the promised byte cap between scheduled prunes`() {
		start()
		var observedBytes = file.length()
		for (index in 0 until 1_000) {
			EventLog.append("probe", "entry $index " + "x".repeat(1_000))
			observedBytes = file.length()
			if (observedBytes > LogRetention.MAX_BYTES) break
		}

		assertTrue(
			"the export file reached $observedBytes bytes against the ${LogRetention.MAX_BYTES}-byte promise",
			observedBytes <= LogRetention.MAX_BYTES,
		)
	}
}
