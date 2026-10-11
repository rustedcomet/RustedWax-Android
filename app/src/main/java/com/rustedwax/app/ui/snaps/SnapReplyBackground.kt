package com.rustedwax.app.ui.snaps

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.Log
import com.rustedwax.app.BuildConfig
import com.rustedwax.app.MainActivity
import com.rustedwax.app.detect.EventLog
import com.rustedwax.app.scrobble.JobRunGate
import com.rustedwax.app.snaps.HiveSnapThreadReader
import com.rustedwax.app.snaps.SharedPreferencesPendingSnapStore
import com.rustedwax.app.storage.KeyVault
import com.rustedwax.app.storage.db.LocalDatabase
import com.rustedwax.app.storage.db.LocalOwner
import kotlin.concurrent.thread
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Issue #42 Stage 2: the Stage 1 reply check, run by Android's job scheduler
 * while RustedWax is not on screen.
 *
 * Nothing here decides who is notified, what is new, which conversations are
 * read or how many: that is all [SnapReplyCheck], built once by [SnapReplyChecks]
 * for both the foreground and this job. This file adds only *when* — one
 * periodic job, at Android's fifteen-minute floor, with a network constraint,
 * persisted across a reboot — and *for whom*: the account signed in at the
 * moment the job runs, read from the vault, never one remembered from earlier.
 */
internal object SnapReplyChecks {

	/**
	 * The one way a reply check is built, foreground and background alike.
	 *
	 * [account] is the only difference between the two: the screen passes its
	 * own signed-in state, the job reads the vault.
	 */
	fun create(context: Context, account: () -> String?): SnapReplyCheck {
		val app = context.applicationContext
		val pendingSnaps = SharedPreferencesPendingSnapStore(app)
		return SnapReplyCheck(
			account = account,
			roots = { who ->
				val owner = LocalOwner.account(who)
				val catalog = owner?.let {
					runCatching {
						SqliteMySnapRows(LocalDatabase.get(app)).page(it, SnapReplyCheck.MAX_CANDIDATES, null)
					}.getOrNull()
				}.orEmpty()
				val confirmed = runCatching {
					MySnapsBackfill.rows(who, pendingSnaps.all(who), { null })
				}.getOrDefault(emptyList())
				catalog + confirmed
			},
			reader = { HiveSnapThreadReader() },
			store = SnapNoticeLocal.store(app),
			notifier = AndroidSnapNoticeNotifier(context = app, target = MainActivity::class.java),
			visible = SnapThreadVisibility::isVisible,
			checkpoints = runCatching { SqliteSyncCheckpoints(LocalDatabase.get(app)) }.getOrNull(),
		)
	}

	@Volatile
	private var background: SnapReplyCheck? = null

	/** The job's check: one per process, reading the vault's account at every question. */
	fun background(context: Context): SnapReplyCheck = background ?: synchronized(this) {
		background ?: run {
			val app = context.applicationContext
			val vault = KeyVault(app)
			// Read late and every time: a sign-out or switch while the job runs
			// is seen at the next question, and an unreadable vault is nobody.
			create(app) { runCatching { vault.account?.username }.getOrNull() }
		}.also { background = it }
	}
}

/**
 * Background runs that found something, for a screen that happens to be open.
 *
 * The job writes the notice store and the shade itself; this only lets a
 * running app re-read its bell and quietly refresh a conversation on screen,
 * exactly as it does after its own foreground check.
 */
internal object SnapReplyResults {

	private val checked = MutableSharedFlow<SnapReplyCheck.Outcome.Checked>(
		extraBufferCapacity = 4,
		onBufferOverflow = BufferOverflow.DROP_OLDEST,
	)

	val results: SharedFlow<SnapReplyCheck.Outcome.Checked> = checked.asSharedFlow()

	fun publish(outcome: SnapReplyCheck.Outcome.Checked) {
		checked.tryEmit(outcome)
	}
}

