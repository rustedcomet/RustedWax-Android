package com.rustedwax.app.ui

import com.rustedwax.app.detect.SessionSnapshot
import com.rustedwax.app.detect.YouTubeProbe
import com.rustedwax.hive.HiveScrobblePayload
import kotlin.math.roundToInt

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
		;

		/**
		 * The mark drawn on this session's thumbnail, or null for no mark.
		 *
		 * The badge names the **service**, which is not the same question the
		 * label answers. A browser is an honest thing to call the row's source —
		 * it is where the sound is coming from — but it is the wrong thing to
		 * stamp on the frame, because the frame is a YouTube video and the
		 * person is watching YouTube. So the two browsers fold into YouTube here
		 * and keep their own names above.
		 *
		 * [OTHER] gets **nothing**, and that is the point of the null. It is the
		 * one case where the app does not know the service, and a badge is a
		 * branded claim about whose service the frame belongs to. Folding it
		 * into YouTube would make that claim on the app's behalf about a source
		 * nothing has identified — cheap to write, and wrong in exactly the way
		 * this project refuses everywhere else. No badge says no more than is
		 * known.
		 */
		val badge: ServiceBadge?
			get() = when (this) {
				YOUTUBE_MUSIC -> ServiceBadge.YOUTUBE_MUSIC
				YOUTUBE, BRAVE, CHROME -> ServiceBadge.YOUTUBE
				OTHER -> null
			}
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
			// A Short playing in picture-in-picture publishes no position, so the
			// transport cannot say "playing" for it and this branch used to claim
			// it was paused while inference credited it a second per second
			// (#12). Narrowed rather than relabelled: the actively inferred case
			// falls through to the ordinary statuses below, which still say what
			// would stop this listen counting, and a real PiP pause — where the
			// authorization is withdrawn and nothing is credited — still lands
			// here.
			!session.isPlaying && !session.pipInferredPlaying -> "Paused"
			!identified -> "Identifying video…"
			durationMs == null -> "Waiting for the length…"
			!autoScrobble -> "Automatic scrobbling is off"
			(percent ?: 0.0) * 100 >= thresholdPercent ->
				"Threshold reached — final checks run when this ends"
			else -> "Scrobbles at $thresholdPercent% played"
		}
	}
}
