package com.rustedwax.app.detect

import android.content.Context
import android.util.Log
import com.rustedwax.app.storage.Settings
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Append-only event log — every detection and scrobble decision, in order.
 *
 * Everything the probe observes lands here so the run can be exported off the
 * device and diffed against what the extension's connectors produce for the
 * same content. One retained window, held in memory for the UI and on disk for
 * export — see [LogRetention] for what "retained" means and why the bound
 * exists.
 *
 * Deliberately unstructured — this is a bounded diagnostic record, not a
 * production decision surface. No control flow may depend on its text.
 */
object EventLog {

	private const val TAG = "RustedWax"
	const val FILE_NAME = "rustedwax-log.txt"

	/** Where the pruned file is staged before it replaces the real one. */
	private const val PRUNING_SUFFIX = ".pruning"

	/**
	 * How many appends may pass between prunes.
	 *
	 * Pruning rewrites the whole file, and [append] is on the path of a line
	 * written several times a second while a Short is on screen — so routine
	 * pruning runs on a cadence rather than per line. Line, byte and time cadence
	 * bounds coexist with the hard retained-size ceiling. The hard ceiling keeps
	 * the export promise exact; the time cadence prunes an app left open
	 * overnight with nothing playing.
	 */
	const val PRUNE_EVERY_LINES = 200

	/** Byte cadence for routine pruning below the hard retained-size ceiling. */
	const val PRUNE_BYTE_SLACK = 64L * 1024L

	/** An idle app still prunes, so "twelve hours" is true of a long session. */
	const val PRUNE_INTERVAL_MILLIS = 5 * 60 * 1000L

	private val stamp = SimpleDateFormat(LogRetention.STAMP_PATTERN, Locale.US)

	private val _lines = MutableStateFlow<List<String>>(emptyList())
	val lines: StateFlow<List<String>> = _lines.asStateFlow()

	@Volatile
	private var file: File? = null

	/**
	 * One worker owns production log state and disk I/O.
	 *
	 * Android delivers notification-listener and accessibility callbacks on the
	 * same main looper that Compose uses. A full retained-file read and atomic
	 * rewrite on that caller froze the measured device for several seconds. A
	 * single worker preserves append/prune/clear ordering without making the UI
	 * wait for storage.
	 */
	private val diskExecutor = Executors.newSingleThreadExecutor { task ->
		Thread(task, "rustedwax-event-log").apply { priority = Thread.MIN_PRIORITY }
	}

	/** Tests use [attach] synchronously; production [init] always enables the worker. */
	@Volatile
	private var asynchronousDisk = false

	/** Invalidates queued writes when policy, target, or an explicit clear changes. */
	@Volatile
	private var generation = 0L

	private fun enqueueDisk(block: () -> Unit) {
		if (asynchronousDisk) diskExecutor.execute(block) else block()
	}

	/**
	 * Whether anything at all may be written — `<redacted-private-path>` §4.2.
	 *
	 * Off means *off*: no file, no ring buffer, no `Log.d`. Not "hidden from the
	 * UI while still accumulating", because a log that exists can be exported,
	 * read by anyone holding the phone, or handed over intact. A switch that
	 * quietly keeps writing is worse than one that was never offered.
	 *
	 * Starts **off**. A fresh install does not log until asked, and a process
	 * rebuilt before [init] has run must not write anything in the gap.
	 */
	@Volatile
	private var loggingEnabled: Boolean = false

	/**
	 * How many shadow runs are in progress *on this thread*.
	 *
	 * Shadow execution is the only caller. The log is a durable, exportable
	 * record of what the app *did*; a run that is deliberately doing nothing has
	 * no business in it, and a shadow line that reached the export would be
	 * indistinguishable from a real one to whoever read it afterwards.
	 *
	 * A count rather than a flag so nesting cannot end suppression early.
	 *
	 * ## Why thread-scoped, and why that is not enough on its own
	 *
	 * This was a global counter, for a stated reason: a finalization straddles a
	 * dispatcher and resumes on another thread, so a thread-scoped switch would
	 * be a shadow run only as far as its first suspension point. That reasoning
	 * was right about the hazard and wrong about the fix. Global suppression has
	 * a worse failure: a shadow run silences the log for *every* concurrent live
	 * finalization, so the cure deletes real evidence.
	 *
	 * Worse, it did not actually hold. Finalization launches its
	 * remaining work on the engine scope; `withWritesSuppressed` returned as soon
	 * as `launch` did, and suppression ended before the `Dispatchers.IO` half ran
	 * at all. The replay tests dispatched inline, so they never saw it.
	 *
	 * So: thread-scoped state, carried across dispatcher hops by [shadowContext]
	 * as a coroutine context element. Suppression now follows exactly the work it
	 * belongs to — the whole of it, including everything awaited — and follows
	 * nothing else.
	 */
	private val suppressionDepth = ThreadLocal.withInitial { 0 }

