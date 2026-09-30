package com.rustedwax.app.scrobble

import com.rustedwax.app.detect.EventLog

/**
 * One later reconsideration of the retry queue, for when nothing else will ask.
 *
 * [ConnectivityRetryTrigger] asks the queue the moment a validated network
 * returns. Measured on a Galaxy A36: an offline retry failed and was put back
 * for a minute, the network validated 45 s later, the trigger asked, `due()`
 * correctly answered "nothing yet" — and then nothing asked again. The entry
 * sat owed for over twenty minutes until the app was opened. Connectivity
 * returning *before* an entry's backoff elapsed was the one ordering that left
 * no later caller.
 *
 * This keeps at most one wake-up armed, at the earliest moment an entry the
 * current account can send becomes due, and recomputes it every time the
 * engine touches the queue. It owns no retry policy: the deadline it arms for
 * is `nextAttemptAtMs`, which `BroadcastQueue.recordFailure` alone sets, and
 * when the wake-up fires it calls the same drain every other caller uses, so
 * `due()`, the backoff, the attempt ceiling, the account check and IN_FLIGHT
 * reconciliation all decide exactly as before.
 *
 * Timing and work are split. An inexact alarm ([Alarm]) says *when*; its
 * receiver asks for one expedited job ([Jobs]) that runs the drain. A plain
 * JobScheduler job with a minimum latency was tried first: on the A36 it sat
 * READY, every constraint satisfied, for over forty minutes without being
 * dispatched. An expedited job cannot carry a latency, so the alarm supplies it.
 * Neither is exact; the alarm is a floor and the platform picks the moment.
 *
 * What is armed for, and what is deliberately not:
 *
 * - **Only a deadline in the future.** An entry still due *after* a drain is one
 *   the drain could not progress — an IN_FLIGHT row whose transaction could not
 *   be reconciled, or a missing key. Arming for it would be a hot loop that asks
 *   the chain the same unanswerable question; those keep waiting for
 *   connectivity, the listener connecting, or the app opening, as before.
 * - **Only the current account's entries.** A row for another account is never
 *   sent under this key, so waking for it would be a wake-up that can do nothing.
 * - **Nothing when nothing is owed.** An empty or settled queue cancels.
 */
