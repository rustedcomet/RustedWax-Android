package com.rustedwax.app.status

import com.rustedwax.app.detect.SessionSnapshot
import com.rustedwax.app.scrobble.DispatchLink
import com.rustedwax.app.scrobble.FinalizationOutcome
import com.rustedwax.app.scrobble.FinalizationRuntime.ScrobbleRecord
import com.rustedwax.app.ui.NowCard
import com.rustedwax.core.TrackInstanceId
import kotlin.math.roundToInt

/**
 * What the scrobbling-status notification says (#4), as plain values.
 *
 * Everything in this file is a *reading* of decisions made elsewhere. The live
 * half is [NowCard.phase] over the reducer's own credited time; the finalized
 * half is the engine's [FinalizationOutcome] for that exact listen, followed by
 * the History row its dispatch wrote. Nothing here decides a credit, a
 * threshold, an identity or a write, and no word here is stronger than the
 * evidence behind it: only a row whose status says `confirmed in block` is ever
 * called confirmed.
 */
internal enum class Tone { NEUTRAL, PINK, GREEN, AMBER, RED }

internal data class StatusLine(
	/** The pill: one or two words, never clipped. */
	val pill: String,
	/** What qualifies it, drawn beside the pill. */
	val detail: String?,
	val tone: Tone,
) {
	/** The whole line, where there is room for one: the collapsed card, the footnote. */
	val label: String get() = detail?.let { "$pill · $it" } ?: pill

	/** The collapsed card's one line: the whole line when it fits, else the pill. */
	val compact: String get() = if (label.length <= COMPACT_CHARS) label else pill

	private companion object {
		/** What One UI's collapsed row showed unclipped on the A36 (13sp). */
		const val COMPACT_CHARS = 30
	}
}

/** Which picture may be drawn, and for which listen. Never an identity. */
internal data class ArtworkKey(
	val listen: TrackInstanceId,
	/** Present only where the thumbnail lookup is consented to. */
	val videoId: String?,
	/** The title the player frame must have arrived under. */
	val title: String,
	/** False for a finished listen: the player's frame is gone with its session. */
	val live: Boolean,
)

internal data class TrackPart(
	val title: String,
	val artist: String?,
	/** `Brave · YouTube`, `YouTube Music`. */
	val source: String,
	/** `2:08 credited`. */
	val credited: String,
	/** `51%`, only where a length is known. */
	val percent: String?,
	/** 0..1000 for the bar, only where a length is known. */
	val progressPermille: Int?,
)

internal data class ScrobbleStatusView(
	/** The collapsed card's first line: the song, or `Waiting for media`. */
	val headline: String,
	/** Shown beside the app name in the system header. */
	val header: String,
	val track: TrackPart?,
	val status: StatusLine,
	/** A finished listen's outcome while another one is playing. */
	val footnote: String?,
	val artwork: ArtworkKey?,
	/** True only while the reducer is crediting time, so the clock is worth ticking. */
	val crediting: Boolean,
) {
	/** Something on the card expires by time alone: a held result or a footnote. */
	val ages: Boolean get() = footnote != null || header == ScrobbleStatusPresenter.FINISHED_HEADER
}

/** One live listen as the notification reads it. Built by the Android side. */
internal data class LiveListen(
	val session: SessionSnapshot,
	/** The reducer's credit now; [SessionSnapshot.playedMs] when no fresher read exists. */
	val creditedMs: Long,
	/** The id the Now card would show, cache-only. */
	val videoId: String?,
	/** The length the Now card's status uses. */
	val effectiveDurationMs: Long?,
)

internal object ScrobbleStatusPresenter {

	/** How long a finished listen's settled outcome stays the main card while idle. */
	const val SETTLED_HOLD_MS = 90_000L

	/** How long an unsettled one (verifying, sending, queued) may hold it. */
	const val PENDING_HOLD_MS = 10 * 60_000L

	/** How long a finished listen is mentioned under a newer one. */
	const val FOOTNOTE_HOLD_MS = 10 * 60_000L

	const val FINISHED_HEADER = "Last scrobble"

