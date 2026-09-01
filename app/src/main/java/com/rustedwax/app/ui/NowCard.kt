package com.rustedwax.app.ui

import com.rustedwax.app.detect.SessionSnapshot
import com.rustedwax.app.detect.YouTubeProbe
import com.rustedwax.hive.HiveScrobblePayload
import kotlin.math.roundToInt

/**
 * What the Now card says, and everything it is allowed to say.
 *
 * ## Why this is a model rather than a composable
 *
 * The Now card was the diagnostics screen. It drew the package name, the source
 * proof, the browser scan's coverage, the observer's coverage, the video id, the
 * canonical URL, the route that proved the id, the notification hint, the
 * MusicBrainz verdict, the YouTube Music catalogue type, why the kind came out
 * the way it did, the whole prospective payload, a monospace dump of the raw
 * MediaSession metadata, and a button that put the listen on-chain by hand.
 *
 * Every one of those is evidence about *this app*, and none of them answers the
 * question a person opens the app to ask: what is playing, and will it count.
 * That evidence has not been deleted — it is in the event log, which is where a
 * recording of what the app observed belongs, behind a switch, with a retention
 * bound and an Export button.
 *
 * What is left is a closed presentation model. It is a model rather than a
 * `Column` of `Text`s because the point of the change is the *set*: the UI
 * cannot quietly regrow a diagnostic field without changing this type and
 * the `NowCardTest` contract together.
 *
 * Nothing here participates in detection, identity, classification, measurement
 * or eligibility. Every value is read from a snapshot those paths already
 * produced; this decides only how to say it.
 */
internal data class NowCard(
	val platform: Platform,
	/** The channel or artist, whichever the source published. */
	val channel: String?,
	val title: String?,
	val durationText: String?,
	/** 0..1 for the progress bar, or null when nothing knows the length. */
	val progress: Float?,
	val percentText: String?,
	/** The final category — `Song`, `Video`, `Movie`, `Episode`, `Podcast`. */
	val category: String?,
	val status: String?,
) {

	/**
	 * The app the sound is coming from, named the way its icon is labelled.
	 *
	 * A package name is a fact about Android, not about what someone is
	 * listening to, and it was the first line of the old card.
	 */
	enum class Platform(val label: String) {
		YOUTUBE("YouTube"),
		YOUTUBE_MUSIC("YouTube Music"),
		BRAVE("Brave"),
		CHROME("Chrome"),

		/**
		 * Anything else that reached the card. Unreachable while only the
		 * YouTube sources are watched, and present so a future source has a
		 * readable name rather than a package one the day it is added.
		 */
		OTHER("Media"),
	}

	companion object {

		fun platformFor(packageName: String): Platform = when (packageName) {
			YouTubeProbe.YOUTUBE_PACKAGE -> Platform.YOUTUBE
			YouTubeProbe.YOUTUBE_MUSIC_PACKAGE -> Platform.YOUTUBE_MUSIC
			in YouTubeProbe.BRAVE_PACKAGES -> Platform.BRAVE
			in YouTubeProbe.CHROME_PACKAGES -> Platform.CHROME
			else -> Platform.OTHER
		}

		/**
		 * The payload kind in the words people use for it.
		 *
		 * Null rather than a guess: an unresolved kind means the classifier has
		 * not decided, and a card that says `Video` before anything decided is
		 * making a claim the app has not made.
		 */
		fun categoryLabel(kind: String?): String? = when (kind) {
			HiveScrobblePayload.KIND_SONG -> "Song"
			HiveScrobblePayload.KIND_VIDEO -> "Video"
			HiveScrobblePayload.KIND_MOVIE -> "Movie"
			HiveScrobblePayload.KIND_EPISODE -> "Episode"
			HiveScrobblePayload.KIND_PODCAST -> "Podcast"
			else -> null
		}

		/** `4:35`, or `1:02:03` once there is an hour to show. */
		fun durationText(ms: Long?): String? {
			if (ms == null || ms <= 0) return null
			val total = ms / 1000
			val hours = total / 3600
			val minutes = (total % 3600) / 60
			val seconds = total % 60
			return if (hours > 0) {
				"%d:%02d:%02d".format(hours, minutes, seconds)
			} else {
				"%d:%02d".format(minutes, seconds)
			}
		}

		/**
		 * @param durationMs the length the engine would measure against, which
		 * is not always the one the session published — see
		 * [com.rustedwax.app.detect.ScrobbleBuilder.effectiveDurationMs].
		 * @param identified whether an exact video is known yet. Taken as a
		 * boolean rather than an id, so the id itself has no route onto the card.
		 * @param kind the classified payload kind, or null while undecided.
		 */
		fun from(
			session: SessionSnapshot,
			durationMs: Long?,
			identified: Boolean,
			kind: String?,
			thresholdPercent: Int,
			autoScrobble: Boolean,
		): NowCard {
			val percent = session.percentPlayed?.coerceIn(0.0, 1.0)
			return NowCard(
				platform = platformFor(session.packageName),
				channel = session.artist?.takeIf { it.isNotBlank() },
				title = session.title?.takeIf { it.isNotBlank() },
				durationText = durationText(durationMs),
				progress = percent?.toFloat(),
				percentText = percent?.let { "${(it * 100).roundToInt()}%" },
				category = categoryLabel(kind),
				status = status(
					session = session,
					durationMs = durationMs,
					identified = identified,
					percent = percent,
					thresholdPercent = thresholdPercent,
					autoScrobble = autoScrobble,
				),
			)
		}

		/**
		 * One short line, and only where it tells someone something.
		 *
		 * Ordered by what would stop the listen counting soonest: an ad is
		 * refused whatever else is true, an unread title cannot be identified,
		 * an unidentified video cannot be broadcast, and an unknown length has
		 * no threshold to cross. Crossing the progress threshold is deliberately
		 * not called "ready": minimum duration, Shorts proof and disablement,
		 * privacy, mute and dedup remain finalization-owned checks.
		 */
		private fun status(
			session: SessionSnapshot,
			durationMs: Long?,
			identified: Boolean,
			percent: Double?,
			thresholdPercent: Int,
			autoScrobble: Boolean,
		): String = when {
			session.explicitAdSignal != null -> "Advertisement — not counted"
			session.title.isNullOrBlank() -> "Reading what's playing…"
			!identified -> "Identifying video…"
			durationMs == null -> "Waiting for the length…"
			!autoScrobble -> "Automatic scrobbling is off"
			(percent ?: 0.0) * 100 >= thresholdPercent ->
				"Threshold reached — final checks run when this ends"
			else -> "Scrobbles at $thresholdPercent% played"
		}
	}
}
