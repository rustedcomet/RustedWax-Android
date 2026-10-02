package com.rustedwax.app.architecture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue 40C refresh triggers, asserted where a unit test cannot reach the
 * screen: History pulls to refresh without clearing the list, a return to the
 * foreground goes through the five-minute rule, the Comments root is the
 * refreshed copy, opening a conversation re-reads its root, and nothing about
 * refreshing is a timer, a service or a write.
 */
class SnapRefreshWiringTest {

	private val root: File by lazy {
		generateSequence(File(checkNotNull(System.getProperty("user.dir"))).absoluteFile) {
			it.parentFile
		}.firstOrNull { File(it, "settings.gradle.kts").isFile }
			?: error("repository root was not found")
	}

	private fun code(path: String): String = File(root, path).let {
		assertTrue("production source missing: $path", it.isFile)
		it.readText()
			.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
			.replace(Regex("//[^\n]*"), " ")
	}

	private val main by lazy { code("app/src/main/java/com/rustedwax/app/ui/MainScreen.kt") }
	private val activity by lazy { code("app/src/main/java/com/rustedwax/app/MainActivity.kt") }
	private val posting by lazy { code("app/src/main/java/com/rustedwax/app/ui/snaps/SnapPosting.kt") }

	@Test
	fun `History pulls to refresh around the same list, which stays on screen`() {
		val history = main.substringAfter("private fun HistoryList(").substringBefore("\nprivate fun ")
		assertEquals(1, Regex("""PullToRefreshBox\(""").findAll(history).count())
		val box = history.substringAfter("PullToRefreshBox(")
		assertTrue(box.substringBefore(") {").contains("isRefreshing = posts.isRefreshing"))
		assertTrue(box.substringBefore(") {").contains("onRefresh = { refreshSnaps(recent, snaps, posts, threads) }"))
		// The list itself is the box's content — not replaced by a spinner.
		assertTrue(box.substringAfter(") {").trimStart().startsWith("LazyColumn(state = listState"))
		assertTrue(history.contains("posts.refreshFailed"))
	}

	@Test
	fun `a foreground return goes through the staleness rule, not a timer`() {
		val screen = main.substringAfter("fun MainScreen(").substringBefore("\nprivate fun ")
		assertTrue(screen.contains("Lifecycle.Event.ON_START"))
		assertTrue(screen.contains("posts.refreshIfStale { refreshNow() }"))
		listOf("while (true)", "delay(", "Timer(", "WorkManager", "AlarmManager", "JobScheduler").forEach {
			assertFalse("refresh must not use $it", (screen + posting).contains(it))
		}
	}

	@Test
	fun `the shared refresh reads posted Snaps and force-reads their threads`() {
		val fn = main.substringAfter("private fun refreshSnaps(").substringBefore("\n}")
		assertTrue(fn.contains("posts.refresh(recent.map { it.eventId })"))
		assertTrue(fn.contains("threads.load(it, force = true)"))
	}


	@Test
	fun `both root Post callbacks and Retry refresh the confirmed thread`() {
		val history = main.substringAfter("private fun HistoryList(").substringBefore("\nprivate fun ")
		val row = main.substringAfter("private fun SnapActionRow(").substringBefore("\nprivate fun ")
		val postCallbacks = Regex("""onPublished = \{ contentId ->(.*?)\},""", RegexOption.DOT_MATCHES_ALL)
		val callbacks = postCallbacks.findAll(history + row).map { it.groupValues[1] }.toList()
		assertEquals(2, callbacks.size)
		callbacks.forEach { assertTrue(it.contains("?.let(threads::reloadAfterWrite)")) }
		val retry = row.substringAfter("posts.retry(key, record.eventId) { contentId ->").substringBefore("\n\t\t\t\t\t}")
		assertTrue(retry.contains("?.let(threads::reloadAfterWrite)"))
	}

	@Test
	fun `the Comments root is the refreshed copy and opening re-reads it`() {
		assertTrue(main.contains("rootSnap = posts.postedFor(root.contentId) ?: threads.openRootSnap"))
		assertTrue(activity.contains("onRootOpened = posts::refreshContent"))
		assertTrue(activity.contains("cache = postedCache"))
	}
}
