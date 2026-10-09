package com.rustedwax.app.snaps

import com.rustedwax.hive.HiveKey
import com.rustedwax.hive.HivePreparationResult
import com.rustedwax.hive.HiveRpc
import com.rustedwax.hive.PreparedHiveTransaction
import com.rustedwax.hive.TxSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The account binding, tested against the **real** [HiveSnapPort].
 *
 * These exist because the controller-level tests cannot prove this. There the
 * port is a fake, and a fake that re-implements the check proves only that it
 * agrees with itself — the one arrangement guaranteed to keep passing if the
 * production guard is deleted. So this file constructs the production class
 * and drives it directly.
 *
 * The hole being guarded is specific and not obvious. One Hive posting key may
 * sit in more than one account's posting authority, so a comment that says
 * `author: alice`, signed with the key on disk after the user has switched to
 * `bob`, is **not** necessarily rejected by the chain: where the same key
 * authorizes both, Hive accepts it and Alice has published from Bob's session.
 * Nothing about the signature is malformed and nothing about the transaction is
 * invalid. The only thing that can refuse it is a comparison of account names,
 * which is what [HiveSnapPort] does and what these pin.
 *
 * No `HiveKey` is constructed anywhere here, and that is deliberate rather than
 * a shortcut. The port exposes two seams — signing and transmission — whose
 * production defaults are the real key read, the real signature and the real
 * broadcast; overriding them lets these tests drive the genuine orchestration
 * and the genuine account guards without a private key existing in the test
 * sources. `loadKey` additionally throws, so any path that reached for the real
 * key would fail loudly rather than pass quietly.
 *
 * The guards run *before* either seam, so an override cannot skip them.
 */
class HiveSnapPortAccountBindingTest {

	private fun comment(author: String) = TxSerializer.CommentOp(
		parentAuthor = "peak.snaps",
		parentPermlink = "snap-container-1789648560",
		author = author,
		permlink = "rustedwax-snap-1000-aaaaaa",
		title = "",
		body = "hello",
		jsonMetadata = """{"app":"rustedwax/test"}""",
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
			signedTransactionJson = """{"operations":[["comment",{}]],"signatures":["1f.."]}""",
			txId = "0123456789abcdef0123456789abcdef01234567",
			expirationEpochSec = 2_000_000_000L,
		)
		var sendResult: HiveRpc.BroadcastResult = HiveRpc.BroadcastResult.Success(
			signed.txId,
			"https://node",
			HiveRpc.BroadcastResult.Evidence.BLOCK,
		)

