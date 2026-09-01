package com.rustedwax.app.scrobble

import com.rustedwax.core.TrackInstanceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class VerifiedPlaybackSequenceTest {
	private val sequence = VerifiedPlaybackSequence()
	private val pkg = "com.brave.browser"

	/** One listen, named the way the probe names it: a monotonic instance token. */
	private fun instance(token: Long, packageName: String = pkg) =
		TrackInstanceId(packageName, token)

	@Test
	fun `returns only the immediately preceding two verified tracks`() {
		sequence.begin(instance(1), 100)
		sequence.remember(instance(1), 100, "8RddqlctLnk", "2pac - When thugs Cry")
		sequence.begin(instance(2), 200)
		sequence.remember(instance(2), 200, "FGjyRjd1_jQ", "2pac When We Ride On Our Enemies")
		sequence.begin(instance(3), 300)

		assertEquals(
			VerifiedPlaybackSequence.Predecessor("8RddqlctLnk", "2pac - When thugs Cry") to
				VerifiedPlaybackSequence.Predecessor("FGjyRjd1_jQ", "2pac When We Ride On Our Enemies"),
			sequence.predecessors(instance(3)),
		)
	}

	@Test
	fun `an unresolved finalized track breaks adjacency`() {
		sequence.begin(instance(1), 100)
		sequence.remember(instance(1), 100, "8RddqlctLnk", "one")
		sequence.begin(instance(2), 200)
		sequence.begin(instance(3), 300)
		sequence.remember(instance(3), 300, "FGjyRjd1_jQ", "two")
		sequence.begin(instance(4), 400)

		assertNull(sequence.predecessors(instance(4)))
	}

	@Test
	fun `network completion order cannot reorder playback`() {
		sequence.begin(instance(1), 100)
		sequence.begin(instance(2), 200)
		sequence.begin(instance(3), 300)
		sequence.remember(instance(2), 200, "FGjyRjd1_jQ", "two")
		sequence.remember(instance(1), 100, "8RddqlctLnk", "one")

		assertEquals(
			VerifiedPlaybackSequence.Predecessor("8RddqlctLnk", "one") to
				VerifiedPlaybackSequence.Predecessor("FGjyRjd1_jQ", "two"),
			sequence.predecessors(instance(3)),
		)
	}

	@Test
	fun `packages and lifecycle clears are isolated`() {
		sequence.begin(instance(1), 100)
		sequence.remember(instance(1), 100, "8RddqlctLnk", "one")
		sequence.begin(instance(2), 200)
		sequence.remember(instance(2), 200, "FGjyRjd1_jQ", "two")
		sequence.begin(instance(3), 300)
		sequence.begin(instance(4, "com.google.android.youtube"), 300)

		assertNull(sequence.predecessors(instance(4, "com.google.android.youtube")))
		sequence.clear(pkg)
		assertNull(sequence.predecessors(instance(3)))
	}

	// ---- same-second transitions ----------------------------------------------

	@Test
	fun `two listens beginning in the same second stay two entries`() {
		// The regression. Chromium's metadata callback finalizes the outgoing
		// listen and stamps the incoming one's start from the same
		// `System.currentTimeMillis()`, so consecutive tracks sharing a start
		// second is ordinary rather than exotic. Keyed by the second alone, the
		// second `begin` found the first already present and added nothing, so the
		// boundary this class exists to create never existed.
		sequence.begin(instance(1), 100)
		sequence.remember(instance(1), 100, "8RddqlctLnk", "one")
		sequence.begin(instance(2), 500)
		sequence.remember(instance(2), 500, "FGjyRjd1_jQ", "two")
		// Track three ends and track four starts inside second 500.
		sequence.begin(instance(3), 500)

		assertEquals(
			VerifiedPlaybackSequence.Predecessor("8RddqlctLnk", "one") to
				VerifiedPlaybackSequence.Predecessor("FGjyRjd1_jQ", "two"),
			sequence.predecessors(instance(3)),
		)
	}

	@Test
	fun `a same-second successor cannot overwrite its predecessor's verified id`() {
		// The other half of the collision, and the one with teeth: `remember`
		// located the earlier row by start second and replaced its id. The pair
		// handed back was then "the same video twice", which `predecessors`
		// refuses — so the adjacent-playlist route silently went unavailable for
		// every track after a same-second transition.
		sequence.begin(instance(1), 100)
		sequence.remember(instance(1), 100, "8RddqlctLnk", "one")
		sequence.begin(instance(2), 200)
		sequence.remember(instance(2), 200, "FGjyRjd1_jQ", "two")
		sequence.begin(instance(3), 200)
		sequence.remember(instance(3), 200, "7J6xA1_f8as", "three")
		sequence.begin(instance(4), 300)

		assertNotNull(
			"the adjacent pair must survive a same-second transition",
			sequence.predecessors(instance(4)),
		)
		assertEquals(
			VerifiedPlaybackSequence.Predecessor("FGjyRjd1_jQ", "two") to
				VerifiedPlaybackSequence.Predecessor("7J6xA1_f8as", "three"),
			sequence.predecessors(instance(4)),
		)
		// And the earlier same-second entry kept its own id rather than being
		// overwritten by its successor's.
		assertEquals(
			VerifiedPlaybackSequence.Predecessor("8RddqlctLnk", "one") to
				VerifiedPlaybackSequence.Predecessor("FGjyRjd1_jQ", "two"),
			sequence.predecessors(instance(3)),
		)
	}

	@Test
	fun `same-second entries are ordered by instance token, not by map order`() {
		// Order matters as much as separation: `resolveEvidenceFromAdjacentPredecessors`
		// is handed the pair oldest-first and reads a playlist direction out of it.
		// Registered out of order on purpose — an overlapping network finalization
		// is exactly how they arrive.
		sequence.begin(instance(2), 700)
		sequence.begin(instance(1), 700)
		sequence.remember(instance(1), 700, "8RddqlctLnk", "first")
		sequence.remember(instance(2), 700, "FGjyRjd1_jQ", "second")
		sequence.begin(instance(3), 700)

		assertEquals(
			VerifiedPlaybackSequence.Predecessor("8RddqlctLnk", "first") to
				VerifiedPlaybackSequence.Predecessor("FGjyRjd1_jQ", "second"),
			sequence.predecessors(instance(3)),
		)
	}

	@Test
	fun `a listen carried across session recreation stays one entry`() {
		// `TrackProgressCarry` restores the instance token along with the played
		// time and the frozen start, so a listen that survives Chromium rebuilding
		// its MediaSession must not appear twice and shift the adjacency window.
		sequence.begin(instance(1), 100)
		sequence.remember(instance(1), 100, "8RddqlctLnk", "one")
		sequence.begin(instance(2), 200)
		sequence.remember(instance(2), 200, "FGjyRjd1_jQ", "two")
		sequence.begin(instance(3), 300)
		// Same token, same frozen start: the replacement session's finalization.
		sequence.begin(instance(3), 300)
		sequence.remember(instance(3), 300, "7J6xA1_f8as", "three")
		sequence.begin(instance(4), 400)

		assertEquals(
			VerifiedPlaybackSequence.Predecessor("FGjyRjd1_jQ", "two") to
				VerifiedPlaybackSequence.Predecessor("7J6xA1_f8as", "three"),
			sequence.predecessors(instance(4)),
		)
	}
}
