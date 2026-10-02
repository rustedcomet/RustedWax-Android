package com.rustedwax.app.ui.snaps

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.widget.ImageView
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.rustedwax.app.ui.Thumbnails
import com.rustedwax.app.ui.WaxIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer

/** What one fetch of preview bytes came to. Pure, so the verdict is testable. */
sealed interface SnapMediaFetch {
	data class Body(val bytes: ByteArray) : SnapMediaFetch

	/** The proxy answered and there is nothing to show: never asked again this run. */
	data object Absent : SnapMediaFetch

	/** No answer worth trusting — a timeout, a 5xx. The next showing retries. */
	data object Unavailable : SnapMediaFetch

	companion object {
		/** Over this, a still is not drawn — the proxy resizes to 640 px, so it never should be. */
		const val MAX_STILL_BYTES = 4 * 1024 * 1024

		/** A GIF is fetched whole; one larger than this stays a link. */
		const val MAX_GIF_BYTES = 8 * 1024 * 1024

		fun classify(status: Int, size: Int, limit: Int): SnapMediaFetch = when {
			status == 200 && size in 1..limit -> Body(ByteArray(0))
			status == 200 -> Absent
			status in 400..499 -> Absent
			else -> Unavailable
		}
	}
}

/** A loaded preview, ready to draw. */
sealed interface SnapMediaImage {
	data class Still(val bitmap: ImageBitmap) : SnapMediaImage

	/** The GIF's own bytes; each showing decodes its own animation from them. */
	class Animated(val bytes: ByteArray) : SnapMediaImage
}

/**
 * Fetches and decodes preview images, with everything bounded:
 *
 *  - **memory** — decoded stills and raw GIF bytes live in two byte-sized LRU
 *    caches, and an animation exists only while its row is composed;
 *  - **network** — at most [PARALLEL] fetch/decode jobs at once,
 *    and a definite "no image" remembered in a bounded cache so a
 *    list that recomposes once a second does not keep asking;
 *  - **size** — each download stops at its byte cap, and decodes are sampled
 *    down with both dimensions bounded.
 *
 * It reads; it writes nothing anywhere but these caches.
 */
object SnapMediaLoader {
	private const val PARALLEL = 3
	private const val TIMEOUT_MS = 10_000

	private val stills = object : LruCache<String, ImageBitmap>(12 * 1024 * 1024) {
		override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
	}
	private val gifs = object : LruCache<String, ByteArray>(16 * 1024 * 1024) {
		override fun sizeOf(key: String, value: ByteArray) = value.size
	}
	private val absent = LruCache<String, Boolean>(512)
	private val gate = Semaphore(PARALLEL)

	fun cached(ref: SnapMediaRef.Image): SnapMediaImage? =
		if (ref.animated) gifs[ref.fetchUrl]?.let(SnapMediaImage::Animated)
		else stills[ref.fetchUrl]?.let(SnapMediaImage::Still)

	/** Null when there is nothing to draw; the caller then shows the link. */
	suspend fun load(ref: SnapMediaRef.Image): SnapMediaImage? = withContext(Dispatchers.IO) {
		gate.withPermit {
			cached(ref)?.let { return@withContext it }
			if (absent[ref.fetchUrl] == true) return@withContext null
			val limit = if (ref.animated) SnapMediaFetch.MAX_GIF_BYTES else SnapMediaFetch.MAX_STILL_BYTES
			val fetched = download(ref.fetchUrl, limit)
			currentCoroutineContext().ensureActive()
			val bytes = when (fetched) {
				is SnapMediaFetch.Body -> fetched.bytes
				SnapMediaFetch.Absent -> {
					absent.put(ref.fetchUrl, true)
					return@withContext null
				}
				SnapMediaFetch.Unavailable -> return@withContext null
			}
			if (ref.animated && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
				// Only kept if it really decodes as an image.
				val ok = runCatching { decodeAnimated(bytes) != null }.getOrDefault(false)
				if (!ok) {
					absent.put(ref.fetchUrl, true)
					return@withContext null
				}
				currentCoroutineContext().ensureActive()
				gifs.put(ref.fetchUrl, bytes)
				return@withContext SnapMediaImage.Animated(bytes)
			}
			val still = runCatching { decodeStill(bytes) }.getOrNull()
			if (still == null) {
				absent.put(ref.fetchUrl, true)
				return@withContext null
			}
			currentCoroutineContext().ensureActive()
			stills.put(ref.fetchUrl, still)
			SnapMediaImage.Still(still)
		}
	}

