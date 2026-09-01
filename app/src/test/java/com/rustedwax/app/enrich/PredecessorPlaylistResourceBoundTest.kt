package com.rustedwax.app.enrich

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Resource bounds for the fallback that reads public playlists in bulk. */
class PredecessorPlaylistResourceBoundTest {

	private val source: String by lazy {
		val root = generateSequence(File(checkNotNull(System.getProperty("user.dir"))).absoluteFile) {
			it.parentFile
		}.first { File(it, "settings.gradle.kts").isFile }
		File(root, "app/src/main/java/com/rustedwax/app/enrich/VideoIdResolver.kt").readText()
	}

	@Test
	fun `predecessor discovery cannot allocate twenty playlist pages together`() {
		assertTrue(
			"the verified candidate budget is too wide for the app process heap",
			VideoIdResolver.MAX_PREDECESSOR_PLAYLIST_CANDIDATES <= 8,
		)
		assertTrue(
			"playlist page parsing concurrency must remain at most two",
			VideoIdResolver.MAX_PREDECESSOR_PLAYLIST_CONCURRENCY <= 2,
		)
		assertTrue(
			"the concurrency limit is declared but not applied to playlist fetches",
			source.contains("Semaphore(MAX_PREDECESSOR_PLAYLIST_CONCURRENCY)") &&
				source.contains("playlistPermits.withPermit"),
		)
	}
}
