package com.rustedwax.app.snaps

import com.rustedwax.hive.HivePreparationResult
import com.rustedwax.hive.HiveRpc
import com.rustedwax.hive.PreparedHiveTransaction
import com.rustedwax.hive.TxSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The account binding on the **vote** path, tested against the real
 * [HiveSnapLikePort].
 *
 * The twin of [HiveSnapPortAccountBindingTest], and it exists because the vote
 * path was missing half of what the comment path has. `prepare` checked the
 * vault before signing; `broadcast` sent whatever it was handed. The
 * controller does check the signed-in account immediately before calling it —
 * but that is *session* state, and a switch does not reach the two in the same
 * instant: `MainActivity` writes the vault first and updates the Compose
 * account afterwards, so there is a window where the key on disk is already
 * Bob's while the caller still believes it is acting as Alice.
 *
 * What makes that window matter is the same fact the comment path is built
 * around: one Hive posting key may sit in more than one account's posting
 * authority. A vote that says `voter: alice`, signed with the key now stored
 * for `bob`, is not necessarily refused by the chain — where the key
 * authorizes both, it is **accepted**, and Alice has voted from Bob's session.
 * Nothing about the signature is malformed. The only thing that can refuse it
 * is a comparison of account names at the moment of transmission, which is
 * what these pin.
 *
 * No `HiveKey` is constructed here, deliberately. The port exposes a signing
 * seam and a transmission seam whose production defaults are the real key
 * read, the real signature and the real broadcast; overriding them drives the
 * genuine orchestration and the genuine guards with no private key in the test
 * sources. `loadKey` additionally throws, so any path reaching for the real key
 * fails loudly rather than passing quietly. Both guards run *before* their
 * seam, so an override cannot skip them.
 */
class HiveSnapLikePortAccountBindingTest {

	private fun vote(voter: String, weight: Int = 1000) = TxSerializer.VoteOp(
		voter = voter,
		author = "bob",
		permlink = "re-skiptvadsvidz-tlmqn4",
		weight = weight,
	)

	/** Counts what the port actually did, and forbids what it must not. */
	private class Port(
		var vault: String?,
		/** Set when the port is expected to reach the signing seam. */
		val maySign: Boolean = false,
	) {
		var signs = 0
		var keyReads = 0
		var sends = 0
		val sent = mutableListOf<PreparedHiveTransaction>()

		/** What the overridden signing seam hands back. Synthetic; no key. */
		val signed = PreparedHiveTransaction(
			signedTransactionJson = """{"operations":[["vote",{}]],"signatures":["1f.."]}""",
			txId = "0123456789abcdef0123456789abcdef01234567",
			expirationEpochSec = 2_000_000_000L,
		)
		val sendResult = HiveRpc.BroadcastResult.Success(
			signed.txId,
			"https://node",
			HiveRpc.BroadcastResult.Evidence.BLOCK,
		)

		val real = HiveSnapLikePort(
			loadKey = {
				keyReads++
				throw AssertionError("the real key must never be read in these tests")
			},
			storedAccount = { vault },
			sign = {
				signs++
				if (!maySign) throw AssertionError("signing must not be reached after a refusal")
				HivePreparationResult.Ready(signed)
			},
			send = { sends++; sent += it; sendResult },
		)
	}

	private fun rejection(result: HiveRpc.BroadcastResult): String =
		(result as HiveRpc.BroadcastResult.Rejected).message

	// ── the blocker, as the exact sequence ────────────────────────────

	/**
	 * **The regression this file was added for.**
	 *
	 * Prepare as Alice through the *real* port, keep the transaction it
	 * returned, switch the vault to Bob, then hand **that same transaction**
	 * back to the real `broadcast`, still captured as Alice. Nothing reaches
	 * the wire.
	 *
	 * This is the narrowest window on the vote path: the bytes are fixed and
	 * perfectly valid, so nothing downstream would object — the only thing
	 * wrong is whose session is sending them, and a shared posting key means
	 * the chain would not object either.
	 */
	@Test
	fun `a vault switch between a real prepare and its broadcast sends nothing`() {
		val port = Port(vault = "alice", maySign = true)

		val prepared = port.real.prepare(vote("alice"), voter = "alice")
		assertTrue("the prepare must succeed", prepared is SnapLikePrepare.Ready)
		val p = (prepared as SnapLikePrepare.Ready).transaction
		assertEquals(1, port.signs)

		// The user switches. `KeyVault` moves the username and the WIF
		// together, so the vault *is* the account — and it moves before the
		// UI's account state does, which is the whole race.
		port.vault = "bob"

		val outcome = port.real.broadcast(p, voter = "alice")

		assertEquals("ZERO transmission", 0, port.sends)
		assertEquals(emptyList<PreparedHiveTransaction>(), port.sent)
		assertTrue("got $outcome", outcome is HiveRpc.BroadcastResult.Rejected)
		assertTrue(rejection(outcome).contains("switched Hive accounts"))
	}

