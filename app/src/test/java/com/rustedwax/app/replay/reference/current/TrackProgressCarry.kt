package com.rustedwax.app.replay.reference.current

// GENERATED — do not edit. See tools/phase03/generate-current-mirror.sh.
// Body below is byte-identical to the shipping detect/TrackProgressCarry.kt.

import com.rustedwax.app.detect.*
import com.rustedwax.core.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import com.rustedwax.app.replay.reference.phase01.VirtualSystem as System

object TrackProgressCarry {

	data class Progress(
		val playedMs: Long,
		val trackStartedAtEpochSec: Long,
		val fastestSpeedSeen: Double,
		val atMillis: Long,
		/** Position at teardown, used to recognise an end-to-start replacement. */
		val lastPositionMs: Long? = null,

		val loopDetected: Boolean = false,
		/** Identity frozen while this fragment was still the active track. */
		val identity: YouTubeProbe.Identity? = null,
		/** Exact visible YouTube ad label, once bound to this track. */
		val explicitAdSignal: String? = null,
		/** Fresh successful root scan, bound to the same token/signature. */
		val accessibilityCoverage: MediaSessionAccessibilityEvidence.Coverage? = null,
		/** Unique ordinary-watch track token; carried only with this signature. */
		val trackInstanceToken: Long? = null,
		/**
		 * The scoring layer says this listen has already crossed the active automatic
		 * threshold, so a vanished session should be disposed promptly rather than
		 * hidden behind the long human-interruption window.
		 *
		 * This store deliberately does not know the threshold or whether automatic
		 * scrobbling is enabled. It only applies the scheduling answer supplied by
		 * that owner. The ordinary replacement window remains, so a routine
		 * MediaSession rebuild can still claim the same listen without splitting it.
		 */
		val promptFinalization: Boolean = false,
		/** Exact automatic-write interval in which this logical listen began. */
		val automaticWriteAuthorization: AutomaticWriteAuthorization =
			AutomaticWriteAuthorization.LegacyEnabled,
		/** Original display-off STOPPED boundary, when this carry continues one. */
		val interruptionStartedAtMillis: Long? = null,
		/** Absolute deadline inherited from that STOPPED; teardown may not renew it. */
		val interruptionDeadlineMillis: Long? = null,
	)

	const val TTL_MS = 60_000L

	const val RESUMED_TTL_MS = 15 * 60_000L

	const val RESUME_WINDOW_MS = 15_000L

	/**
	 * How far into the item the carry must have stopped before position may
	 * extend its life.
	 *
	 * Strictly greater than [RESUME_WINDOW_MS], which is the whole point: a
	 * replay from the beginning reports a position near zero, and it must never
	 * land inside the window around the carried one. Below this floor the
	 * ordinary [TTL_MS] still applies and at most a few seconds are at stake.
	 */
	const val RESUME_MIN_POSITION_MS = 30_000L

	private data class Stored(
		val token: Long,
		val progress: Progress,
		val trackIdentity: TrackIdentity? = null,
		/** This entry's own deadline: [TTL_MS], or [RESUMED_TTL_MS] when position can speak. */
		val ttlMs: Long = TTL_MS,
		/** Absolute eligibility boundary; an inherited interruption may make it earlier. */
		val deadlineMillis: Long = progress.atMillis + ttlMs,
	)

	private val carried = ConcurrentHashMap<String, Stored>()

	/**
	 * Continuations displaced by a newer one for the same key, held until their
	 * owner's timer collects them.
	 *
	 * A replacement that does not claim is a separate viewing, so the displaced
	 * fragment is a listen in its own right and still has to be finalized. While
	 * the window was 60 seconds an overwrite was barely reachable; over fifteen
	 * minutes it is ordinary — play a song, play it again — and without this the
	 * first fragment's timer would find someone else's token and drop the listen
	 * on the floor in silence.
	 */
	private val displaced = ConcurrentHashMap<Long, Progress>()

	private val nextToken = AtomicLong(1)

