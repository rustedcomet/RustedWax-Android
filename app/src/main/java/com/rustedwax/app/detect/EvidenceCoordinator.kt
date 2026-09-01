package com.rustedwax.app.detect

import com.rustedwax.core.*
import com.rustedwax.core.SourceSessionId
import com.rustedwax.core.TrackInstanceId
import com.rustedwax.core.EvidenceRun
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * The single mutable owner of observations made during one monitoring run.
 *
 * The listener service owns the production instance. Writers receive that
 * instance through the live probe instead of writing process-global stores,
 * source adapters read it through constructor injection, and [events] replaces
 * the six mutable singleton callback properties previously installed by
 * `SessionProbe.start()`.
 *
 * Lifecycle changes take the write lock. Ordinary operations take its shared
 * side and then synchronize only the addressed [SourceSessionId], so two
 * sources can progress independently while each source retains one total order.
 */
class EvidenceCoordinator : EvidenceRun {

	sealed interface Event {
		val sourceSession: SourceSessionId

		data class NotificationChanged(
			override val sourceSession: SourceSessionId,
		) : Event

		data class UrlChanged(
			override val sourceSession: SourceSessionId,
		) : Event

		data class VisibleAdAccepted(
			override val sourceSession: SourceSessionId,
			val evidence: AdEvidence.Evidence,
		) : Event

		data class HostAdObserved(
			override val sourceSession: SourceSessionId,
			val observation: MediaSessionAdEvidence.Observation,
		) : Event

		data class ScreenScanned(
			override val sourceSession: SourceSessionId,
			val scan: MediaSessionAccessibilityEvidence.Scan,
		) : Event

		data class NativeShortObserved(
			override val sourceSession: SourceSessionId,
			val event: NativeShortsObserver.Event,
		) : Event
	}

	private data class SourceState(
		var notifications: List<NotificationHints.Hint> = emptyList(),
		var url: UrlEvidence.Evidence? = null,
		var playlist: Pair<String, Long>? = null,
		var urlGeneration: Long = 0,
		var visibleAd: VisibleAdState? = null,
		var establishedVisibleAd: VisibleAdInstance? = null,
		val nativePlaylistLatch: NativePlaylistLatch = NativePlaylistLatch(),
		var nativePlaylist: NativePlaylistLatch.Held? = null,
	)

	private data class VisibleAdInstance(val videoId: String, val generation: Long)

	private data class VisibleAdState(
		val instance: VisibleAdInstance,
		val provisional: AdEvidence.Evidence? = null,
		val accepted: AdEvidence.Evidence? = null,
	)

	private data class TrackKey(
		val sourceSession: SourceSessionId,
		val trackInstance: TrackInstanceId,
	)

	private data class TrackState(
		val establishedAtMillis: Long,
		val instance: MediaSessionAdEvidence.TrackInstance? = null,
		var provisionalAd: MediaSessionAdEvidence.Observation? = null,
		var acceptedAd: MediaSessionAdEvidence.Evidence? = null,
		var coverage: MediaSessionAccessibilityEvidence.Coverage? = null,
		var lastSuccessfulRootAtMillis: Long? = null,
		var outage: Boolean = false,
	)

	private val lifecycle = ReentrantReadWriteLock()
	private val sources = ConcurrentHashMap<SourceSessionId, SourceState>()
	private val tracks = ConcurrentHashMap<TrackKey, TrackState>()
	private val nextTrackToken = AtomicLong(1)
	private val mutableEvents = MutableSharedFlow<Event>(extraBufferCapacity = EVENT_BUFFER)

	val events: SharedFlow<Event> = mutableEvents.asSharedFlow()

	@Volatile
	private var active = false

	@Volatile
	var runGeneration: Long = 0
		private set

	fun start() = lifecycle.write {
		if (active) return@write
		clearLocked()
		runGeneration = RUN_GENERATION.incrementAndGet()
		active = true
	}

	fun stop() = lifecycle.write {
		if (!active) return@write
		active = false
		clearLocked()
	}

	/** Reset this active owner without reusing any observation from its old generation. */
	fun reset() = lifecycle.write {
		if (!active) return@write
		clearLocked()
		runGeneration = RUN_GENERATION.incrementAndGet()
	}