internal class QueueWakeup(
	private val alarm: Alarm,
	private val jobs: Jobs,
) {

	/**
	 * Timing: one inexact alarm, replaced on every [arm].
	 *
	 * Production is [QueueRetryAlarmReceiver]'s single PendingIntent. The alarm
	 * does no work of its own; it only decides *when* to ask [Jobs] for a run.
	 */
	interface Alarm {
		/** Replace any armed alarm with one about [delayMs] from now; false if refused. */
		fun arm(delayMs: Long): Boolean

		fun disarm()
	}

	/**
	 * Work: the single job that runs the ordinary queue drain.
	 *
	 * Production is [QueueRetryJobService] under one fixed job id. It is asked
	 * for only once something is already due, so it carries no latency — which
	 * is what lets it be expedited — and it keeps the network constraint, so a
	 * deadline that passes offline waits for a network instead of spending an
	 * attempt.
	 */
	interface Jobs {
		/** Whether the job id is already scheduled or running. */
		fun isScheduled(): Boolean

		/** Schedule the expedited run; false when the platform refused it (quota, limits). */
		fun scheduleExpedited(): Boolean

		/** Schedule an ordinary run; false when the platform refused that too. */
		fun scheduleOrdinary(): Boolean

		/** Drop a scheduled run that nothing is owed for any more. */
		fun cancel()
	}

	/** What one alarm delivery did. Logged, and asserted on by the tests. */
	enum class Delivery {
		/** Something is due: the expedited job was scheduled. */
		EXPEDITED,

		/** Expedited was refused; an ordinary job was scheduled instead. It may run late. */
		ORDINARY_FALLBACK,

		/** Neither could be scheduled. Left to connectivity, the listener and the app. */
		FAILED,

		/** The job is already scheduled or running: a duplicate delivery changes nothing. */
		ALREADY_SCHEDULED,

		/** Nothing the current account can send is due: a stale alarm, re-armed or cancelled. */
		NOTHING_DUE,

		/** A wake-up drain is running now; its own end re-arms. */
		RUNNING,
	}

	/** Target of the alarm this object last armed; null when it last disarmed. */
	private var armedAtMs: Long? = null

	/** Unknown until this process has armed or disarmed once — an alarm outlives a process. */
	private var known = false

	/**
	 * Whether a run may be scheduled. True at start: a job, like an alarm, can
	 * outlive the process that scheduled it.
	 */
	private var jobMayBeScheduled = true

	/**
	 * Wake-up drains started and not yet finished, by run token.
	 *
	 * Non-empty while a wake-up's own drain is running. Cancelling or
	 * rescheduling the job id from inside it would make the platform stop the job
	 * doing the work, so every recompute is held until the last [runFinished]
	 * makes it. A token, not a flag: a drain whose job the platform stopped can
	 * still be finishing when a later job starts another, and the first one
	 * ending must not clear the second one's run.
	 */
	private val activeRuns = HashSet<Long>()
	private var lastRun = 0L

	private val running: Boolean get() = activeRuns.isNotEmpty()

	/**
	 * Recompute from the queue as it is now.
	 *
	 * [nextDueAtMs] is evaluated under this object's monitor, so two callers
	 * racing cannot leave the older of their two answers armed.
	 */
	@Synchronized
	fun reconsider(nowMs: Long, nextDueAtMs: () -> Long?, dueNow: () -> Boolean) {
		if (running) return
		// A scheduled run exists for something due. Once nothing is due (sent by
		// another trigger, backed off again, another account) it is stale; while
		// something still is, it is the run that will retry it — keep it.
		if (jobMayBeScheduled && !dueNow()) {
			jobs.cancel()
			jobMayBeScheduled = false
		}
		val target = nextDueAtMs()?.takeIf { it > nowMs }
		if (known && target == armedAtMs) return
		if (target == null) {
			alarm.disarm()
			if (known && armedAtMs != null) {
				EventLog.append("queue", "retry wake-up cancelled — nothing waiting on a backoff")
			}
		} else if (!alarm.arm(target - nowMs)) {
			// Never cached as armed: the next queue change tries again.
			armedAtMs = null
			known = false
			return
		} else {
			EventLog.append(
				"queue",
				"retry wake-up alarm set for ~${(target - nowMs + 999) / 1_000}s from now, when the " +
					"next queued scrobble's backoff elapses (inexact)",
			)
		}
		armedAtMs = target
		known = true
	}

	/**
	 * The alarm went off. No network, no Hive: only decide whether a run is owed
	 * and, if so, ask for one.
	 *
	 * [dueNow] answers "is an entry the current account can send due now";
	 * [nextDueAtMs] is the same planner [reconsider] uses, for a stale alarm.
	 */
	fun alarmFired(nowMs: Long, dueNow: () -> Boolean, nextDueAtMs: () -> Long?): Delivery {
		val delivery = synchronized(this) {
			// The alarm is spent either way.
			armedAtMs = null
			known = true
			when {
				running -> Delivery.RUNNING
				!dueNow() -> Delivery.NOTHING_DUE
				jobs.isScheduled() -> Delivery.ALREADY_SCHEDULED
				jobs.scheduleExpedited() -> Delivery.EXPEDITED
				jobs.scheduleOrdinary() -> Delivery.ORDINARY_FALLBACK
				else -> Delivery.FAILED
			}.also {
				if (it == Delivery.EXPEDITED || it == Delivery.ORDINARY_FALLBACK ||
					it == Delivery.ALREADY_SCHEDULED
				) {
					jobMayBeScheduled = true
				}
			}
		}
		EventLog.append(
			"queue",
			when (delivery) {
				Delivery.EXPEDITED -> "retry wake-up alarm fired — expedited retry job scheduled"
				Delivery.ORDINARY_FALLBACK ->
					"retry wake-up alarm fired — expedited job refused (quota?); ordinary job " +
						"scheduled instead, which the system may run late"
				Delivery.FAILED ->
					"retry wake-up alarm fired — no retry job could be scheduled; the queue waits " +
						"for connectivity, the listener or the app"
				Delivery.ALREADY_SCHEDULED -> "retry wake-up alarm fired — retry job already scheduled"
				Delivery.NOTHING_DUE -> "retry wake-up alarm fired — nothing due for this account"
				Delivery.RUNNING -> "retry wake-up alarm fired — a retry is already running"
			},
		)
		if (delivery == Delivery.NOTHING_DUE) reconsider(nowMs, nextDueAtMs) { false }
		return delivery
	}

	/** The wake-up job started its drain. Returns the token [runFinished] takes. */
	@Synchronized
	fun runStarted(): Long {
		val run = ++lastRun
		activeRuns += run
		// The job that was scheduled is the one running now.
		jobMayBeScheduled = false
		armedAtMs = null
		// Another caller may have armed just before the job started; the end of
		// the run must arm or disarm rather than trust a cache.
		known = false
		return run
	}

	/**
	 * The drain of [run] ended. Arm for whatever the queue now owes next.
	 *
	 * Exactly once per run: a second call for the same token — or one for a
	 * token never started — changes nothing and returns false.
	 */
	fun runFinished(run: Long, nowMs: Long, nextDueAtMs: () -> Long?, dueNow: () -> Boolean): Boolean {
		synchronized(this) {
			if (!activeRuns.remove(run)) return false
		}
		reconsider(nowMs, nextDueAtMs, dueNow)
		return true
	}

	companion object {
		/**
		 * When the earliest entry [account] can send becomes due, if that is
		 * after [nowMs]. Null means there is nothing a wake-up could progress.
		 */
		fun nextDueAtMs(
			pending: List<BroadcastQueue.Entry>,
			account: String?,
			nowMs: Long,
		): Long? {
			if (account.isNullOrEmpty()) return null
			return pending
				.asSequence()
				.filter { it.state != BroadcastQueue.State.SETTLED }
				.filter { it.username.equals(account, ignoreCase = true) }
				.map { it.nextAttemptAtMs }
				.filter { it > nowMs }
				.minOrNull()
		}

		/** Whether an entry [account] can send is due at [nowMs] — what an alarm may act on. */
		fun hasDueEntry(
			pending: List<BroadcastQueue.Entry>,
			account: String?,
			nowMs: Long,
		): Boolean {
			if (account.isNullOrEmpty()) return false
			return pending.any {
				it.state != BroadcastQueue.State.SETTLED &&
					it.username.equals(account, ignoreCase = true) &&
					it.nextAttemptAtMs <= nowMs
			}
		}
	}
}