/**
 * The schedule: one job id, its shape, and when to replace or drop it.
 *
 * Pure apart from [Platform], so the decisions are unit-tested.
 */
internal object SnapReplySchedule {

	/** Distinct from the queue retry job's id. */
	const val JOB_ID = 0x5152_0042

	/** Android's periodic floor; the platform may run it later, never promised sooner. */
	const val PERIOD_MILLIS = 15 * 60 * 1000L

	/**
	 * Android's minimum flex. The platform runs a periodic job anywhere in the
	 * last [FLEX_MILLIS] of each period; with a flex of the whole period, two
	 * on-time runs landed 14m41s apart on the A36 and the second was refused as
	 * too soon, halving the real cadence.
	 */
	const val FLEX_MILLIS = 5 * 60 * 1000L

	/**
	 * How soon after the last check, by either side, a scheduled run may read:
	 * the closest two runs of this schedule normally fall. Still refuses the
	 * back-to-back catch-up run the platform makes after an overdue one.
	 */
	const val JOB_MIN_INTERVAL_MILLIS = PERIOD_MILLIS - FLEX_MILLIS

	/**
	 * After a run that reached no node. Longer than the period and growing, so a
	 * Hive outage costs fewer reads than an ordinary day, not more.
	 */
	const val BACKOFF_MILLIS = 30 * 60 * 1000L

	/** What the job must look like. Compared with the pending job to decide whether to touch it. */
	data class Spec(
		val periodMillis: Long,
		val flexMillis: Long,
		val needsNetwork: Boolean,
		val persisted: Boolean,
		val backoffMillis: Long,
		val exponentialBackoff: Boolean,
	)

	val SPEC = Spec(
		periodMillis = PERIOD_MILLIS,
		flexMillis = FLEX_MILLIS,
		needsNetwork = true,
		// Survives a reboot. Needs RECEIVE_BOOT_COMPLETED, held for this alone.
		persisted = true,
		backoffMillis = BACKOFF_MILLIS,
		exponentialBackoff = true,
	)

	enum class Action { SCHEDULE, KEEP, CANCEL }

	/**
	 * Unique: a job already pending in exactly this shape is kept, because
	 * scheduling it again would restart its period every time the app opened.
	 * A missing job (first sign-in, a force-stop, an update that dropped it) or
	 * one of another shape is scheduled. Nobody signed in means no job at all.
	 */
	fun decide(signedIn: Boolean, pending: Spec?): Action = when {
		!signedIn -> if (pending != null) Action.CANCEL else Action.KEEP
		pending == SPEC -> Action.KEEP
		else -> Action.SCHEDULE
	}

	/**
	 * Whether the platform should retry this run on the backoff rather than wait
	 * for the next period: only when nothing could be read because no node
	 * answered, or because storage was not ready on a cold start. Everything else
	 * — nobody signed in, too soon, another check already running, an account
	 * change, a run that read something — waits for the next period.
	 */
	fun retryAfter(outcome: SnapReplyCheck.Outcome): Boolean = when (outcome) {
		is SnapReplyCheck.Outcome.Checked -> !outcome.complete && outcome.read == 0
		is SnapReplyCheck.Outcome.Skipped -> outcome.reason == "storage not ready"
	}

	/** [decide] against the real scheduler. */
	class Platform(context: Context) {
		private val context = context.applicationContext

		private val scheduler: JobScheduler?
			get() = context.getSystemService(JobScheduler::class.java)

		fun pending(): Spec? = runCatching { scheduler?.getPendingJob(JOB_ID) }.getOrNull()?.let {
			Spec(
				periodMillis = it.intervalMillis,
				flexMillis = it.flexMillis,
				needsNetwork = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
					it.requiredNetwork != null
				} else {
					@Suppress("DEPRECATION")
					it.networkType != JobInfo.NETWORK_TYPE_NONE
				},
				persisted = it.isPersisted,
				backoffMillis = it.initialBackoffMillis,
				exponentialBackoff = it.backoffPolicy == JobInfo.BACKOFF_POLICY_EXPONENTIAL,
			)
		}

