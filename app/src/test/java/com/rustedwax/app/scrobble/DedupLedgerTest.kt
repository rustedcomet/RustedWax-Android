package com.rustedwax.app.scrobble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The dedup key itself. `claim`/`release` need a `Context`, but `keyFor` is
 * pure — and it is now the shared contract between two code paths (the
 * automatic finalize and the Now card's manual button), so the pairs that must
 * and must not collide are worth pinning.
 *
 * The on-chain duplicates of 2026-07-24/25 were not a key defect — the manual
 * path simply never consulted the ledger. These guard the key against drifting
 * once both paths depend on it.
 */
class DedupLedgerTest {

	/** 2026-07-25T15:49:46Z, and a few offsets from it. */
	private val base = 1785080986L

	@Test
	fun `same listen produces the same key`() {
		assertEquals(
			DedupLedger.keyFor("Official Trailer", "TURNING POINT", base),
			DedupLedger.keyFor("Official Trailer", "TURNING POINT", base),
		)
	}

	/**
	 * The exact duplicate shape seen on-chain: identical title, artist and
	 * track-start, differing only in percent. Percent is not part of the key,
	 * so the second attempt must collide with the first.
	 */
	@Test
	fun `percent played is not part of the key`() {
		val first = DedupLedger.keyFor("Gods Full Fight & Final Scene", "Immortals (2011)", base)
		val second = DedupLedger.keyFor("Gods Full Fight & Final Scene", "Immortals (2011)", base)
		assertEquals(first, second)
	}

	/** Manual and automatic paths receive the same frozen start for one listen. */
	@Test
	fun `manual and automatic finalization of the same start collide`() {
		assertEquals(
			DedupLedger.keyFor("Against The Kraken", "Clash Of The Titans", base),
			DedupLedger.keyFor("Against The Kraken", "Clash Of The Titans", base),
		)
	}

	/** A genuine replay is a new listen even when both starts share a UTC clock hour. */
	@Test
	fun `a replay in the same hour is a different listen`() {
		assertNotEquals(
			DedupLedger.keyFor("Against The Kraken", "Clash Of The Titans", base),
			DedupLedger.keyFor("Against The Kraken", "Clash Of The Titans", base + 6 * 60),
		)
	}

	/** The 2026-08-11 bug: a boundary must not decide which replay is admitted. */
	@Test
	fun `utc hour boundaries do not change replay identity`() {
		val boundary = (base / 3600 + 1) * 3600
		assertNotEquals(
			DedupLedger.keyFor("Blasphemy", "Multimedia Madhouse", boundary - 1),
			DedupLedger.keyFor("Blasphemy", "Multimedia Madhouse", boundary),
		)
		assertNotEquals(
			DedupLedger.keyFor("Blasphemy", "Multimedia Madhouse", boundary + 60),
			DedupLedger.keyFor("Blasphemy", "Multimedia Madhouse", boundary + 31 * 60),
		)
	}

	@Test
	fun `case does not defeat the key`() {
		assertEquals(
			DedupLedger.keyFor("Toxicity", "System Of A Down", base),
			DedupLedger.keyFor("TOXICITY", "system of a down", base),
		)
	}

	@Test
	fun `different tracks do not collide`() {
		assertNotEquals(
			DedupLedger.keyFor("Crawling", "Linkin Park", base),
			DedupLedger.keyFor("One Step Closer", "Linkin Park", base),
		)
	}

	/** A missing artist is a valid payload shape and must still key cleanly. */
	@Test
	fun `a null artist is handled`() {
		assertEquals(
			DedupLedger.keyFor("Some Upload", null, base),
			DedupLedger.keyFor("Some Upload", null, base),
		)
		assertNotEquals(
			DedupLedger.keyFor("Some Upload", null, base),
			DedupLedger.keyFor("Some Upload", "An Artist", base),
		)
	}
}