	private fun beginSuppression() {
		suppressionDepth.set((suppressionDepth.get() ?: 0) + 1)
	}

	private fun endSuppression() {
		val current = suppressionDepth.get() ?: 0
		if (current > 0) suppressionDepth.set(current - 1)
	}

	/** Run [block] with every write from this thread discarded. */
	fun <T> withWritesSuppressed(block: () -> T): T {
		beginSuppression()
		return try {
			block()
		} finally {
			endSuppression()
		}
	}

	/**
	 * Suppression that survives a dispatcher hop.
	 *
	 * Launch the asynchronous half of a shadow finalization with this in its
	 * context and every thread the coroutine resumes on inherits the switch,
	 * including work awaited on `Dispatchers.IO`. Without it, a shadow run is
	 * silent up to its first suspension and writes to the real log afterwards.
	 */
	fun shadowContext(): CoroutineContext = suppressionDepth.asContextElement(1)

	/** Whether a line written now would be kept. */
	private fun writing(): Boolean = loggingEnabled && (suppressionDepth.get() ?: 0) == 0

	/**
	 * Apply the user's choice.
	 *
	 * One switch, not two: the temporary diagnostic mode that used to override
	 * this is gone. It answered the same question in a second place, and a
	 * second answer over one file is how somebody ends up certain they turned
	 * logging off while the log keeps growing.
	 *
	 * Turning writing off **erases what was already written**. The setting says
	 * nothing is being recorded, and leaving yesterday's log on disk would make
	 * that false the moment anyone looked.
	 */
	fun applyPolicy(enabled: Boolean) {
		val clearTarget = synchronized(this) {
			if (loggingEnabled == enabled) return
			loggingEnabled = enabled
			generation++
			if (enabled) return
			_lines.value = emptyList()
			retainedBytes = 0
			sinceLastPrune = 0
			bytesSinceLastPrune = 0
			file
		}
		// The policy takes effect above before this is queued. Earlier writes run
		// before the erase and later writes are rejected while the switch is off.
		enqueueDisk { runCatching { clearTarget?.writeText("") } }
	}

	/**
	 * Reads the user's logging choice itself rather than waiting to be told.
	 *
	 * Every entry point into the app calls this — the activity, the listener
	 * service, the sign-in screen — and a policy that had to be pushed in
	 * afterwards would leave whichever one forgot writing a full log. The switch
	 * has to be the first thing that happens, not the second.
	 */
	fun init(context: Context) {
		val target = File(context.filesDir, FILE_NAME)
		val enabled = Settings(context).eventLogging
		val start = synchronized(this) {
			if (asynchronousDisk && file?.absolutePath == target.absolutePath &&
				loggingEnabled == enabled
			) return

			asynchronousDisk = true
			generation++
			clock = System::currentTimeMillis
			retention = LogRetention(clock = clock)
			file = target
			loggingEnabled = enabled
			_lines.value = emptyList()
			retainedBytes = 0
			sinceLastPrune = 0
			bytesSinceLastPrune = 0
			lastPrunedAt = clock()
			generation
		}

		if (!enabled) {
			enqueueDisk { runCatching { target.writeText("") } }
			return
		}

		// Startup retention used to run inline before setContent and was measured
		// skipping 239 frames. It is the first queued operation, so later appends
		// still land after the retained previous session in exact order.
		enqueueDisk {
			val retained = readRetained(target, retention)
			replaceAtomically(target, retained)
			synchronized(this) {
				if (generation == start && loggingEnabled) {
					_lines.value = retained
					retainedBytes = retained.sumOf(::lineBytes)
					lastPrunedAt = clock()
				}
			}
		}
		append("app", "--- session start ${Date(clock())} ---")
	}

	/**
	 * The context-free half of [init], and where retention is enforced.
	 *
	 * A bound that only held while the app happened to be running would leave
	 * the file exactly as the last run left it — which is how 22 MB accumulated
	 * in the first place. So startup is a prune: what the previous run wrote is
	 * read, filtered to the retained window, and written back before a single
	 * new line is added. The in-memory list the UI draws is seeded from the same
	 * retained set, so the screen and the export agree by construction rather
	 * than by two code paths being kept in step.
	 */
	@Synchronized
	internal fun attach(
		target: File?,
		enabled: Boolean,
		now: () -> Long = System::currentTimeMillis,
	) {
		asynchronousDisk = false
		generation++
		clock = now
		retention = LogRetention(clock = now)
		file = target

		// Not `applyPolicy`: that erases only on a *transition* to off, and an
		// install whose switch was already off must not inherit a file written
		// by the run before it. Off means the file is empty, unconditionally.
		loggingEnabled = enabled
		if (!enabled) {
			_lines.value = emptyList()
			runCatching { target?.writeText("") }
			retainedBytes = 0
			sinceLastPrune = 0
			bytesSinceLastPrune = 0
			return
		}

		val retained = readRetained(target)
		_lines.value = retained
		replaceAtomically(target, retained)
		retainedBytes = retained.sumOf(::lineBytes)
		sinceLastPrune = 0
		bytesSinceLastPrune = 0
		lastPrunedAt = clock()
		append("app", "--- session start ${Date(clock())} ---")
	}

