package com.rustedwax.app.ui.snaps

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import com.rustedwax.app.snaps.SnapAttachmentBlock
import com.rustedwax.app.snaps.SnapImageFormat
import com.rustedwax.hive.ImageHoster
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Where an incoming image came from. Only ever used to word a message. */
enum class SnapImageOrigin { PICKER, CLIPBOARD, KEYBOARD }

/** One incoming image, before RustedWax has read a byte of it. */
data class SnapImageSource(
	/** A `content://` URI as the platform handed it over. */
	val uri: String,
	val origin: SnapImageOrigin,
)

/**
 * One image attached to a draft (Issue 40D): a private copy RustedWax owns,
 * and — once uploaded — the address Hive's image host gave it.
 */
data class SnapAttachment(
	val id: Long,
	/** App-private copy (converted to JPEG when the source was HEIC/HEIF). */
	val file: File,
	val format: SnapImageFormat,
	val bytes: Long,
	/** Set once, by a verified upload; reused by every later attempt. */
	val hostedUrl: String? = null,
)

/**
 * The single intake every source goes through — picker, clipboard and
 * keyboard alike: read the bytes now, decide the format from them, enforce the
 * size limit, convert HEIC/HEIF, and keep a private copy so a URI whose grant
 * expires later cannot break an upload.
 */
interface SnapImageIntake {
	sealed interface Taken {
		data class Accepted(val file: File, val format: SnapImageFormat, val bytes: Long) : Taken
		data class Rejected(val message: String) : Taken
	}

	/** Blocking; called off the main thread. */
	fun take(source: SnapImageSource): Taken

	/** Remove a private copy that is no longer attached. */
	fun discard(file: File)
}

/**
 * What an Edit needs from the attachment store (Issue 40D): how many new
 * images wait under a key, uploading them, and letting them go. Kept this
 * narrow so the thread controller cannot reach anything else here.
 */
interface SnapEditImages {
	fun count(key: String): Int
	fun upload(key: String, onReady: (List<String>) -> Unit, onFailed: (String) -> Unit)
	fun clear(key: String)
}

/** Signs and uploads one private copy under [account]'s posting key. Blocking. */
fun interface SnapImageUploader {
	fun upload(
		account: String,
		attachment: SnapAttachment,
		onProgress: (sent: Long, total: Long) -> Unit,
	): ImageHoster.Result
}

/**
 * The images on every open draft, keyed exactly as the draft's text is
 * (account + slot), and the upload that turns them into hosted addresses.
 *
 * Sending is two steps and they never overlap: every image is uploaded first,
 * and only when **all** of them have a verified address is the caller handed
 * the list — publication itself, and its duplicate-safety, stay exactly where
 * they were. An upload that fails keeps the words and every image; addresses
 * already obtained are kept and never requested again, so a retry uploads only
 * what is still missing.
 *
 * In memory only. The words survive a restart through the draft stores; the
 * images do not, and their private copies are swept on the next start.
 */
