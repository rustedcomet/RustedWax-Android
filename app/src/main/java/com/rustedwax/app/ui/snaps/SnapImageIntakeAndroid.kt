package com.rustedwax.app.ui.snaps

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import com.rustedwax.app.snaps.SnapImageFormat
import com.rustedwax.app.snaps.SnapImageSniff
import com.rustedwax.app.snaps.SnapImageSniffer
import com.rustedwax.hive.HiveKey
import com.rustedwax.hive.ImageHoster
import com.rustedwax.hive.ImageHosterClient
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/**
 * [SnapImageIntake] on a real device: every source is read once, now, into a
 * private copy under the app's cache, so nothing later depends on a
 * picker/clipboard/keyboard grant that may already have expired.
 */
internal fun androidSnapImageIntake(context: Context): SnapImageIntake {
	val app = context.applicationContext
	val resolver: ContentResolver = app.contentResolver
	val dir = File(app.cacheDir, "snap-attachments")
	// Nothing in memory refers to copies from an earlier process: attachments
	// live only as long as the process that took them.
	dir.listFiles()?.forEach { it.delete() }
	return FileSnapImageIntake(
		dir = dir,
		open = { uri -> resolver.openInputStream(Uri.parse(uri)) },
		decodes = { file ->
			val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
			BitmapFactory.decodeFile(file.path, o)
			o.outWidth > 0 && o.outHeight > 0
		},
		heifToJpeg = ::heifToJpeg,
	)
}

/**
 * HEIC/HEIF → JPEG, once, at a fixed quality. Not a fitting loop: a result
 * over the limit is refused by the caller, never squeezed.
 */
private fun heifToJpeg(source: File, out: File): Boolean {
	val bitmap: Bitmap = try {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
			ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, _, _ ->
				decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
			}
		} else {
			BitmapFactory.decodeFile(source.path)
		}
	} catch (_: Exception) {
		null
	} catch (_: OutOfMemoryError) {
		null
	} ?: return false
	return try {
		out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
	} catch (_: IOException) {
		false
	} finally {
		bitmap.recycle()
	}
}

/**
 * The intake itself, with the platform reached only through [open],
 * [decodes] and [heifToJpeg] — so every decision it makes is pinned by JVM
 * tests.
 */
