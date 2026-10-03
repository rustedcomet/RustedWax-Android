package com.rustedwax.app.ui.snaps

import com.rustedwax.app.snaps.SnapAttachmentBlock
import com.rustedwax.app.snaps.SnapImageFormat
import com.rustedwax.hive.ImageHoster
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Issue 40D's composer state: one intake for every source, the four-image
 * limit, removal that keeps everything else, and uploads that never repeat a
 * success, never publish a partial set and never run twice.
 *
 * Unconfined, so every launch completes inline — the same arrangement the
 * other controller tests use.
 */
class SnapAttachmentControllerTest {

	private val key = "alice|reply|bob/rustedwax-reply-1"

	/** Accepts `ok-*` URIs as JPEGs (`gif-*` as GIFs) and refuses everything else by name. */
	private class Intake : SnapImageIntake {
		val taken = mutableListOf<SnapImageSource>()
		val discarded = mutableListOf<File>()
		override fun take(source: SnapImageSource): SnapImageIntake.Taken {
			taken += source
			val name = source.uri.substringAfterLast('/')
			return when {
				name.startsWith("ok-") -> SnapImageIntake.Taken.Accepted(File(name), SnapImageFormat.JPEG, 10)
				name.startsWith("gif-") -> SnapImageIntake.Taken.Accepted(File(name), SnapImageFormat.GIF, 10)
				name.startsWith("pdf-") -> SnapImageIntake.Taken.Rejected("PDF files can't be attached.")
				name.startsWith("big-") -> SnapImageIntake.Taken.Rejected("That image is over the 14 MB limit.")
				name.startsWith("gone-") -> SnapImageIntake.Taken.Rejected("RustedWax couldn't read that image.")
				else -> throw IllegalStateException("malformed")
			}
		}
		override fun discard(file: File) {
			discarded += file
		}
	}

	/** Answers per file name; records every upload actually attempted. */
	private class Uploader : SnapImageUploader {
		val attempts = mutableListOf<String>()
		val failing = mutableSetOf<String>()
		var duringUpload: (() -> Unit)? = null
		override fun upload(
			account: String,
			attachment: SnapAttachment,
			onProgress: (Long, Long) -> Unit,
		): ImageHoster.Result {
			attempts += attachment.file.name
			duringUpload?.invoke()
			onProgress(5, 10)
			if (attachment.file.name in failing) return ImageHoster.Result.Failed("Host down.")
			return ImageHoster.Result.Uploaded(urlFor(attachment.file.name))
		}

		companion object {
			fun urlFor(name: String): String {
				val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
				val tail = (0 until 44).map { alphabet[(it + name.hashCode().and(0xff)) % alphabet.length] }
				return "https://images.hive.blog/DQm${tail.joinToString("")}/image.jpg"
			}
		}
	}

	private fun controller(
		intake: Intake = Intake(),
		uploader: Uploader? = Uploader(),
		account: () -> String? = { "alice" },
	) = SnapAttachmentController(
		scope = CoroutineScope(Dispatchers.Unconfined),
		intake = intake,
		uploader = { uploader },
		account = account,
		io = Dispatchers.Unconfined,
	)

	private fun src(name: String, origin: SnapImageOrigin = SnapImageOrigin.PICKER) =
		SnapImageSource("content://test/$name", origin)

	@Test
	fun `a cancelled intake cannot attach its late copy to a replacement edit`() {
		val discarded = mutableListOf<File>()
		lateinit var c: SnapAttachmentController
		val intake = object : SnapImageIntake {
			override fun take(source: SnapImageSource): SnapImageIntake.Taken {
				if (source.uri.endsWith("old")) {
					c.clear(key)
					c.add(key, listOf(src("replacement")))
				}
				return SnapImageIntake.Taken.Accepted(
					File(source.uri.substringAfterLast('/')), SnapImageFormat.JPEG, 10,
				)
			}
			override fun discard(file: File) { discarded += file }
		}
		c = SnapAttachmentController(
			scope = CoroutineScope(Dispatchers.Unconfined), intake = intake,
			uploader = { Uploader() }, account = { "alice" }, io = Dispatchers.Unconfined,
		)
		c.add(key, listOf(src("old"), src("never-read")))
		assertEquals(listOf("replacement"), c.items(key).map { it.file.name })
		assertEquals(listOf(File("old")), discarded)
		assertEquals(0, c.arriving(key))
	}

	// ── intake and limits ──────────────────────────────────────────────

	@Test
	fun `one image is attached`() {
		val c = controller()
		c.add(key, listOf(src("ok-1")))
		assertEquals(1, c.count(key))
		assertNull(c.notice(key))
	}