	fun resetSource(sourceSession: SourceSessionId) = lifecycle.write {
		if (!active) return@write
		sources.remove(sourceSession)
		tracks.keys.removeIf { it.sourceSession == sourceSession }
	}

	fun resetPackage(packageName: String) = lifecycle.write {
		if (!active) return@write
		sources.keys.removeIf { it.packageName == packageName }
		tracks.keys.removeIf { it.sourceSession.packageName == packageName }
	}

	fun putNotification(
		sourceSession: SourceSessionId,
		hint: NotificationHints.Hint,
	): Boolean = activeRead(false) {
		val state = sourceState(sourceSession)
		val changed = synchronized(state) {
			val existing = state.notifications
			val without = existing.filterNot { it.title == hint.title && it.host == hint.host }
			state.notifications = (listOf(hint) + without).take(MAX_NOTIFICATIONS_PER_SOURCE)
			val previous = existing.firstOrNull()
			previous == null || previous.host != hint.host || previous.title != hint.title
		}
		if (changed) mutableEvents.tryEmit(Event.NotificationChanged(sourceSession))
		true
	}

	fun notificationFor(
		sourceSession: SourceSessionId,
		title: String?,
		artist: String?,
		soleSession: Boolean,
		nowMillis: Long = System.currentTimeMillis(),
	): NotificationHints.Hint? = activeRead(null) {
		val state = sources[sourceSession] ?: return@activeRead null
		synchronized(state) {
			val hints = state.notifications
			if (hints.isEmpty()) return@synchronized null
			if (!title.isNullOrBlank()) {
				hints.firstOrNull { it.title.equalsTrimmed(title) }?.let { return@synchronized it }
			}
			if (!artist.isNullOrBlank()) {
				hints.firstOrNull {
					it.text.equalsTrimmed(artist) && nowMillis - it.atMillis <= HINT_FALLBACK_MS
				}?.let { return@synchronized it }
			}
			val newest = hints.first()
			newest.takeIf { soleSession && nowMillis - it.atMillis <= HINT_FALLBACK_MS }
		}
	}

	fun removeNotification(sourceSession: SourceSessionId, title: String?) = activeRead(Unit) {
		val state = sources[sourceSession] ?: return@activeRead
		synchronized(state) {
			state.notifications = if (title == null) {
				emptyList()
			} else {
				state.notifications.filterNot { it.title.equalsTrimmed(title) }
			}
		}
	}

	fun putUrl(
		sourceSession: SourceSessionId,
		evidence: UrlEvidence.Evidence,
	): UrlEvidence.Evidence? = activeRead(null) {
		val state = sourceState(sourceSession)
		val result = synchronized(state) {
			val previous = state.url
			if (previous?.videoId != null && evidence.videoId == null && previous.host == evidence.host) {
				return@synchronized previous to false
			}
			val changed = previous == null ||
				previous.host != evidence.host ||
				previous.videoId != evidence.videoId ||
				previous.isShort != evidence.isShort ||
				previous.playlistId != evidence.playlistId
			if (changed) state.urlGeneration += 1
			val stored = evidence.copy(generation = state.urlGeneration)
			state.url = stored
			stored.playlistId?.let { state.playlist = it to stored.atMillis }
			val instance = stored.videoId?.takeIf(VIDEO_ID::matches)
				?.let { VisibleAdInstance(it, stored.generation) }
			if (state.visibleAd?.instance != instance) state.visibleAd = null
			if (state.establishedVisibleAd != instance) state.establishedVisibleAd = null
			stored to changed
		}
		if (result.second) mutableEvents.tryEmit(Event.UrlChanged(sourceSession))
		result.first
	}

	fun urlFor(
		sourceSession: SourceSessionId,
		nowMillis: Long = System.currentTimeMillis(),
	): UrlEvidence.Evidence? = activeRead(null) {
		val state = sources[sourceSession] ?: return@activeRead null
		synchronized(state) {
			state.url?.takeIf { nowMillis - it.atMillis <= URL_FRESH_MS }
		}
	}

	fun playlistFor(
		sourceSession: SourceSessionId,
		nowMillis: Long = System.currentTimeMillis(),
	): String? = activeRead(null) {
		val state = sources[sourceSession] ?: return@activeRead null
		synchronized(state) {
			state.playlist?.takeIf { nowMillis - it.second <= PLAYLIST_FRESH_MS }?.first
		}
	}

