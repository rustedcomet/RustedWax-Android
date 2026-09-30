package com.rustedwax.app.scrobble

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import com.rustedwax.app.detect.EventLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs the queue drain once, when [QueueRetryAlarmReceiver] found something due.
 *
 * The job does the *work* half of the wake-up; the alarm did the timing. It is
 * a platform `JobService` because starting a job binds this service, which
 * thaws or starts the process and holds it until [jobFinished] — a receiver's
 * few seconds are not enough for a slow node, and a coroutine timer inside a
 * frozen process never runs.
 *
 * Scheduled expedited, with no latency (the platform forbids one on an
 * expedited job) and with a network constraint: an alarm that fires offline
 * leaves the job waiting for a network rather than running the drain into a
 * failure that would advance the attempt ceiling. One fixed id, so a second
 * schedule replaces rather than adds. Not persisted across a reboot (that would
 * need `RECEIVE_BOOT_COMPLETED`); the listener connecting after boot drains the
 * queue and re-arms.
 */
class QueueRetryJobService : JobService() {

	private val jobs = JobRunGate<JobParameters>()

	override fun onStartJob(params: JobParameters): Boolean {
		EventLog.init(applicationContext)
		FinalizationRuntime.init(applicationContext)
		EventLog.append(
			"queue",
			"retry job started${if (isExpedited(params)) " (expedited)" else ""} — " +
				"retrying scrobbles whose backoff has elapsed",
		)
		val claim = jobs.begin(params.jobId, params)
		// The runtime calls this when its own drain ends, before it re-arms the
		// wake-up — whether or not this service instance still exists by then.
		val started = FinalizationRuntime.flushQueueForWakeup(onDrained = {
			if (claim.finish()) jobFinished(claim.params, false)
		})
		if (started == null) {
			claim.finish()
			return false
		}
		return true
	}

	/**
	 * The platform took the job away (a lost network, a time limit). The job is
	 * no longer ours to finish, so the drain's end must not call [jobFinished]
	 * for it. The drain itself is not cancelled: it is already under the
	 * broadcast lock, its durable write-ahead state is what makes stopping it
	 * mid-send safe, and its own completion re-arms the wake-up. No platform
	 * reschedule either.
	 */
	override fun onStopJob(params: JobParameters): Boolean {
		// By job id, not by this parameters object: the platform may stop an
		// execution with a different instance than the one it started it with.
		if (jobs.stop(params.jobId)) {
			EventLog.append("queue", "retry job stopped by the system; the retry in progress finishes on its own")
		}
		return false
	}

	private fun isExpedited(params: JobParameters): Boolean =
		Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && params.isExpeditedJob

	/** Nothing to cancel: the drain belongs to the runtime. Only stop answering for jobs. */
	override fun onDestroy() {
		jobs.closeAll()
		super.onDestroy()
	}

	/** [QueueWakeup.Jobs] over one fixed job id. */
	internal class Jobs(context: Context) : QueueWakeup.Jobs {
		private val context = context.applicationContext

		private val scheduler: JobScheduler?
			get() = context.getSystemService(JobScheduler::class.java)

		override fun isScheduled(): Boolean =
			runCatching { scheduler?.getPendingJob(JOB_ID) != null }.getOrDefault(false)

		/**
		 * Expedited jobs exist from Android 12 (API 31). Below that this answers
		 * "refused", and the caller's ordinary-job fallback is what runs.
		 */
		override fun scheduleExpedited(): Boolean =
			Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && schedule(expedited = true)

		override fun scheduleOrdinary(): Boolean = schedule(expedited = false)

		/**
		 * Only [JobScheduler.RESULT_SUCCESS] counts as scheduled. A refused
		 * expedited job (quota exhausted) returns RESULT_FAILURE, and a caller that
		 * schedules too often can be thrown at; neither is reported as armed.
		 */
		private fun schedule(expedited: Boolean): Boolean {
			val job = JobInfo.Builder(JOB_ID, ComponentName(context, QueueRetryJobService::class.java))
				.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
				.apply {
					if (expedited && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setExpedited(true)
				}
				.build()
			return runCatching { scheduler?.schedule(job) == JobScheduler.RESULT_SUCCESS }
				.onFailure {
					EventLog.append("queue", "retry job could not be scheduled: ${it.message}")
				}
				.getOrDefault(false)
		}

		override fun cancel() {
			runCatching { scheduler?.cancel(JOB_ID) }
		}
	}

	private companion object {
		/** The only job this app schedules. */
		const val JOB_ID = 0x5157_0001
	}
}

/**
 * Which started jobs this service may still finish.
 *
 * A job is finished at most once, and never after the platform stopped it or
 * the service was destroyed: [JobService.jobFinished] for a job the system
 * already took back is a call about somebody else's state.
 *
 * Keyed by the scheduled job id. `JobParameters` has no value equality, and the
 * platform may call `onStopJob` with a different instance than the one
 * `onStartJob` received for the same execution; a map keyed by the object
 * would miss that stop and let the drain's end finish a job already taken back.
 * The claim keeps the parameters it started with, which is what a valid
 * `jobFinished` needs.
 */
internal class JobRunGate<P : Any> {

	/** A started job's right to be finished; spent by [finish], [stop], a replacing [begin] or [closeAll]. */
	class Claim<P : Any> internal constructor(
		private val gate: JobRunGate<P>,
		val jobId: Int,
		/** The parameters this execution started with. */
		val params: P,
	) {
		private val open = AtomicBoolean(true)

		/** True exactly once, and only if the job was not stopped, replaced or closed first. */
		fun finish(): Boolean = open.compareAndSet(true, false).also { if (it) gate.forget(this) }

		/** Take the right away. True if it was still live. */
		internal fun revoke(): Boolean = open.compareAndSet(true, false)
	}

	private val live = ConcurrentHashMap<Int, Claim<P>>()

	/**
	 * An execution of [jobId] started. A claim still live for the same id belongs
	 * to an execution the platform has already replaced, so it is revoked.
	 */
	fun begin(jobId: Int, params: P): Claim<P> {
		val claim = Claim(this, jobId, params)
		live.put(jobId, claim)?.revoke()
		return claim
	}

	/** The platform stopped [jobId]. True if its claim was still live. */
	fun stop(jobId: Int): Boolean = live.remove(jobId)?.revoke() ?: false

	/** The service is going away: no started job will be finished from here. Idempotent. */
	fun closeAll() {
		live.values.forEach { it.revoke() }
		live.clear()
	}

	private fun forget(claim: Claim<P>) {
		live.remove(claim.jobId, claim)
	}
}