	@Test
	fun `four images are attached and a fifth is refused without losing any`() {
		val intake = Intake()
		val c = controller(intake)
		c.add(key, (1..4).map { src("ok-$it") })
		assertEquals(4, c.count(key))
		assertEquals(0, c.room(key))

		c.add(key, listOf(src("ok-5")))

		assertEquals(4, c.count(key))
		assertEquals(listOf("ok-1", "ok-2", "ok-3", "ok-4"), c.items(key).map { it.file.name })
		assertTrue(c.notice(key)!!.contains("up to 4"))
		// The refused fifth was never even read.
		assertEquals(4, intake.taken.size)
	}

	@Test
	fun `a burst larger than the room takes what fits and says how many were left`() {
		val c = controller()
		c.add(key, listOf(src("ok-1")))
		c.add(key, (2..6).map { src("ok-$it") })
		assertEquals(4, c.count(key))
		assertTrue(c.notice(key)!!.contains("2 images weren't added"))
	}

	@Test
	fun `picker, clipboard and keyboard all go through the same intake`() {
		val intake = Intake()
		val c = controller(intake)
		c.add(key, listOf(src("ok-1", SnapImageOrigin.PICKER)))
		c.add(key, listOf(src("ok-2", SnapImageOrigin.CLIPBOARD)))
		c.add(key, listOf(src("gif-3", SnapImageOrigin.KEYBOARD)))
		assertEquals(3, c.count(key))
		assertEquals(
			listOf(SnapImageOrigin.PICKER, SnapImageOrigin.CLIPBOARD, SnapImageOrigin.KEYBOARD),
			intake.taken.map { it.origin },
		)
		assertEquals(SnapImageFormat.GIF, c.items(key).last().format)
	}

	@Test
	fun `unsupported, oversized, inaccessible and malformed items are refused one by one`() {
		val c = controller()

		c.add(key, listOf(src("pdf-1"), src("ok-2"), src("big-3"), src("gone-4")))

		assertEquals(listOf("ok-2"), c.items(key).map { it.file.name })
		val notice = c.notice(key)!!
		assertTrue(notice.contains("PDF"))
		assertTrue(notice.contains("14 MB"))
		assertTrue(notice.contains("couldn't read"))

		// An intake that throws is a refusal too, not a crash.
		c.add(key, listOf(src("weird-6")))
		assertEquals(1, c.count(key))
		assertNotNull(c.notice(key))
	}

	// ── remove, replace, remove all ────────────────────────────────────

	@Test
	fun `removing one keeps the others, and replacing is remove then add`() {
		val intake = Intake()
		val c = controller(intake)
		c.add(key, (1..3).map { src("ok-$it") })
		val second = c.items(key)[1]

		c.remove(key, second.id)

		assertEquals(listOf("ok-1", "ok-3"), c.items(key).map { it.file.name })
		assertEquals(listOf(second.file), intake.discarded)

		c.add(key, listOf(src("ok-9")))
		assertEquals(listOf("ok-1", "ok-3", "ok-9"), c.items(key).map { it.file.name })
	}

	@Test
	fun `removing every image leaves a text-only draft`() {
		val c = controller()
		c.add(key, (1..2).map { src("ok-$it") })
		c.items(key).toList().forEach { c.remove(key, it.id) }
		assertEquals(0, c.count(key))
		assertFalse(SnapAttachmentBlock.canSend("", c.count(key)))
		assertTrue(SnapAttachmentBlock.canSend("still here", c.count(key)))

		var handed: List<String>? = null
		c.upload(key) { handed = it }
		assertEquals(emptyList<String>(), handed)
	}

	@Test
	fun `drafts are kept apart by key`() {
		val c = controller()
		c.add(key, listOf(src("ok-1")))
		c.add("alice|evt-2", listOf(src("ok-2")))
		c.add("bob|evt-2", listOf(src("ok-3")))
		assertEquals(listOf("ok-1"), c.items(key).map { it.file.name })
		assertEquals(listOf("ok-2"), c.items("alice|evt-2").map { it.file.name })
	}

	// ── uploads ────────────────────────────────────────────────────────

	@Test
	fun `every image is uploaded before the addresses are handed over, in order`() {
		val uploader = Uploader()
		val c = controller(uploader = uploader)
		c.add(key, (1..4).map { src("ok-$it") })

		var handed: List<String>? = null
		c.upload(key) { handed = it }

		assertEquals(listOf("ok-1", "ok-2", "ok-3", "ok-4"), uploader.attempts)
		assertEquals((1..4).map { Uploader.urlFor("ok-$it") }, handed)
		assertFalse(c.isUploading(key))
	}

