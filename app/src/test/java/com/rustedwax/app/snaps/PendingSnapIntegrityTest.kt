package com.rustedwax.app.snaps

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
		kind = PendingSnapKind.ROOT,
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

	/**
	 * The harder half of the same case: the claimed event has a record of its
	 * own, and that record is impeccable.
	 *
	 * Judged alone it passes every test — its account matches, its id matches
	 * its key. It still must not be enumerable, because two entries now claim
	 * event B and nothing here can say which of them describes the comment that
	 * may already be on chain. Letting the tidy one through would hand a caller
	 * a record it cannot prove belongs to B, which is the same mistake as
	 * answering "absent".
	 */
	@Test
	fun `a contested event is invalid even when its own record is well formed`() {
		val entries = mapOf(
			"event-a" to record(eventId = "event-b"),
			"event-b" to record(eventId = "event-b"),
		)

		assertEquals(setOf("event-a", "event-b"), PendingSnapIntegrity.lockedEventIds(account, entries))
		assertEquals(
			"the well-formed record under the contested key is not valid either",
			emptyList<PendingSnap>(),
			PendingSnapIntegrity.valid(account, entries),
		)
	}

	/** Whatever is locked is never also returned as valid. */
	@Test
	fun `valid and locked never overlap`() {
		val entries = mapOf(
			"event-a" to record(eventId = "event-b"),
			"event-b" to record(eventId = "event-b"),
			"event-c" to record(eventId = "event-c"),
			"event-d" to "not json at all",
		)

		val locked = PendingSnapIntegrity.lockedEventIds(account, entries)
		val valid = PendingSnapIntegrity.valid(account, entries).map { it.eventId }.toSet()

		assertEquals("nothing may be both", emptySet<String>(), locked intersect valid)
		assertEquals(setOf("event-c"), valid)
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

	// ── expiration must fail closed ────────────────────────────────────
	//
	// The stored expiration decides two actionable things about a signed
	// transaction: whether those bytes may still be broadcast, and whether they
	// must be rebuilt instead. `JSONObject.getLong` used to answer it, and it
	// coerces — it truncates `1.9` to `1` and parses `"12"` — so a corrupt
	// record could arrive looking either long expired (and therefore
	// rebuildable) or perfectly fresh (and therefore deliverable). Neither may
	// be inferred from a number the parser could not read exactly.

	/** A record with a given raw expiration literal, otherwise well formed. */
	private fun recordJson(state: String, expiration: String): String = """
		{"account":"alice","eventId":"e1","author":"alice",
		 "permlink":"rustedwax-snap-1000-aaaaaa",
		 "parentAuthor":"peak.snaps","parentPermlink":"snap-container-1",
		 "body":"hello","jsonMetadata":"{}",
		 "signedTransactionJson":"{}","txId":"tx-1",
		 "expirationEpochSec":$expiration,
		 "state":"$state","createdAtEpochSec":1000,"updatedAtEpochSec":1000,
		 "lastError":null,"kind":"root_snap"}
	""".trimIndent()

	@Test
	fun `a prepared record with a valid positive expiration parses`() {
		val snap = PendingSnap.fromJson(recordJson("PREPARED", "2000000000"))
		assertEquals(2_000_000_000L, snap?.expirationEpochSec)
		assertEquals(PendingSnapState.PREPARED, snap?.state)
	}

	@Test
	fun `a prepared record with an unusable expiration is refused`() {
		mapOf(
			"zero" to "0",
			"negative" to "-1",
			"very negative" to "-2000000000",
			"fractional" to "1.9",
			"fractional near a real value" to "2000000000.5",
			"a numeric string" to "\"2000000000\"",
			"a nonsense string" to "\"soon\"",
			"an empty string" to "\"\"",
			"null" to "null",
			"a boolean" to "true",
			"an object" to "{}",
			"an array" to "[]",
			"beyond Long" to "99999999999999999999999999",
			"exponent notation" to "2e9",
		).forEach { (why, literal) ->
			assertNull(
				"a PREPARED record with $why expiration must not parse",
				PendingSnap.fromJson(recordJson("PREPARED", literal)),
			)
		}
	}

	/**
	 * **Every decimal token is refused, whole-looking or not.**
	 *
	 * This is the rule that cannot be softened. Android's `JSONTokener`
	 * resolves any token containing `.` to a `Double`, and a `Double` has
	 * already thrown away what would be needed to trust it:
	 * `9007199254740993.0` arrives as `9007199254740992.0`, byte-identical to
	 * the token one below it, so no test applied afterwards can tell the two
	 * apart. "It looks like a whole number" is therefore not evidence of
	 * anything.
	 *
	 * Nothing legitimate is lost. `toJson` writes this field with `put(Long)`,
	 * which emits an integer token, and an integer token is never a `Double` on
	 * either runtime — so a decimal here means the record was not written by
	 * this app.
	 */
	@Test
	fun `a decimal expiration token is refused however whole it looks`() {
		listOf(
			"9007199254740993.0",
			"9007199254740992.0",
			"2000000000.0",
			"123.0",
			"123.5",
			"0.0",
			"-0.0",
		).forEach { literal ->
			assertNull(
				"the token $literal must not parse",
				PendingSnap.fromJson(recordJson("PREPARED", literal)),
			)
		}
	}

	/**
	 * The same rule, exercised at the **type** Android actually hands over.
	 *
	 * These unit tests run against the reference `org.json`, which boxes a
	 * decimal token as `BigDecimal`; Android's `JSONTokener` always chooses
	 * `Double`. That branch is therefore unreachable through
	 * `fromJson(String)` here, and round-tripping does not help — `put(123.0)`
	 * is re-serialised as the integer token `123`. So the reader is called
	 * directly on an object holding the real thing, which is the only way to
	 * reproduce the device's representation on the JVM.
	 */
	@Test
	fun `a Double or Float expiration is refused as Android would deliver it`() {
		with(PendingSnap.Companion) {
			listOf<Number>(
				9_007_199_254_740_993.0,
				9_007_199_254_740_992.0,
				2_000_000_000.0,
				123.0,
				123.5,
				2.0e9,
				0.0,
				1.0f,
				2.0e9f,
			).forEach { value ->
				val o = JSONObject().put("expirationEpochSec", value)
				assertNull(
					"a ${value.javaClass.simpleName} ($value) must not be read as exact",
					o.exactLong("expirationEpochSec"),
				)
			}
			// And the integer types it must still accept.
			assertEquals(123L, JSONObject().put("expirationEpochSec", 123).exactLong("expirationEpochSec"))
			assertEquals(
				Long.MAX_VALUE,
				JSONObject().put("expirationEpochSec", Long.MAX_VALUE).exactLong("expirationEpochSec"),
			)
		}
	}

	/**
	 * `BigDecimal` is refused too, and this is why it must be.
	 *
	 * `scale()` cannot separate an integer token from a decimal one:
	 * `BigDecimal("9.007199254740992E15")` and
	 * `BigDecimal("9007199254740992")` both report scale zero and the same
	 * precision. A rule that accepted scale-zero decimals would therefore let
	 * an exponent-notation token through — which is exactly the value Android
	 * would have already rounded.
	 */
	@Test
	fun `a BigDecimal expiration is refused whatever its scale`() {
		with(PendingSnap.Companion) {
			listOf("9.007199254740992E15", "2E9", "123.0", "123", "0").forEach { text ->
				val o = JSONObject().put("expirationEpochSec", java.math.BigDecimal(text))
				assertNull(
					"BigDecimal($text) must not be read as exact",
					o.exactLong("expirationEpochSec"),
				)
			}
		}
	}

	/** Integer tokens still work, including the largest one that fits. */
	@Test
	fun `integer expiration tokens parse where the state permits`() {
		assertEquals(123L, PendingSnap.fromJson(recordJson("PREPARED", "123"))?.expirationEpochSec)
		assertEquals(
			Long.MAX_VALUE,
			PendingSnap.fromJson(recordJson("PREPARED", "9223372036854775807"))?.expirationEpochSec,
		)
		assertEquals(1L, PendingSnap.fromJson(recordJson("PREPARED", "1"))?.expirationEpochSec)
	}

	/** And a record this app wrote itself round-trips. */
	@Test
	fun `a record written by toJson round-trips through the strict reader`() {
		val original = PendingSnap.fromJson(recordJson("PREPARED", "2000000000"))!!
		val reparsed = PendingSnap.fromJson(original.toJson())
		assertEquals(original, reparsed)
	}

	@Test
	fun `a prepared record with no expiration field at all is refused`() {
		val withoutField = recordJson("PREPARED", "0")
			.replace("\"expirationEpochSec\":0,", "")
		assertNull(PendingSnap.fromJson(withoutField))
	}

	/** The same rule for every other state that describes a real transaction. */
	@Test
	fun `every signed-transaction state refuses a zero or fractional expiration`() {
		listOf("BROADCASTING", "ACCEPTED_UNCONFIRMED", "UNRESOLVED", "CONFIRMED", "FAILED")
			.forEach { state ->
				assertNull("$state with zero", PendingSnap.fromJson(recordJson(state, "0")))
				assertNull("$state with a fraction", PendingSnap.fromJson(recordJson(state, "1.5")))
				assertEquals(
					"$state with a real expiry still parses",
					2_000_000_000L,
					PendingSnap.fromJson(recordJson(state, "2000000000"))?.expirationEpochSec,
				)
			}
	}

	/**
	 * An intent has no transaction, so zero is the only honest value — and the
	 * only accepted one. An intent claiming a real expiry would be a record
	 * asserting bytes it does not have.
	 */
	@Test
	fun `an intent requires exactly zero`() {
		assertEquals(0L, PendingSnap.fromJson(recordJson("INTENT", "0"))?.expirationEpochSec)
		assertNull(
			"an intent may not carry a real expiry",
			PendingSnap.fromJson(recordJson("INTENT", "2000000000")),
		)
		assertNull(PendingSnap.fromJson(recordJson("INTENT", "-1")))
		assertNull(PendingSnap.fromJson(recordJson("INTENT", "0.5")))
	}

	/**
	 * And the consequence, which is the whole point: a record that will not
	 * parse is [PendingSnapRead.Corrupt], which locks the row. It cannot be
	 * delivered, cannot be rebuilt, and cannot mint a replacement — the same
	 * fail-closed treatment every other unreadable record already gets.
	 */
	@Test
	fun `an unusable expiration locks the row exactly like any corrupt record`() {
		val entries = mapOf("e1" to recordJson("PREPARED", "1.9"))

		assertEquals(setOf("e1"), PendingSnapIntegrity.lockedEventIds("alice", entries))
		assertEquals(emptyList<PendingSnap>(), PendingSnapIntegrity.valid("alice", entries))
	}

}