	fun present(
		live: List<LiveListen>,
		finished: List<FinishedListen>,
		account: String?,
		thresholdPercent: Int,
		autoScrobble: Boolean,
		thumbnailsAllowed: Boolean,
		nowMs: Long,
	): ScrobbleStatusView {
		val visible = finished.filter { it.visibleTo(account) }
		val latest = visible.maxByOrNull { it.finishedAtMs }
		val held = latest?.takeIf {
			val age = nowMs - it.finishedAtMs
			if (it.state(nowMs).settled) age <= SETTLED_HOLD_MS else age <= PENDING_HOLD_MS
		}

		// What is being credited right now comes first. A paused session does
		// not outrank a listen that just finished: browsers and players keep a
		// paused session around long after anyone stopped looking at it, and it
		// would bury the one result the card exists to show.
		val crediting = live.filter { it.session.isPlaying || it.session.pipInferredPlaying }
			.maxByOrNull { it.session.trackStartedAtEpochSec }
		val paused = live.maxByOrNull { it.session.trackStartedAtEpochSec }

		if (crediting != null) return liveView(crediting, footnote(latest, crediting, nowMs), thresholdPercent, autoScrobble, thumbnailsAllowed)
		if (held != null) {
			return finishedView(held, thumbnailsAllowed, nowMs)
				.copy(footnote = paused?.session?.title?.takeIf { it.isNotBlank() }?.let { "Paused: $it" })
		}
		if (paused != null) return liveView(paused, footnote(latest, paused, nowMs), thresholdPercent, autoScrobble, thumbnailsAllowed)

		return ScrobbleStatusView(
			headline = "Waiting for media",
			header = "Active",
			track = null,
			status = StatusLine("RustedWax active", "Waiting for media", Tone.PINK),
			footnote = null,
			artwork = null,
			crediting = false,
		)
	}

	private fun footnote(latest: FinishedListen?, primary: LiveListen, nowMs: Long): String? = latest
		?.takeIf { it.listen != primary.session.trackInstance }
		?.takeIf { nowMs - it.finishedAtMs <= FOOTNOTE_HOLD_MS }
		?.let { "Last: ${it.title} · ${it.statusLine(nowMs).label}" }

	private fun liveView(
		listen: LiveListen,
		footnote: String?,
		thresholdPercent: Int,
		autoScrobble: Boolean,
		thumbnailsAllowed: Boolean,
	): ScrobbleStatusView {
		val s = listen.session
		val duration = s.durationMs?.takeIf { it > 0 }
		// The engine's own denominator: percentPlayed is credited / durationMs.
		val fraction = duration?.let { listen.creditedMs.toDouble() / it }
		val fresh = s.copy(playedMs = listen.creditedMs, percentPlayed = fraction)
		val phase = NowCard.phase(
			session = fresh,
			durationMs = listen.effectiveDurationMs,
			identified = listen.videoId != null,
			percent = fraction?.coerceIn(0.0, 1.0),
			thresholdPercent = thresholdPercent,
			autoScrobble = autoScrobble,
		)
		val reached = (fraction ?: 0.0) * 100 >= thresholdPercent
		val status = when (phase) {
			NowCard.Phase.ADVERTISEMENT -> StatusLine("Ad", "Not counted", Tone.AMBER)
			NowCard.Phase.READING -> StatusLine("Reading", "What's playing", Tone.NEUTRAL)
			NowCard.Phase.PAUSED -> StatusLine("Paused", null, Tone.NEUTRAL)
			NowCard.Phase.IDENTIFYING -> if (reached) {
				StatusLine("Threshold reached", "Verifying", Tone.PINK)
			} else {
				StatusLine("Listening", "Identifying", Tone.PINK)
			}
			NowCard.Phase.NO_LENGTH -> StatusLine("Listening", "Length unknown", Tone.PINK)
			NowCard.Phase.AUTO_SCROBBLE_OFF -> StatusLine("Listening", "Auto-scrobble off", Tone.AMBER)
			NowCard.Phase.THRESHOLD_REACHED -> StatusLine("Threshold reached", "Checks at end", Tone.PINK)
			NowCard.Phase.BELOW_THRESHOLD -> StatusLine("Listening", "Scrobbles at $thresholdPercent%", Tone.PINK)
		}
		val title = s.title?.takeIf { it.isNotBlank() }
		return ScrobbleStatusView(
			headline = title ?: "Reading what's playing",
			header = "Tracking",
			track = TrackPart(
				title = title ?: "—",
				artist = s.artist?.takeIf { it.isNotBlank() },
				source = sourceLine(s),
				credited = "${clock(listen.creditedMs)} credited",
				percent = fraction?.let { "${(it.coerceIn(0.0, 1.0) * 100).roundToInt()}%" },
				progressPermille = fraction?.let { (it.coerceIn(0.0, 1.0) * 1000).roundToInt() },
			),
			status = status,
			footnote = footnote,
			artwork = title?.let {
				ArtworkKey(
					listen = s.trackInstance,
					videoId = listen.videoId?.takeIf { thumbnailsAllowed },
					title = it,
					live = true,
				)
			},
			crediting = s.isPlaying || s.pipInferredPlaying,
		)
	}