	/** A fresh animation over [bytes], scaled to the preview width. API 28+. */
	fun decodeAnimated(bytes: ByteArray): Drawable? {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
		val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
		return ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
			val (w, h) = SnapMediaDecode.target(info.size.width, info.size.height)
				?: throw IOException("Media canvas exceeds decode limit")
			decoder.setTargetSize(w, h)
		}
	}

	private fun decodeStill(bytes: ByteArray): ImageBitmap? {
		val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
		BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
		val sample = SnapMediaDecode.sample(bounds.outWidth, bounds.outHeight) ?: return null
		return BitmapFactory.decodeByteArray(
			bytes, 0, bytes.size,
			BitmapFactory.Options().apply { inSampleSize = sample },
		)?.asImageBitmap()
	}

	private suspend fun download(url: String, limit: Int): SnapMediaFetch = try {
		currentCoroutineContext().ensureActive()
		val startedNanos = System.nanoTime()
		var current = url
		var result: SnapMediaFetch? = null
		var hops = 0
		while (result == null) {
			currentCoroutineContext().ensureActive()
			val connection = (URL(current).openConnection() as HttpURLConnection).apply {
				connectTimeout = TIMEOUT_MS
				readTimeout = TIMEOUT_MS
				// Redirects are followed by hand below, and only on the proxy's
				// own origin: the device is never sent on to an image's origin.
				instanceFollowRedirects = false
			}
			try {
				val status = connection.responseCode
				if (status in 300..399) {
					val next = SnapMediaRedirect.next(current, connection.getHeaderField("Location"))
					// Off-origin, or a chain too long: not requested, link stays.
					if (next == null || hops >= SnapMediaRedirect.MAX_HOPS) {
						result = SnapMediaFetch.Absent
					} else {
						hops++
						current = next
					}
				} else {
					val body = if (status == 200) {
						connection.inputStream.use { SnapMediaRead.read(it, limit, startedNanos) }
					} else ByteArray(0)
					result = when (SnapMediaFetch.classify(status, body.size, limit)) {
						is SnapMediaFetch.Body -> SnapMediaFetch.Body(body)
						SnapMediaFetch.Absent -> SnapMediaFetch.Absent
						SnapMediaFetch.Unavailable -> SnapMediaFetch.Unavailable
					}
				}
			} finally {
				connection.disconnect()
			}
		}
		result
	} catch (_: IOException) {
		SnapMediaFetch.Unavailable
	}
}

/**
 * Opens a link outside RustedWax, trying each (package, URL) in order. False
 * when nothing on the device would take it — the caller says so.
 */
internal fun openExternally(context: Context, attempts: List<Pair<String?, String>>): Boolean {
	for ((pkg, url) in attempts) {
		val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
			pkg?.let(::setPackage)
			addCategory(Intent.CATEGORY_BROWSABLE)
		}
		try {
			context.startActivity(intent)
			return true
		} catch (_: ActivityNotFoundException) {
		} catch (_: SecurityException) {
		}
	}
	Toast.makeText(context, "Nothing on this phone can open that link.", Toast.LENGTH_SHORT).show()
	return false
}

/**
 * A comment's words with their web links tappable. The text is exactly what
 * was written; only http(s) links become links, and they open outside the app.
 */
@Composable
internal fun SnapLinkedText(text: String, style: TextStyle) {
	val context = LocalContext.current
	val linkColor = MaterialTheme.colorScheme.primary
	val annotated = remember(text, linkColor) {
		val links = SnapMediaParser.links(text)
		if (links.isEmpty()) {
			AnnotatedString(text)
		} else {
			buildAnnotatedString {
				var at = 0
				for ((range, url) in links) {
					append(text.substring(at, range.first))
					withLink(
						LinkAnnotation.Clickable(
							tag = url,
							styles = TextLinkStyles(
								SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
							),
						) { openExternally(context, SnapMediaOpen.link(url)) },
					) { append(text.substring(range.first, range.last + 1)) }
					at = range.last + 1
				}
				append(text.substring(at))
			}
		}
	}
	Text(annotated, style = style)
}