		/** Bring the scheduler in line with whether anybody is signed in. Cheap when nothing changes. */
		fun ensure(signedIn: Boolean): Action {
			val action = decide(signedIn, pending())
			when (action) {
				Action.KEEP -> Unit
				Action.CANCEL -> {
					runCatching { scheduler?.cancel(JOB_ID) }
					EventLog.append("snaps", "background reply check cancelled — nobody signed in")
				}
				Action.SCHEDULE -> {
					val job = JobInfo.Builder(JOB_ID, ComponentName(context, SnapReplyJobService::class.java))
						.setPeriodic(SPEC.periodMillis, SPEC.flexMillis)
						.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
						.setPersisted(SPEC.persisted)
						.setBackoffCriteria(SPEC.backoffMillis, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
						.build()
					val ok = runCatching { scheduler?.schedule(job) == JobScheduler.RESULT_SUCCESS }
						.onFailure { EventLog.append("snaps", "background reply check could not be scheduled: ${it.message}") }
						.getOrDefault(false)
					if (ok) EventLog.append("snaps", "background reply check scheduled — about every 15 minutes, with a network")
				}
			}
			if (BuildConfig.DEBUG) Log.i("RustedWaxReplyJob", "schedule: $action")
			return action
		}
	}
}

/**
 * The periodic job. Runs one [SnapReplyCheck] on a worker thread and finishes.
 *
 * A platform `JobService`, like the queue's retry job: being started binds this
 * service, which starts or thaws the process and holds it until [jobFinished].
 * No wake lock of its own, no loop, no retry inside a run.
 */
class SnapReplyJobService : JobService() {

	private val jobs = JobRunGate<JobParameters>()

	override fun onStartJob(params: JobParameters): Boolean {
		val app = applicationContext
		EventLog.init(app)
		val claim = jobs.begin(params.jobId, params)
		if (BuildConfig.DEBUG) Log.i(LOG_TAG, "job ${params.jobId}: started")
		thread(name = "snap-reply-job") {
			val outcome = runCatching {
				SnapReplyChecks.background(app).run(minIntervalMillis = SnapReplySchedule.JOB_MIN_INTERVAL_MILLIS)
			}
				.getOrElse { SnapReplyCheck.Outcome.Skipped("failed: ${it.javaClass.simpleName}") }
			val line = "background reply check — ${describe(outcome)}"
			EventLog.append("snaps", line)
			// Debug builds only, and regardless of the event-log switch: device
			// evidence that the scheduler, not a person, started this run.
			if (BuildConfig.DEBUG) Log.i(LOG_TAG, "job ${params.jobId}: $line")
			if (outcome is SnapReplyCheck.Outcome.Checked) SnapReplyResults.publish(outcome)
			if (claim.finish()) jobFinished(claim.params, SnapReplySchedule.retryAfter(outcome))
		}
		return true
	}

	/**
	 * The platform took the run back (the network went, a time limit). The read
	 * in progress ends on its own and records what it found; the period carries
	 * on, so no reschedule is asked for.
	 */
	override fun onStopJob(params: JobParameters): Boolean {
		if (jobs.stop(params.jobId)) EventLog.append("snaps", "background reply check stopped by the system")
		if (BuildConfig.DEBUG) Log.i(LOG_TAG, "job ${params.jobId}: stopped by the system")
		return false
	}

	override fun onDestroy() {
		jobs.closeAll()
		super.onDestroy()
	}

	private companion object {
		const val LOG_TAG = "RustedWaxReplyJob"
	}

	private fun describe(outcome: SnapReplyCheck.Outcome): String = when (outcome) {
		is SnapReplyCheck.Outcome.Skipped -> "skipped (${outcome.reason})"
		is SnapReplyCheck.Outcome.Checked ->
			"read ${outcome.read}, announced ${outcome.announced.size}, quiet ${outcome.quiet.size}" +
				if (outcome.complete) "" else ", incomplete"
	}
}
