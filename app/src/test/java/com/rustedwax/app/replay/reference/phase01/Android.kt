package com.rustedwax.app.replay.reference.phase01

import com.rustedwax.core.MetadataFields

/**
 * Faithful stand-ins for the Android media surface the Phase 0/1 `SessionProbe`
 * reads.
 *
 * ## Why stand-ins rather than the real classes
 *
 * The reference in this package is a retained legacy `SessionProbe.kt`. To run
 * it at all, the nine
 * `android.*` types it imports have to exist on a JVM. The unit-test classpath
 * has `android.jar`, but it is the *stub* jar: `MediaMetadata.Builder().build()`
 * returns null under `isReturnDefaultValues`, so the real classes can be
 * compiled against and not fed. A state machine that can only ever be handed
 * empty metadata cannot be compared against anything.
 *
 * These are therefore data carriers with the same names, the same members, and
 * the same documented semantics as the platform types — nothing more. The
 * reference file's Android imports were replaced so these resolve in their
 * place.
 *
 * ## What is deliberately not modelled
 *
 * Only the members the reference actually touches exist here. That is a feature:
 * a missing member is a compile error naming exactly which platform behaviour
 * the reference depends on, whereas a stub returning a default would invent an
 * answer and let the parity run report it as the old implementation's opinion.
 *
 * Two members exist purely to type-check code the replay never reaches
 * ([Context.contentResolver], [PackageManager]); both throw rather than return a
 * plausible value, so "the replay quietly took the Android path" is impossible.
 */

/**
 * Virtual time, shared by [VirtualSystem], [SystemClock] and [Handler].
 *
 * The machines read both `System.currentTimeMillis()` and
 * `SystemClock.elapsedRealtime()` directly and post their continuation and
 * stopped-grace timers to a `Handler`. All three are driven from here so that a
 * replay advancing the clock also reaches wall-clock carry deadlines and fires
 * the timers that were due. Only elapsed differences matter to these tests; the
 * virtual epoch deliberately shares the same numeric value.
 */
class VirtualTime(private var elapsedRealtimeMs: Long = 10_000L) {

	private data class Posted(val dueAtMs: Long, val seq: Long, val task: Runnable)

	private val queue = sortedSetOf<Posted>(
		compareBy({ it.dueAtMs }, { it.seq }),
	)
	private var sequence = 0L

	fun elapsedRealtime(): Long = elapsedRealtimeMs

	fun post(delayMs: Long, task: Runnable) {
		queue += Posted(elapsedRealtimeMs + delayMs, sequence++, task)
	}

	/** Run everything already due, without moving the clock. */
	fun drain() {
		while (true) {
			val next = queue.firstOrNull() ?: return
			if (next.dueAtMs > elapsedRealtimeMs) return
			queue.remove(next)
			next.task.run()
		}
	}

	/**
	 * Move time forward, firing each timer *at its own due time*.
	 *
	 * Stepping to each deadline rather than jumping to the end matters: a task
	 * that reads the clock — the continuation tick re-posts itself against it —
	 * must see the moment it was scheduled for, not the moment the test stopped.
	 */
	fun advance(millis: Long) {
		val target = elapsedRealtimeMs + millis
		while (true) {
			val next = queue.firstOrNull()
			if (next == null || next.dueAtMs > target) break
			queue.remove(next)
			elapsedRealtimeMs = maxOf(elapsedRealtimeMs, next.dueAtMs)
			next.task.run()
		}
		elapsedRealtimeMs = target
		drain()
	}

	/** Timers still outstanding, for a test that wants to assert none leaked. */
	fun pendingTimers(): Int = queue.size
}

/**
 * The ambient virtual clock.
 *
 * Global because the reference reads `SystemClock.elapsedRealtime()` as a static
 * — the same shape the platform has. `Phase01Environment` installs a fresh one
 * per replay and clears it afterwards, so no run inherits another's time.
 */
object SystemClock {
	@Volatile
	var current: VirtualTime = VirtualTime()