internal class FileSnapImageIntake(
	private val dir: File,
	/** Opens a `content://` URI. May throw (a revoked grant) or return null. */
	private val open: (String) -> InputStream?,
	/** Whether the platform can read the image's dimensions. */
	private val decodes: (File) -> Boolean,
	/** Writes [source] (HEIC/HEIF) to `out` as JPEG; false when it cannot. */
	private val heifToJpeg: (source: File, out: File) -> Boolean,
) : SnapImageIntake {

	override fun take(source: SnapImageSource): SnapImageIntake.Taken {
		if (!source.uri.startsWith("content://")) return rejected(UNREADABLE)
		val input = try {
			open(source.uri)
		} catch (_: SecurityException) {
			null
		} catch (_: IOException) {
			null
		} catch (_: IllegalArgumentException) {
			null
		} ?: return rejected(UNREADABLE)

		dir.mkdirs()
		val raw = File(dir, UUID.randomUUID().toString())
		val copied = try {
			input.use { copyBounded(it, raw) }
		} catch (_: IOException) {
			raw.delete()
			return rejected(UNREADABLE)
		} catch (_: SecurityException) {
			raw.delete()
			return rejected(UNREADABLE)
		}
		if (copied == null) {
			raw.delete()
			return rejected(TOO_LARGE)
		}
		if (copied == 0L) {
			raw.delete()
			return rejected(DAMAGED)
		}

		val header = raw.inputStream().use { s ->
			ByteArray(SnapImageSniffer.HEADER_BYTES).let { b -> b.copyOf(readFully(s, b)) }
		}
		return when (val sniff = SnapImageSniffer.sniff(header)) {
			is SnapImageSniff.Supported -> {
				val file = File(dir, "${raw.name}.${sniff.format.extension}")
				if (!raw.renameTo(file)) {
					raw.delete()
					return rejected(UNREADABLE)
				}
				if (!decodes(file)) {
					file.delete()
					return rejected(DAMAGED)
				}
				// The bytes exactly as they came: a GIF keeps every frame, a PNG
				// its transparency, and nothing is recompressed.
				SnapImageIntake.Taken.Accepted(file, sniff.format, copied)
			}
			SnapImageSniff.Heif -> {
				val out = File(dir, "${raw.name}.jpg")
				val converted = heifToJpeg(raw, out)
				raw.delete()
				when {
					!converted || out.length() == 0L -> {
						out.delete()
						rejected("This HEIC image couldn't be converted on this phone.")
					}
					out.length() > ImageHoster.MAX_SOURCE_BYTES -> {
						out.delete()
						rejected("This HEIC image is over the 14 MB limit once converted to JPEG.")
					}
					else -> SnapImageIntake.Taken.Accepted(out, SnapImageFormat.JPEG, out.length())
				}
			}
			is SnapImageSniff.Unsupported -> {
				raw.delete()
				rejected(
					if (sniff.kind == "file") {
						"That file isn't a supported image. Use JPEG, PNG, WebP, GIF or HEIC."
					} else {
						"${sniff.kind} files can't be attached. Use JPEG, PNG, WebP, GIF or HEIC."
					},
				)
			}
		}
	}

	override fun discard(file: File) {
		// Only ever this intake's own copies.
		if (file.parentFile == dir) file.delete()
	}

	/** Bytes copied, or null once the source passes the limit (nothing larger is kept). */
	private fun copyBounded(input: InputStream, out: File): Long? {
		var total = 0L
		out.outputStream().use { o ->
			val buf = ByteArray(64 * 1024)
			while (true) {
				val n = input.read(buf)
				if (n < 0) break
				total += n
				if (total > ImageHoster.MAX_SOURCE_BYTES) return null
				o.write(buf, 0, n)
			}
		}
		return total
	}

	private fun readFully(s: InputStream, b: ByteArray): Int {
		var n = 0
		while (n < b.size) {
			val r = s.read(b, n, b.size - n)
			if (r < 0) break
			n += r
		}
		return n
	}

	private fun rejected(message: String) = SnapImageIntake.Taken.Rejected(message)

	private companion object {
		const val UNREADABLE = "RustedWax couldn't read that image. Try adding it again."
		const val TOO_LARGE = "That image is over the 14 MB limit."
		const val DAMAGED = "That image couldn't be read — it may be damaged."
	}
}

/**
 * [SnapImageUploader] with the device's own posting key. The key and the
 * account stored beside it are read at upload time, as every other signing
 * port in the app does, and an upload for any other account is refused.
 */
internal class HiveSnapImageUploader(
	private val loadKey: () -> HiveKey?,
	private val storedAccount: () -> String?,
	private val client: ImageHosterClient = ImageHosterClient(),
) : SnapImageUploader {

	override fun upload(
		account: String,
		attachment: SnapAttachment,
		onProgress: (Long, Long) -> Unit,
	): ImageHoster.Result {
		val stored = storedAccount()
		val key = loadKey()
		if (key == null || stored == null || !stored.equals(account, ignoreCase = true)) {
			return ImageHoster.Result.Failed("Sign in to your Hive account to upload images.")
		}
		if (attachment.file.length() > ImageHoster.MAX_SOURCE_BYTES) {
			return ImageHoster.Result.Failed("That image is over the 14 MB limit.")
		}
		val data = try {
			attachment.file.readBytes()
		} catch (_: IOException) {
			return ImageHoster.Result.Failed("RustedWax couldn't read that image any more. Remove it and add it again.")
		}
		return client.upload(
			account = stored,
			key = key,
			data = data,
			fileName = attachment.format.fileName,
			mimeType = attachment.format.mimeType,
			onProgress = onProgress,
		)
	}
}

/** A small still for the composer strip, decoded off the main thread. */
internal object SnapAttachmentThumbs {
	fun decode(file: File, targetPx: Int): Bitmap? = runCatching {
		val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
		BitmapFactory.decodeFile(file.path, bounds)
		var sample = 1
		while (bounds.outWidth / (sample * 2) >= targetPx && bounds.outHeight / (sample * 2) >= targetPx) sample *= 2
		BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
	}.getOrNull()
}