		val real = HiveSnapPort(
			loadKey = {
				keyReads++
				throw AssertionError("the real key must never be read in these tests")
			},
			storedAccount = { vault },
			// Stands in for "read the key and sign". The account guards run
			// before it, so overriding it cannot skip them — it only removes
			// the need for a private key in the test sources.
			sign = {
				signs++
				if (!maySign) throw AssertionError("signing must not be reached after a refusal")
				HivePreparationResult.Ready(signed)
			},
			send = { sends++; sent += it; sendResult },
			signDelete = {
				signs++
				if (!maySign) throw AssertionError("signing must not be reached after a refusal")
				HivePreparationResult.Ready(signed)
			},
		)
	}

	private fun rejection(result: HivePreparationResult): String {
		val failed = result as HivePreparationResult.Failed
		return (failed.result as HiveRpc.BroadcastResult.Rejected).message
	}

	// ── prepareComment: three identities must agree ────────────────────

	/**
	 * Agreement lets it through to signing.
	 *
	 * Reaching the seam *is* the assertion: it sits past the account gate, so
	 * arriving there proves the gate allowed this pairing — and the transaction
	 * that comes back is the one the caller will later try to broadcast.
	 */
	@Test
	fun `matching operation, captured and vault accounts reach signing`() {
		val port = Port(vault = "alice", maySign = true)

		val result = port.real.prepareComment(comment("alice"), author = "alice")

		assertEquals("the account gate passed", 1, port.signs)
		assertTrue(result is HivePreparationResult.Ready)
		assertEquals(port.signed, (result as HivePreparationResult.Ready).transaction)
	}

	@Test
	fun `an operation naming a different author than the caller captured is refused`() {
		val port = Port(vault = "alice")

		val why = rejection(port.real.prepareComment(comment("carol"), author = "alice"))

		assertEquals("signing must never be reached", 0, port.signs)
		assertTrue("got: $why", why.contains("different account"))
	}

	@Test
	fun `a captured author the vault no longer holds is refused`() {
		val port = Port(vault = "bob")

		val why = rejection(port.real.prepareComment(comment("alice"), author = "alice"))

		assertEquals("signing must never be reached", 0, port.signs)
		assertTrue("got: $why", why.contains("switched Hive accounts"))
	}

	@Test
	fun `no saved account at all is refused`() {
		listOf(null, "", "   ").forEach { stored ->
			val port = Port(vault = stored)
			val why = rejection(port.real.prepareComment(comment("alice"), author = "alice"))
			assertEquals("vault '$stored': signing must never be reached", 0, port.signs)
			assertTrue("got: $why", why.contains("no saved Hive account"))
		}
	}

	/** Hive account names are lowercase; casing must not decide identity. */
	@Test
	fun `the three identities are compared without regard to case`() {
		val port = Port(vault = "Alice", maySign = true)

		port.real.prepareComment(comment("ALICE"), author = "alice")

		assertEquals("casing alone must not refuse", 1, port.signs)
	}

	/**
	 * The bypass this whole binding exists for.
	 *
	 * Suppose the key on disk authorizes **both** Alice and Bob. A signature it
	 * produced over Alice's comment would be accepted by the chain even though
	 * the user is now Bob — so a guard that only asked "can this key sign?"
	 * would wave it through. This asserts the port refuses on the account name
	 * and never consults the key at all, which makes what the key can authorize
	 * irrelevant.
	 */
	@Test
	fun `a key that could authorize both accounts cannot bypass the name check`() {
		val port = Port(vault = "bob")

		val why = rejection(port.real.prepareComment(comment("alice"), author = "alice"))

		assertEquals(
			"whatever the key authorizes is never asked",
			0,
			port.signs,
		)
		assertEquals("and the key itself is never read", 0, port.keyReads)
		assertTrue(why.contains("switched Hive accounts"))
	}

	// ── broadcastPrepared: the last check before the wire ──────────────

	/**
	 * **The critical regression test, as the exact sequence.**
	 *
	 * Prepare successfully as Alice through the *real* [HiveSnapPort], keep the
	 * transaction it returned, switch the vault to Bob, then hand **that same
	 * transaction** back to the real `broadcastPrepared`. Nothing reaches the
	 * wire.
	 *
	 * This is the narrowest window in the whole path. The bytes are already
	 * fixed and perfectly valid, so nothing downstream would object — the only
	 * thing wrong is whose session is sending them, and one Hive posting key
	 * can authorize both accounts, so the chain would not object either.
	 */
	@Test
	fun `a vault switch between a real prepare and its broadcast sends nothing`() {
		val port = Port(vault = "alice", maySign = true)

		// B/C — the real port prepares, and returns P.
		val result = port.real.prepareComment(comment("alice"), author = "alice")
		assertTrue("the prepare must succeed", result is HivePreparationResult.Ready)
		val p = (result as HivePreparationResult.Ready).transaction
		assertEquals(1, port.signs)

		// D — the user switches. `KeyVault` moves the username and the WIF
		// together, so the vault is the account.
		port.vault = "bob"

		// E — the same object, not a fresh fixture.
		val outcome = port.real.broadcastPrepared(p, author = "alice")

		// F
		assertEquals("ZERO transmission", 0, port.sends)
		assertEquals(emptyList<PreparedHiveTransaction>(), port.sent)
		assertTrue("got $outcome", outcome is HiveRpc.BroadcastResult.Rejected)
		assertTrue(
			(outcome as HiveRpc.BroadcastResult.Rejected).message.contains("switched Hive accounts"),
		)
	}

	/** The same sequence with the vault cleared rather than switched. */
	@Test
	fun `a vault cleared between a real prepare and its broadcast sends nothing`() {
		val port = Port(vault = "alice", maySign = true)
		val p = (port.real.prepareComment(comment("alice"), author = "alice")
			as HivePreparationResult.Ready).transaction

		port.vault = null
		val outcome = port.real.broadcastPrepared(p, author = "alice")

		assertEquals(0, port.sends)
		assertTrue(outcome is HiveRpc.BroadcastResult.Rejected)
	}

	/**
	 * The control: identical sequence, account untouched, and **the same
	 * object** goes out exactly once.
	 *
	 * Paired with the test above this is as close as a test can get to
	 * "removing the final guard would fail": the two runs differ in nothing but
	 * the vault, and the only code between `broadcastPrepared`'s entry and the
	 * transmission seam is that guard.
	 */
	@Test
	fun `an unchanged account transmits the very transaction that was prepared`() {
		val port = Port(vault = "alice", maySign = true)
		val p = (port.real.prepareComment(comment("alice"), author = "alice")
			as HivePreparationResult.Ready).transaction

		val outcome = port.real.broadcastPrepared(p, author = "alice")

		assertEquals("exactly one transmission", 1, port.sends)
		assertSame("and it is the prepared transaction itself", p, port.sent.single())
		assertTrue(outcome is HiveRpc.BroadcastResult.Success)
	}

	@Test
	fun `a case-only difference is still the same account, and transmits`() {
		val port = Port(vault = "ALICE", maySign = true)
		val p = (port.real.prepareComment(comment("Alice"), author = "alice")
			as HivePreparationResult.Ready).transaction

		port.real.broadcastPrepared(p, author = "alice")

		assertEquals(1, port.sends)
		assertSame(p, port.sent.single())
	}

	/**
	 * The seam is the only way out: every refusal leaves it untouched, so a
	 * refusal really is silence on the wire rather than a different reply.
	 */
	@Test
	fun `every refusal leaves the transmission seam untouched`() {
		listOf("switched" to "bob", "cleared" to null).forEach { (why, vault) ->
			val port = Port(vault = "alice", maySign = true)
			val p = (port.real.prepareComment(comment("alice"), author = "alice")
				as HivePreparationResult.Ready).transaction
			port.vault = vault

			port.real.broadcastPrepared(p, author = "alice")

			assertEquals("$why must not transmit", 0, port.sends)
			assertEquals(emptyList<PreparedHiveTransaction>(), port.sent)
		}
	}

	// ── prepareDelete: the same three identities ──────────────────────

	@Test
	fun `a delete for the vault's own account reaches signing`() {
		val port = Port(vault = "alice", maySign = true)
		val result = port.real.prepareDelete(TxSerializer.DeleteCommentOp("alice", "re-x"), author = "alice")
		assertEquals(1, port.signs)
		assertTrue(result is HivePreparationResult.Ready)
	}

	@Test
	fun `a delete naming another author, or after an account switch, is refused before signing`() {
		listOf(
			Triple("alice", "bob", "alice"),
			Triple("alice", "alice", "bob"),
			Triple(null, "alice", "alice"),
		).forEach { (vault, opAuthor, captured) ->
			val port = Port(vault = vault)
			val result = port.real.prepareDelete(TxSerializer.DeleteCommentOp(opAuthor, "re-x"), author = captured)
			assertTrue(result is HivePreparationResult.Failed)
			assertEquals(0, port.signs)
			assertEquals(0, port.keyReads)
		}
	}

	// ── sendPrepared: never-sent is proven only by this port's own guard ─

	private fun preparedDelete(port: Port): PreparedHiveTransaction =
		(port.real.prepareDelete(TxSerializer.DeleteCommentOp("alice", "re-x"), author = "alice")
			as HivePreparationResult.Ready).transaction

	@Test
	fun `an account switch or sign-out before sending is NotSent, with zero transmissions`() {
		listOf("switched" to "bob", "cleared" to null).forEach { (why, vault) ->
			val port = Port(vault = "alice", maySign = true)
			val p = preparedDelete(port)
			port.vault = vault

			val outcome = port.real.sendPrepared(p, author = "alice")

			assertEquals("$why must not transmit", 0, port.sends)
			assertTrue("$why got $outcome", outcome is SnapSend.NotSent)
			assertTrue((outcome as SnapSend.NotSent).reason.contains("switched Hive accounts"))
		}
	}

	@Test
	fun `an unchanged account is Sent, carrying exactly the node's answer`() {
		val port = Port(vault = "alice", maySign = true)
		val p = preparedDelete(port)

		val outcome = port.real.sendPrepared(p, author = "alice")

		assertEquals(1, port.sends)
		assertSame(p, port.sent.single())
		assertEquals(SnapSend.Sent(port.sendResult), outcome)
	}

	/** A node's refusal happens after the boundary, so it is never "not sent". */
	@Test
	fun `a node's rejection after transmission is Sent, not NotSent`() {
		val port = Port(vault = "alice", maySign = true)
		port.sendResult = HiveRpc.BroadcastResult.Rejected("Duplicate transaction check failed")
		val p = preparedDelete(port)

		val outcome = port.real.sendPrepared(p, author = "alice")

		assertEquals(1, port.sends)
		assertEquals(SnapSend.Sent(port.sendResult), outcome)
	}

	@Test
	fun `broadcastPrepared answers exactly as before for both refusal and transmission`() {
		val refused = Port(vault = "alice", maySign = true)
		val p = preparedDelete(refused)
		refused.vault = "bob"
		assertEquals(
			HiveRpc.BroadcastResult.Rejected("You've switched Hive accounts — this Snap belonged to a different one."),
			refused.real.broadcastPrepared(p, author = "alice"),
		)

		val sent = Port(vault = "alice", maySign = true)
		assertEquals(sent.sendResult, sent.real.broadcastPrepared(preparedDelete(sent), author = "alice"))
	}

	/** Fakes that only know how to broadcast can never claim never-sent. */
	@Test
	fun `the interface default treats every answer as possibly sent`() {
		val rejection = HiveRpc.BroadcastResult.Rejected("anything at all")
		val fake = object : SnapHivePort {
			override fun resolveContainer() = error("unused")
			override fun prepareComment(operation: TxSerializer.CommentOp, author: String) = error("unused")
			override fun broadcastPrepared(prepared: PreparedHiveTransaction, author: String) = rejection
			override fun observeTransaction(txId: String, expirationEpochSec: Long) = error("unused")
			override fun contentExists(author: String, permlink: String) = error("unused")
		}
		val p = PreparedHiveTransaction("{}", "tx", 1L)
		assertEquals(SnapSend.Sent(rejection), fake.sendPrepared(p, "alice"))
	}
}
