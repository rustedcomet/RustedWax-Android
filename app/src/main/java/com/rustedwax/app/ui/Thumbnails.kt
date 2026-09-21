package com.rustedwax.app.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * The still frame beside a row in History and Not logged.
 *
 * Fetched from `i.ytimg.com`, which is off-device traffic keyed to a video the
 * app already identified — so it rides the **Look videos up** switch rather
 * than introducing a second, quieter consent. With lookups off, no request is
 * made and the rows keep the placeholder.
 *
 * Cached to disk because these lists are re-read constantly and a thumbnail
 * never changes. Nothing else about a scrobble is stored here: the file name is
 * the video id, and the id was already on the record that draws the row.
 */
object Thumbnails {

	/**
	 * ~6 MB of decoded frames — around 100 rows at the size below, which is more
	 * than both lists hold. Sized in bytes rather than entries because the cost
	 * of a wrong guess here is an OOM on a 2 GB phone.
	 */
	// Raised with the frames themselves. A banner decodes to about 640x360,
	// which is ~900KB in memory against the ~58KB a 160x90 `mqdefault` used to
	// cost — the old 6MB would have held six of them and thrashed on every
	// scroll.
	private const val MEMORY_BUDGET_BYTES = 16 * 1024 * 1024

	/** Enough for both 50-row lists several times over. */
	private const val MAX_FILES = 240

	/** 320×180 halved on decode: crisp at 68dp on a 3x screen, 1/4 the memory. */
	/**
	 * How wide the banner crop is for its height.
	 *
	 * Measured off the v1.1 mockup, whose four cards sit between 3.7:1 and
	 * 5.4:1 — they are pasted crops rather than one ratio, so this is the middle
	 * of that range rather than any one of them.
	 */
	const val BANNER_ASPECT = 4f

	/**
	 * The smallest decoded width worth drawing a full-width banner from.
	 *
	 * The card is as wide as the screen, so a 160px bitmap — which is what
	 * `mqdefault` at `inSampleSize = 2` used to give — was being stretched
	 * about five times and looked it. Sampling is chosen against this instead
	 * of being a constant, so a small source is left alone and a large one is
	 * only halved while it stays above the line.
	 */
	private const val TARGET_WIDTH = 600

	private val VIDEO_ID = Regex("""[A-Za-z0-9_-]{11}""")

	/** Written on the main thread at startup, read from IO threads afterwards. */
	@Volatile
	private var dir: File? = null

	private val memory = object : LruCache<String, ImageBitmap>(MEMORY_BUDGET_BYTES) {
		override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
	}

	/**
	 * Ids there is provably no thumbnail for — a deleted or private video.
	 *
	 * Without this they re-request on every recomposition of a list that redraws
	 * once a second, which is a request storm aimed at Google from an app whose
	 * whole point is not making unannounced requests.
	 *
	 * **Only definite negatives go in here.** A timeout is not a negative: this
	 * set is never cleared, so recording a flight-mode failure would blank that
	 * row's thumbnail until the process is killed, and a phone that loses signal
	 * for a minute would lose its thumbnails for the rest of the day.
	 */
	private val absent = ConcurrentHashMap.newKeySet<String>()

	/**
	 * Loads already running, keyed by id.
	 *
	 * The same video legitimately appears more than once on screen — a repeat
	 * listen puts it in History twice, and a track can be in History and Not
	 * logged at once. Two rows composing together used to mean two downloads
	 * racing on one file: one truncating what the other was reading, the reader
	 * then deleting the half-written file as corrupt and marking the id dead.
	 */
	private val inFlight = ConcurrentHashMap<String, Deferred<ImageBitmap?>>()

	private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

	fun init(context: Context) {
		if (dir == null) dir = File(context.filesDir, "thumbs").apply { mkdirs() }
	}

	/** Cache-only, for the first composition of a row that already has one. */
	fun cached(videoId: String): ImageBitmap? = memory[videoId]