	fun urlGeneration(sourceSession: SourceSessionId): Long = activeRead(0) {
		val state = sources[sourceSession] ?: return@activeRead 0
		synchronized(state) { state.urlGeneration }
	}

	/** URL navigation owns only URL state. It cannot erase any other evidence layer. */
	fun clearUrl(sourceSession: SourceSessionId) = activeRead(Unit) {
		val state = sources[sourceSession] ?: return@activeRead
		synchronized(state) {
			state.url = null
			state.playlist = null
			state.urlGeneration = 0
		}
	}

	fun markVisibleAdSessionEstablished(
		sourceSession: SourceSessionId,
		videoId: String,
		generation: Long,
	): Boolean = activeRead(false) {
		if (generation <= 0 || !VIDEO_ID.matches(videoId)) return@activeRead false
		val state = sourceState(sourceSession)
		synchronized(state) {
			state.establishedVisibleAd = VisibleAdInstance(videoId, generation)
		}
		true
	}

	fun observeVisibleAd(
		sourceSession: SourceSessionId,
		evidence: AdEvidence.Evidence,
	): AdEvidence.Evidence? = activeRead(null) {
		if (evidence.packageName != sourceSession.packageName ||
			!VIDEO_ID.matches(evidence.videoId) || evidence.signal.isBlank() ||
			evidence.urlGeneration <= 0
		) return@activeRead null
		val state = sourceState(sourceSession)
		val accepted = synchronized(state) {
			val instance = VisibleAdInstance(evidence.videoId, evidence.urlGeneration)
			val prior = state.visibleAd?.takeIf { it.instance == instance }
			prior?.accepted?.takeIf { it.signal == evidence.signal }?.let {
				val refreshed = evidence.copy(atMillis = evidence.atMillis)
				state.visibleAd = VisibleAdState(instance, accepted = refreshed)
				return@synchronized refreshed
			}
			if (state.establishedVisibleAd == instance ||
				prior?.provisional?.signal == evidence.signal
			) {
				state.visibleAd = VisibleAdState(instance, accepted = evidence)
				evidence
			} else {
				state.visibleAd = VisibleAdState(instance, provisional = evidence)
				null
			}
		}
		accepted?.also { mutableEvents.tryEmit(Event.VisibleAdAccepted(sourceSession, it)) }
	}

	fun visibleAd(
		sourceSession: SourceSessionId,
		videoId: String,
		urlGeneration: Long? = null,
		nowMillis: Long = System.currentTimeMillis(),
	): AdEvidence.Evidence? = activeRead(null) {
		val state = sources[sourceSession] ?: return@activeRead null
		synchronized(state) {
			state.visibleAd?.accepted?.takeIf {
				it.videoId == videoId &&
					(urlGeneration == null || it.urlGeneration == urlGeneration) &&
					nowMillis >= it.atMillis && nowMillis - it.atMillis <= TRACK_AD_FRESH_MS
			}
		}
	}

	fun visibleAdLabelAbsent(
		sourceSession: SourceSessionId,
		videoId: String?,
		generation: Long,
	) = activeRead(Unit) {
		val state = sources[sourceSession] ?: return@activeRead
		synchronized(state) {
			val current = state.visibleAd ?: return@synchronized
			if (videoId != null && current.instance == VisibleAdInstance(videoId, generation)) {
				state.visibleAd = null
			}
		}
	}

	fun allocateTrackInstance(sourceSession: SourceSessionId): TrackInstanceId? = activeRead(null) {
		TrackInstanceId(sourceSession.packageName, nextTrackToken.getAndIncrement())
	}

	fun bindTrack(
		sourceSession: SourceSessionId,
		trackInstance: TrackInstanceId,
		establishedAtMillis: Long,
	): Boolean = activeRead(false) {
		if (trackInstance.packageName != sourceSession.packageName || establishedAtMillis <= 0) {
			return@activeRead false
		}
		tracks.putIfAbsent(TrackKey(sourceSession, trackInstance), TrackState(establishedAtMillis))
		true
	}