	@JvmStatic
	fun elapsedRealtime(): Long = current.elapsedRealtime()
}

/** Test-only wall clock imported under the name `System` by generated mirrors. */
object VirtualSystem {
	@Volatile
	private var virtualTime: VirtualTime? = null

	fun use(time: VirtualTime) {
		virtualTime = time
	}

	fun useRealTime() {
		virtualTime = null
	}

	@JvmStatic
	fun currentTimeMillis(): Long =
		virtualTime?.elapsedRealtime() ?: java.lang.System.currentTimeMillis()
}

object Looper {
	@JvmStatic
	fun getMainLooper(): Looper = this
}

/** Posts onto [VirtualTime]; runs inline once due. */
class Handler(@Suppress("UNUSED_PARAMETER") looper: Looper) {

	private val time: VirtualTime get() = SystemClock.current

	fun post(task: Runnable): Boolean {
		time.post(0L, task)
		return true
	}

	fun postDelayed(task: Runnable, delayMs: Long): Boolean {
		time.post(delayMs, task)
		return true
	}
}

class ComponentName(
	@Suppress("UNUSED_PARAMETER") context: Context,
	val cls: Class<*>,
)

class PackageManager {
	fun getApplicationInfo(packageName: String, flags: Int): Any =
		throw UnsupportedOperationException(
			"the Phase 0/1 replay never resolves an app label: $packageName/$flags",
		)

	fun getApplicationLabel(info: Any): CharSequence =
		throw UnsupportedOperationException("the Phase 0/1 replay never resolves an app label")
}

open class Context(private val sessionManager: MediaSessionManager = MediaSessionManager()) {

	val applicationContext: Context get() = this
	val packageManager: PackageManager = PackageManager()
	val packageName: String = "com.rustedwax.app"

	fun getSystemService(name: String): Any =
		if (name == MEDIA_SESSION_SERVICE) sessionManager
		else throw UnsupportedOperationException("unmodelled system service: $name")

	/**
	 * Types `SessionProbe.hasNotificationAccess`, which the replay never calls.
	 *
	 * That companion helper reaches `android.provider.Settings.Secure` by its
	 * fully-qualified name, so it is the one place the reference still names a
	 * real platform class. It is a UI permission check with no bearing on
	 * measurement or lifecycle. Throwing keeps it that way: if a replay ever
	 * reaches it, the run fails instead of reading a default.
	 */
	val contentResolver: android.content.ContentResolver
		get() = throw UnsupportedOperationException(
			"the Phase 0/1 replay does not query notification-listener settings",
		)

	companion object {
		const val MEDIA_SESSION_SERVICE = "media_session"
	}
}

class MediaSessionManager {

	fun interface OnActiveSessionsChangedListener {
		fun onActiveSessionsChanged(controllers: List<MediaController>?)
	}

	private val listeners = mutableListOf<OnActiveSessionsChangedListener>()

	/** Controllers the harness has decided are active right now. */
	var active: List<MediaController> = emptyList()
		private set

	fun addOnActiveSessionsChangedListener(
		listener: OnActiveSessionsChangedListener,
		@Suppress("UNUSED_PARAMETER") component: ComponentName,
		@Suppress("UNUSED_PARAMETER") handler: Handler,
	) {
		listeners += listener
	}

	fun removeOnActiveSessionsChangedListener(listener: OnActiveSessionsChangedListener) {
		listeners -= listener
	}

	fun getActiveSessions(@Suppress("UNUSED_PARAMETER") component: ComponentName):
		List<MediaController> = active

	/** Publish a new active-session list, exactly as the platform would. */
	fun publish(controllers: List<MediaController>) {
		active = controllers
		listeners.toList().forEach { it.onActiveSessionsChanged(controllers) }
	}
}