	/**
	 * Hold this track's progress for whatever session picks it up next.
	 *
	 * Only called when a session disappears on its own. A user Stop deliberately
	 * discards the in-flight track (decision D2), and carrying it would smuggle
	 * that discarded time into the next session.
	 */
	fun remember(packageName: String, trackKey: String, progress: Progress): Long? {
		if (trackKey.isBlank()) return null
		// A free-form key carries no independently proven source item id. Keep
		// this compatibility overload browser-only so no future native caller can
		// bypass the semantic overload's exact-id invariant.
		if (SourceRegistry.packageProvesSource(packageName)) return null
		prune(progress.atMillis)
		val token = nextToken.getAndIncrement()
		val stored = stored(token, progress) ?: return null
		store(keyFor(packageName, trackKey), stored)
		return token
	}

	/** Semantic variant used by SessionProbe so duration refinements keep time. */
	fun remember(
		packageName: String,
		trackIdentity: TrackIdentity,
		progress: Progress,
	): Long? {
		if (!trackIdentity.isUsable) return null
		if (SourceRegistry.packageProvesSource(packageName) && !trackIdentity.hasExactSourceItemId) {
			return null
		}
		prune(progress.atMillis)
		val token = nextToken.getAndIncrement()
		val stored = stored(token, progress, trackIdentity) ?: return null
		store(keyFor(packageName, trackIdentity.semanticKey), stored)
		return token
	}

	private fun stored(
		token: Long,
		progress: Progress,
		trackIdentity: TrackIdentity? = null,
	): Stored? {
		val ttlMs = trackIdentity?.let { ttlFor(it, progress) } ?: TTL_MS
		val ordinaryDeadline = progress.atMillis + ttlMs
		val deadline = progress.interruptionDeadlineMillis
			?.coerceAtMost(ordinaryDeadline)
			?: ordinaryDeadline
		if (progress.interruptionDeadlineMillis != null && deadline <= progress.atMillis) return null
		return Stored(token, progress, trackIdentity, ttlMs, deadline)
	}

	/**
	 * This entry's deadline. The long one is offered only where the claim can be
	 * corroborated: an exact id to match on, and a stopping position far enough
	 * in that a replay from the beginning could not be confused with it.
	 */
	private fun ttlFor(trackIdentity: TrackIdentity, progress: Progress): Long = when {
		// Once an automatic listen is already earned, fifteen minutes buys no
		// eligibility and leaves it absent from both History and Not logged. Keep
		// the ordinary rebuild grace, but do not hide a terminal outcome behind the
		// long human-interruption window.
		progress.promptFinalization -> TTL_MS
		exactIdOf(trackIdentity, progress) == null -> TTL_MS
		(progress.lastPositionMs ?: 0L) < RESUME_MIN_POSITION_MS -> TTL_MS

		finishedItem(trackIdentity, progress) -> TTL_MS
		else -> RESUMED_TTL_MS
	}

	/** The carry stopped at the end of its own item: complete, not interrupted. */
	private fun finishedItem(trackIdentity: TrackIdentity, progress: Progress): Boolean {
		val duration = trackIdentity.durationMs?.takeIf { it > 0 } ?: return false
		val stoppedAt = progress.lastPositionMs ?: return false
		return stoppedAt >= duration - RESUME_WINDOW_MS
	}

	private fun exactIdOf(trackIdentity: TrackIdentity?, progress: Progress): String? =
		trackIdentity?.sourceItemId?.takeIf(String::isNotBlank)
			?: (progress.identity as? YouTubeProbe.Identity.Confirmed)?.videoId

	/**
	 * Whether this continuation gets the long window — the same question
	 * [ttlFor] answers, asked out loud so the log can state the wait it will
	 * actually keep rather than a constant that may not apply.
	 */
	fun holdsResumeWindow(trackIdentity: TrackIdentity, progress: Progress): Boolean =
		ttlFor(trackIdentity, progress) == RESUMED_TTL_MS &&
			(progress.interruptionDeadlineMillis ?: Long.MAX_VALUE) > progress.atMillis

	/** The actual deadline a stored semantic continuation will receive. */
	fun continuationDeadlineMillis(trackIdentity: TrackIdentity, progress: Progress): Long {
		val ordinaryDeadline = progress.atMillis + ttlFor(trackIdentity, progress)
		return progress.interruptionDeadlineMillis?.coerceAtMost(ordinaryDeadline) ?: ordinaryDeadline
	}

