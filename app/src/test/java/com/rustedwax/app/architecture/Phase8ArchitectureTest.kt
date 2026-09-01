package com.rustedwax.app.architecture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Executable dependency and package-boundary checks. */
class Phase8ArchitectureTest {

	private val root: File by lazy {
		generateSequence(File(checkNotNull(System.getProperty("user.dir"))).absoluteFile) {
			it.parentFile
		}.firstOrNull { File(it, "settings.gradle.kts").isFile }
			?: error("repository root was not found")
	}

	private val modules = listOf(
		"core",
		"identity-api",
		"youtube-identity",
		"android-sources",
		"hive",
		"app",
	)

	private fun kotlinSources(module: String): List<File> =
		File(root, "$module/src/main").walkTopDown()
			.filter { it.isFile && it.extension == "kt" }
			.toList()

	private fun allProductionSources(): List<File> = modules.flatMap(::kotlinSources)

	private fun violations(
		files: Iterable<File>,
		forbidden: Regex,
	): List<String> = buildList {
		for (file in files) {
			file.readLines().forEachIndexed { index, line ->
				if (forbidden.containsMatchIn(line)) {
					add("${file.relativeTo(root)}:${index + 1}: ${line.trim()}")
				}
			}
		}
	}

	@Test
	fun `the six intended modules exist and are included`() {
		val settings = File(root, "settings.gradle.kts").readText()
		modules.forEach { module ->
			assertTrue("missing module directory :$module", File(root, module).isDirectory)
			assertTrue(":$module is not included", settings.contains("\":$module\""))
		}
	}

	@Test
	fun `core imports no Android and names no platform`() {
		val core = kotlinSources("core")
		assertTrue("core has no production sources", core.isNotEmpty())
		val forbidden = Regex(
			"(?i)(^\\s*import\\s+(android|androidx)\\.|\\bandroid\\b|" +
				"\\byoutube\\b|\\bspotify\\b|\\bnetflix\\b|\\bsoundcloud\\b|" +
				"com\\.google\\.android)",
		)
		assertEquals(emptyList<String>(), violations(core, forbidden))
	}

	@Test
	fun `source adapters do not depend on one another`() {
		val adapterFiles = allProductionSources().filter {
			it.name.endsWith("Adapter.kt") && it.name != "SourceAdapter.kt"
		}
		val adapterNames = adapterFiles.map { it.nameWithoutExtension }.toSet()
		assertTrue("no production source adapters were found", adapterNames.isNotEmpty())
		val failures = buildList {
			for (file in adapterFiles) {
				val own = file.nameWithoutExtension
				val text = file.readText()
				for (other in adapterNames - own) {
					if (Regex("\\b${Regex.escape(other)}\\b").containsMatchIn(text)) {
						add("${file.relativeTo(root)} references $other")
					}
				}
			}
		}
		assertEquals(emptyList<String>(), failures)
	}

	@Test
	fun `identity resolution imports neither UI nor media-session classes`() {
		val appRepositories = File(
			root,
			"app/src/main/java/com/rustedwax/app/enrich",
		).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
		val identity = kotlinSources("identity-api") + kotlinSources("youtube-identity") +
			appRepositories
		assertTrue("identity modules have no production sources", identity.isNotEmpty())
		val forbidden = Regex(
			"^\\s*import\\s+(android\\.media\\.|androidx\\.media\\.|androidx\\.compose\\.|" +
				"com\\.rustedwax\\.app\\.ui\\.)",
		)
		assertEquals(emptyList<String>(), violations(identity, forbidden))
	}

	@Test
	fun `source kind checks exist only in source registration and adapters`() {
		val allowed = setOf("SourceRegistry.kt", "SourceAdapter.kt")
		val failures = buildList {
			allProductionSources().filterNot {
				it.name in allowed || it.name.endsWith("Adapter.kt")
			}.forEach { file ->
				file.readLines().forEachIndexed { index, line ->
					if (Regex("\\bisNative[A-Za-z0-9_]*").containsMatchIn(line)) {
						add("${file.relativeTo(root)}:${index + 1}: ${line.trim()}")
					}
				}
			}
		}
		assertEquals(emptyList<String>(), failures)
	}

	@Test
	fun `shared domain and finalization APIs are source neutral`() {
		val shared = kotlinSources("core") + kotlinSources("identity-api") +
			allProductionSources().filter { it.name == "FinalizeTrackUseCase.kt" }
		assertTrue("the shared finalization boundary is missing", shared.any {
			it.name == "FinalizeTrackUseCase.kt"
		})
		val forbidden = Regex("(?i)(\\byoutube\\b|\\bvideoId\\b|watch\\?v=)")
		assertEquals(emptyList<String>(), violations(shared, forbidden))
	}

	@Test
	fun `the fake source is genuinely independent of Android and YouTube adapters`() {
		val fixture = File(
			root,
			"app/src/test/java/com/rustedwax/app/replay/ForeignSourceFinalizationReplayTest.kt",
		)
		assertTrue("foreign-source production-wiring fixture is missing", fixture.isFile)
		val text = fixture.readText()
		listOf(
			"android.media",
			"MediaMetadata",
			"NativeYouTubeAdapter",
			"YouTubeProbe",
			"youtube.com",
			"watch?v=",
		).forEach { forbidden ->
			assertFalse("foreign fixture still depends on $forbidden", text.contains(forbidden))
		}
		assertTrue("fixture does not traverse an adapter", text.contains("SourceAdapter"))
		assertTrue("fixture does not traverse the reducer", text.contains("PlaybackReducer"))
		assertTrue("fixture does not enter production finalization", text.contains("finalizeTrack.execute"))
		val runtime = File(
			root,
			"app/src/main/java/com/rustedwax/app/scrobble/FinalizationRuntime.kt",
		).readText()
		assertTrue("production runtime bypasses the finalization use case", runtime.contains("FinalizeTrackUseCase"))
		assertTrue("fixture does not assert recorded dispatch", text.contains("recordedDispatch"))
	}

	@Test
	fun `storage cannot depend on scrobble orchestration`() {
		val storage = allProductionSources().filter {
			it.invariantSeparatorsPath.contains("/storage/")
		}
		assertTrue("no production storage sources were found", storage.isNotEmpty())
		assertEquals(
			emptyList<String>(),
			violations(storage, Regex("\\bcom\\.rustedwax\\.app\\.scrobble\\b|^\\s*import\\s+.*\\.scrobble(\\.|$)")),
		)
	}

	@Test
	fun `module dependencies follow the intended acyclic direction`() {
		val expected = mapOf(
			"core" to emptySet(),
			"identity-api" to setOf("core"),
			"youtube-identity" to setOf("core", "identity-api"),
			"android-sources" to setOf("core", "identity-api"),
			"hive" to setOf("core"),
			"app" to setOf("core", "identity-api", "youtube-identity", "android-sources", "hive"),
		)
		val projectDependency = Regex("project\\(\\s*\"?:([^\"')]+)\"?\\s*\\)")
		val actual = modules.associateWith { module ->
			val build = File(root, "$module/build.gradle.kts")
			assertTrue("missing :$module/build.gradle.kts", build.isFile)
			projectDependency.findAll(build.readText()).map { it.groupValues[1] }.toSet()
		}
		assertEquals(expected, actual)

		val visiting = mutableSetOf<String>()
		val visited = mutableSetOf<String>()
		fun visit(module: String) {
			assertTrue("module dependency cycle reaches :$module", visiting.add(module))
			actual.getValue(module).forEach(::visit)
			visiting.remove(module)
			visited += module
		}
		modules.forEach { if (it !in visited) visit(it) }
	}
}