	private fun finishedView(
		listen: FinishedListen,
		thumbnailsAllowed: Boolean,
		nowMs: Long,
	): ScrobbleStatusView {
		val fraction = listen.durationMs?.takeIf { it > 0 }?.let { listen.creditedMs.toDouble() / it }
		return ScrobbleStatusView(
			headline = listen.title,
			header = FINISHED_HEADER,
			track = TrackPart(
				title = listen.title,
				artist = listen.artist,
				source = listen.source,
				credited = "${clock(listen.creditedMs)} credited",
				percent = fraction?.let { "${(it.coerceIn(0.0, 1.0) * 100).roundToInt()}%" },
				progressPermille = fraction?.let { (it.coerceIn(0.0, 1.0) * 1000).roundToInt() },
			),
			status = listen.statusLine(nowMs),
			footnote = null,
			artwork = ArtworkKey(
				listen = listen.listen,
				videoId = listen.videoId?.takeIf { thumbnailsAllowed },
				title = listen.title,
				live = false,
			),
			crediting = false,
		)
	}

	/**
	 * Where the sound comes from, and which service — only once proven.
	 *
	 * A browser is named as itself, and `· YouTube` is added only where the
	 * session is proven YouTube; the native apps are their service.
	 */
	fun sourceLine(session: SessionSnapshot): String {
		val platform = NowCard.platformFor(session.packageName)
		return when (platform) {
			NowCard.Platform.BRAVE, NowCard.Platform.CHROME ->
				if (session.isYouTube) "${platform.label} · YouTube" else platform.label
			else -> platform.label
		}
	}

	/** `2:08`, or `1:02:03`. Zero is `0:00`, not absent: nothing credited is a fact. */
	fun clock(ms: Long): String = NowCard.durationText(ms.coerceAtLeast(0)) ?: "0:00"
}

/**
 * A listen that has ended, from finalization to its last known Hive state.
 *
 * Keyed by the listen's [TrackInstanceId]. Its Hive state comes **only** from
 * the queue operations its own dispatch reported creating ([links]) and the
 * History rows that carry exactly those operation ids, signed for exactly the
 * account the operation named. A video id, a timestamp, History order or
 * verdict order never binds anything: two listens of one video, an old queued
 * send and a payload that never became an operation all look alike by those
 * measures, and every one of them used to be able to lend this card a
 * confirmation it had not earned.
 */