	/** Whether a replacement's first position continues the carried one. */
	fun resumesCarriedPosition(progress: Progress, resumePositionMs: Long?): Boolean {
		val stoppedAt = progress.lastPositionMs ?: return false
		if (stoppedAt < RESUME_MIN_POSITION_MS) return false
		val resumedAt = resumePositionMs ?: return false
		return abs(resumedAt - stoppedAt) <= RESUME_WINDOW_MS
	}

	/**
	 * Whether a replacement's first position continues the carried one *through an
	 * interruption nobody could observe*.
	 *
	 * [resumesCarriedPosition] asks whether the item picked up where it was left,
	 * which is what a MediaSession rebuilt in place looks like. An interruption —
	 * the screen off, the window torn down and remade — is the other shape: the
	 * item may have gone on playing while nothing was watching, so the position it
	 * comes back at legitimately sits *ahead* of where the carry stopped.
	 *
	 * How far ahead is not a guess. At most the wall clock that actually passed
	 * while this continuation waited, which is all of the item that could possibly
	 * have gone by, plus the same [RESUME_WINDOW_MS] slack the in-place case gets
	 * for sampling. Further in than that is a seek or a later viewing, and gets
	 * nothing; further *back* than the ordinary window is a replay, and already
	 * got nothing.
	 *
	 * Nothing here credits that interval. It decides only whether the play time
	 * already measured belongs to the transport now asking for it; the unobserved
	 * gap stays unobserved, and `SessionSnapshot.unobservedLeadInMs` still reports
	 * it against the resumed listen.
	 */
	fun continuesCarriedPosition(
		progress: Progress,
		resumePositionMs: Long?,
		now: Long,
	): Boolean {
		if (resumesCarriedPosition(progress, resumePositionMs)) return true
		val stoppedAt = progress.lastPositionMs ?: return false
		if (stoppedAt < RESUME_MIN_POSITION_MS) return false
		val resumedAt = resumePositionMs ?: return false
		val unobservedMs = now - (progress.interruptionStartedAtMillis ?: progress.atMillis)
		if (unobservedMs <= 0) return false
		return resumedAt - stoppedAt in 0..(unobservedMs + RESUME_WINDOW_MS)
	}

	/** Overwriting a live continuation must not silently discard its listen. */
	private fun store(key: String, entry: Stored) {
		carried.put(key, entry)?.let { previous -> displaced[previous.token] = previous.progress }
	}

	/**
	 * Claim progress for a track that has just reappeared, or null.
	 *
	 * **Consumed on read.** Two sessions must never both inherit the same play
	 * time, and a stale entry must not be applied twice to one track.
	 */
	fun claim(
		packageName: String,
		trackKey: String,
		now: Long = System.currentTimeMillis(),
	): Progress? {
		if (trackKey.isBlank()) return null
		if (SourceRegistry.packageProvesSource(packageName)) return null
		val stored = carried.remove(keyFor(packageName, trackKey)) ?: return null
		return stored.progress.takeIf { now - it.atMillis <= TTL_MS }
	}

	fun claim(
		packageName: String,
		trackIdentity: TrackIdentity,
		now: Long = System.currentTimeMillis(),
		resumePositionMs: Long? = null,
		resumeVideoId: String? = null,
	): Progress? {
		if (!trackIdentity.isUsable) return null
		if (SourceRegistry.packageProvesSource(packageName) && !trackIdentity.hasExactSourceItemId) {
			return null
		}
		val key = keyFor(packageName, trackIdentity.semanticKey)
		val stored = carried[key] ?: return null
		if (expiredForClaim(stored, now)) {
			carried.remove(key, stored)
			return null
		}
		val remembered = stored.trackIdentity ?: return null
		if (!remembered.sameTrackAs(trackIdentity)) return null
		// Inside the metadata window the key is enough, exactly as before. Beyond
		// it the replacement has to show it is continuing this viewing rather than
		// starting another one, and only an exactly identified track may ask.
		val admissionStartedAt = stored.progress.interruptionStartedAtMillis
			?: stored.progress.atMillis
		if (now - admissionStartedAt > TTL_MS) {
			val wanted = exactIdOf(remembered, stored.progress) ?: return null
			val offered = trackIdentity.sourceItemId?.takeIf(String::isNotBlank) ?: resumeVideoId
			if (wanted != offered ||
				!continuesCarriedPosition(stored.progress, resumePositionMs, now)
			) {
				return null
			}
		}
		return if (carried.remove(key, stored)) stored.progress else null
	}