	fun bindTrack(
		sourceSession: SourceSessionId,
		instance: MediaSessionAdEvidence.TrackInstance,
		establishedAtMillis: Long,
	): Boolean = activeRead(false) {
		if (!validInstance(sourceSession, instance) || establishedAtMillis <= 0) return@activeRead false
		val key = trackKey(sourceSession, instance)
		val prior = tracks[key]
		if (prior == null || prior.instance?.let { !sameInstance(it, instance) } == true) {
			tracks[key] = TrackState(establishedAtMillis, instance)
		}
		true
	}

	fun releaseTrack(sourceSession: SourceSessionId, trackInstance: TrackInstanceId) =
		activeRead(Unit) { tracks.remove(TrackKey(sourceSession, trackInstance)) }

	fun acceptTrackAd(
		sourceSession: SourceSessionId,
		trackInstance: TrackInstanceId,
		signal: String,
		atMillis: Long,
	): Boolean = activeRead(false) {
		if (signal.isBlank()) return@activeRead false
		val state = tracks[TrackKey(sourceSession, trackInstance)] ?: return@activeRead false
		val instance = state.instance ?: MediaSessionAdEvidence.TrackInstance(
			trackInstance.packageName,
			trackInstance.instanceToken,
			TrackIdentity("coordinator test", null, null, null),
		)
		synchronized(state) {
			state.acceptedAd = MediaSessionAdEvidence.Evidence(instance, signal, atMillis)
		}
		true
	}

	fun trackAd(
		sourceSession: SourceSessionId,
		trackInstance: TrackInstanceId,
		nowMillis: Long = System.currentTimeMillis(),
	): String? = activeRead(null) {
		val state = tracks[TrackKey(sourceSession, trackInstance)] ?: return@activeRead null
		synchronized(state) {
			state.acceptedAd?.takeIf {
				nowMillis >= it.atMillis && nowMillis - it.atMillis <= TRACK_AD_FRESH_MS
			}?.signal
		}

	}

	fun observeTrackAd(
		sourceSession: SourceSessionId,
		observation: MediaSessionAdEvidence.Observation,
		instance: MediaSessionAdEvidence.TrackInstance,
		instanceEstablishedBeforeObservation: Boolean,
		unambiguous: Boolean,
	): MediaSessionAdEvidence.Evidence? = activeRead(null) {
		if (!validInstance(sourceSession, instance) || observation.packageName != instance.packageName) {
			return@activeRead null
		}
		val state = tracks[trackKey(sourceSession, instance)] ?: return@activeRead null
		synchronized(state) {
			if (state.instance?.let { !sameInstance(it, instance) } != false) return@synchronized null
			val signal = observation.signal?.trim()?.takeIf(String::isNotEmpty)
			if (signal == null || !unambiguous) {
				state.provisionalAd = null
				return@synchronized null
			}
			if (observation.atMillis - (state.provisionalAd?.atMillis ?: observation.atMillis) >
				MediaSessionAdEvidence.PROVISIONAL_TTL_MS
			) state.provisionalAd = null
			state.acceptedAd?.takeIf { it.signal == signal }?.let {
				return@synchronized it.copy(atMillis = observation.atMillis).also { refreshed ->
					state.acceptedAd = refreshed
				}
			}
			if (instanceEstablishedBeforeObservation || state.provisionalAd?.signal == signal) {
				MediaSessionAdEvidence.Evidence(instance, signal, observation.atMillis).also {
					state.acceptedAd = it
					state.provisionalAd = null
				}
			} else {
				state.provisionalAd = observation
				null
			}
		}
	}

	fun restoreTrackAd(
		sourceSession: SourceSessionId,
		instance: MediaSessionAdEvidence.TrackInstance,
		signal: String,
		atMillis: Long,
	): Boolean = activeRead(false) {
		if (signal.isBlank()) return@activeRead false
		val state = tracks[trackKey(sourceSession, instance)] ?: return@activeRead false
		synchronized(state) {
			if (state.instance?.let { !sameInstance(it, instance) } != false) return@synchronized false
			state.acceptedAd = MediaSessionAdEvidence.Evidence(instance, signal, atMillis)
			true
		}
	}

	fun clearTrackAdProvisional(sourceSession: SourceSessionId) = activeRead(Unit) {
		tracks.entries.asSequence()
			.filter { it.key.sourceSession == sourceSession }
			.forEach { synchronized(it.value) { it.value.provisionalAd = null } }
	}