	@Test
	fun `a partial failure keeps the successes, hands nothing over, and a retry uploads only the rest`() {
		val uploader = Uploader().apply { failing += "ok-2" }
		val c = controller(uploader = uploader)
		c.add(key, (1..3).map { src("ok-$it") })

		var calls = 0
		c.upload(key) { calls++ }

		assertEquals(0, calls)
		assertEquals(listOf("ok-1", "ok-2"), uploader.attempts)
		assertEquals(Uploader.urlFor("ok-1"), c.items(key)[0].hostedUrl)
		assertNull(c.items(key)[1].hostedUrl)
		assertNull(c.items(key)[2].hostedUrl)
		assertTrue(c.notice(key)!!.contains("still here"))
		assertEquals("nothing removed", 3, c.count(key))

		uploader.failing.clear()
		uploader.attempts.clear()
		var handed: List<String>? = null
		c.upload(key) { calls++; handed = it }

		assertEquals("the success is never uploaded again", listOf("ok-2", "ok-3"), uploader.attempts)
		assertEquals(1, calls)
		assertEquals((1..3).map { Uploader.urlFor("ok-$it") }, handed)
	}

	@Test
	fun `once everything is hosted a further attempt uploads nothing`() {
		val uploader = Uploader()
		val c = controller(uploader = uploader)
		c.add(key, (1..2).map { src("ok-$it") })
		c.upload(key) {}
		uploader.attempts.clear()

		var handed: List<String>? = null
		c.upload(key) { handed = it }

		assertEquals(emptyList<String>(), uploader.attempts)
		assertEquals(2, handed!!.size)
	}

	@Test
	fun `a stored address that is not a valid hosted reference is asked for again`() {
		val uploader = Uploader()
		val c = controller(uploader = uploader)
		c.add(key, listOf(src("ok-1")))
		// Force a previous answer that is not a reference RustedWax would publish.
		val field = SnapAttachmentController::class.java.getDeclaredField("items").apply { isAccessible = true }
		@Suppress("UNCHECKED_CAST")
		val map = field.get(c) as MutableMap<String, List<SnapAttachment>>
		map[key] = map[key]!!.map { it.copy(hostedUrl = "https://evil.example/x.jpg") }

		var handed: List<String>? = null
		c.upload(key) { handed = it }

		assertEquals(listOf("ok-1"), uploader.attempts)
		assertEquals(listOf(Uploader.urlFor("ok-1")), handed)
	}

	@Test
	fun `a second Send during an upload starts nothing`() {
		val uploader = Uploader()
		val c = controller(uploader = uploader)
		c.add(key, (1..2).map { src("ok-$it") })
		var calls = 0
		uploader.duringUpload = {
			// The tap that arrives while the first upload is running.
			c.upload(key) { calls++ }
			uploader.duringUpload = null
		}

		c.upload(key) { calls++ }

		assertEquals(listOf("ok-1", "ok-2"), uploader.attempts)
		assertEquals("published once", 1, calls)
	}

	@Test
	fun `images cannot change under a running upload`() {
		val uploader = Uploader()
		val c = controller(uploader = uploader)
		c.add(key, (1..2).map { src("ok-$it") })
		uploader.duringUpload = {
			assertTrue(c.isUploading(key))
			assertNotNull(c.progress(key))
			c.remove(key, c.items(key).first().id)
			c.add(key, listOf(src("ok-3")))
			uploader.duringUpload = null
		}

		var handed: List<String>? = null
		c.upload(key) { handed = it }

		assertEquals(2, handed!!.size)
		assertEquals(listOf("ok-1", "ok-2"), c.items(key).map { it.file.name })
	}

	@Test
	fun `a progress update arriving after the upload finished does not revive it`() {
		var late: (() -> Unit)? = null
		val uploader = object : SnapImageUploader {
			override fun upload(
				account: String,
				attachment: SnapAttachment,
				onProgress: (Long, Long) -> Unit,
			): ImageHoster.Result {
				late = { onProgress(10, 10) }
				return ImageHoster.Result.Uploaded(Uploader.urlFor(attachment.file.name))
			}
		}
		val c = SnapAttachmentController(
			scope = CoroutineScope(Dispatchers.Unconfined),
			intake = Intake(),
			uploader = { uploader },
			account = { "alice" },
			io = Dispatchers.Unconfined,
		)
		c.add(key, listOf(src("ok-1")))
		c.upload(key) {}

		late!!.invoke()

		assertFalse(c.isUploading(key))
		assertNull(c.progress(key))
	}