internal data class FinishedListen(
	val listen: TrackInstanceId,
	val title: String,
	val artist: String?,
	val source: String,
	val creditedMs: Long,
	val durationMs: Long?,
	/** The Hive account signed in when it finalized; null when nobody was. */
	val account: String?,
	val finishedAtMs: Long,
	val outcome: Outcome = Outcome.Pending,
	/** Per payload index: the operation its dispatch wrote, and for whom. */
	val links: Map<Int, OperationLink> = emptyMap(),
	/** Per payload index: why its dispatch stopped with no operation at all. */
	val ended: Map<Int, String> = emptyMap(),
	/** The latest row for each linked operation, by operation id. */
	val rows: Map<String, ScrobbleRecord> = emptyMap(),
	val videoId: String? = null,
	/**
	 * Whether a refusal of this listen would be a Not-logged row at all.
	 *
	 * The same three gates that list does: notable, proven YouTube, titled. A
	 * metadata blip or another site's page is refused invisibly there, and is
	 * refused invisibly here.
	 */
	val refusalVisible: Boolean = true,
	/**
	 * False when the signed-in account was not known at finalization — mid-read
	 * after a key was saved or forgotten. Such a listen has no provable owner
	 * and is never shown to anyone.
	 */
	val ownerKnown: Boolean = true,
) {
	data class OperationLink(val operationId: String, val account: String)

	sealed interface Outcome {
		/** Finalization is still running: identity, enrichment, policy. */
		data object Pending : Outcome

		/** Ruled eligible with [payloadCount] payloads; [videoIds] are display hints only. */
		data class Eligible(val payloadCount: Int, val videoIds: List<String?>, val atMs: Long) : Outcome

		data class Refused(val reason: String) : Outcome

		/** Designed to be invisible — auto-scrobble off, no key. */
		data class Ignored(val reason: String) : Outcome
	}

	enum class State(val settled: Boolean) {
		VERIFYING(false),
		SENDING(false),
		QUEUED(false),
		MEMPOOL(false),
		SENT_UNVERIFIED(true),
		ACCEPTED_UNCONFIRMED(true),
		CONFIRMED(true),
		FAILED(true),
		/** Ruled eligible, but at least one payload never became an operation. */
		NOT_SENT(true),
		/** Eligible, but its operations or rows never arrived in time; nothing is claimed. */
		UNVERIFIED(false),
		NOT_LOGGED(true),
		IGNORED(true),
	}

	/**
	 * Every owner this card would speak for must be [viewer]: the account at
	 * finalization, the account each operation was written for, and the account
	 * each row is stamped with. Anything unknown or mismatched hides the card.
	 */
	fun visibleTo(viewer: String?): Boolean {
		if (outcome is Outcome.Ignored || !ownerKnown) return false
		val who = viewer?.trim()?.takeIf { it.isNotEmpty() }
		if (account == null) {
			// The signed-out device owns only what nobody signed: a refusal or a
			// listen that never reached an operation. A row always has a signer.
			return who == null && links.isEmpty() && rows.isEmpty()
		}
		if (who == null || !account.equals(who, ignoreCase = true)) return false
		return links.values.all { it.account.equals(who, ignoreCase = true) } &&
			rows.values.all { it.account.equals(who, ignoreCase = true) }
	}

	fun state(nowMs: Long): State = when (val o = outcome) {
		Outcome.Pending -> State.VERIFYING
		is Outcome.Refused -> State.NOT_LOGGED
		is Outcome.Ignored -> State.IGNORED
		is Outcome.Eligible -> eligibleState(o, nowMs)
	}

	private fun eligibleState(o: Outcome.Eligible, nowMs: Long): State {
		if (ended.isNotEmpty()) return State.NOT_SENT
		val states = (0 until o.payloadCount).map { index ->
			val link = links[index] ?: return waiting(o, nowMs)
			val row = rows[link.operationId] ?: return waiting(o, nowMs)
			rowState(row)
		}
		// Weakest wins: a double listen is confirmed only when both rows are.
		return states.minByOrNull { RANK.indexOf(it) } ?: waiting(o, nowMs)
	}

	/** An operation or row has not arrived. Honest for a while, then not claimed at all. */
	private fun waiting(o: Outcome.Eligible, nowMs: Long): State =
		if (nowMs - o.atMs > SEND_WAIT_MS) State.UNVERIFIED else State.SENDING

	fun statusLine(nowMs: Long): StatusLine = when (state(nowMs)) {
		State.VERIFYING -> StatusLine("Verifying", "Final checks", Tone.PINK)
		State.SENDING -> StatusLine("Eligible", "Sending to Hive", Tone.GREEN)
		State.QUEUED -> StatusLine("Queued", "Will retry", Tone.AMBER)
		State.MEMPOOL -> StatusLine("Sent", "Awaiting block", Tone.PINK)
		State.SENT_UNVERIFIED -> StatusLine("Sent to Hive", "Block not verified", Tone.PINK)
		State.ACCEPTED_UNCONFIRMED -> StatusLine("Accepted", "Confirmation unavailable", Tone.AMBER)
		State.CONFIRMED -> StatusLine("Scrobbled", "Hive confirmed", Tone.GREEN)
		State.FAILED -> StatusLine("Not logged", "Hive did not take it", Tone.RED)
		State.NOT_SENT -> StatusLine("Not sent", shortReason(ended.values.first()), Tone.RED)
		State.UNVERIFIED -> StatusLine("Unverified", "See History", Tone.NEUTRAL)
		State.NOT_LOGGED -> StatusLine(
			"Not logged",
			shortReason((outcome as Outcome.Refused).reason),
			Tone.RED,
		)
		State.IGNORED -> StatusLine("Not sent", null, Tone.NEUTRAL)
	}

	companion object {
		/** How long an eligible listen may wait for its operations and rows before claiming nothing. */
		const val SEND_WAIT_MS = 2 * 60_000L

		/** Least to most advanced; a multi-payload listen reads as its weakest row. */
		private val RANK = listOf(
			State.FAILED,
			State.QUEUED,
			State.SENDING,
			State.ACCEPTED_UNCONFIRMED,
			State.MEMPOOL,
			State.SENT_UNVERIFIED,
			State.CONFIRMED,
		)

		/**
		 * One History row's status, in the engine's own words.
		 *
		 * Only `confirmed in block` is block evidence. A queued send, a
		 * reconciliation and a mempool sighting are each written by a branch
		 * that either saw no block or did not record that it did, so none of
		 * them may say confirmed.
		 */
		fun rowState(row: ScrobbleRecord): State {
			val status = row.status
			return when {
				status.startsWith("confirmed in block") -> State.CONFIRMED
				row.acceptedUnconfirmed -> State.ACCEPTED_UNCONFIRMED
				row.queueFailed || status.startsWith("rejected") -> State.FAILED
				status.startsWith("seen relaying in mempool") -> State.MEMPOOL
				row.queued -> State.QUEUED
				status.startsWith("sent from queue") ||
					status.startsWith("reconciled") -> State.SENT_UNVERIFIED
				else -> State.FAILED
			}
		}

		/** A refusal reason short enough for one line. */
		fun shortReason(reason: String): String {
			val first = reason.substringBefore(" — ").substringBefore(": ").trim()
			return if (first.length <= 48) first else first.take(47).trimEnd() + "…"
		}
	}
}