/**
 * Metadata as key/value, which is all the platform class is to this code.
 *
 * Implements [MetadataFields] so the reference reaches the *real, unmodified*
 * production `MetadataDump` and `YouTubeProbe` bodies rather than a copy of
 * them. A copied reader would make a parity difference in the reader
 * indistinguishable from one in the state machine.
 */
class MediaMetadata(private val values: Map<String, Any?> = emptyMap()) : MetadataFields {

	override fun getString(key: String): String? = values[key] as? String

	override fun getLong(key: String): Long = when (val v = values[key]) {
		is Long -> v
		is Int -> v.toLong()
		else -> 0L
	}

	override fun bitmapDimensions(key: String): Pair<Int, Int>? =
		@Suppress("UNCHECKED_CAST")
		(values[key] as? Pair<Int, Int>)

	override fun keySet(): Set<String> = values.keys

	companion object {
		const val METADATA_KEY_TITLE = "android.media.metadata.TITLE"
		const val METADATA_KEY_ARTIST = "android.media.metadata.ARTIST"
		const val METADATA_KEY_ALBUM = "android.media.metadata.ALBUM"
		const val METADATA_KEY_ALBUM_ARTIST = "android.media.metadata.ALBUM_ARTIST"
		const val METADATA_KEY_DISPLAY_TITLE = "android.media.metadata.DISPLAY_TITLE"
		const val METADATA_KEY_DISPLAY_SUBTITLE = "android.media.metadata.DISPLAY_SUBTITLE"
		const val METADATA_KEY_DISPLAY_DESCRIPTION = "android.media.metadata.DISPLAY_DESCRIPTION"
		const val METADATA_KEY_ART_URI = "android.media.metadata.ART_URI"
		const val METADATA_KEY_ALBUM_ART_URI = "android.media.metadata.ALBUM_ART_URI"
		const val METADATA_KEY_DISPLAY_ICON_URI = "android.media.metadata.DISPLAY_ICON_URI"
		const val METADATA_KEY_MEDIA_URI = "android.media.metadata.MEDIA_URI"
		const val METADATA_KEY_MEDIA_ID = "android.media.metadata.MEDIA_ID"
		const val METADATA_KEY_AUTHOR = "android.media.metadata.AUTHOR"
		const val METADATA_KEY_WRITER = "android.media.metadata.WRITER"
		const val METADATA_KEY_COMPOSER = "android.media.metadata.COMPOSER"
		const val METADATA_KEY_GENRE = "android.media.metadata.GENRE"
		const val METADATA_KEY_DATE = "android.media.metadata.DATE"
		const val METADATA_KEY_DURATION = "android.media.metadata.DURATION"
		const val METADATA_KEY_YEAR = "android.media.metadata.YEAR"
		const val METADATA_KEY_TRACK_NUMBER = "android.media.metadata.TRACK_NUMBER"
		const val METADATA_KEY_NUM_TRACKS = "android.media.metadata.NUM_TRACKS"
		const val METADATA_KEY_DISC_NUMBER = "android.media.metadata.DISC_NUMBER"
		const val METADATA_KEY_ART = "android.media.metadata.ART"
		const val METADATA_KEY_ALBUM_ART = "android.media.metadata.ALBUM_ART"
		const val METADATA_KEY_DISPLAY_ICON = "android.media.metadata.DISPLAY_ICON"
	}
}

class PlaybackState(
	val state: Int,
	val position: Long,
	val playbackSpeed: Float = 1f,
	val lastPositionUpdateTime: Long = 0L,
) {
	companion object {
		const val STATE_NONE = 0
		const val STATE_STOPPED = 1
		const val STATE_PAUSED = 2
		const val STATE_PLAYING = 3
		const val STATE_FAST_FORWARDING = 4
		const val STATE_REWINDING = 5
		const val STATE_BUFFERING = 6
		const val STATE_ERROR = 7
		const val STATE_CONNECTING = 8
		const val STATE_SKIPPING_TO_PREVIOUS = 9
		const val STATE_SKIPPING_TO_NEXT = 10
		const val STATE_SKIPPING_TO_QUEUE_ITEM = 11
	}
}