	suspend fun load(videoId: String): ImageBitmap? {
		if (!VIDEO_ID.matches(videoId)) return null
		memory[videoId]?.let { return it }
		if (videoId in absent) return null
		val work = inFlight.computeIfAbsent(videoId) { id -> scope.async { fetch(id) } }
		return try {
			work.await()
		} finally {
			// By now the result is in the memory cache, so a later caller that
			// misses this window takes the cache hit rather than the network.
			inFlight.remove(videoId, work)
		}
	}

	private fun fetch(videoId: String): ImageBitmap? {
		val directory = dir ?: return null
		val file = File(directory, "$videoId.jpg")
		val bytes = if (file.exists()) {
			runCatching { file.readBytes() }.getOrNull()
		} else {
			when (val result = download(videoId)) {
				is Fetched.Body -> result.bytes.also { data ->
					runCatching {
						// Written beside the target and renamed, so a kill
						// mid-write leaves no half a JPEG behind claiming to
						// be a cache hit.
						val staging = File(directory, "$videoId.jpg.part")
						staging.writeBytes(data)
						if (!staging.renameTo(file)) staging.delete()
						evictIfNeeded()
					}
				}

				Fetched.Absent -> {
					absent += videoId
					return null
				}

				// Transient. Not remembered — the next time this row composes
				// is the retry.
				Fetched.Unavailable -> return null
			}
		}
		if (bytes == null) return null
		val bitmap = runCatching {
			// Bounds first, so the sampling is decided by what actually arrived
			// rather than by what was hoped for: `maxresdefault` is not
			// published for every video, and the fallback is a third the width.
			val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
			BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
			var sample = 1
			while (bounds.outWidth / (sample * 2) >= TARGET_WIDTH) sample *= 2
			BitmapFactory.decodeByteArray(
				bytes,
				0,
				bytes.size,
				BitmapFactory.Options().apply { inSampleSize = sample },
			)
		}.getOrNull()
		if (bitmap == null) {
			// Whatever is on disk is not an image. Drop it and let the next
			// composition fetch again rather than caching the failure.
			runCatching { file.delete() }
			return null
		}
		return bitmap.asImageBitmap().also { memory.put(videoId, it) }
	}

	/**
	 * The best frame YouTube publishes for this video, then the one it always
	 * publishes.
	 *
	 * `maxresdefault` is 1280 wide and missing for plenty of videos; `hqdefault`
	 * is 480 and effectively always there. Asking for the first and accepting
	 * the second is the only way to get a sharp banner without leaving some
	 * rows with no image at all — an `Absent` on maxres used to be remembered as
	 * "this video has no thumbnail", which would have been wrong for most of
	 * them.
	 */
	private fun download(videoId: String): Fetched =
		when (val best = downloadFrame(videoId, "maxresdefault")) {
			is Fetched.Body -> best
			else -> downloadFrame(videoId, "hqdefault")
		}

	private fun downloadFrame(videoId: String, frame: String): Fetched = runCatching {
		val connection = URL("https://i.ytimg.com/vi/$videoId/$frame.jpg")
			.openConnection() as HttpURLConnection
		connection.connectTimeout = 8_000
		connection.readTimeout = 8_000
		connection.instanceFollowRedirects = true
		try {
			val status = connection.responseCode
			val body = if (status == HttpURLConnection.HTTP_OK) {
				connection.inputStream.readBytesUpTo(ThumbnailFetch.MAX_BYTES)
			} else {
				ByteArray(0)
			}
			// The verdict is `ThumbnailFetch`'s; this only supplies the bytes it
			// decided were worth keeping.
			when (ThumbnailFetch.classify(status, body.size)) {
				is Fetched.Body -> Fetched.Body(body)
				Fetched.Absent -> Fetched.Absent
				Fetched.Unavailable -> Fetched.Unavailable
			}
		} finally {
			connection.disconnect()
		}
	}.getOrDefault(Fetched.Unavailable)