	/** The same sequence with the account forgotten rather than switched. */
	@Test
	fun `a vault cleared between a real prepare and its broadcast sends nothing`() {
		listOf(null, "", "   ").forEach { cleared ->
			val port = Port(vault = "alice", maySign = true)
			val p = (port.real.prepare(vote("alice"), voter = "alice") as SnapLikePrepare.Ready)
				.transaction

			port.vault = cleared
			val outcome = port.real.broadcast(p, voter = "alice")

			assertEquals("vault '$cleared': ZERO transmission", 0, port.sends)
			assertTrue("got $outcome", outcome is HiveRpc.BroadcastResult.Rejected)
			assertTrue(rejection(outcome).contains("no saved Hive account"))
		}
	}

	/**
	 * The control: identical sequence, account untouched, and **the same
	 * object** goes out exactly once.
	 *
	 * Paired with the two above this is as close as a test gets to "removing
	 * the guard would fail": the runs differ in nothing but the vault, and the
	 * only code between `broadcast`'s entry and the transmission seam is that
	 * guard.
	 */
	@Test
	fun `an unchanged account transmits the very transaction that was prepared`() {
		val port = Port(vault = "alice", maySign = true)
		val p = (port.real.prepare(vote("alice"), voter = "alice") as SnapLikePrepare.Ready)
			.transaction

		val outcome = port.real.broadcast(p, voter = "alice")

		assertEquals("exactly one transmission", 1, port.sends)
		assertSame("and it is the prepared transaction itself", p, port.sent.single())
		assertTrue(outcome is HiveRpc.BroadcastResult.Success)
	}

	/** Hive account names are lowercase; casing must not refuse a real vote. */
	@Test
	fun `a case-only difference is still the same account, and transmits`() {
		val port = Port(vault = "ALICE", maySign = true)
		val p = (port.real.prepare(vote("Alice"), voter = "alice") as SnapLikePrepare.Ready)
			.transaction

		port.real.broadcast(p, voter = "alice")

		assertEquals(1, port.sends)
		assertSame(p, port.sent.single())
	}

	/**
	 * The bypass the whole binding exists for, on the transmission side.
	 *
	 * Suppose the key on disk authorizes **both** Alice and Bob. A signature it
	 * produced over Alice's vote would be accepted by the chain even though the
	 * user is now Bob — so a guard that only asked "can this key sign?" would
	 * wave it through. The port refuses on the account name and never consults
	 * the key at all, which makes what the key can authorize irrelevant.
	 */
	@Test
	fun `a key that could authorize both accounts cannot bypass the name check`() {
		val port = Port(vault = "alice", maySign = true)
		val p = (port.real.prepare(vote("alice"), voter = "alice") as SnapLikePrepare.Ready)
			.transaction
		port.vault = "bob"

		port.real.broadcast(p, voter = "alice")

		assertEquals("whatever the key authorizes is never asked", 0, port.sends)
		assertEquals("and the key itself is never read", 0, port.keyReads)
	}

	/** A refusal rebuilds nothing: no re-sign, no second attempt, no retry. */
	@Test
	fun `a refused broadcast never re-signs or re-sends`() {
		val port = Port(vault = "alice", maySign = true)
		val p = (port.real.prepare(vote("alice"), voter = "alice") as SnapLikePrepare.Ready)
			.transaction
		val signsAfterPrepare = port.signs
		port.vault = "bob"

		repeat(3) { port.real.broadcast(p, voter = "alice") }

		assertEquals("nothing was signed again", signsAfterPrepare, port.signs)
		assertEquals("and nothing was sent, however many times it was asked", 0, port.sends)
	}

	// ── the signing boundary still refuses too ────────────────────────

	@Test
	fun `signing still refuses a switched or missing vault`() {
		val switched = Port(vault = "bob")
		val missing = Port(vault = null)

		val a = switched.real.prepare(vote("alice"), voter = "alice")
		val b = missing.real.prepare(vote("alice"), voter = "alice")

		assertTrue((a as SnapLikePrepare.Refused).message.contains("switched Hive accounts"))
		assertTrue((b as SnapLikePrepare.Refused).message.contains("no saved Hive account"))
		assertEquals("signing must never be reached", 0, switched.signs)
		assertEquals(0, missing.signs)
	}

	@Test
	fun `an operation naming a different voter than the caller captured is refused`() {
		val port = Port(vault = "alice")

		val refused = port.real.prepare(vote("carol"), voter = "alice")

		assertEquals(0, port.signs)
		assertTrue((refused as SnapLikePrepare.Refused).message.contains("different account"))
	}
}
