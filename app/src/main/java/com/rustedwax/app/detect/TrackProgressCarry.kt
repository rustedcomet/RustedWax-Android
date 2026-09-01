package com.rustedwax.app.detect

import com.rustedwax.core.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * Play time rescued from a media session that vanished while its track kept
 * playing.
 *
 * ## The bug this exists for
 *
 * Chrome destroys and recreates its `MediaSession` mid-video — around ad breaks
 * and playlist transitions. Each recreation is a new `sessionToken`, so
 * [SessionProbe] built a fresh `Watch` with `playedMs` back at zero, and every
 * fragment was scored against the 60% threshold on its own. Observed 2026-07-30,
 * `LUNA`, 196 s long:
 *
 * ```
 * 11:45:05  [finalize] LUNA — played 47s of 196s   → 24%, skipped
 * 11:45:50  [finalize] LUNA — played 24s of 196s   → 12%, skipped
 * 11:47:33  [finalize] LUNA — played 85s of 196s   → 43%, skipped
 * ```
 *
 * 47 + 24 + 85 = **156 s of 196 = 80% watched**, and it produced no entry. The
 * playback positions show the video never actually stopped — `pos=47039ms` at
 * the start of the second fragment, `pos=70769ms` at the start of the third
 * (47 + 24 = 71). Only our accumulator restarted.
 *
 * ## Why a separate object
 *
 * `Watch` owns a `MediaController` and can't be unit-tested. The rules worth
 * pinning — how progress is keyed, when it expires, that it is consumed exactly
 * once — are pure, so they live here where a test can reach them.
 */
object TrackProgressCarry {

	/**
	 * @param fastestSpeedSeen carried too, so a track watched at 2× before the
	 * teardown still reports the rate it was actually watched at
	 */
	data class Progress(
		val playedMs: Long,
		val trackStartedAtEpochSec: Long,
		val fastestSpeedSeen: Double,
		val atMillis: Long,
		/** Position at teardown, used to recognise an end-to-start replacement. */
		val lastPositionMs: Long? = null,
		/** Once observed, the loop signal must survive later session churn. */
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
	)

	/**
	 * How long a vanished track's progress stays claimable on metadata alone.
	 *
	 * The observed gaps between a session disappearing and its replacement
	 * appearing were 18 and 21 seconds, so this is generous *for the case it was
	 * measured on* — Chrome rebuilding its session around an ad break. It is
	 * bounded at all because progress must not survive long enough to attach
	 * itself to a genuine, separate viewing of the same video later on.
	 */
	const val TTL_MS = 60_000L

	/**
	 * How long a continuation stays claimable when the replacement can *prove*
	 * it is the same viewing.
	 *
	 * ## Why the 60-second bound was the wrong instrument
	 *
	 * Measured 2026-08-09, native YouTube. The user minimized the app mid-song
	 * and came back four minutes later:
	 *
	 * ```
	 * 21:52:07  session ended, 105s carried, stopped at pos=105s
	 * 21:53:07  [session continuation expired] played 105s of 234s → 45%, skipped
	 * 21:56:10  session + com.google.android.youtube   pos=107968ms
	 * 21:58:25  [track change]                played 129s of 234s → 55%, skipped
	 * ```
	 *
	 * 105 + 129 = 234, the entire video, watched start to finish, and it
	 * produced nothing. The replacement resumed at 107968 ms — within three
	 * seconds of where the first fragment stopped, same package, same resolved
	 * video id — so every fact needed to recognise one continuous listen was in
	 * hand. The only thing that refused it was a stopwatch. 69 of these in one
	 * day's log.
	 *
	 * ## Why position is the better bound
	 *
	 * The 60-second limit is a proxy for the real question — "is this the same
	 * viewing, or a later separate one?" — and it answers it by guessing that
	 * separate viewings are far apart in time. They are not necessarily. But a
	 * separate viewing *does* start at the beginning, and a continuation starts
	 * where the last fragment stopped. That is the direct evidence, so it is what
	 * this asks for: an exact source item id on both sides and a resume position
	 * that continues the carried one. Time then only has to be bounded at all,
	 * not bounded tightly, and long enough to cover a phone call, another app, or
	 * YouTube's own "Video paused. Continue watching?" prompt.
	 */
	const val RESUMED_TTL_MS = 15 * 60_000L