	private fun java.io.InputStream.readBytesUpTo(limit: Int): ByteArray {
		val out = java.io.ByteArrayOutputStream()
		val buffer = ByteArray(8 * 1024)
		while (out.size() <= limit) {
			val read = read(buffer)
			if (read <= 0) break
			out.write(buffer, 0, read)
		}
		return out.toByteArray()
	}

	private fun evictIfNeeded() {
		val files = dir?.listFiles() ?: return
		if (files.size <= MAX_FILES) return
		files.sortedBy { it.lastModified() }
			.take(files.size - MAX_FILES)
			.forEach { it.delete() }
	}
}

/**
 * The thumbnail as the mockup draws it: a rounded 16:9 frame with the play
 * badge in the corner.
 *
 * Always occupies its space, so a row's text starts in the same column whether
 * the frame arrived, is still coming, or was never asked for.
 */
@Composable
fun VideoThumbnail(
	videoId: String?,
	enabled: Boolean,
	modifier: Modifier = Modifier,
	/**
	 * A fixed width, or **null** to fill whatever the caller gives it and take
	 * the height from the 16:9 ratio.
	 *
	 * Null is what the History banner uses: the card is as wide as the screen
	 * allows, so pinning a number here would make the image a different shape
	 * on every device. 16:9 is not an arbitrary choice either — it is the shape
	 * YouTube serves, so filling the width crops nothing and stretches nothing.
	 */
	width: androidx.compose.ui.unit.Dp? = 68.dp,
) {
	val shape = RoundedCornerShape(9.dp)
	// Starts from the memory cache so a row that has been drawn before does not
	// blink through its placeholder on every scroll back.
	var bitmap by remember(videoId) {
		mutableStateOf(videoId?.let { Thumbnails.cached(it) })
	}
	LaunchedEffect(videoId, enabled) {
		bitmap = if (enabled && videoId != null) Thumbnails.load(videoId) else null
	}

	Box(
		modifier = modifier
			.let {
				if (width != null) {
					it.width(width).height(width * 9 / 16)
				} else {
					// The banner crop from the mockup rather than the whole
					// 16:9 frame. `ContentScale.Crop` takes the middle of the
					// image, which is where a music thumbnail puts its subject.
					it.aspectRatio(Thumbnails.BANNER_ASPECT)
				}
			}
			.clip(shape)
			.background(MaterialTheme.colorScheme.surfaceContainerHighest),
		contentAlignment = Alignment.Center,
	) {
		val frame = bitmap
		if (frame != null) {
			Image(
				bitmap = frame,
				contentDescription = null,
				contentScale = ContentScale.Crop,
				modifier = Modifier.fillMaxSize(),
			)
		} else {
			Icon(
				WaxIcons.Record,
				contentDescription = null,
				tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
				modifier = Modifier.size(20.dp),
			)
		}
		if (frame != null) {
			Box(
				modifier = Modifier
					.align(Alignment.BottomStart)
					.padding(if (width != null) 4.dp else 8.dp)
					// Scaled with the frame. The badge was sized for a 68dp
					// row; left at that size on a full-width banner it reads as
					// a speck rather than a mark.
					.size(
						width = if (width != null) 20.dp else 32.dp,
						height = if (width != null) 14.dp else 22.dp,
					)
					.clip(RoundedCornerShape(4.dp))
					.background(YOUTUBE_RED),
				contentAlignment = Alignment.Center,
			) {
				Icon(
					WaxIcons.PlayTriangle,
					contentDescription = null,
					tint = Color.White,
					modifier = Modifier.size(if (width != null) 11.dp else 17.dp),
				)
			}
		}
	}
}

/** YouTube's own badge colour — it identifies the source, so it isn't themed. */
private val YOUTUBE_RED = Color(0xFFFF0033)
