package com.rustedwax.app.detect

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real SharedPreferences coverage for the restart-finalization boundary. */
@RunWith(AndroidJUnit4::class)
class FinalizedPlaybackTombstonesDeviceTest {

	private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
	private val prefs get() =
		context.getSharedPreferences("rustedwax_finished_transport", Context.MODE_PRIVATE)

	@Before
	fun setUp() = prefs.edit().clear().commit().let { Unit }

	@After
	fun tearDown() = prefs.edit().clear().commit().let { Unit }

	@Test
	fun persistsOnlyOpaqueBoundedDataAndMatchesAcrossInstances() {
		val packageName = "com.brave.browser"
		val semanticKey = "private video title|private channel|"
		SharedPreferencesFinalizedPlaybackTombstones(context).record(
			packageName,
			semanticKey,
			finalizedAtEpochMs = 1_000_000,
			finalizedAtElapsedMs = 100_000,
			transportUpdatedAtElapsedMs = 90_000,
			durationMs = 94_000,
		)

		val serialized = prefs.all.entries.joinToString("|") { "${it.key}:${it.value}" }
		assertTrue(prefs.all.keys.single().matches(Regex("[0-9a-f]{64}")))
		assertTrue("package leaked into persistent state", packageName !in serialized)
		assertTrue("title leaked into persistent state", "private video title" !in serialized)
		assertTrue("channel leaked into persistent state", "private channel" !in serialized)
		assertNotNull(
			SharedPreferencesFinalizedPlaybackTombstones(context).findStaleTransport(
				packageName,
				semanticKey,
				nowEpochMs = 1_001_000,
				nowElapsedMs = 101_000,
				transportUpdatedAtElapsedMs = 90_000,
				positionMs = 94_000,
			),
		)

		repeat(80) { index ->
			SharedPreferencesFinalizedPlaybackTombstones(context).record(
				packageName,
				"title $index|channel|",
				finalizedAtEpochMs = 1_001_000L + index,
				finalizedAtElapsedMs = 101_000L + index,
				transportUpdatedAtElapsedMs = 90_000L + index,
				durationMs = 94_000,
			)
		}
		assertEquals("persistent tombstones were not bounded", 64, prefs.all.size)
	}

	@Test
	fun newerTransportSameBootIsNotSuppressed() {
		val store = SharedPreferencesFinalizedPlaybackTombstones(context)
		store.record("browser", "title|channel|", 1_000_000, 100_000, 90_000, 94_000)

		assertNull(
			store.findStaleTransport(
				"browser", "title|channel|", 1_001_000, 101_000, 90_001, 0,
			),
		)
		assertNotNull(
			"Android-restamped transport past its recorded end was not recognized",
			store.findStaleTransport(
				"browser", "title|channel|", 1_001_000, 101_000, 100_000, 100_000,
			),
		)
	}

	@Test
	fun expiredOrPreviousBootRecordIsNotSuppressed() {
		val store = SharedPreferencesFinalizedPlaybackTombstones(context)
		store.record("browser", "title|channel|", 1_000_000, 100_000, 90_000, 94_000)

		assertNull(
			store.findStaleTransport(
				"browser", "title|channel|", 1_000_001, 99_999, 90_000, 100_000,
			),
		)
		assertNull(
			store.findStaleTransport(
				"browser",
				"title|channel|",
				nowEpochMs = 1_000_000 + 6 * 60 * 60 * 1000L + 1,
				nowElapsedMs = 200_000,
				transportUpdatedAtElapsedMs = 90_000,
				positionMs = 100_000,
			),
		)
	}
}