	/**
	 * How far the replacement's first position may sit from where the carry
	 * stopped. YouTube rewinds a few seconds on resume — measured 2026-08-09,
	 * `pos=108358ms` then `pos=104801ms` on the same resume — and buffering moves
	 * it either way, so this is two-sided.
	 */
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
		store(keyFor(packageName, trackKey), Stored(token, progress))
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
		store(
			keyFor(packageName, trackIdentity.semanticKey),
			Stored(token, progress, trackIdentity, ttlFor(trackIdentity, progress)),
		)
		return token
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
		// A track that stopped at its own end has nothing left to resume, so
		// patience buys nothing and costs the listen its promptness. Measured
		// 2026-08-09: Zara Larsson ran out at `pos=223381ms` of a 223s video with
		// 210s measured — a finished, well-over-threshold listen — and the ads
		// that followed it carry no id of their own, so nothing would have
		// collected it for a quarter of an hour.
		finishedItem(trackIdentity, progress) -> TTL_MS
		else -> RESUMED_TTL_MS
	}

	/** The carry stopped at the end of its own item: complete, not interrupted. */
	private fun finishedItem(trackIdentity: TrackIdentity, progress: Progress): Boolean {
		val duration = trackIdentity.durationMs?.takeIf { it > 0 } ?: return false
		val stoppedAt = progress.lastPositionMs ?: return false
		return stoppedAt >= duration - RESUME_WINDOW_MS
	}

	/**
	 * The exact video this continuation is for, however it was proven.
	 *
	 * Native sessions carry it on the identity, put there by the resolver. A
	 * browser never does — measured 2026-08-09, Chrome playing
	 * `eC-F_VZ2T1c` had the id latched from the address bar and still fell out of
	 * the short window and split the listen, because the long one was keyed to
	 * where native happens to keep it. The address-bar latch is the same fact
	 * about the same question, so it answers here too.
	 */
	private fun exactIdOf(trackIdentity: TrackIdentity?, progress: Progress): String? =
		trackIdentity?.sourceItemId?.takeIf(String::isNotBlank)
			?: (progress.identity as? YouTubeProbe.Identity.Confirmed)?.videoId

	/**
	 * Whether this continuation gets the long window — the same question
	 * [ttlFor] answers, asked out loud so the log can state the wait it will
	 * actually keep rather than a constant that may not apply.
	 */
	fun holdsResumeWindow(trackIdentity: TrackIdentity, progress: Progress): Boolean =
		ttlFor(trackIdentity, progress) == RESUMED_TTL_MS

	/** Whether a replacement's first position continues the carried one. */
	fun resumesCarriedPosition(progress: Progress, resumePositionMs: Long?): Boolean {
		val stoppedAt = progress.lastPositionMs ?: return false
		if (stoppedAt < RESUME_MIN_POSITION_MS) return false
		val resumedAt = resumePositionMs ?: return false
		return abs(resumedAt - stoppedAt) <= RESUME_WINDOW_MS
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

	/**
	 * Claim only when the replacement metadata is the same semantic track. A
	 * material duration conflict shares the stable key but fails this predicate.
	 *
	 * @param resumePositionMs the replacement's first observed position. Past
	 * [TTL_MS] this is what the claim rests on: without it, or without continuity
	 * with where the carry stopped, an older continuation is refused and left for
	 * its own timer to finalize as the separate listen it evidently is.
	 */
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
		if (now - stored.progress.atMillis > stored.ttlMs) {
			carried.remove(key, stored)
			return null
		}
		val remembered = stored.trackIdentity ?: return null
		if (!remembered.sameTrackAs(trackIdentity)) return null
		// Inside the metadata window the key is enough, exactly as before. Beyond
		// it the replacement has to show it is continuing this viewing rather than
		// starting another one, and only an exactly identified track may ask.
		if (now - stored.progress.atMillis > TTL_MS) {
			val wanted = exactIdOf(remembered, stored.progress) ?: return null
			val offered = trackIdentity.sourceItemId?.takeIf(String::isNotBlank) ?: resumeVideoId
			if (wanted != offered || !resumesCarriedPosition(stored.progress, resumePositionMs)) {
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
		if (stored.token != token || now - stored.progress.atMillis < stored.ttlMs) return null
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

	/** Monitoring stopped — nothing observed before it should leak past it. */
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
		carried.entries.removeAll { now - it.value.progress.atMillis > it.value.ttlMs }
	}
}
