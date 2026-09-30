package com.rustedwax.app.enrich

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class VerifiedIdentityCandidateCacheTest {

	private val pkg = "com.android.chrome"
	private val plus57 =
		"KAROL G, Feid, DFZM ft. Ovy On The Drums, J Balvin, Maluma, Ryan Castro, Blessd - +57"

	@Before fun setUp() = VerifiedIdentityCandidateCache.clearAll()
	@After fun tearDown() {
		VerifiedIdentityCandidateCache.beforeStore = null
		VerifiedIdentityCandidateCache.clearAll()
	}

	@Test
	fun `5r5UePOgMQU later replay becomes a candidate in the same run`() {
		VerifiedIdentityCandidateCache.remember(
			packageName = pkg,
			videoId = "5r5UePOgMQU",
			title = plus57,
			channel = "KarolGVEVO",
			durationMs = 301_701,
			now = 10_000,
		)
		assertEquals(
			listOf("5r5UePOgMQU"),
			VerifiedIdentityCandidateCache.candidates(
				pkg, plus57, "KarolGVEVO", 301_000, now = 20_000,
			),
		)
	}

	@Test
	fun `cache is capped aged and cleared at lifecycle boundaries`() {
		repeat(VerifiedIdentityCandidateCache.MAX_ENTRIES + 5) { index ->
			VerifiedIdentityCandidateCache.remember(
				pkg,
				"id${index.toString().padStart(9, '0')}",
				"Replay",
				"Uploader",
				180_000,
				now = index.toLong(),
			)
		}
		assertEquals(VerifiedIdentityCandidateCache.MAX_ENTRIES, VerifiedIdentityCandidateCache.size())
		VerifiedIdentityCandidateCache.clear(pkg)
		assertEquals(0, VerifiedIdentityCandidateCache.size())

		VerifiedIdentityCandidateCache.remember(
			pkg, "5r5UePOgMQU", plus57, "KarolGVEVO", 301_000, now = 1_000,
		)
		assertTrue(
			VerifiedIdentityCandidateCache.candidates(
				pkg, plus57, "KarolGVEVO", 301_000,
				now = 1_001 + VerifiedIdentityCandidateCache.MAX_AGE_MS,
			).isEmpty(),
		)
	}

	@Test
	fun `Best movie duplicate uploads remain ambiguous`() {
		val candidates = listOf("PlTiqSpwzTI", "VL_1TfgB2pw").map { id ->
			SearchResultsParser.Candidate(id, "Best movie!!! #movie #shorts", "Edit Channel", 173)
		}
		assertEquals(
			2,
			SearchResultsParser.identityMatches(
				candidates,
				"Best movie!!! #movie #shorts",
				"Edit Channel",
				173,
			).size,
		)
		assertNull(
			SearchResultsParser.bestMatch(
				candidates,
				"Best movie!!! #movie #shorts",
				"Edit Channel",
				173,
			),
		)
	}

	@Test
	fun `WHEN SINCE gets a bounded presentation-cleaned search variant`() {
		val queries = VideoIdResolver().searchQueries(
			"VYBZ KARTEL WHEN SINCE",
			"Vybz Kartel",
		)
		assertTrue(queries.any { it.equals("when since Vybz Kartel", ignoreCase = true) })
		assertTrue(queries.size <= 6)
	}

	@Test
	fun `foreground handle candidates are exact and legacy channel rows cannot satisfy them`() {
		val title = "Which is your favorite team?"
		VerifiedIdentityCandidateCache.remember(
			pkg, "Bf7Qtyr-2IQ", title, "Status", 41_000,
			ownerHandle = "@Status_svijet", now = 10_000,
		)
		VerifiedIdentityCandidateCache.remember(
			pkg, "abcdefghijk", title, "Status", 41_000, now = 11_000,
		)
		assertEquals(
			listOf("Bf7Qtyr-2IQ"),
			VerifiedIdentityCandidateCache.candidates(
				pkg, title, "Different display author", 42_000,
				ownerHandle = "@status_SVIJET", now = 12_000,
			),
		)
		assertTrue(
			VerifiedIdentityCandidateCache.candidates(
				pkg, title, "Status", 42_000,
				ownerHandle = "@status-svijet", now = 12_000,
			).isEmpty(),
		)
	}

	// ---- a Short proof is never lost to a concurrent remember (Issue #3) -------

	private val shortId = "c1Ykgp7mqIg"
	private val shortTitle = "Would you take her offer? #goth"

	private fun rememberShort(provenShort: Boolean, videoId: String = shortId) =
		VerifiedIdentityCandidateCache.remember(
			packageName = pkg,
			videoId = videoId,
			title = shortTitle,
			channel = "Queen Nephie",
			durationMs = 46_000,
			provenShort = provenShort,
		)

	/**
	 * The exact interleaving two finalizations on `Dispatchers.IO` can produce:
	 * the less-informed remember has read "not a Short" and is about to store;
	 * the Shorts-proven remember for the same key runs meanwhile. Whatever order
	 * they then land in, the stored entry has to say Short.
	 *
	 * The seam holds the first remember at that point until the second has
	 * finished, or for [RACE_WINDOW_MS] if the second cannot finish while the
	 * first holds the entry. Only the outcome is asserted, never the timing.
	 */
	@Test
	fun `a racing less-informed remember cannot erase a Short proof`() {
		val lessInformedInside = CountDownLatch(1)
		val provenFinished = CountDownLatch(1)
		val firstStore = AtomicBoolean(true)
		VerifiedIdentityCandidateCache.beforeStore = {
			if (firstStore.compareAndSet(true, false)) {
				lessInformedInside.countDown()
				provenFinished.await(RACE_WINDOW_MS, TimeUnit.MILLISECONDS)
			}
		}

		val lessInformed = thread { rememberShort(provenShort = false) }
		assertTrue(lessInformedInside.await(5, TimeUnit.SECONDS))
		val proven = thread {
			rememberShort(provenShort = true)
			provenFinished.countDown()
		}
		lessInformed.join(5_000)
		proven.join(5_000)

		assertFalse(lessInformed.isAlive || proven.isAlive)
		assertTrue(
			"a concurrent remember with no Short proof overwrote the proof",
			VerifiedIdentityCandidateCache.provesShort(pkg, shortId),
		)
	}

	@Test
	fun `less-informed refreshes cannot downgrade a proven Short`() {
		rememberShort(provenShort = true)
		val workers = 8
		val start = CyclicBarrier(workers)
		(1..workers).map {
			thread {
				start.await(5, TimeUnit.SECONDS)
				repeat(50) { rememberShort(provenShort = false) }
			}
		}.forEach { it.join(10_000) }

		assertTrue(VerifiedIdentityCandidateCache.provesShort(pkg, shortId))
		assertEquals(1, VerifiedIdentityCandidateCache.size())
	}

	@Test
	fun `an ordinary entry stays unproven however it is refreshed`() {
		val ordinaryId = "5r5UePOgMQU"
		val workers = 8
		val start = CyclicBarrier(workers)
		(1..workers).map { index ->
			thread {
				start.await(5, TimeUnit.SECONDS)
				repeat(50) {
					rememberShort(provenShort = false, videoId = ordinaryId)
					// A Short proof for a different upload is not this one's.
					if (index == 1) rememberShort(provenShort = true)
				}
			}
		}.forEach { it.join(10_000) }

		assertFalse(VerifiedIdentityCandidateCache.provesShort(pkg, ordinaryId))
		assertTrue(VerifiedIdentityCandidateCache.provesShort(pkg, shortId))
	}

	private companion object {
		const val RACE_WINDOW_MS = 500L
	}
}
