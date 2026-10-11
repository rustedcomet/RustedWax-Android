package com.rustedwax.app.status

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.rustedwax.app.R
import com.rustedwax.app.detect.MonitorSwitch
import com.rustedwax.app.detect.NativeSourceSwitches
import com.rustedwax.app.detect.PlayerArtwork
import com.rustedwax.app.detect.ProbeHolder
import com.rustedwax.app.detect.ScrobbleBuilder
import com.rustedwax.app.detect.SessionSnapshot
import com.rustedwax.app.scrobble.DispatchLink
import com.rustedwax.app.scrobble.FinalizationObserver
import com.rustedwax.app.scrobble.FinalizationOutcome
import com.rustedwax.app.scrobble.FinalizationRuntime
import com.rustedwax.app.storage.HiveAccountChanges
import com.rustedwax.app.storage.KeyVault
import com.rustedwax.app.storage.Settings
import com.rustedwax.app.ui.NowPreview
import com.rustedwax.app.ui.Thumbnails
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The one scrobbling-status card in the shade (#4).
 *
 * Owned by the notification listener, so it lives exactly as long as detection
 * does and needs no service of its own: no foreground service, no MediaSession,
 * no MediaStyle and no transport controls. It reads what already exists — the
 * probe's sessions and the reducer's credited clock, the engine's outcome for
 * each finished listen, History, and the display-only player artwork — and
 * writes nothing anywhere but the shade.
 *
 * Updates are event-driven and coalesced to at most one post a second; the
 * only timer is a five-second refresh of the credited clock, which runs only
 * while something is being credited and the screen is on.
 */
internal class ScrobbleStatusNotification(
	context: Context,
	/** The screen a tap opens; [ACTION_OPEN_NOW] tells it which page. */
	private val target: Class<*>,
) {
	private val context = context.applicationContext
	private val manager = NotificationManagerCompat.from(this.context)
	private val ledger = FinishedListenLedger()
	private val settings by lazy { Settings(this.context) }
	private var scope: CoroutineScope? = null
	private var renderJob: Job? = null
	private var lastRenderAt = 0L
	private var posted: Posted? = null
	private var monitoring = false

	/** Who the card may speak for; see [AccountGate] and [accountChanged]. */
	private val gate = AccountGate()

	private val observer = object : FinalizationObserver {
		// Engine threads. Each only records; none can change what the engine does.
		override fun onFinalizationOutcome(session: SessionSnapshot, outcome: FinalizationOutcome) {
			ledger.outcome(session.trackInstance, outcome, now())
			requestRender()
		}

		override fun onOperationCreated(link: DispatchLink, operationId: String, account: String) {
			ledger.operationCreated(link, operationId, account)
			requestRender()
		}

		override fun onDispatchEnded(link: DispatchLink, reason: String) {
			ledger.dispatchEnded(link, reason)
			requestRender()
		}
	}

	private data class Posted(val view: ScrobbleStatusView, val art: Any?, val since: Long)

	/** The prepared picture for one listen, so a frame is scaled once, not per post. */
	private var art: Triple<ArtworkKey, Any, Bitmap>? = null
	private val thumbnailRequests = HashSet<String>()

	@OptIn(ExperimentalCoroutinesApi::class)
	fun start() {
		stop()
		Thumbnails.init(context)
		val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
		scope = s
		FinalizationRuntime.finalizationObserver = observer
		s.launch { HiveAccountChanges.generation.collect(::accountChanged) }
		s.launch {
			MonitorSwitch.enabled.collect { on ->
				monitoring = on
				if (!on) {
					// Stop means nothing is being tracked: no card, and no
					// leftover outcome to resurface when monitoring returns.
					ledger.clear()
					cancelCard()
				} else {
					requestRender()
				}
			}
		}
		s.launch {
			ProbeHolder.probe
				.flatMapLatest { it?.sessions ?: flowOf(emptyList()) }
				.collect { requestRender() }
		}
		s.launch {
			FinalizationRuntime.recent.collect { rows ->
				ledger.history(rows)
				requestRender()
			}
		}
		s.launch { PlayerArtwork.frames.collect { requestRender() } }
		s.launch { NativeSourceSwitches.config.collect { requestRender() } }
		s.launch {
			// The credited clock. Nothing publishes while a track simply plays,
			// so this is what moves the bar; it is also what lets a held result
			// or a footnote age out, since nothing else happens when one does.
			// Never with the screen off, where nobody can see it, and an
			// unchanged view is never re-posted.
			while (true) {
				delay(TICK_MS)
				val view = posted?.view ?: continue
				if ((view.crediting || view.ages) && interactive()) requestRender()
			}
		}
	}

	/** Listener torn down: say nothing rather than something stale. */
	fun stop() {
		if (FinalizationRuntime.finalizationObserver === observer) {
			FinalizationRuntime.finalizationObserver = FinalizationObserver.None
		}
		scope?.cancel()
		scope = null
		renderJob = null
		ledger.clear()
		gate.invalidate(Long.MIN_VALUE)
		cancelCard()
	}

	/**
	 * A listen ended and is going to the engine. Called by the listener just
	 * before it hands the track over, on the main thread.
	 */
	fun onTrackFinalizing(snapshot: SessionSnapshot) {
		if (!FinalizationRuntime.isReady) return
		val title = snapshot.title?.takeIf { it.isNotBlank() } ?: return
		ledger.finalized(
			FinishedListen(
				listen = snapshot.trackInstance,
				title = title,
				artist = snapshot.artist?.takeIf { it.isNotBlank() },
				source = ScrobbleStatusPresenter.sourceLine(snapshot),
				creditedMs = snapshot.playedMs,
				durationMs = snapshot.durationMs,
				// Unknown while a read is in flight: then nobody owns it, and it
				// is never shown. Never the previous account by default.
				account = gate.account,
				ownerKnown = gate.known,
				finishedAtMs = now(),
				videoId = snapshot.confirmed?.videoId ?: snapshot.resolverContext.preResolvedNativeVideoId,
				refusalVisible = maxOf(snapshot.playedMs, snapshot.unattributedMeasuredMs) >=
					FinalizationRuntime.MIN_NOTABLE_PLAYED_MS && snapshot.isYouTube,
			),
		)
		requestRender()
	}

	private fun requestRender() {
		val s = scope ?: return
		if (renderJob?.isActive == true) return
		renderJob = s.launch {
			val wait = lastRenderAt + MIN_INTERVAL_MS - SystemClock.elapsedRealtime()
			if (wait > 0) delay(wait)
			lastRenderAt = SystemClock.elapsedRealtime()
			runCatching { render() }
		}
	}

	private fun render() {
		if (!monitoring) return cancelCard()
		val probe = ProbeHolder.current
		val sessions = probe?.sessions?.value.orEmpty()
		val credits = runCatching { probe?.creditedNow() }.getOrNull().orEmpty()
		val live = sessions.map { s ->
			val preview = NowPreview.from(
				session = s,
				cachedFacts = FinalizationRuntime::cachedFacts,
				cachedMusicMatch = FinalizationRuntime::cachedMusicMatch,
			)
			LiveListen(
				session = s,
				creditedMs = credits[s.trackInstance] ?: s.playedMs,
				videoId = preview.videoId,
				effectiveDurationMs = ScrobbleBuilder.effectiveDurationMs(s, preview.facts),
			)
		}
		val view = ScrobbleStatusPresenter.present(
			live = live,
			finished = if (gate.known) ledger.all() else emptyList(),
			account = gate.account,
			thresholdPercent = settings.thresholdPercent,
			autoScrobble = FinalizationRuntime.autoScrobbleEnabled(),
			thumbnailsAllowed = NativeSourceSwitches.config.value.youTubeScrobbling,
			nowMs = now(),
		)
		val picture = artworkFor(view.artwork)
		val previous = posted
		if (previous != null && previous.view == view && previous.art === picture) return
		// Inline, for the reason the Snap notifier gives: lint wants the
		// POST_NOTIFICATIONS check in the method that calls notify. A refusal is
		// silent: the card is a convenience, never a reason to nag.
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
			ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
			PackageManager.PERMISSION_GRANTED
		) {
			posted = null
			return
		}
		if (!channelOpen()) {
			posted = null
			return
		}
		val since = if (previous != null && sameState(previous.view, view)) previous.since else System.currentTimeMillis()
		// Guarded as well: the grant can be revoked between the check and here.
		runCatching { manager.notify(ID, build(view, picture, since)) }
			.onSuccess { posted = Posted(view, picture, since) }
	}

	/** Same listen and same status: the card's age keeps counting. */
	private fun sameState(a: ScrobbleStatusView, b: ScrobbleStatusView): Boolean =
		a.headline == b.headline && a.status == b.status

	private fun cancelCard() {
		posted = null
		art = null
		runCatching { manager.cancel(ID) }
	}

	/**
	 * The picture for [key], prepared once and only ever for that listen.
	 *
	 * The order the Now card uses: the exact video's thumbnail when the lookup
	 * is allowed and cached, else the frame the player attached to *this*
	 * listen under *this* title. A thumbnail not yet cached is fetched once
	 * through the shared pipeline, and its arrival only asks for a render —
	 * which re-reads whichever listen is current, so a late picture can never
	 * land on a different one.
	 */
	private fun artworkFor(key: ArtworkKey?): Bitmap? {
		if (key == null) return null
		val thumbnail = key.videoId?.let { id ->
			Thumbnails.cached(id) ?: run {
				if (!Thumbnails.isAbsent(id) && thumbnailRequests.add(id)) {
					scope?.launch {
						val loaded = withContext(Dispatchers.IO) { runCatching { Thumbnails.load(id) }.getOrNull() }
						// A transient miss may be asked again on a later listen.
						if (loaded == null) thumbnailRequests.remove(id)
						requestRender()
					}
				}
				null
			}
		}
		val source: Any? = thumbnail ?: if (key.live) {
			PlayerArtwork.frames.value[key.listen]?.takeIf { frameMatches(key, it.title) }?.bitmap
		} else {
			null
		}
		if (source == null) return null
		art?.let { (k, s, prepared) -> if (k == key && s === source) return prepared }
		val bitmap = (source as? Bitmap) ?: (source as androidx.compose.ui.graphics.ImageBitmap).asAndroidBitmap()
		val prepared = runCatching { roundedSquare(bitmap, ART_PX) }.getOrNull() ?: return null
		art = Triple(key, source, prepared)
		return prepared
	}

	private fun frameMatches(key: ArtworkKey, frameTitle: String): Boolean =
		frameTitle.trim() == key.title.trim()

	private fun build(view: ScrobbleStatusView, picture: Bitmap?, since: Long) =
		NotificationCompat.Builder(context, CHANNEL)
			.setSmallIcon(R.drawable.ic_notification_rustedwax)
			.setColor(ContextCompat.getColor(context, R.color.status_pink))
			.setSubText(view.header)
			.setContentTitle(view.headline)
			.setContentText(view.status.label)
			.setStyle(NotificationCompat.DecoratedCustomViewStyle())
			.setCustomContentView(collapsed(view, picture))
			.setCustomBigContentView(expanded(view, picture))
			.setContentIntent(openNow())
			.setOngoing(true)
			.setOnlyAlertOnce(true)
			.setSilent(true)
			.setLocalOnly(true)
			.setShowWhen(true)
			.setWhen(since)
			.setGroup(GROUP)
			.setCategory(NotificationCompat.CATEGORY_STATUS)
			.setPriority(NotificationCompat.PRIORITY_LOW)
			// What is playing is nobody's business on a locked screen.
			.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
			.setPublicVersion(
				NotificationCompat.Builder(context, CHANNEL)
					.setSmallIcon(R.drawable.ic_notification_rustedwax)
					.setColor(ContextCompat.getColor(context, R.color.status_pink))
					.setContentTitle(context.getString(R.string.app_name))
					.setContentText(if (view.track == null) "Active" else "Tracking a scrobble")
					.setSilent(true)
					.build(),
			)
			.build()

	private fun collapsed(view: ScrobbleStatusView, picture: Bitmap?) =
		RemoteViews(context.packageName, R.layout.notification_status_collapsed).apply {
			setTextViewText(R.id.collapsed_title, view.headline)
			setTextViewText(R.id.collapsed_status, if (view.track == null) view.status.pill else view.status.compact)
			setImageViewResource(R.id.collapsed_icon, iconFor(view.status.tone))
			setInt(R.id.collapsed_icon, "setColorFilter", toneColor(view.status.tone))
			if (picture != null) {
				setImageViewBitmap(R.id.collapsed_art, picture)
				setViewVisibility(R.id.collapsed_art, View.VISIBLE)
			} else {
				setViewVisibility(R.id.collapsed_art, View.GONE)
			}
		}

	private fun expanded(view: ScrobbleStatusView, picture: Bitmap?) =
		RemoteViews(context.packageName, R.layout.notification_status_expanded).apply {
			val track = view.track
			setViewVisibility(R.id.idle_block, if (track == null) View.VISIBLE else View.GONE)
			setViewVisibility(R.id.track_block, if (track == null) View.GONE else View.VISIBLE)
			// Idle stays one small line: no tap hint under it.
			setViewVisibility(R.id.footer_divider, if (track == null) View.GONE else View.VISIBLE)
			setViewVisibility(R.id.footer, if (track == null) View.GONE else View.VISIBLE)
			if (track == null) {
				setTextViewText(R.id.idle_title, view.status.pill)
				setTextViewText(R.id.idle_text, view.status.detail.orEmpty())
			}
			if (track != null) {
				if (picture != null) {
					setImageViewBitmap(R.id.art, picture)
				} else {
					// The brand, not somebody else's picture, until one is known.
					setImageViewResource(R.id.art, R.drawable.rustedwax_mark)
				}
				setTextViewText(R.id.title, track.title)
				setTextViewText(R.id.artist, track.artist.orEmpty())
				setViewVisibility(R.id.artist, if (track.artist == null) View.GONE else View.VISIBLE)
				setTextViewText(R.id.source, track.source)
				setTextViewText(
					R.id.credited,
					listOfNotNull(track.credited, track.percent).joinToString(" · "),
				)
				val permille = track.progressPermille
				setViewVisibility(R.id.progress, if (permille == null) View.GONE else View.VISIBLE)
				setProgressBar(R.id.progress, 1000, permille ?: 0, false)
				setInt(R.id.pill, "setBackgroundResource", pillFor(view.status.tone))
				setImageViewResource(R.id.pill_icon, iconFor(view.status.tone))
				setInt(R.id.pill_icon, "setColorFilter", toneColor(view.status.tone))
				setTextViewText(R.id.pill_text, view.status.pill)
				setTextColor(R.id.pill_text, toneColor(view.status.tone))
				setTextViewText(R.id.pill_detail, view.status.detail.orEmpty())
				setViewVisibility(R.id.pill_detail, if (view.status.detail == null) View.GONE else View.VISIBLE)
			}
			setTextViewText(R.id.footnote, view.footnote.orEmpty())
			setViewVisibility(R.id.footnote, if (view.footnote == null) View.GONE else View.VISIBLE)
		}

	private fun iconFor(tone: Tone): Int = when (tone) {
		Tone.GREEN -> R.drawable.ic_status_check
		Tone.RED -> R.drawable.ic_status_cross
		Tone.AMBER -> R.drawable.ic_status_pending
		Tone.PINK, Tone.NEUTRAL -> R.drawable.ic_status_dot
	}

	private fun pillFor(tone: Tone): Int = when (tone) {
		Tone.PINK -> R.drawable.bg_status_pill_pink
		Tone.GREEN -> R.drawable.bg_status_pill_green
		Tone.AMBER -> R.drawable.bg_status_pill_amber
		Tone.RED -> R.drawable.bg_status_pill_red
		Tone.NEUTRAL -> R.drawable.bg_status_pill_neutral
	}

	private fun toneColor(tone: Tone): Int = ContextCompat.getColor(
		context,
		when (tone) {
			Tone.PINK -> R.color.status_pink
			Tone.GREEN -> R.color.status_green
			Tone.AMBER -> R.color.status_amber
			Tone.RED -> R.color.status_red
			Tone.NEUTRAL -> R.color.status_neutral
		},
	)

	private fun openNow(): PendingIntent = PendingIntent.getActivity(
		context,
		ID,
		Intent(context, target).apply {
			action = ACTION_OPEN_NOW
			flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
		},
		PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
	)

	/** Notifications on for the app, and this channel not switched off. */
	private fun channelOpen(): Boolean {
		if (!manager.areNotificationsEnabled()) return false
		ensureChannel()
		val channel = manager.getNotificationChannel(CHANNEL)
		return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
	}

	/**
	 * Low importance: shown in the shade, never a sound, a vibration or a
	 * heads-up. Created every time, as the Snap channel is; recreating it never
	 * overrides what the user changed.
	 */
	private fun ensureChannel() {
		manager.createNotificationChannel(
			NotificationChannel(
				CHANNEL,
				context.getString(R.string.scrobble_status_channel_name),
				NotificationManager.IMPORTANCE_LOW,
			).apply {
				description = context.getString(R.string.scrobble_status_channel_description)
				setShowBadge(false)
				enableVibration(false)
				setSound(null, null)
				lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
			},
		)
	}

	/**
	 * A key was saved or forgotten (or the card just started). Redact first,
	 * read second: the card re-renders with no finished listen before the
	 * vault is opened, and the read only counts if it is still the newest.
	 */
	private fun accountChanged(generation: Long) {
		gate.invalidate(generation)
		art = null
		renderNow()
		scope?.launch {
			// The vault decrypts; never on the main thread.
			val read = withContext(Dispatchers.IO) {
				runCatching { KeyVault(context).account?.username }
			}
			// A failed read is not "signed out": it stays unknown, and hidden.
			read.onSuccess { who -> if (gate.resolve(generation, who)) requestRender() }
		}
	}

	/** Render now, not after the coalescing delay: used for redaction. */
	private fun renderNow() {
		val s = scope ?: return
		renderJob?.cancel()
		renderJob = s.launch {
			lastRenderAt = SystemClock.elapsedRealtime()
			runCatching { render() }
		}
	}

	private fun interactive(): Boolean = runCatching {
		context.getSystemService(PowerManager::class.java)?.isInteractive ?: true
	}.getOrDefault(true)

	private fun now(): Long = System.currentTimeMillis()

	companion object {
		const val CHANNEL = "rustedwax_scrobble_status"
		const val ACTION_OPEN_NOW = "com.rustedwax.app.action.OPEN_NOW"
		private const val GROUP = "com.rustedwax.app.status"
		private const val ID = 0x5157_0004
		private const val TICK_MS = 5_000L
		private const val MIN_INTERVAL_MS = 1_000L

		/** Square and rounded, ~104dp at xxhdpi; bounded whatever the player sent. */
		private const val ART_PX = 288

		internal fun roundedSquare(source: Bitmap, size: Int): Bitmap {
			val side = minOf(source.width, source.height)
			val scale = size.toFloat() / side
			val matrix = Matrix().apply {
				setScale(scale, scale)
				postTranslate(
					-(source.width - side) / 2f * scale,
					-(source.height - side) / 2f * scale,
				)
			}
			val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
			val shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
				.apply { setLocalMatrix(matrix) }
			val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader }
			val radius = size * 0.12f
			Canvas(out).drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), radius, radius, paint)
			return out
		}
	}
}