	/**
	 * Whether this continuation is still waiting for someone to claim it.
	 *
	 * The owner's timer polls rather than firing once, because the deadline is no
	 * longer a single known number: a claim, a displacement or the deadline
	 * itself can all end the wait, and only this can tell "still pending" from
	 * "already resolved by someone else".
	 */
	fun isPending(packageName: String, trackIdentity: TrackIdentity, token: Long): Boolean =
		carried[keyFor(packageName, trackIdentity.semanticKey)]?.token == token

	/**
	 * Take a continuation that no replacement claimed before its grace period.
	 *
	 * The token prevents an older delayed callback from consuming a newer
	 * continuation for the same metadata key.
	 */
	fun expire(
		packageName: String,
		trackKey: String,
		token: Long,
		now: Long = System.currentTimeMillis(),
	): Progress? {
		// A continuation a newer one pushed aside is owed the same finalization as
		// one that simply timed out; from its owner's side the two are the same
		// event — nobody continued this listen.
		displaced.remove(token)?.let { return it }
		val key = keyFor(packageName, trackKey)
		val stored = carried[key] ?: return null
		if (stored.token != token || now < stored.deadlineMillis) return null
		return if (carried.remove(key, stored)) stored.progress else null
	}

	fun expire(
		packageName: String,
		trackIdentity: TrackIdentity,
		token: Long,
		now: Long = System.currentTimeMillis(),
	): Progress? = expire(packageName, trackIdentity.semanticKey, token, now)

	/**
	 * Give up on continuations for this package that [nowPlaying] cannot be.
	 *
	 * The long window is a patience budget for a listen that might resume, and
	 * the moment a *different* exactly identified track starts in the same app,
	 * it plainly will not. Without this, abandoning a track mid-play would leave
	 * its listen unfinalized for the full fifteen minutes — and a listen that is
	 * only written when it finalizes is a listen at risk for as long as it waits.
	 *
	 * Only an exactly identified newcomer may say so. A native ad or transition
	 * phase arrives with no resolved id of its own, and treating one of those as
	 * proof the song had ended is how the carry would lose exactly the listens it
	 * exists to protect.
	 *
	 * Collected rather than discarded: these fragments are real listens, so they
	 * move to [displaced] for their owners to finalize on the next poll.
	 */
	fun abandon(packageName: String, nowPlaying: TrackIdentity) {
		if (!nowPlaying.hasExactSourceItemId) return
		val prefix = "$packageName|"
		carried.entries.removeAll { (key, stored) ->
			val supersededHere = key.startsWith(prefix) &&
				stored.trackIdentity?.sameTrackAs(nowPlaying) == false
			if (supersededHere) displaced[stored.token] = stored.progress
			supersededHere
		}
	}

	/** Cancel one pending continuation without touching a newer replacement. */
	fun cancel(packageName: String, trackKey: String, token: Long) {
		displaced.remove(token)
		val key = keyFor(packageName, trackKey)
		val stored = carried[key] ?: return
		if (stored.token == token) carried.remove(key, stored)
	}

	fun cancel(packageName: String, trackIdentity: TrackIdentity, token: Long) =
		cancel(packageName, trackIdentity.semanticKey, token)

	fun clear() {
		carried.clear()
		displaced.clear()
	}

	/** Whether this package is currently holding play time for something. */
	fun hasPackage(packageName: String): Boolean {
		val prefix = "$packageName|"
		return carried.keys.any { it.startsWith(prefix) }
	}

	/** Package opt-out/teardown must not disturb another source package. */
	fun clearPackage(packageName: String) {
		val prefix = "$packageName|"
		carried.entries.removeAll { entry ->
			entry.key.startsWith(prefix).also { if (it) displaced.remove(entry.value.token) }
		}
	}

	/** Visible for tests. */
	fun size(): Int = carried.size

	private fun keyFor(packageName: String, trackKey: String) = "$packageName|$trackKey"

	private fun prune(now: Long) {
		carried.entries.removeAll { expiredForClaim(it.value, now) }
	}

	private fun expiredForClaim(stored: Stored, now: Long): Boolean =
		if (stored.progress.interruptionDeadlineMillis != null) {
			now >= stored.deadlineMillis
		} else {
			now > stored.deadlineMillis
		}
}