	@Test
	fun `an upload for another account's draft is refused`() {
		val uploader = Uploader()
		val c = controller(uploader = uploader, account = { "bob" })
		c.add(key, listOf(src("ok-1")))
		var calls = 0
		c.upload(key) { calls++ }
		assertEquals(0, calls)
		assertTrue(uploader.attempts.isEmpty())
	}

	@Test
	fun `an account switch during an upload hands nothing over`() {
		var who = "alice"
		val uploader = Uploader()
		val c = controller(uploader = uploader, account = { who })
		c.add(key, (1..2).map { src("ok-$it") })
		uploader.duringUpload = { who = "bob" }
		var calls = 0
		c.upload(key) { calls++ }
		assertEquals(0, calls)
		assertEquals(listOf("ok-1"), uploader.attempts)
		assertFalse(c.isUploading(key))
	}

	@Test
	fun `a successful upload is reused after switching away and back`() {
		var who = "alice"
		val uploader = Uploader()
		val c = controller(uploader = uploader, account = { who })
		c.add(key, listOf(src("ok-1")))
		uploader.duringUpload = { who = "bob" }
		var ready = 0
		c.upload(key) { ready++ }
		assertEquals(0, ready)
		who = "alice"
		uploader.duringUpload = null
		c.upload(key) { ready++ }
		assertEquals(1, ready)
		assertEquals(listOf("ok-1"), uploader.attempts)
	}

	@Test
	fun `no uploader means a failure with everything kept`() {
		val c = controller(uploader = null)
		c.add(key, listOf(src("ok-1")))
		var calls = 0
		c.upload(key) { calls++ }
		assertEquals(0, calls)
		assertEquals(1, c.count(key))
		assertNotNull(c.notice(key))
	}

	@Test
	fun `clear after publication removes the images and their copies`() {
		val intake = Intake()
		val c = controller(intake)
		c.add(key, (1..2).map { src("ok-$it") })
		c.upload(key) {}
		c.clear(key)
		assertEquals(0, c.count(key))
		assertEquals(2, intake.discarded.size)
	}

	@Test
	fun `a discard during an upload neither clears the images nor lets a partial set through`() {
		val uploader = Uploader()
		val c = controller(uploader = uploader)
		c.add(key, (1..2).map { src("ok-$it") })
		uploader.duringUpload = {
			c.clear(key)
			uploader.duringUpload = null
		}
		var handed: List<String>? = null
		c.upload(key) { handed = it }
		assertEquals("clear is refused while uploading", 2, c.count(key))
		assertEquals(2, handed!!.size)
	}

	// ── Issue 40D: Edit shares the four with kept images ───────────────

	@Test
	fun `images an edit already keeps count towards the same four`() {
		val intake = Intake()
		val c = controller(intake)
		assertEquals(2, c.room(key, reserved = 2))
		c.add(key, (1..3).map { src("ok-$it") }, reserved = 2)
		assertEquals(2, c.count(key))
		assertTrue(c.notice(key)!!.contains("up to 4"))
		assertEquals(0, c.room(key, reserved = 2))
		c.add(key, listOf(src("ok-9")), reserved = 2)
		assertEquals("a fifth is refused without losing any", 2, c.count(key))
		assertEquals(2, intake.taken.size)
	}

	@Test
	fun `an upload that hands nothing over always says why, exactly once`() {
		val uploader = Uploader().apply { failing += "ok-2" }
		val c = controller(uploader = uploader)
		c.add(key, (1..2).map { src("ok-$it") })
		val failures = mutableListOf<String>()
		var ready = 0
		c.upload(key, onReady = { ready++ }, onFailed = { failures += it })
		assertEquals(0, ready)
		assertEquals(listOf("Host down."), failures)

		uploader.failing.clear()
		failures.clear()
		c.upload(key, onReady = { ready++ }, onFailed = { failures += it })
		assertEquals(1, ready)
		assertTrue("success is not also a failure", failures.isEmpty())
	}

	@Test
	fun `an account switch during an upload reports a failure`() {
		var who = "alice"
		val uploader = Uploader()
		val c = controller(uploader = uploader, account = { who })
		c.add(key, (1..2).map { src("ok-$it") })
		uploader.duringUpload = { who = "bob" }
		val failures = mutableListOf<String>()
		c.upload(key, onReady = { error("must not publish") }, onFailed = { failures += it })
		assertEquals(1, failures.size)
	}
}
