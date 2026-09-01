package com.rustedwax.app.detect

/**
 * Holds one exact foreground-Short interval until the browser names the track.
 *
 * Brave can expose `/shorts/<id>` while its sole MediaSession still carries the
 * previous tab title. When the browser is backgrounded it finally publishes the
 * new title, but starts the MediaSession position at zero. The interval before
 * that handoff is real playback evidence: an exact visible URL and one
 * continuously PLAYING controller. It is not identity authority on its own, so
 * [claim] is called only after the video's own page corroborates the title.
 *
 * Any ambiguity, pause, controller replacement or different exact URL spends
 * nothing. A scan already matching its controller starts nothing, which avoids
 * counting the ordinary MediaSession clock twice.
 */
class BrowserForegroundLeadIn {

	data class Observation(
		val packageName: String,
		val videoId: String?,
		val urlGeneration: Long?,
		val isShort: Boolean,
		val atMillis: Long,
	)

	data class Candidate(
		val key: String,
		val live: Boolean,
		val playing: Boolean,
		val describesNamedVideo: Boolean,
	)

	data class Credit(
		val playedMs: Long,
		val startedAtEpochSec: Long,
	)

	private data class Pending(
		val videoId: String,
		val urlGeneration: Long,
		val key: String,
		val startedAtMillis: Long,
		val boundaryInstanceToken: Long? = null,
		val boundaryMillis: Long? = null,
	)

	private val pendingByPackage = mutableMapOf<String, Pending>()

	fun observe(observation: Observation, candidates: List<Candidate>) {
		val packageName = observation.packageName
		val videoId = observation.videoId
		val generation = observation.urlGeneration
		// A host-only toolbar redraw says nothing new. UrlEvidence deliberately
		// retains the exact id through that Chromium presentation change too.
		if (videoId == null) return
		if (!observation.isShort || generation == null || generation <= 0 ||
			observation.atMillis <= 0
		) {
			pendingByPackage.remove(packageName)
			return
		}
		val live = candidates.filter(Candidate::live)
		val sole = live.singleOrNull()
		if (sole == null || !sole.playing) {
			pendingByPackage.remove(packageName)
			return
		}
		val current = pendingByPackage[packageName]
		if (current != null && current.videoId == videoId &&
			current.urlGeneration == generation && current.key == sole.key
		) {
			return
		}
		if (sole.describesNamedVideo) {
			pendingByPackage.remove(packageName)
			return
		}
		pendingByPackage[packageName] = Pending(
			videoId = videoId,
			urlGeneration = generation,
			key = sole.key,
			startedAtMillis = observation.atMillis,
		)
	}

	/** A non-playing callback or controller teardown breaks continuity. */
	fun invalidate(packageName: String, key: String) {
		if (pendingByPackage[packageName]?.key == key) pendingByPackage.remove(packageName)
	}

	/** Bind the first real metadata generation; a later generation cannot inherit it. */
	fun noteMetadataBoundary(
		packageName: String,
		key: String,
		instanceToken: Long,
		atMillis: Long,
	) {
		val pending = pendingByPackage[packageName]?.takeIf { it.key == key } ?: return
		when (pending.boundaryInstanceToken) {
			null -> pendingByPackage[packageName] = pending.copy(
				boundaryInstanceToken = instanceToken,
				boundaryMillis = atMillis,
			)
			instanceToken -> Unit
			else -> pendingByPackage.remove(packageName)
		}
	}

	/**
	 * Spend the interval through the exact metadata-generation boundary.
	 * [nowMillis] is only a freshness check; it is never added to playback, so a
	 * slow page fetch cannot overlap the MediaSession clock already running.
	 */
	fun claim(
		packageName: String,
		videoId: String,
		urlGeneration: Long?,
		key: String,
		instanceToken: Long,
		throughMillis: Long,
		nowMillis: Long,
		currentlySoleAndPlaying: Boolean,
	): Credit? {
		val pending = pendingByPackage[packageName] ?: return null
		val boundToken = pending.boundaryInstanceToken ?: instanceToken
		val boundThroughMillis = pending.boundaryMillis ?: throughMillis
		if (!currentlySoleAndPlaying || urlGeneration == null ||
			pending.videoId != videoId || pending.urlGeneration != urlGeneration ||
			pending.key != key || boundToken != instanceToken ||
			boundThroughMillis <= pending.startedAtMillis ||
			nowMillis < boundThroughMillis || nowMillis - pending.startedAtMillis > MAX_PENDING_MS
		) {
			pendingByPackage.remove(packageName)
			return null
		}
		pendingByPackage.remove(packageName)
		return Credit(
			playedMs = boundThroughMillis - pending.startedAtMillis,
			startedAtEpochSec = pending.startedAtMillis / 1000,
		)
	}

	companion object {
		/** Longer than the maximum ordinary Short, but bounded against stale tabs. */
		const val MAX_PENDING_MS = 5L * 60 * 1000
	}
}
