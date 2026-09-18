package com.rustedwax.app.ui.snaps

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rustedwax.app.snaps.HiveAvatarUrl
import com.rustedwax.app.ui.Fetched
import com.rustedwax.app.ui.ThumbnailFetch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * The little round picture beside a posted Snap.
 *
 * Memory only, unlike [com.rustedwax.app.ui.Thumbnails], and on purpose: a
 * History screen shows Snaps by exactly one account — the person holding the
 * phone — so this cache holds one image, not a list's worth, and giving it a
 * disk cache would mean managing files for a single small picture that costs one
 * request per process.
 *
 * Its verdicts are [ThumbnailFetch]'s, reused rather than restated. The rule
 * that matters there matters here too: a definite "there is no image" may be
 * remembered forever, a timeout may never be, because a minute without signal
 * must not blank the avatar for the rest of the session.
 */
internal object HiveAvatars {

	/** A handful of small round images. One account, a few sizes of cache miss. */
	private const val MEMORY_BUDGET_BYTES = 1024 * 1024

	private val memory = object : LruCache<String, ImageBitmap>(MEMORY_BUDGET_BYTES) {
		override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
	}

	/** Accounts that provably have no avatar. Only ever definite negatives. */
	private val absent = ConcurrentHashMap.newKeySet<String>()

	private val inFlight = ConcurrentHashMap<String, Deferred<ImageBitmap?>>()

	private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

	fun cached(account: String): ImageBitmap? = memory[account]

	suspend fun load(account: String): ImageBitmap? {
		// No URL means no request. A name that is not a Hive account name never
		// becomes a request path.
		val url = HiveAvatarUrl.of(account) ?: return null
		memory[account]?.let { return it }
		if (account in absent) return null
		val work = inFlight.computeIfAbsent(account) { scope.async { fetch(account, url) } }
		return try {
			work.await()
		} finally {
			inFlight.remove(account, work)
		}
	}

	private fun fetch(account: String, url: String): ImageBitmap? {
		val bytes = when (val result = download(url)) {
			is Fetched.Body -> result.bytes
			Fetched.Absent -> {
				absent += account
				return null
			}
			// Transient. Not remembered; the next composition is the retry.
			Fetched.Unavailable -> return null
		}
		val bitmap = runCatching {
			BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
		}.getOrNull() ?: return null
		return bitmap.asImageBitmap().also { memory.put(account, it) }
	}

	private fun download(url: String): Fetched = runCatching {
		val connection = (URL(url).openConnection() as HttpURLConnection).apply {
			connectTimeout = 8_000
			readTimeout = 8_000
			instanceFollowRedirects = true
		}
		try {
			val status = connection.responseCode
			val body = if (status == HttpURLConnection.HTTP_OK) {
				connection.inputStream.readBytesUpTo(ThumbnailFetch.MAX_BYTES)
			} else {
				ByteArray(0)
			}
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
}

/**
 * The avatar, or the neutral circle that stands in for it.
 *
 * The circle is drawn first and always: the image, when there is one, is painted
 * *into* a space that already exists. So a missing avatar, a refused request, a
 * name that is not an account name and a phone in flight mode all produce the
 * same quiet grey disc, and none of them can stop the Snap beside it from
 * rendering — the card's text does not wait on a picture.
 */
@Composable
internal fun HiveAvatar(
	account: String,
	modifier: Modifier = Modifier,
	size: Dp = 26.dp,
) {
	var bitmap by remember(account) { mutableStateOf(HiveAvatars.cached(account)) }
	LaunchedEffect(account) {
		// Failure is a value here, not an exception: `load` answers null for
		// every way this can go wrong, and null is the placeholder.
		bitmap = HiveAvatars.load(account)
	}

	Box(
		modifier = modifier
			.size(size)
			.clip(CircleShape)
			.background(MaterialTheme.colorScheme.surfaceContainerHighest),
	) {
		bitmap?.let {
			Image(
				bitmap = it,
				contentDescription = null,
				contentScale = ContentScale.Crop,
				modifier = Modifier.fillMaxSize(),
			)
		}
	}
}