	/** Where "now" comes from, so retention can be tested without waiting. */
	@Volatile
	private var clock: () -> Long = System::currentTimeMillis

	@Volatile
	private var retention = LogRetention()

	private var sinceLastPrune = 0
	private var bytesSinceLastPrune = 0L
	private var retainedBytes = 0L
	private var lastPrunedAt = 0L

	private fun lineBytes(line: String): Long =
		line.toByteArray(Charsets.UTF_8).size.toLong() + 1

	fun append(tag: String, message: String) {
		if (!writing()) return
		// Capture order and policy on the caller, but do not format or talk to
		// logd there. Accessibility and MediaSession callbacks normally arrive on
		// the same main looper Compose draws on; an end-of-track metadata dump can
		// contain dozens of lines and synchronous Log.d calls starved that looper.
		val observedAt = clock()
		val queuedGeneration = generation
		enqueueDisk {
			val line = synchronized(stamp) {
				"${stamp.format(Date(observedAt))}  [$tag] $message"
			}
			synchronized(this) {
				if (generation != queuedGeneration || !loggingEnabled) return@enqueueDisk
				// Keep the privacy ordering exact: applyPolicy(false) takes the same
				// lock, so no logcat line can slip out after the off transition.
				Log.d(TAG, line)
			}
			appendOnDisk(line, queuedGeneration)
		}
	}

	private fun appendOnDisk(line: String, queuedGeneration: Long) {
		val target: File
		val shouldPrune: Boolean
		synchronized(this) {
			if (generation != queuedGeneration || !loggingEnabled) return
			_lines.value = _lines.value + line
			target = file ?: return
			sinceLastPrune++
			val addedBytes = lineBytes(line)
			bytesSinceLastPrune += addedBytes
			retainedBytes += addedBytes
			shouldPrune = retainedBytes > LogRetention.MAX_BYTES ||
				sinceLastPrune >= PRUNE_EVERY_LINES ||
				bytesSinceLastPrune >= PRUNE_BYTE_SLACK ||
				clock() - lastPrunedAt >= PRUNE_INTERVAL_MILLIS
		}

		// Both operations are on the serial worker. No second append can overtake
		// the rewrite, and none of this storage work runs on Android's UI looper.
		runCatching { target.appendText(line + "\n") }
		if (shouldPrune) pruneOnDisk(target, queuedGeneration)
	}

	/**
	 * Bring the file and the list back inside the retained window.
	 *
	 * Reads from disk rather than from [_lines] when there is a file, because
	 * the file is the thing being bounded and the export sends it.
	 */
	private fun pruneOnDisk(target: File?, queuedGeneration: Long) {
		val policy = retention
		val retained = if (target != null && target.isFile) {
			readRetained(target, policy)
		} else {
			policy.prune(_lines.value)
		}
		replaceAtomically(target, retained)
		synchronized(this) {
			if (generation != queuedGeneration || !loggingEnabled) return
			_lines.value = retained
			retainedBytes = retained.sumOf(::lineBytes)
			sinceLastPrune = 0
			bytesSinceLastPrune = 0
			lastPrunedAt = clock()
		}
	}

	private fun readRetained(target: File?, policy: LogRetention = retention): List<String> {
		if (target == null || !target.isFile) return emptyList()
		return runCatching { target.useLines { policy.prune(it) } }.getOrDefault(emptyList())
	}

	/**
	 * Replace the log in one step.
	 *
	 * A truncate-then-rewrite leaves a window where the file is empty or half
	 * written, and the process can be killed in it — Android stops a listener
	 * service whenever it likes. Staging beside the real file and renaming means
	 * a reader either sees the whole old log or the whole new one.
	 */
	private fun replaceAtomically(target: File?, lines: List<String>) {
		if (target == null) return
		runCatching {
			val staged = File(target.parentFile, target.name + PRUNING_SUFFIX)
			staged.writeText(if (lines.isEmpty()) "" else lines.joinToString("\n", postfix = "\n"))
			if (!staged.renameTo(target)) {
				// Same-directory rename should not fail; if the platform refuses,
				// the copy is still better than leaving the unbounded file.
				target.writeText(staged.readText())
				staged.delete()
			}
		}
	}

	/** Multi-line block, indented so metadata dumps stay readable. */
	fun appendBlock(tag: String, title: String, body: List<String>) {
		append(tag, title)
		body.forEach { append(tag, "    $it") }
	}

	fun clear() {
		val clearTarget = synchronized(this) {
			generation++
			_lines.value = emptyList()
			retainedBytes = 0
			sinceLastPrune = 0
			bytesSinceLastPrune = 0
			file
		}
		enqueueDisk { runCatching { clearTarget?.writeText("") } }
	}

	fun logFile(): File? = file
}
