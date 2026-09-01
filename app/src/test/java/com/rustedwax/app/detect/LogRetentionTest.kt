package com.rustedwax.app.detect

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What an enabled event log is allowed to keep.
 *
 * Measured on the field device on 2026-08-25 before this existed: 22,606,065
 * bytes across 240,631 lines, going back days. The log is a recording of what
 * somebody watched, so an unbounded one is both a diagnostic and a liability,
 * and "twelve hours" is the window a fault is actually diagnosed in.
 */
class LogRetentionTest {

	private val format = SimpleDateFormat(LogRetention.STAMP_PATTERN, Locale.US).apply {
		timeZone = TimeZone.getDefault()
	}

	private var now = 1_787_000_000_000L

	private fun retention(maxBytes: Long = LogRetention.MAX_BYTES) =
		LogRetention(maxBytes = maxBytes, clock = { now })

	private fun line(agoMillis: Long, body: String = "[probe] something happened") =
		"${format.format(java.util.Date(now - agoMillis))}  $body"

	private val hour = 60 * 60 * 1000L

	@Test
	fun `a stamped line reports the instant it was written`() {
		val written = now - 3 * hour
		val parsed = retention().timestampOf(line(3 * hour))
		assertEquals(written / 1000, checkNotNull(parsed) / 1000)
	}

	@Test
	fun `lines inside the window are kept in order`() {
		val kept = listOf(line(11 * hour, "[a] one"), line(hour, "[b] two"), line(0, "[c] three"))
		assertEquals(kept, retention().prune(kept))
	}

	@Test
	fun `lines older than twelve hours are dropped`() {
		val old = line(13 * hour, "[old] yesterday")
		val fresh = line(hour, "[new] today")
		assertEquals(listOf(fresh), retention().prune(listOf(old, fresh)))
	}

	@Test
	fun `the retention window is twelve hours`() {
		assertEquals(12 * hour, LogRetention.RETENTION_MILLIS)
		assertEquals(512L * 1024L, LogRetention.MAX_BYTES)
	}

	/**
	 * A pre-upgrade log is stamped `HH:mm:ss.SSS` with no date at all, so its
	 * age cannot be established — and 240,631 such lines were sitting on the
	 * field device. An age that cannot be proven inside the window fails closed
	 * rather than being kept on the assumption it is recent.
	 */
	@Test
	fun `an undated legacy line has no readable age`() {
		assertNull(retention().timestampOf("21:10:46.534  [health] browser evidence dropped"))
	}

	@Test
	fun `undated legacy lines are dropped rather than assumed fresh`() {
		val legacy = listOf(
			"21:10:46.534  [health] browser evidence dropped",
			"21:10:46.536  [health] shorts evidence dropped",
		)
		val fresh = line(hour, "[new] today")
		assertEquals(listOf(fresh), retention().prune(legacy + fresh))
	}

	@Test
	fun `a log made entirely of legacy lines prunes to nothing without failing`() {
		assertEquals(emptyList<String>(), retention().prune(listOf("21:10:46.534  [x] a")))
	}

	@Test
	fun `a blank or malformed line is dropped rather than crashing the prune`() {
		val fresh = line(hour)
		assertEquals(
			listOf(fresh),
			retention().prune(listOf("", "   ", "not a log line at all", fresh)),
		)
	}

	/**
	 * Twelve hours of a busy session is still far more than 512 KiB — the field
	 * device wrote 22 MB — so the byte cap is the binding constraint most of the
	 * time, and it drops the *oldest* lines, because a fault is diagnosed from
	 * what happened last.
	 */
	@Test
	fun `the byte cap drops the oldest lines first`() {
		val lines = (0 until 40).map { line((40 - it) * 60_000L, "[n] entry $it") }
		val capped = retention(maxBytes = 400).prune(lines)
		assertTrue("nothing survived the cap", capped.isNotEmpty())
		assertTrue("the cap did not bite", capped.size < lines.size)
		assertEquals("the newest line was dropped", lines.last(), capped.last())
		assertTrue(
			"the retained lines are not a suffix of the input",
			lines.takeLast(capped.size) == capped,
		)
	}

	@Test
	fun `the retained bytes never exceed the cap`() {
		val lines = (0 until 200).map { line((200 - it) * 1_000L, "[n] entry $it padding padding") }
		val cap = 2_000L
		val capped = retention(maxBytes = cap).prune(lines)
		val bytes = capped.sumOf { (it.length + 1).toLong() }
		assertTrue("retained $bytes bytes against a $cap cap", bytes <= cap)
	}

	@Test
	fun `a single line longer than the cap does not survive it`() {
		val huge = line(0, "[n] " + "x".repeat(2_000))
		assertEquals(emptyList<String>(), retention(maxBytes = 100).prune(listOf(huge)))
	}

	@Test
	fun `pruning an already pruned log changes nothing`() {
		val lines = (0 until 40).map { line((40 - it) * 60_000L, "[n] entry $it") }
		val once = retention(maxBytes = 400).prune(lines)
		assertEquals(once, retention(maxBytes = 400).prune(once))
	}

	/**
	 * The defect this exists to stop.
	 *
	 * Trimming to *exactly* the ceiling leaves the very next line over it, so the
	 * hard cap re-arms on every single append and a whole-file read, reparse and
	 * rewrite lands in the path of a line written several times a second. The
	 * three documented cadences — 200 lines, 64 KiB, five minutes — never get to
	 * govern anything, because the ceiling fires first, always.
	 *
	 * Measured on the field device on 2026-08-26 with the log sitting at 524,263
	 * bytes across 4,497 lines: 302 skipped frames — five seconds of frozen UI —
	 * at playback start, gone entirely once the retained window was emptied.
	 *
	 * So a prune has to reclaim *headroom*, not merely return to the ceiling.
	 */
	@Test
	fun `pruning reclaims headroom rather than stopping at the ceiling`() {
		val lines = (0 until 400).map { line((400 - it) * 1_000L, "[n] entry $it padding padding") }
		val cap = 4_000L
		val capped = retention(maxBytes = cap).prune(lines)
		val bytes = capped.sumOf { (it.length + 1).toLong() }
		assertTrue("pruning reclaimed nothing at all", capped.isNotEmpty())
		assertTrue(
			"pruning stopped at $bytes of a $cap cap, so the next line re-arms the ceiling",
			bytes <= LogRetention.pruneTargetFor(cap),
		)
	}

	/**
	 * The headroom has to outlast the line cadence, or the ceiling still wins and
	 * the cadence is decorative. A typical line is around 120 bytes, so 128 KiB of
	 * reclaimed room is roughly a thousand — comfortably past both the 200-line
	 * and the 64 KiB bound.
	 */
	@Test
	fun `the prune target sits below the ceiling`() {
		assertEquals(384L * 1024L, LogRetention.PRUNE_TO_BYTES)
		assertTrue(LogRetention.PRUNE_TO_BYTES < LogRetention.MAX_BYTES)
		assertEquals(LogRetention.PRUNE_TO_BYTES, LogRetention.pruneTargetFor(LogRetention.MAX_BYTES))
	}

	@Test
	fun `a line written in the future is not treated as expired`() {
		val ahead = line(-60_000L, "[clock] a second ahead")
		assertFalse(retention().prune(listOf(ahead)).isEmpty())
	}
}
