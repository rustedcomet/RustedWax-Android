package com.rustedwax.app.detect

import android.graphics.Bitmap
import android.media.MediaMetadata
import com.rustedwax.core.MetadataFields
import com.rustedwax.core.TrackInstanceId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The picture a player attached to its own MediaSession, per live listen.
 *
 * **Display only.** Nothing here is an identity: it carries no video id, it is
 * never read by finalization, and nothing that decides a scrobble, a credit or a
 * Hive write may consult it. It exists because a browser playing YouTube in the
 * background publishes a perfectly good frame long before — sometimes instead
 * of — any video id the app can prove, and the Now card used to show a
 * placeholder for that whole listen (issue #62).
 *
 * Keyed by [TrackInstanceId], the same key the Now list uses, so a frame can
 * only ever be drawn on the listen whose metadata carried it. Republished whole
 * on every session publication, so a listen that ends takes its frame with it.
 *
 * Kept off [SessionSnapshot] deliberately: snapshots are frozen into
 * finalization, and a bitmap has no business there. Nothing is fetched, written
 * or logged — the bitmap is the one the player already handed this process.
 */
object PlayerArtwork {

	data class Frame(
		val bitmap: Bitmap,
		/** The title published in the same metadata bundle as [bitmap]. */
		val title: String,
	)

	/** What one live listen's current metadata offers. Built by the probe. */
	internal class Source(
		val packageName: String,
		val listen: TrackInstanceId,
		val token: Long,
		val title: String?,
		val fields: MetadataFields?,
		/** A browser, whose page artwork can trail its title by a track. */
		val lagsArtwork: Boolean,
	)

	/** Where players put the picture they show; first present wins. */
	private val ARTWORK_KEYS = listOf(
		MediaMetadata.METADATA_KEY_ART,
		MediaMetadata.METADATA_KEY_ALBUM_ART,
		MediaMetadata.METADATA_KEY_DISPLAY_ICON,
	)

	private val _frames = MutableStateFlow<Map<TrackInstanceId, Frame>>(emptyMap())
	val frames: StateFlow<Map<TrackInstanceId, Frame>> = _frames.asStateFlow()

	/**
	 * Per package, not per session: Chromium rebuilds its MediaSession across
	 * videos, and the previous listen's picture is exactly what a fresh binding
	 * would otherwise forget to refuse. Bounded by the media apps installed.
	 */
	private class PackageState(val gate: PlayerArtworkGate<Bitmap>) {
		var metadata: MediaMetadata? = null
		var token: Long? = null
		var accepted: PlayerArtworkGate.Accepted<Bitmap>? = null
	}

	private val packages = HashMap<String, PackageState>()

	@Synchronized
	internal fun publish(sources: List<Source>) {
		_frames.value = sources
			.mapNotNull { source -> observe(source)?.let { source.listen to it } }
			.toMap()
	}

	/** Re-judged only when the bundle or listen changes; publish runs often. */
	private fun observe(source: Source): Frame? {
		val state = packages.getOrPut(source.packageName) {
			PackageState(
				PlayerArtworkGate(
					rejectInherited = source.lagsArtwork,
					same = { a, b -> a === b || runCatching { a.sameAs(b) }.getOrDefault(false) },
				),
			)
		}
		val metadata = (source.fields as? AndroidMetadataFields)?.metadata
		if (metadata !== state.metadata || source.token != state.token) {
			state.metadata = metadata
			state.token = source.token
			state.accepted = state.gate.observe(source.token, source.title, metadata?.let(::pictureOf))
		}
		return state.accepted?.let { Frame(it.image, it.title) }
	}

	private fun pictureOf(metadata: MediaMetadata): Bitmap? = ARTWORK_KEYS.firstNotNullOfOrNull { key ->
		runCatching { metadata.getBitmap(key) }.getOrNull()
			?.takeIf { !it.isRecycled && it.width > 0 && it.height > 0 }
	}
}

/**
 * Decides whether one metadata bundle's picture may be shown for the listen it
 * arrived under.
 *
 * Pure, and generic over the image so it is testable without Android bitmaps.
 *
 * The one hazard is a picture that belongs to the *previous* listen. Chromium
 * downloads page artwork asynchronously, so a browser can announce the next
 * video's title while still holding the last one's frame. With
 * [rejectInherited], a picture equal to the last one *accepted* for an earlier
 * listen is refused for every later listen until a different picture is
 * accepted — however many switches arrive while the page still holds it. A
 * listen that only offered that stale frame accepted nothing, so it must not
 * reset the reference: two quick skips would otherwise resurrect it. A
 * placeholder is honest, someone else's thumbnail is not. Native players
 * publish metadata atomically, and consecutive tracks of one album
 * legitimately share a cover, so they do not ask for that rule.
 */
internal class PlayerArtworkGate<T : Any>(
	private val rejectInherited: Boolean,
	private val same: (T, T) -> Boolean,
) {
	data class Accepted<T>(val image: T, val title: String)

	private var token: Long? = null

	/** Accepted for the current listen; becomes [stale] when the listen ends. */
	private var current: T? = null

	/** The newest picture accepted for any earlier listen. */
	private var stale: T? = null

	fun observe(token: Long, title: String?, image: T?): Accepted<T>? {
		if (token != this.token) {
			// Only a listen that showed a picture of its own replaces the reference.
			current?.let { stale = it }
			current = null
			this.token = token
		}
		if (image == null || title.isNullOrBlank()) return null
		val reference = stale
		if (rejectInherited && reference != null && same(reference, image)) return null
		current = image
		return Accepted(image, title)
	}
}