	fun observeCoverage(
		sourceSession: SourceSessionId,
		scan: MediaSessionAccessibilityEvidence.Scan,
		instance: MediaSessionAdEvidence.TrackInstance,
		unambiguous: Boolean,
		namedThisInstance: Boolean = false,
	): MediaSessionAccessibilityEvidence.Coverage? = activeRead(null) {
		if (!(unambiguous || namedThisInstance) || !isSuccessfulYouTubeScan(scan) ||
			scan.packageName != instance.packageName
		) return@activeRead null
		val candidates = tracks.entries.filter { it.key.sourceSession == sourceSession }
		if (candidates.size != 1 && !namedThisInstance) return@activeRead null
		val state = tracks[trackKey(sourceSession, instance)] ?: return@activeRead null
		synchronized(state) {
			if (state.instance?.let { !sameInstance(it, instance) } != false ||
				scan.atMillis < state.establishedAtMillis
			) return@synchronized null
			MediaSessionAccessibilityEvidence.Coverage(
				instance = instance,
				atMillis = scan.atMillis,
				urlGeneration = scan.urlGeneration?.takeIf { it > 0 },
				videoId = scan.videoId,
				lifecycleEpoch = runGeneration,
			).also {
				state.coverage = it
				state.lastSuccessfulRootAtMillis = scan.atMillis
			}
		}
	}

	fun currentCoverage(
		sourceSession: SourceSessionId,
		instance: MediaSessionAdEvidence.TrackInstance,
		expectedUrlGeneration: Long? = null,
		nowMillis: Long = System.currentTimeMillis(),
	): MediaSessionAccessibilityEvidence.Coverage? = activeRead(null) {
		val state = tracks[trackKey(sourceSession, instance)] ?: return@activeRead null
		synchronized(state) {
			val coverage = state.coverage ?: return@synchronized null
			if (coverage.lifecycleEpoch != runGeneration || nowMillis < coverage.atMillis ||
				nowMillis - coverage.atMillis > MediaSessionAccessibilityEvidence.COVERAGE_FRESH_MS ||
				(expectedUrlGeneration != null && coverage.urlGeneration != null &&
					coverage.urlGeneration != expectedUrlGeneration)
			) {
				state.coverage = null
				null
			} else coverage
		}
	}

	fun restoreCoverage(
		sourceSession: SourceSessionId,
		instance: MediaSessionAdEvidence.TrackInstance,
		coverage: MediaSessionAccessibilityEvidence.Coverage,
		nowMillis: Long = System.currentTimeMillis(),
	): Boolean = activeRead(false) {
		if (!sameInstance(instance, coverage.instance) || coverage.lifecycleEpoch != runGeneration ||
			nowMillis < coverage.atMillis ||
			nowMillis - coverage.atMillis > MediaSessionAccessibilityEvidence.COVERAGE_FRESH_MS
		) return@activeRead false
		val state = tracks[trackKey(sourceSession, instance)] ?: return@activeRead false
		synchronized(state) {
			if (state.instance?.let { !sameInstance(it, instance) } != false) return@synchronized false
			state.coverage = coverage.copy(instance = instance)
			state.lastSuccessfulRootAtMillis = coverage.atMillis
			true
		}
	}

	fun isCoverageLifecycleCurrent(coverage: MediaSessionAccessibilityEvidence.Coverage): Boolean =
		activeRead(false) { coverage.lifecycleEpoch == runGeneration }

	fun noteRefreshResult(
		successfulPackageName: String?,
		nowMillis: Long = System.currentTimeMillis(),
	): List<MediaSessionAccessibilityEvidence.FreshnessTransition> = activeRead(emptyList()) {
		val transitions = mutableListOf<MediaSessionAccessibilityEvidence.FreshnessTransition>()
		val bySource = tracks.entries.groupBy { it.key.sourceSession }
		for ((sourceSession, entries) in bySource) {
			if (sourceSession.packageName == successfulPackageName) {
				entries.forEach { (_, state) -> synchronized(state) {
					state.lastSuccessfulRootAtMillis = nowMillis
					if (state.outage) {
						state.outage = false
						transitions += MediaSessionAccessibilityEvidence.FreshnessTransition(
							sourceSession.packageName,
							MediaSessionAccessibilityEvidence.Freshness.RECOVERED,
							nowMillis,
						)
					}
				} }
				continue
			}
			val reference = entries.maxOfOrNull { (_, state) ->
				synchronized(state) { state.lastSuccessfulRootAtMillis ?: state.establishedAtMillis }
			} ?: continue
			if (nowMillis - reference >= MediaSessionAccessibilityEvidence.OUTAGE_AFTER_MS &&
				entries.none { (_, state) -> synchronized(state) { state.outage } }
			) {
				entries.forEach { (_, state) -> synchronized(state) { state.outage = true } }
				transitions += MediaSessionAccessibilityEvidence.FreshnessTransition(
					sourceSession.packageName,
					MediaSessionAccessibilityEvidence.Freshness.OUTAGE,
					nowMillis,
				)
			}
		}
		transitions.distinctBy { it.packageName to it.freshness }
	}