/**
 * The controller, and the harness's handle on it.
 *
 * `metadata` and `playbackState` are `var` because the platform's are live
 * getters onto the session's current values: the Phase 0/1 code reads
 * `controller.metadata` long after registering, and a snapshot taken at
 * construction would answer a question it never asked.
 */
class MediaController(val packageName: String, tokenId: String? = null) {

	/**
	 * Identity of the underlying session.
	 *
	 * The Phase 0/1 code keys its `watches` map on `sessionToken.toString()`, so
	 * this is load-bearing rather than decorative: two controllers for the same
	 * package with different tokens are two sessions, which is exactly what
	 * MediaSession recreation looks like from the outside. Defaults to a fresh
	 * identity per controller so a replacement is never mistaken for the
	 * original by accident.
	 */
	val sessionToken: String = tokenId ?: "token-${nextToken.getAndIncrement()}"

	open class Callback {
		open fun onMetadataChanged(md: MediaMetadata?) = Unit
		open fun onPlaybackStateChanged(ps: PlaybackState?) = Unit
		open fun onSessionDestroyed() = Unit
	}

	var metadata: MediaMetadata? = null
		private set

	var playbackState: PlaybackState? = null
		private set

	private val callbacks = mutableListOf<Callback>()

	fun registerCallback(callback: Callback, @Suppress("UNUSED_PARAMETER") handler: Handler) {
		callbacks += callback
	}

	fun unregisterCallback(callback: Callback) {
		callbacks -= callback
	}

	/** Whether anything is still listening — a leaked callback is a parity fault. */
	val callbackCount: Int get() = callbacks.size

	fun publishMetadata(md: MediaMetadata?) {
		metadata = md
		callbacks.toList().forEach { it.onMetadataChanged(md) }
	}

	fun publishPlaybackState(ps: PlaybackState?) {
		playbackState = ps
		callbacks.toList().forEach { it.onPlaybackStateChanged(ps) }
	}

	fun destroySession() {
		callbacks.toList().forEach { it.onSessionDestroyed() }
	}

	/** Seed state without notifying, for a controller built mid-playback. */
	fun seed(md: MediaMetadata?, ps: PlaybackState?) {
		metadata = md
		playbackState = ps
	}

	companion object {
		private val nextToken = java.util.concurrent.atomic.AtomicLong(1)
	}
}

/**
 * The address-bar watcher's enablement, as a harness input.
 *
 * Shadows the production object of the same name for this package only. The
 * production `isEnabled` asks `Settings.Secure` whether an accessibility service
 * is running — a device permission query with no playback logic in it, and one
 * the stub `android.jar` answers `0` to unconditionally. Left unshadowed, every
 * reference replay would run with browser evidence off and disagree with
 * production for a reason that is about the test environment rather than about
 * either state machine.
 *
 * The answer therefore becomes an explicit scenario input through an
 * injectable evidence interface.
 */
object UrlWatcherService {
	@Volatile
	var enabled: Boolean = true

	@JvmStatic
	fun isEnabled(@Suppress("UNUSED_PARAMETER") context: Context): Boolean = enabled
}

/**
 * The diagnostic transport dump, over the stand-in state.
 *
 * Shadows the production object of the same name for this package only. The
 * production one reads `Bundle`, `Build.VERSION` and custom actions — platform
 * detail the stand-in `PlaybackState` does not carry and the state machine never
 * consults. The eight lines it does produce are the eight the reference's own
 * fields can answer, in the production order, so an EventLog comparison lines up
 * on the fields that exist rather than on invented ones.
 */
object PlaybackStateDump {
	fun dump(state: PlaybackState?): List<String> {
		if (state == null) return listOf("<null state>")
		return listOf(
			"state = ${state.state}",
			"position = ${state.position}",
			"playbackSpeed = ${state.playbackSpeed}",
			"lastPositionUpdateTime = ${state.lastPositionUpdateTime}",
		)
	}
}
