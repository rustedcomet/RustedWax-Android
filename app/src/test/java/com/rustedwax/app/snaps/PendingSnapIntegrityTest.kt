package com.rustedwax.app.snaps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A stored record must agree with the key it is filed under.
 *
 * The case this exists for: a row stored under event A whose decoded record
 * claims event B. Asking about A finds an inconsistency; asking about B finds
 * *nothing at all*, and "nothing" is the one answer that would let B mint a
 * fresh permlink and post a duplicate of a Snap that may already be on chain
 * under A's record. Both identities are therefore locked.
 */
class PendingSnapIntegrityTest {

	private val account = "alice"

	private fun record(
		account: String = this.account,
		eventId: String,
		permlink: String = "rustedwax-snap-1000-aaaaaa",
	) = PendingSnap(
		account = account,
		eventId = eventId,
		author = account,
		permlink = permlink,
		parentAuthor = "peak.snaps",
		parentPermlink = "snap-container-1789648560",
		body = "body",
		jsonMetadata = "{}",
		signedTransactionJson = """{"op":1}""",
		txId = "tx-1",
		expirationEpochSec = 2_000_000_000L,
		state = PendingSnapState.ACCEPTED_UNCONFIRMED,
		createdAtEpochSec = 1_000L,
		updatedAtEpochSec = 1_000L,
	).toJson()

	@Test
	fun `a consistent record is valid and locks nothing`() {
		val entries = mapOf("event-a" to record(eventId = "event-a"))
		assertEquals(emptySet<String>(), PendingSnapIntegrity.lockedEventIds(account, entries))
		assertEquals(1, PendingSnapIntegrity.valid(account, entries).size)
	}

	/** The blocker: key A, record claiming B. */
	@Test
	fun `a key and record event mismatch locks both identities`() {
		val entries = mapOf("event-a" to record(eventId = "event-b"))

		val locked = PendingSnapIntegrity.lockedEventIds(account, entries)
		assertEquals(setOf("event-a", "event-b"), locked)
		assertTrue("the key's event is locked", "event-a" in locked)
		assertTrue("the claimed event is locked too", "event-b" in locked)
		assertEquals(
			"a mismatched record is valid for neither event",
			emptyList<PendingSnap>(),
			PendingSnapIntegrity.valid(account, entries),
		)
	}

	@Test
	fun `a record claiming another account locks its key`() {
		val entries = mapOf("event-a" to record(account = "bob", eventId = "event-a"))
		assertTrue("event-a" in PendingSnapIntegrity.lockedEventIds(account, entries))
		assertEquals(emptyList<PendingSnap>(), PendingSnapIntegrity.valid(account, entries))
	}

	@Test
	fun `an unreadable record locks its key`() {
		val entries = mapOf("event-a" to "not json at all")
		assertEquals(setOf("event-a"), PendingSnapIntegrity.lockedEventIds(account, entries))
		assertEquals(emptyList<PendingSnap>(), PendingSnapIntegrity.valid(account, entries))
	}

	/** One bad row must not take the account's healthy rows down with it. */
	@Test
	fun `a mismatch does not lock unrelated events`() {
		val entries = mapOf(
			"event-a" to record(eventId = "event-b"),
			"event-c" to record(eventId = "event-c"),
		)
		val locked = PendingSnapIntegrity.lockedEventIds(account, entries)
		assertEquals(setOf("event-a", "event-b"), locked)
		assertEquals(listOf("event-c"), PendingSnapIntegrity.valid(account, entries).map { it.eventId })
	}
}
