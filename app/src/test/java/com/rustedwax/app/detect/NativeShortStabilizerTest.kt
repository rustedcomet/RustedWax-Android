package com.rustedwax.app.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeShortStabilizerTest {

	@Test
	fun `identity must remain complete and unchanged across the stability interval`() {
		val stabilizer = NativeShortStabilizer()
		val first = organic("A", "@owner_a", total = 60, position = 0)
		assertTrue(stabilizer.observe(first, 0) is NativeShortStabilizer.Decision.Waiting)
		assertTrue(stabilizer.observe(first.copy(currentSeconds = 1), 500) is NativeShortStabilizer.Decision.Waiting)
		val accepted = stabilizer.observe(first.copy(currentSeconds = 2), 750)
		assertTrue(accepted is NativeShortStabilizer.Decision.Accepted)
	}

	@Test
	fun `outgoing title with incoming duration never stabilizes into a session`() {
		val stabilizer = NativeShortStabilizer()
		val outgoing = organic("Outgoing", "@old_owner", total = 59, position = 8)
		stabilizer.observe(outgoing, 0)
		stabilizer.observe(outgoing.copy(currentSeconds = 9), 800)

		val torn = outgoing.copy(totalSeconds = 101, currentSeconds = 0)
		assertTrue(stabilizer.observe(torn, 1_000) is NativeShortStabilizer.Decision.Waiting)
		val incoming = organic("Incoming", "@new_owner", total = 101, position = 1)
		assertTrue(stabilizer.observe(incoming, 1_200) is NativeShortStabilizer.Decision.Waiting)
		assertTrue(stabilizer.observe(incoming.copy(currentSeconds = 2), 1_949) is NativeShortStabilizer.Decision.Waiting)
		val accepted = stabilizer.observe(incoming.copy(currentSeconds = 2), 1_950)
		assertEquals(incoming.copy(currentSeconds = 2), (accepted as NativeShortStabilizer.Decision.Accepted).result)
	}

	@Test
	fun `organic ad organic each require independent stability`() {
		val stabilizer = NativeShortStabilizer()
		val organic = organic("Organic", "@creator", total = 30, position = 0)
		stabilizer.observe(organic, 0)
		stabilizer.observe(organic.copy(currentSeconds = 1), 800)

		val ad = NativeShortParser.Result.Ad("Ad", "Advert", 0, 20)
		assertTrue(stabilizer.observe(ad, 1_000) is NativeShortStabilizer.Decision.Waiting)
		assertTrue(stabilizer.observe(ad.copy(currentSeconds = 1), 1_800) is NativeShortStabilizer.Decision.Accepted)

		val successor = organic("Successor", "@next_owner", total = 45, position = 0)
		assertTrue(stabilizer.observe(successor, 2_000) is NativeShortStabilizer.Decision.Waiting)
		assertTrue(stabilizer.observe(successor.copy(currentSeconds = 1), 2_800) is NativeShortStabilizer.Decision.Accepted)
	}

	@Test
	fun `missing proof resets accumulated stability`() {
		val stabilizer = NativeShortStabilizer()
		val result = organic("A", "@owner_a", total = 60, position = 0)
		stabilizer.observe(result, 0)
		stabilizer.reset()
		assertTrue(stabilizer.observe(result.copy(currentSeconds = 1), 800) is NativeShortStabilizer.Decision.Waiting)
		assertTrue(stabilizer.observe(result.copy(currentSeconds = 2), 1_550) is NativeShortStabilizer.Decision.Accepted)
	}

	@Test
	fun `fresh complete first frame can hand directly to proven picture in picture`() {
		val stabilizer = NativeShortStabilizer()
		val first = organic("Immediate PiP", "@owner", total = 33, position = 1)

		assertTrue(stabilizer.observe(first, 1_000) is NativeShortStabilizer.Decision.Waiting)
		assertEquals(first, stabilizer.promotePendingOrganicForImmediatePip(1_900))
		assertEquals(null, stabilizer.promotePendingOrganicForImmediatePip(2_000))
	}

	@Test
	fun `immediate picture in picture handoff refuses stale ad and unnamed candidates`() {
		val stale = NativeShortStabilizer()
		stale.observe(organic("Stale", "@owner", total = 33, position = 1), 1_000)
		assertEquals(
			null,
			stale.promotePendingOrganicForImmediatePip(
				1_000 + NativeShortStabilizer.IMMEDIATE_PIP_HANDOVER_MS + 1,
			),
		)

		val ad = NativeShortStabilizer()
		ad.observe(NativeShortParser.Result.Ad("Ad", "Advert", 0, 20), 1_000)
		assertEquals(null, ad.promotePendingOrganicForImmediatePip(1_500))

		val unnamed = NativeShortStabilizer()
		unnamed.observe(NativeShortParser.Result.OrganicUnnamed(1, 33), 1_000)
		assertEquals(null, unnamed.promotePendingOrganicForImmediatePip(1_500))
	}

	@Test
	fun `unnamed incoming seekbar cannot inherit outgoing identity after navigation reset`() {
		val stabilizer = NativeShortStabilizer()
		val outgoing = NativeShortParser.Result.OrganicUnmeasured("Outgoing", "@old_owner")
		stabilizer.observe(outgoing, 0)
		assertTrue(
			stabilizer.observe(outgoing, 750) is NativeShortStabilizer.Decision.Accepted,
		)

		// Production resets stability on YouTube's TYPE_VIEW_SCROLLED boundary.
		stabilizer.reset()
		val incomingBar = NativeShortParser.Result.OrganicUnnamed(
			currentSeconds = 0,
			totalSeconds = 108,
		)
		assertTrue(
			stabilizer.observe(incomingBar, 1_000) is NativeShortStabilizer.Decision.Waiting,
		)
	}

	@Test
	fun `unnamed progress continues only the accepted Short of the same duration`() {
		val stabilizer = NativeShortStabilizer()
		val current = organic("Current", "@owner", total = 55, position = 0)
		stabilizer.observe(current, 0)
		stabilizer.observe(current.copy(currentSeconds = 1), 750)

		assertTrue(
			stabilizer.observe(
				NativeShortParser.Result.OrganicUnnamed(2, 55),
				1_000,
			) is NativeShortStabilizer.Decision.Accepted,
		)
		assertTrue(
			stabilizer.observe(
				NativeShortParser.Result.OrganicUnnamed(0, 108),
				1_100,
			) is NativeShortStabilizer.Decision.Waiting,
		)
	}

	/**
	 * Field 2026-08-16, Galaxy A36: four Shorts played back to back at 2x.
	 *
	 * The 2x press-and-hold strips the title and the owner handle for as long as
	 * the finger is down and leaves the seekbar readable, so every frame after the
	 * hold begins is `OrganicUnnamed`. A Short whose footer was read once but had
	 * not yet crossed the 750 ms interval therefore received no further identity
	 * frame at all: one Short was never acquired and produced no listen, and a
	 * second finalized at 1s of 20s and was refused below threshold.
	 */
	@Test
	fun `a same-length unnamed frame completes stabilization when the 2x hold hides the footer`() {
		val stabilizer = NativeShortStabilizer()
		val footer = organic("Held at 2x", "@owner", total = 20, position = 1)
		assertTrue(stabilizer.observe(footer, 0) is NativeShortStabilizer.Decision.Waiting)

		// The hold begins. Nothing but the bar is left, and it is too early.
		assertTrue(
			stabilizer.observe(
				NativeShortParser.Result.OrganicUnnamed(2, 20, playbackRate = 2.0),
				400,
			) is NativeShortStabilizer.Decision.Waiting,
		)

		val accepted = stabilizer.observe(
			NativeShortParser.Result.OrganicUnnamed(4, 20, playbackRate = 2.0),
			NativeShortStabilizer.STABILITY_MS,
		)
		assertEquals(
			"the identity read off the footer is what the listen must acquire",
			footer,
			(accepted as NativeShortStabilizer.Decision.Accepted).result,
		)

		// Now accepted, so the hold's own frames continue it as usual.
		assertTrue(
			stabilizer.observe(
				NativeShortParser.Result.OrganicUnnamed(6, 20, playbackRate = 2.0),
				NativeShortStabilizer.STABILITY_MS + 1_000,
			) is NativeShortStabilizer.Decision.Accepted,
		)
	}

	@Test
	fun `an unnamed frame never completes stabilization on a length it cannot corroborate`() {
		// A different length is a different Short.
		val mismatched = NativeShortStabilizer()
		mismatched.observe(organic("Outgoing", "@old_owner", total = 55, position = 8), 0)
		assertTrue(
			mismatched.observe(
				NativeShortParser.Result.OrganicUnnamed(0, 108),
				2_000,
			) is NativeShortStabilizer.Decision.Waiting,
		)

		// A pending Short with no seekbar has no length to corroborate at all, so
		// an unnamed bar may not complete it either.
		val unmeasured = NativeShortStabilizer()
		unmeasured.observe(
			NativeShortParser.Result.OrganicUnmeasured("Outgoing", "@old_owner"),
			0,
		)
		assertTrue(
			unmeasured.observe(
				NativeShortParser.Result.OrganicUnnamed(0, 108),
				2_000,
			) is NativeShortStabilizer.Decision.Waiting,
		)

		// And a navigation reset leaves nothing for an incoming bar to complete.
		val navigated = NativeShortStabilizer()
		navigated.observe(organic("Outgoing", "@old_owner", total = 108, position = 8), 0)
		navigated.reset()
		assertTrue(
			navigated.observe(
				NativeShortParser.Result.OrganicUnnamed(0, 108),
				2_000,
			) is NativeShortStabilizer.Decision.Waiting,
		)
	}

	private fun organic(
		title: String,
		handle: String,
		total: Long,
		position: Long,
	) = NativeShortParser.Result.Organic(title, handle, position, total)
}