/**
 * The previews under one comment — the same for a root Snap and a reply.
 * Nothing at all when the text links no supported media. [refs] come from
 * [SnapMediaText.display], which also took their links out of the text above.
 */
@Composable
internal fun SnapMediaPreviews(refs: List<SnapMediaRef>, thumbnails: Boolean) {
	if (refs.isEmpty()) return
	Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
		refs.forEach { ref ->
			when (ref) {
				is SnapMediaRef.Image -> SnapImagePreview(ref)
				is SnapMediaRef.YouTube -> SnapYouTubePreview(ref, thumbnails)
			}
			Spacer(Modifier.height(6.dp))
		}
	}
}

private val PREVIEW_SHAPE = RoundedCornerShape(10.dp)

@Composable
private fun SnapImagePreview(ref: SnapMediaRef.Image) {
	val context = LocalContext.current
	var image by remember(ref) { mutableStateOf(SnapMediaLoader.cached(ref)) }
	var failed by remember(ref) { mutableStateOf(false) }
	LaunchedEffect(ref) {
		if (image == null) {
			val loaded = SnapMediaLoader.load(ref)
			image = loaded
			failed = loaded == null
		}
	}
	val open = Modifier.clickable(onClickLabel = "Open image") {
		openExternally(context, SnapMediaOpen.link(ref.source))
	}
	when (val shown = image) {
		is SnapMediaImage.Still -> PreviewFrame(
			shown.bitmap.width.toFloat() / shown.bitmap.height.coerceAtLeast(1),
			open,
		) {
			Image(
				bitmap = shown.bitmap,
				contentDescription = if (ref.animated) "GIF" else "Image",
				contentScale = ContentScale.Fit,
				modifier = Modifier.fillMaxSize(),
			)
		}
		is SnapMediaImage.Animated -> AnimatedPreview(shown, open)
		null -> Box(
			Modifier
				.fillMaxWidth()
				.height(if (failed) 36.dp else 120.dp)
				.clip(PREVIEW_SHAPE)
				.background(MaterialTheme.colorScheme.surfaceContainerHighest)
				.then(open),
			contentAlignment = Alignment.Center,
		) {
			Text(
				if (failed) {
					if (ref.animated) "GIF unavailable · open link" else "Image unavailable · open link"
				} else {
					""
				},
				style = MaterialTheme.typography.labelMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
	}
}

/**
 * A GIF that moves. The animation is decoded for this showing and stopped the
 * moment the row leaves the screen, so only what is visible animates.
 */
@Composable
private fun AnimatedPreview(image: SnapMediaImage.Animated, modifier: Modifier) {
	val drawable = remember(image) { runCatching { SnapMediaLoader.decodeAnimated(image.bytes) }.getOrNull() }
	if (drawable == null) {
		Box(
			Modifier.fillMaxWidth().height(36.dp).clip(PREVIEW_SHAPE)
				.background(MaterialTheme.colorScheme.surfaceContainerHighest).then(modifier),
			contentAlignment = Alignment.Center,
		) {
			Text(
				"GIF unavailable · open link",
				style = MaterialTheme.typography.labelMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
		return
	}
	DisposableEffect(drawable) {
		(drawable as? Animatable)?.start()
		onDispose { (drawable as? Animatable)?.stop() }
	}
	val ratio = drawable.intrinsicWidth.toFloat() / drawable.intrinsicHeight.coerceAtLeast(1)
	PreviewFrame(ratio, modifier) {
		AndroidView(
			factory = { ctx ->
				ImageView(ctx).apply {
					scaleType = ImageView.ScaleType.FIT_CENTER
					contentDescription = "GIF"
				}
			},
			update = { it.setImageDrawable(drawable) },
			onRelease = { it.setImageDrawable(null) },
			modifier = Modifier.fillMaxSize(),
		)
	}
}

/** The tallest a preview may be; a taller image is fitted inside, never overflowing. */
private val MAX_PREVIEW_HEIGHT = 260.dp

/**
 * A full-width box exactly as tall as the image needs, capped at
 * [MAX_PREVIEW_HEIGHT]. Sized here rather than by `aspectRatio`, which on a tall
 * image asks for more height than the cap allows and is then drawn centred over
 * whatever sits above it.
 */
@Composable
private fun PreviewFrame(ratio: Float, modifier: Modifier, content: @Composable () -> Unit) {
	BoxWithConstraints(Modifier.fillMaxWidth()) {
		val safe = if (ratio.isFinite() && ratio > 0f) ratio else 1f
		val height = (maxWidth / safe).coerceAtMost(MAX_PREVIEW_HEIGHT)
		Box(
			Modifier
				.fillMaxWidth()
				.height(height)
				.clip(PREVIEW_SHAPE)
				.then(modifier),
			contentAlignment = Alignment.Center,
		) { content() }
	}
}

/**
 * A YouTube link as a thumbnail with a play button. Playing happens in YouTube:
 * there is no player here, and showing this frame plays nothing.
 */
@Composable
private fun SnapYouTubePreview(ref: SnapMediaRef.YouTube, thumbnails: Boolean) {
	val context = LocalContext.current
	var frame by remember(ref.videoId) { mutableStateOf(Thumbnails.cached(ref.videoId)) }
	LaunchedEffect(ref.videoId, thumbnails) {
		// The same i.ytimg.com request, and the same consent, as History's banners.
		frame = if (thumbnails) Thumbnails.load(ref.videoId) else null
	}
	Box(
		Modifier
			.fillMaxWidth()
			.aspectRatio(16f / 9f)
			.clip(PREVIEW_SHAPE)
			.background(MaterialTheme.colorScheme.surfaceContainerHighest)
			.clickable(onClickLabel = "Play on YouTube") {
				openExternally(context, SnapMediaOpen.youtube(ref))
			},
		contentAlignment = Alignment.Center,
	) {
		frame?.let {
			Image(
				bitmap = it,
				contentDescription = null,
				contentScale = ContentScale.Crop,
				modifier = Modifier.fillMaxSize(),
			)
		}
		Box(
			Modifier
				.size(56.dp)
				.clip(CircleShape)
				.background(Color(0xCC000000)),
			contentAlignment = Alignment.Center,
		) {
			Icon(
				WaxIcons.PlayTriangle,
				contentDescription = "Play on YouTube",
				tint = Color.White,
				modifier = Modifier.size(28.dp),
			)
		}
	}
}

/** A History thumbnail's edge. Small enough that a media Snap's card stays a card. */
private val HISTORY_THUMB = 72.dp

/**
 * The one still a History card shows for a root Snap's first image or GIF.
 * Always a still — [ref] is the first-frame fetch from
 * [SnapHistoryMedia.thumbnailStill] — so nothing moves on the History list.
 *
 * Loading is a quiet tile with no URL in it. A loaded thumbnail opens the
 * conversation ([onOpen]); one that cannot load says so and opens the link,
 * the same way out the Comments sheet's fallback gives.
 */
@Composable
internal fun SnapHistoryThumbnail(ref: SnapMediaRef.Image, gif: Boolean, onOpen: () -> Unit) {
	val context = LocalContext.current
	var image by remember(ref) { mutableStateOf(SnapMediaLoader.cached(ref)) }
	var failed by remember(ref) { mutableStateOf(false) }
	LaunchedEffect(ref) {
		if (image == null) {
			val loaded = SnapMediaLoader.load(ref)
			image = loaded
			failed = loaded == null
		}
	}
	val still = (image as? SnapMediaImage.Still)?.bitmap
	Box(
		Modifier
			.padding(top = 6.dp)
			.size(HISTORY_THUMB)
			.clip(RoundedCornerShape(8.dp))
			.background(MaterialTheme.colorScheme.surfaceContainerHighest)
			.clickable(onClickLabel = if (failed) "Open link" else "Open comments") {
				if (failed) openExternally(context, SnapMediaOpen.link(ref.source)) else onOpen()
			},
		contentAlignment = Alignment.Center,
	) {
		if (still != null) {
			Image(
				bitmap = still,
				contentDescription = if (gif) "GIF" else "Image",
				contentScale = ContentScale.Crop,
				modifier = Modifier.fillMaxSize(),
			)
		} else if (failed) {
			Text(
				if (gif) "GIF unavailable" else "Image unavailable",
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.padding(4.dp),
			)
		}
	}
}