	fun observeNativePlaylist(
		sourceSession: SourceSessionId,
		result: NativePlaylistParser.Result,
		nowMillis: Long,
	): NativePlaylistLatch.Held? = activeRead(null) {
		val state = sourceState(sourceSession)
		synchronized(state) {
			state.nativePlaylist = when (val decision = state.nativePlaylistLatch.observe(result, nowMillis)) {
				is NativePlaylistLatch.Decision.Latched -> decision.held
				is NativePlaylistLatch.Decision.Retained -> decision.held
				is NativePlaylistLatch.Decision.Idle -> null
			}
			state.nativePlaylist
		}
	}

	fun nativePlaylist(sourceSession: SourceSessionId): NativePlaylistLatch.Held? = activeRead(null) {
		val state = sources[sourceSession] ?: return@activeRead null
		synchronized(state) { state.nativePlaylist }
	}

	fun clearNativePlaylist(sourceSession: SourceSessionId) = activeRead(Unit) {
		val state = sources[sourceSession] ?: return@activeRead
		synchronized(state) {
			state.nativePlaylistLatch.reset()
			state.nativePlaylist = null
		}
	}

	fun emit(event: Event): Boolean = activeRead(false) { mutableEvents.tryEmit(event) }

	private fun sourceState(sourceSession: SourceSessionId): SourceState =
		sources.computeIfAbsent(sourceSession) { SourceState() }

	private fun trackKey(
		sourceSession: SourceSessionId,
		instance: MediaSessionAdEvidence.TrackInstance,
	) = TrackKey(sourceSession, TrackInstanceId(instance.packageName, instance.token))

	private fun validInstance(
		sourceSession: SourceSessionId,
		instance: MediaSessionAdEvidence.TrackInstance,
	): Boolean = instance.packageName == sourceSession.packageName && instance.token > 0 &&
		instance.signature.isUsable

	private fun sameInstance(
		first: MediaSessionAdEvidence.TrackInstance,
		second: MediaSessionAdEvidence.TrackInstance,
	): Boolean = first.packageName == second.packageName && first.token == second.token &&
		first.signature.sameTrackAs(second.signature)

	private fun isSuccessfulYouTubeScan(scan: MediaSessionAccessibilityEvidence.Scan): Boolean =
		scan.rootVisible && scan.packageName in YouTubeProbe.TARGET_PACKAGES &&
			YouTubeProbe.isYouTubeHost(scan.host)

	private inline fun <T> activeRead(fallback: T, block: () -> T): T = lifecycle.read {
		if (!active) fallback else block()
	}

	private fun clearLocked() {
		sources.clear()
		tracks.clear()
		nextTrackToken.set(1)
	}

	private fun String?.equalsTrimmed(other: String?): Boolean {
		val first = this?.trim() ?: return false
		val second = other?.trim() ?: return false
		return first.isNotEmpty() && first.equals(second, ignoreCase = true)
	}

	private companion object {
		val RUN_GENERATION = AtomicLong(0)
		const val EVENT_BUFFER = 256
		const val MAX_NOTIFICATIONS_PER_SOURCE = 4
		const val HINT_FALLBACK_MS = 90_000L
		const val URL_FRESH_MS = 5L * 60 * 1_000
		const val PLAYLIST_FRESH_MS = 3L * 60 * 60 * 1_000
		const val TRACK_AD_FRESH_MS = 30_000L
		val VIDEO_ID = Regex("""[A-Za-z0-9_-]{11}""")
	}
}
