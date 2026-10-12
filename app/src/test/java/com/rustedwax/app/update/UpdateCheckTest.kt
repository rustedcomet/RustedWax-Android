package com.rustedwax.app.update

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckTest {

	private class MemoryState : UpdateCheckState {
		override var lastAttemptAt = 0L
		override var lastSuccessAt = 0L
		override var notifiedVersionCode = 0
	}

	private class RecordingNotifier(var permitted: Boolean = true) : UpdateNotifier {
		val posted = mutableListOf<Pair<UpdateMetadata, String>>()
		var cancels = 0
		override fun canPost() = permitted
		override fun post(available: UpdateMetadata, destination: String) {
			posted += available to destination
		}
		override fun cancel() {
			cancels++
		}
	}

	private fun json(code: Any, name: String = "0.14.0", schema: Any = 1, id: Any = "com.rustedwax.app") =
		"""{"schema":${q(schema)},"applicationId":${q(id)},"versionCode":${q(code)},"versionName":"$name"}"""

	private fun q(v: Any) = if (v is String) "\"$v\"" else v.toString()

	private val v65 = UpdateMetadata(65, "0.13.0")
	private val v66 = UpdateMetadata(66, "0.14.0")
	private val v67 = UpdateMetadata(67, "0.15.0")

	private val day = UpdateCheck.SUCCESS_INTERVAL_MILLIS
	private val t0 = 1_800_000_000_000L

	private fun check(
		state: UpdateCheckState,
		notifier: UpdateNotifier,
		official: () -> Result<UpdateMetadata>,
		installed: Int = 65,
		distribution: Distribution = Distribution.GITHUB,
	) = UpdateCheck(installed, distribution, state, { official() }, notifier)

	// --- metadata -----------------------------------------------------------

	@Test
	fun `well formed metadata is accepted`() {
		assertEquals(v66, UpdateMetadata.parse(json(66)))
		assertEquals(v66, UpdateMetadata.parse(json(66), tag = "v0.14.0"))
		assertEquals(v66, UpdateMetadata.parse(json(66).dropLast(1) + ""","notes":"ignored"}"""))
	}

	@Test
	fun `malformed, missing, inconsistent or foreign metadata is refused`() {
		listOf(
			"",
			"not json",
			"[]",
			"""{"versionCode":66}""",
			json("66"),
			json(66.0),
			json(true),
			json(0),
			json(-1),
			json(2_100_000_001L),
			json(99_999_999_999L),
			json(66, name = "latest"),
			json(66, name = "0.14"),
			json(66, name = "0.14.0-beta"),
			json(66, schema = 2),
			json(66, schema = "1"),
			json(66, id = "com.rustedwax.app.freshtest"),
			json(66, id = "com.example.app"),
			"""{"schema":1,"applicationId":"com.rustedwax.app","versionName":"0.14.0"}""",
		).forEach { assertNull("accepted: $it", UpdateMetadata.parse(it)) }
		assertNull("tag disagrees with versionName", UpdateMetadata.parse(json(66), tag = "v0.13.0"))
	}

	// --- comparison ---------------------------------------------------------

	@Test
	fun `installed equal or newer than official shows nothing and clears a leftover`() {
		listOf(65 to v65, 66 to v65).forEach { (installed, official) ->
			val notifier = RecordingNotifier()
			val outcome = check(MemoryState(), notifier, { Result.success(official) }, installed).run(t0)
			assertEquals(UpdateCheck.Outcome.Current(official), outcome)
			assertTrue(notifier.posted.isEmpty())
			assertEquals(1, notifier.cancels)
		}
	}

	@Test
	fun `a higher versionCode is announced once, with the version`() {
		val state = MemoryState()
		val notifier = RecordingNotifier()
		val outcome = check(state, notifier, { Result.success(v66) }).run(t0)
		assertEquals(UpdateCheck.Outcome.Announced(v66, UpdateDestination.GITHUB), outcome)
		assertEquals(listOf(v66 to UpdateDestination.GITHUB), notifier.posted)
		assertEquals(66, state.notifiedVersionCode)
	}

	@Test
	fun `versionName never decides, only versionCode`() {
		val notifier = RecordingNotifier()
		val lowerCodeHigherName = UpdateMetadata(64, "9.9.9")
		val outcome = check(MemoryState(), notifier, { Result.success(lowerCodeHigherName) }).run(t0)
		assertTrue(outcome is UpdateCheck.Outcome.Current)
		assertTrue(notifier.posted.isEmpty())
	}

	// --- remembered versions ------------------------------------------------

	@Test
	fun `an already announced or dismissed version is not announced again, a later one is`() {
		val state = MemoryState()
		val notifier = RecordingNotifier()
		var official = v66
		val check = check(state, notifier, { Result.success(official) })
		check.run(t0)
		assertEquals(UpdateCheck.Outcome.AlreadyNotified(v66), check.run(t0 + day))
		assertEquals(UpdateCheck.Outcome.AlreadyNotified(v66), check.run(t0 + 30 * day))
		official = v67
		assertEquals(UpdateCheck.Outcome.Announced(v67, UpdateDestination.GITHUB), check.run(t0 + 31 * day))
		assertEquals(listOf(66, 67), notifier.posted.map { it.first.versionCode })
	}

	@Test
	fun `remembered state survives a new process`() {
		val state = MemoryState()
		check(state, RecordingNotifier(), { Result.success(v66) }).run(t0)
		// A new process: new check, new notifier, same persisted state.
		val after = RecordingNotifier()
		val outcome = check(state, after, { Result.success(v66) }).run(t0 + day)
		assertEquals(UpdateCheck.Outcome.AlreadyNotified(v66), outcome)
		assertTrue(after.posted.isEmpty())
	}

	@Test
	fun `notifications off records nothing, so a later grant still hears about it once`() {
		val state = MemoryState()
		val notifier = RecordingNotifier(permitted = false)
		val check = check(state, notifier, { Result.success(v66) })
		assertEquals(UpdateCheck.Outcome.Skipped("notifications off"), check.run(t0))
		assertEquals(0, state.notifiedVersionCode)
		notifier.permitted = true
		assertTrue(check.run(t0 + day) is UpdateCheck.Outcome.Announced)
		assertTrue(check.run(t0 + 2 * day) is UpdateCheck.Outcome.AlreadyNotified)
		assertEquals(1, notifier.posted.size)
	}

	// --- distribution -------------------------------------------------------

	@Test
	fun `only the Play Store counts as Play, everything else goes to GitHub`() {
		assertEquals(Distribution.PLAY, Distribution.of("com.android.vending"))
		listOf(null, "", "com.android.shell", "com.google.android.packageinstaller", "com.android.chrome", "org.fdroid.fdroid")
			.forEach { assertEquals(it.toString(), Distribution.GITHUB, Distribution.of(it)) }
	}

	@Test
	fun `a Play install is sent to Play and never to a GitHub APK`() {
		val notifier = RecordingNotifier()
		check(MemoryState(), notifier, { Result.success(v66) }, distribution = Distribution.PLAY).run(t0)
		val destination = notifier.posted.single().second
		assertEquals("https://play.google.com/store/apps/details?id=com.rustedwax.app", destination)
		assertFalse("github" in destination)
	}

	@Test
	fun `destinations are fixed pages, never an APK`() {
		Distribution.entries.forEach {
			val url = UpdateDestination.of(it)
			assertTrue(url.startsWith("https://"))
			assertFalse(url.endsWith(".apk"))
			assertFalse("/download/" in url)
		}
		assertEquals("https://github.com/rustedcomet/RustedWax-Android/releases/latest", UpdateDestination.GITHUB)
	}

	// --- throttling ---------------------------------------------------------

	@Test
	fun `a fresh success is not re-read, a stale one is`() {
		val state = MemoryState()
		var reads = 0
		val check = check(state, RecordingNotifier(), { reads++; Result.success(v65) })
		check.run(t0)
		assertEquals(UpdateCheck.Outcome.Skipped("checked recently"), check.run(t0 + 1))
		assertEquals(UpdateCheck.Outcome.Skipped("checked recently"), check.run(t0 + day - 1))
		assertTrue(check.run(t0 + day) is UpdateCheck.Outcome.Current)
		assertEquals(2, reads)
	}

	@Test
	fun `a failure is retried after six hours, not on every start`() {
		val state = MemoryState()
		var reads = 0
		val check = check(state, RecordingNotifier(), { reads++; Result.failure(IllegalStateException("offline")) })
		assertEquals(UpdateCheck.Outcome.Failed("offline"), check.run(t0))
		assertEquals(UpdateCheck.Outcome.Skipped("checked recently"), check.run(t0 + 60_000))
		assertEquals(UpdateCheck.Outcome.Skipped("checked recently"), check.run(t0 + UpdateCheck.RETRY_INTERVAL_MILLIS - 1))
		assertTrue(check.run(t0 + UpdateCheck.RETRY_INTERVAL_MILLIS) is UpdateCheck.Outcome.Failed)
		assertEquals(2, reads)
		assertEquals(0L, state.lastSuccessAt)
	}

	@Test
	fun `a clock that went backwards counts as stale`() {
		assertTrue(UpdateCheck.due(now = t0 - day, lastAttemptAt = t0, lastSuccessAt = t0))
		assertTrue(UpdateCheck.due(now = t0, lastAttemptAt = 0, lastSuccessAt = 0))
		assertFalse(UpdateCheck.due(now = t0 + 1, lastAttemptAt = t0, lastSuccessAt = t0))
	}

	@Test
	fun `the attempt is recorded before the read, so process death mid-read does not retry at once`() {
		val state = MemoryState()
		val died = runCatching {
			check(state, RecordingNotifier(), { throw IllegalStateException("process killed") }).run(t0)
		}
		assertTrue(died.isFailure)
		assertEquals(t0, state.lastAttemptAt)
		assertEquals(
			UpdateCheck.Outcome.Skipped("checked recently"),
			check(state, RecordingNotifier(), { Result.success(v66) }).run(t0 + 1),
		)
	}

	@Test
	fun `failures and refused metadata never notify`() {
		val notifier = RecordingNotifier()
		listOf<Throwable>(IllegalStateException("HTTP 404"), java.net.SocketTimeoutException("timeout"), IllegalStateException("metadata refused"))
			.forEach { error ->
				val outcome = check(MemoryState(), notifier, { Result.failure(error) }).run(t0)
				assertTrue(outcome is UpdateCheck.Outcome.Failed)
			}
		assertTrue(notifier.posted.isEmpty())
		assertEquals(0, notifier.cancels)
	}

	@Test
	fun `overlapping checks share one read`() {
		val entered = CountDownLatch(1)
		val release = CountDownLatch(1)
		val notifier = RecordingNotifier()
		val state = MemoryState()
		var reads = 0
		val slow = check(state, notifier, {
			reads++
			entered.countDown()
			release.await(5, TimeUnit.SECONDS)
			Result.success(v66)
		})
		var first: UpdateCheck.Outcome? = null
		val t = thread { first = slow.run(t0) }
		assertTrue(entered.await(5, TimeUnit.SECONDS))
		val second = check(MemoryState(), notifier, { reads++; Result.success(v66) }).run(t0)
		release.countDown()
		t.join(5_000)
		assertEquals(UpdateCheck.Outcome.Skipped("already running"), second)
		assertTrue(first is UpdateCheck.Outcome.Announced)
		assertEquals(1, reads)
		assertEquals(1, notifier.posted.size)
	}

	@Test
	fun `the debug override re-reads on every start but still announces a version once`() {
		val state = MemoryState()
		val notifier = RecordingNotifier()
		var reads = 0
		val check = UpdateCheck(65, Distribution.GITHUB, state, { reads++; Result.success(v66) }, notifier, ignoreThrottle = true)
		check.run(t0)
		check.run(t0 + 1)
		check.run(t0 + 2)
		assertEquals(3, reads)
		assertEquals(1, notifier.posted.size)
	}
}