/**
 * Finished listens, from finalization through their own Hive operations.
 *
 * Thread-safe: verdicts and operation reports arrive on the engine's threads,
 * History on whichever thread wrote it, and the notification reads on the
 * main thread. Bounded to the last [capacity] listens.
 */
internal class FinishedListenLedger(private val capacity: Int = 8) {

	private val entries = ArrayList<FinishedListen>()

	/** The last History seen, so an operation reported after its row still finds it. */
	private var lastRows: List<ScrobbleRecord> = emptyList()

	@Synchronized
	fun finalized(entry: FinishedListen) {
		entries.removeAll { it.listen == entry.listen }
		entries += entry
		while (entries.size > capacity) entries.removeAt(0)
	}

	/** The engine's verdict for [listen]. Only the first one counts. */
	@Synchronized
	fun outcome(listen: TrackInstanceId, outcome: FinalizationOutcome, nowMs: Long) {
		val index = entries.indexOfFirst { it.listen == listen }
		if (index < 0) return
		val entry = entries[index]
		if (entry.outcome != FinishedListen.Outcome.Pending) return
		entries[index] = when (outcome) {
			is FinalizationOutcome.Eligible -> {
				val ids = outcome.payloads.map { payloadVideoId(it.url) }
				entry.copy(
					outcome = FinishedListen.Outcome.Eligible(outcome.payloads.size, ids, nowMs),
					videoId = entry.videoId ?: ids.firstNotNullOfOrNull { it },
				)
			}
			is FinalizationOutcome.Refused -> entry.copy(
				outcome = if (entry.refusalVisible) {
					FinishedListen.Outcome.Refused(outcome.reason)
				} else {
					FinishedListen.Outcome.Ignored(outcome.reason)
				},
			)
			is FinalizationOutcome.Ignored -> entry.copy(outcome = FinishedListen.Outcome.Ignored(outcome.reason))
		}
	}

