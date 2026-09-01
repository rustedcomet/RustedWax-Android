package com.rustedwax.app.detect

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class AdEvidenceTest {

	private val pkg = "com.android.chrome"

	@Before fun setUp() = AdEvidence.clearAll()
	@After fun tearDown() = AdEvidence.clearAll()

	private fun evidence(id: String, generation: Long, at: Long = 1_000L) =
		AdEvidence.Evidence(pkg, id, "Sponsored", generation, at)

	@Test
	fun `log 14 stadium label cannot veto IW524Zl2Pus successor`() {
		val stadium = "stadiumAd01"
		AdEvidence.onUrlObserved(pkg, stadium, generation = 1)
		AdEvidence.markSessionEstablished(pkg, stadium, generation = 1)
		assertNotNull(AdEvidence.observe(evidence(stadium, generation = 1)))

		val organic = "IW524Zl2Pus"
		AdEvidence.onUrlObserved(pkg, organic, generation = 2)
		// Exact field ordering: URL advanced, then the stale Sponsored label
		// appeared once. It is provisional and disappears on the next frame.
		assertNull(AdEvidence.observe(evidence(organic, generation = 2, at = 1_002)))
		assertNull(AdEvidence.get(pkg, organic, urlGeneration = 2, now = 1_003))
		AdEvidence.labelAbsent(pkg, organic, generation = 2)
		assertNull(AdEvidence.get(pkg, organic, urlGeneration = 2, now = 1_004))
	}

	@Test
	fun `stable label is accepted only after re-observation`() {
		val id = "abcdefghijk"
		AdEvidence.onUrlObserved(pkg, id, generation = 7)
		assertNull(AdEvidence.observe(evidence(id, generation = 7, at = 10)))
		assertNotNull(AdEvidence.observe(evidence(id, generation = 7, at = 20)))
		assertEquals("Sponsored", AdEvidence.get(pkg, id, urlGeneration = 7, now = 21)?.signal)
	}

	@Test
	fun `new generation label disappearance stop and package reset clear provisional state`() {
		val id = "abcdefghijk"
		AdEvidence.onUrlObserved(pkg, id, generation = 1)
		AdEvidence.observe(evidence(id, generation = 1))
		AdEvidence.onUrlObserved(pkg, "lmnopqrstuv", generation = 2)
		assertNull(AdEvidence.observe(evidence("lmnopqrstuv", generation = 2)))
		AdEvidence.labelAbsent(pkg, "lmnopqrstuv", generation = 2)
		assertNull(AdEvidence.get(pkg, "lmnopqrstuv", urlGeneration = 2))

		AdEvidence.observe(evidence("lmnopqrstuv", generation = 2))
		AdEvidence.clear(pkg) // user Stop/package reset
		assertNull(AdEvidence.get(pkg, "lmnopqrstuv", urlGeneration = 2))
	}

	@Test
	fun `accepted veto does not poison a later organic generation of the same id`() {
		val id = "abcdefghijk"
		AdEvidence.onUrlObserved(pkg, id, generation = 1)
		AdEvidence.observe(evidence(id, generation = 1, at = 10))
		AdEvidence.observe(evidence(id, generation = 1, at = 20))
		assertNotNull(AdEvidence.get(pkg, id, urlGeneration = 1, now = 21))

		AdEvidence.onUrlObserved(pkg, id, generation = 2)
		assertNull(AdEvidence.get(pkg, id, urlGeneration = 2, now = 22))
	}
}
