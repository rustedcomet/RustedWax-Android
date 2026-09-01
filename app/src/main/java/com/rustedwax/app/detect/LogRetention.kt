package com.rustedwax.app.detect

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