	/**
	 * The transport wrote [operationId] for one of [link]'s payloads, to be
	 * signed for [account]. The first report for a payload wins; a report for
	 * a listen that is not eligible here, or whose shape disagrees, is dropped.
	 */
	@Synchronized
	fun operationCreated(link: DispatchLink, operationId: String, account: String) {
		val index = eligibleIndexFor(link) ?: return
		val entry = entries[index]
		if (link.payloadIndex in entry.links || link.payloadIndex in entry.ended) return
		// One operation is one payload of one listen, never two.
		if (entries.any { e -> e.links.values.any { it.operationId == operationId } }) return
		entries[index] = refreshed(
			entry.copy(links = entry.links + (link.payloadIndex to FinishedListen.OperationLink(operationId, account))),
			lastRows,
		)
	}

	/** That payload will never have an operation. */
	@Synchronized
	fun dispatchEnded(link: DispatchLink, reason: String) {
		val index = eligibleIndexFor(link) ?: return
		val entry = entries[index]
		if (link.payloadIndex in entry.links || link.payloadIndex in entry.ended) return
		entries[index] = entry.copy(ended = entry.ended + (link.payloadIndex to reason))
	}

	/** Follow each linked operation's own row — by operation id and signer only. */
	@Synchronized
	fun history(rows: List<ScrobbleRecord>) {
		lastRows = rows
		for (i in entries.indices) entries[i] = refreshed(entries[i], rows)
	}

	private fun refreshed(entry: FinishedListen, rows: List<ScrobbleRecord>): FinishedListen {
		if (entry.links.isEmpty()) return entry
		val byOperation = rows.filter { it.queueOperationId != null }.associateBy { it.queueOperationId!! }
		val next = entry.links.values.mapNotNull { link ->
			// A row that has since aged out of History keeps its last state.
			val row = byOperation[link.operationId] ?: entry.rows[link.operationId] ?: return@mapNotNull null
			// The row must be signed for the account the operation was written
			// for. A mismatch is not this listen's evidence, whatever it says.
			row.takeIf { it.account.equals(link.account, ignoreCase = true) }?.let { link.operationId to it }
		}.toMap()
		return entry.copy(rows = next)
	}

	private fun eligibleIndexFor(link: DispatchLink): Int? {
		val index = entries.indexOfFirst { it.listen == link.listen }
		if (index < 0) return null
		val outcome = entries[index].outcome as? FinishedListen.Outcome.Eligible ?: return null
		if (outcome.payloadCount != link.payloadCount) return null
		if (link.payloadIndex !in 0 until outcome.payloadCount) return null
		return index
	}

	@Synchronized
	fun all(): List<FinishedListen> = entries.toList()

	@Synchronized
	fun clear() {
		entries.clear()
		lastRows = emptyList()
	}

	companion object {
		private val ID = Regex("""(?:[?&]v=|youtu\.be/|/shorts/)([A-Za-z0-9_-]{11})(?![A-Za-z0-9_-])""")

		/** The video a payload's canonical URL names — artwork only, never a binding. */
		fun payloadVideoId(url: String?): String? = url?.let { ID.find(it)?.groupValues?.get(1) }
	}
}

/**
 * Who the card may speak for, and whether that is known right now.
 *
 * [invalidate] runs the moment a key is saved or forgotten: until a read that
 * started *after* that moment lands, nobody is known and no finished listen
 * is shown. A read that started before a newer change is discarded, so rapid
 * A→B→signed-out cannot settle on A or B. Thread-safe.
 */
internal class AccountGate {
	private var generation = Long.MIN_VALUE
	private var current: String? = null
	private var resolved = false

	@Synchronized
	fun invalidate(newGeneration: Long) {
		generation = newGeneration
		current = null
		resolved = false
	}

	/** Apply a read begun at [readGeneration]; false when a newer change superseded it. */
	@Synchronized
	fun resolve(readGeneration: Long, who: String?): Boolean {
		if (readGeneration != generation) return false
		current = who?.trim()?.takeIf { it.isNotEmpty() }
		resolved = true
		return true
	}

	@get:Synchronized
	val known: Boolean get() = resolved

	/** The signed-in account, or null — meaningful only while [known]. */
	@get:Synchronized
	val account: String? get() = current.takeIf { resolved }
}