@Stable
class SnapAttachmentController(
	private val scope: CoroutineScope,
	private val intake: SnapImageIntake,
	private val uploader: () -> SnapImageUploader?,
	/** Read late, at the moment of acting. */
	private val account: () -> String?,
	private val io: CoroutineDispatcher = Dispatchers.IO,
) : SnapEditImages {

	/** Upload progress for one draft: image [index] (1-based) of [total], and its sent fraction. */
	data class Progress(val index: Int, val total: Int, val fraction: Float)

	private val items = mutableStateMapOf<String, List<SnapAttachment>>()
	/** Slots claimed by sources still being copied, so a burst cannot pass the limit. */
	private val arriving = mutableStateMapOf<String, Int>()
	/** A cancelled intake may finish copying, but cannot populate a replacement draft. */
	private val intakeOwners = mutableMapOf<String, Any>()
	private val notices = mutableStateMapOf<String, String>()
	private val progress = mutableStateMapOf<String, Progress>()
	/** Claimed synchronously, so a second Send tap is turned away before anything runs. */
	private val uploading = mutableSetOf<String>()
	private var nextId = 1L

	fun items(key: String): List<SnapAttachment> = items[key].orEmpty()

	override fun count(key: String): Int = items(key).size

	/** Images still being copied in for [key]. Send waits for them. */
	fun arriving(key: String): Int = arriving[key] ?: 0

	/**
	 * How many more images [key] can take. [reserved] counts images already
	 * attached elsewhere that share the same four-image total — an Edit's kept,
	 * already-hosted images.
	 */
	fun room(key: String, reserved: Int = 0): Int =
		(SnapAttachmentBlock.MAX - reserved - count(key) - arriving(key)).coerceAtLeast(0)

	fun notice(key: String): String? = notices[key]

	fun progress(key: String): Progress? = progress[key]

	fun isUploading(key: String): Boolean = key in progress

	fun dismissNotice(key: String) {
		notices.remove(key)
	}

	/**
	 * Take images from any source. Up to the four-image total is accepted; the
	 * rest — and anything unreadable, unsupported or too large — is refused one
	 * by one with a reason, never by clearing the words or the other images.
	 */
	fun add(key: String, sources: List<SnapImageSource>, reserved: Int = 0) {
		if (sources.isEmpty()) return
		if (key in uploading) {
			notices[key] = "Wait for the upload to finish before adding images."
			return
		}
		val taken = sources.take(room(key, reserved))
		val refused = sources.size - taken.size
		val problems = mutableListOf<String>()
		if (refused > 0) {
			problems += "You can attach up to ${SnapAttachmentBlock.MAX} images. " +
				if (refused == 1) "1 image wasn't added." else "$refused images weren't added."
		}
		if (taken.isEmpty()) {
			notices[key] = problems.joinToString(" ")
			return
		}
		notices.remove(key)
		arriving[key] = arriving(key) + taken.size
		val owner = intakeOwners.getOrPut(key) { Any() }
		scope.launch {
			for (source in taken) {
				if (intakeOwners[key] !== owner) return@launch
				val result = withContext(io) {
					runCatching { intake.take(source) }.getOrElse {
						SnapImageIntake.Taken.Rejected("RustedWax couldn't read that image.")
					}
				}
				if (intakeOwners[key] !== owner) {
					if (result is SnapImageIntake.Taken.Accepted) withContext(io) { intake.discard(result.file) }
					return@launch
				}
				arriving[key] = (arriving(key) - 1).coerceAtLeast(0)
				if (arriving(key) == 0) {
					arriving.remove(key)
					intakeOwners.remove(key)
				}
				when (result) {
					is SnapImageIntake.Taken.Accepted -> items[key] = items(key) + SnapAttachment(
						id = nextId++,
						file = result.file,
						format = result.format,
						bytes = result.bytes,
					)
					is SnapImageIntake.Taken.Rejected -> problems += result.message
				}
			}
			if (problems.isNotEmpty()) notices[key] = problems.distinct().joinToString(" ")
		}
	}

	/** Take one image off the draft. The words and the other images stay. */
	fun remove(key: String, id: Long) {
		if (key in uploading) return
		val gone = items(key).firstOrNull { it.id == id } ?: return
		val left = items(key) - gone
		if (left.isEmpty()) items.remove(key) else items[key] = left
		notices.remove(key)
		scope.launch(io) { intake.discard(gone.file) }
	}

	/**
	 * Every image on [key] has gone out with a proven publication, or the
	 * draft was discarded. Refused while an upload owns them.
	 */
	override fun clear(key: String) {
		if (key in uploading) return
		intakeOwners.remove(key)
		arriving.remove(key)
		val gone = items.remove(key).orEmpty()
		notices.remove(key)
		if (gone.isNotEmpty()) scope.launch(io) { gone.forEach { intake.discard(it.file) } }
	}

	/**
	 * Upload whatever on [key] has no address yet, then hand every address —
	 * in the order shown — to [onReady]. Nothing is handed over unless every
	 * image has one, and with no images [onReady] runs at once with none.
	 */
	fun upload(key: String, onReady: (List<String>) -> Unit) = upload(key, onReady) {}

	/**
	 * [upload], also telling [onFailed] why whenever it ends without handing
	 * addresses over — so a caller that has already moved on (an Edit saved in
	 * the background) is never left waiting. A second call while one is
	 * running is not a failure: the first one will answer.
	 */
	override fun upload(key: String, onReady: (List<String>) -> Unit, onFailed: (String) -> Unit) {
		if (arriving(key) > 0) {
			notices[key] = "Still adding images…"
			onFailed("Images were still being added.")
			return
		}
		val snapshot = items(key)
		// Reused only while still a well-formed hosted address. A previous answer
		// that is not one is not a reference to publish, so it is asked for again.
		val missing = snapshot.filter { it.hostedUrl?.let(ImageHoster.HOSTED_URL::matches) != true }
		if (missing.isEmpty()) {
			onReady(snapshot.map { it.hostedUrl!! })
			return
		}
		if (!uploading.add(key)) return
		val who = account()?.takeIf { it.isNotBlank() }
		// The draft key carries its account; never upload one account's images
		// under another's key.
		if (who == null || key.substringBefore('|') != who) {
			uploading.remove(key)
			notices[key] = "Sign in to your Hive account to attach images."
			onFailed("Sign in to your Hive account to attach images.")
			return
		}
		notices.remove(key)
		progress[key] = Progress(1, missing.size, 0f)
		scope.launch {
			var handed = false
			var why = "The images couldn't be uploaded."
			try {
				missing.forEachIndexed { i, item ->
					progress[key] = Progress(i + 1, missing.size, 0f)
					val port = uploader()
					val result = if (port == null) {
						ImageHoster.Result.Failed("Image upload isn't available right now.")
					} else {
						withContext(io) {
							runCatching {
								port.upload(who, item.copy(hostedUrl = null)) { sent, total ->
									val f = if (total > 0) sent.toFloat() / total else 0f
									// Posted from the upload thread, so it can land after the
									// upload has finished; a late one must not revive it.
									scope.launch {
										if (key in uploading && progress[key]?.index == i + 1) {
											progress[key] = Progress(i + 1, missing.size, f)
										}
									}
								}
							}.getOrElse {
								ImageHoster.Result.Failed("Couldn't reach Hive's image host. Try again.")
							}
						}
					}
					// This verified result belongs to the captured account's key even
					// if the active account changed while the network was answering.
					// Keep it for that account's retry; never publish it as the new one.
					when (result) {
						is ImageHoster.Result.Uploaded -> items[key] = items(key).map {
							if (it.id == item.id) it.copy(hostedUrl = result.url) else it
						}
						is ImageHoster.Result.Failed -> {
							val left = missing.size - i
							notices[key] = result.message + " Your text and images are still here" +
								if (left > 1) " — $left images still need uploading." else "."
							why = result.message
							return@launch
						}
					}
					if (account() != who) {
						why = "You've switched Hive accounts."
						return@launch
					}
				}
				// Exactly the images Send was tapped with, every one hosted — or
				// nothing at all. A set changed underneath is never published.
				val now = items(key)
				if (now.map { it.id } != snapshot.map { it.id }) return@launch
				val urls = now.map { it.hostedUrl }
				if (urls.any { it == null }) return@launch
				uploading.remove(key)
				progress.remove(key)
				handed = true
				onReady(urls.filterNotNull())
			} finally {
				uploading.remove(key)
				progress.remove(key)
				if (!handed) onFailed(why)
			}
		}
	}
}
