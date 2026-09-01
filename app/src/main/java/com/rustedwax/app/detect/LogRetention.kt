package com.rustedwax.app.detect

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What an enabled event log is allowed to keep.
 *
 * ## Why a bound exists at all
 *
 * The log had none. Measured on the field device on 2026-08-25, before this
 * existed: **22,606,065 bytes across 240,631 lines**, reaching back days. Two
 * separate problems, and the second is the serious one:
 *
 *  - a 22 MB file is not diagnosable — the fault someone is looking for is in
 *    the last few minutes, and it is buried under a week of `foreground root was
 *    hidden` at one line every thirty seconds; and
 *  - the log is a **recording of what somebody watched**. An unbounded one is a
 *    permanent, exportable, plain-text viewing history sitting in the app's
 *    private storage, growing forever, for as long as the switch stays on.
 *
 * Twelve hours is the window a fault is actually diagnosed in — a session is
 * exported the same day it went wrong — and 512 KiB is what a busy twelve hours
 * of that log costs. Whichever binds first wins.
 *
 * ## Why an unreadable age is dropped rather than kept
 *
 * Lines written before this existed are stamped `HH:mm:ss.SSS` with no date, so
 * their age cannot be established at all. "At most twelve hours" is a promise
 * about the file, and a line whose age is unknown cannot be shown to keep it —
 * so it fails closed, the same direction every other unproven thing in this app
 * fails. The cost is that the first launch after upgrading discards a legacy
 * log. That is stated in the release notes rather than hidden: the alternative
 * is a retention bound that quietly does not apply to the 22 MB already there.
 *
 * Pure and clock-injected, so the whole policy is decidable without waiting
 * twelve hours or owning an Android `Context`.
 */
class LogRetention(
	private val retentionMillis: Long = RETENTION_MILLIS,
	private val maxBytes: Long = MAX_BYTES,
	private val clock: () -> Long = System::currentTimeMillis,
) {

	/**
	 * What a prune trims down to — see [PRUNE_TO_BYTES] for why this is below
	 * [maxBytes] rather than equal to it.
	 *
	 * Derived rather than passed so a caller that narrows [maxBytes] narrows the
	 * target with it, and so no caller can construct the degenerate policy where
	 * the two are the same number.
	 */
	private val pruneToBytes = pruneTargetFor(maxBytes)

	/**
	 * `SimpleDateFormat` is not thread-safe and the log is written from the
	 * listener service, the activity and the finalization scope.
	 */
	private val parser = ThreadLocal.withInitial {
		SimpleDateFormat(STAMP_PATTERN, Locale.US).apply { isLenient = false }
	}

	/**
	 * When the line was written, or null when that cannot be established.
	 *
	 * The shape is checked before the parse rather than left to
	 * `SimpleDateFormat`, which will happily read `21:10:46.534` as year 21 under
	 * some settings — and a legacy line silently dated to the year 21 is a line
	 * that is always outside the window, which happens to be right today and
	 * would be wrong the moment the format changed again.
	 */
	fun timestampOf(line: String): Long? {
		if (line.length < STAMP_LENGTH) return null
		val head = line.take(STAMP_LENGTH)
		if (!STAMP_SHAPE.matches(head)) return null
		return runCatching { checkNotNull(parser.get()).parse(head) }.getOrNull()?.time
	}

	fun prune(lines: List<String>): List<String> = prune(lines.asSequence())

	/**
	 * Streaming, because the input may be the 22 MB file this was written for
	 * and reading it whole to throw all of it away would be the one moment the
	 * retention policy caused the problem it exists to prevent.
	 *
	 * Peak memory is the cap, not the file: the deque never holds more than
	 * [maxBytes] worth of lines at any point in the pass.
	 */
	fun prune(lines: Sequence<String>): List<String> {
		val cutoff = clock() - retentionMillis
		val kept = ArrayDeque<String>()
		var bytes = 0L
		lines.forEach { line ->
			val writtenAt = timestampOf(line) ?: return@forEach
			if (writtenAt < cutoff) return@forEach
			val size = sizeOf(line)
			// A single line that cannot fit is dropped on its own. Clearing what
			// is already retained would let one oversized line erase a whole
			// window of good evidence. Measured against the trim target rather
			// than the ceiling: a line too big for the target could never be
			// retained past this prune anyway, and letting it in would empty the
			// deque below and then ask it for one more.
			if (size > pruneToBytes) return@forEach
			kept.addLast(line)
			bytes += size
			// The target, not the ceiling. Stopping at the ceiling leaves the very
			// next line over it, which re-arms the hard cap in
			// [com.rustedwax.app.detect.EventLog] on every single append and puts
			// a whole-file rewrite in the path of a line written several times a
			// second. See [PRUNE_TO_BYTES].
			while (bytes > pruneToBytes) bytes -= sizeOf(kept.removeFirst())
		}
		return kept.toList()
	}

	/** What the line costs on disk, newline included. */
	private fun sizeOf(line: String): Long = line.toByteArray(Charsets.UTF_8).size.toLong() + 1

	companion object {
		/** Long enough to hold the session someone is about to export. */
		const val RETENTION_MILLIS = 12 * 60 * 60 * 1000L

		/** What a busy twelve hours of this log costs, rounded to a page count. */
		const val MAX_BYTES = 512L * 1024L

		/**
		 * What a prune trims down to, below [MAX_BYTES].
		 *
		 * A prune has to reclaim **headroom**, not merely return to the ceiling.
		 * Trimming to exactly [MAX_BYTES] leaves the very next line over it, so the
		 * hard cap re-arms on every single append and a whole-file read, reparse
		 * and rewrite lands in the path of a line written several times a second —
		 * on whichever thread wrote it, which in this single-process app is
		 * usually the main one. The 200-line, 64 KiB and five-minute cadences in
		 * [com.rustedwax.app.detect.EventLog] never get to govern anything,
		 * because the ceiling fires first, always.
		 *
		 * Measured on the field device on 2026-08-26 with the log sitting at
		 * 524,263 bytes across 4,497 lines: 302 skipped frames at playback start —
		 * five seconds of frozen UI — and none at all once the window was emptied.
		 *
		 * 128 KiB of reclaimed room is roughly a thousand typical lines, which
		 * outlasts both the line and the byte cadence, so the cadence governs
		 * pruning and the ceiling goes back to being the promise it was written to
		 * be rather than the thing doing the work.
		 */
		const val PRUNE_TO_BYTES = 384L * 1024L

		/**
		 * The same three-quarters rule for any cap, so a caller that narrows
		 * [maxBytes] — every test that does — narrows the target with it rather
		 * than silently keeping the 384 KiB default above its own ceiling.
		 */
		fun pruneTargetFor(maxBytes: Long): Long = maxBytes / 4L * 3L

		/**
		 * Dated as well as timed.
		 *
		 * The old stamp was `HH:mm:ss.SSS`, which is why every line already on a
		 * device has an age nobody can establish — see the class note.
		 */
		const val STAMP_PATTERN = "yyyy-MM-dd HH:mm:ss.SSS"

		private const val STAMP_LENGTH = 23

		private val STAMP_SHAPE = Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}""")

		/** A line written by [Date] and this pattern, for callers building one. */
		fun stamp(millis: Long): String =
			SimpleDateFormat(STAMP_PATTERN, Locale.US).format(Date(millis))
	}
}
