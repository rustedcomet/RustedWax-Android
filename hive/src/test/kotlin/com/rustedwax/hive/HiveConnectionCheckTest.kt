package com.rustedwax.hive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The developer-mode connection check.
 *
 * "It isn't scrobbling" has four separable causes — no reachable node, no such
 * account, a key that is not on the account's posting authority, and a key that
 * cannot be loaded to sign with at all — and until now the only way to tell them
 * apart was to broadcast a real test entry onto a ledger that cannot be edited.
 * This asks the same questions and writes nothing.
 *
 * The two network calls are injected, so every branch below is decided here
 * rather than against a live chain.
 */
class HiveConnectionCheckTest {

	private val props = HiveRpc.GlobalProperties(
		headBlockNumber = 109_328_730,
		headBlockId = "0684dd5a",
		timeEpochSec = 1_800_000_000,
	)

	private fun check(
		headBlock: () -> HiveRpc.GlobalProperties = { props },
		postingKeys: (String) -> List<String> = { listOf(PUBLIC_KEY) },
	) = HiveConnectionCheck(headBlock, postingKeys)

	@Test
	fun `a healthy account passes every step`() {
		val report = check().run(username = "someone", derivedPublicKey = PUBLIC_KEY)

		assertTrue(report.summary, report.ok)
		assertEquals(
			listOf("Hive node", "Account", "Posting key", "Signing"),
			report.steps.map { it.name },
		)
		assertTrue(report.steps.all { it.ok })
		assertTrue(report.steps[0].detail.contains("109328730") ||
			report.steps[0].detail.contains("109,328,730"))
	}

	@Test
	fun `an unreachable node fails the first step and is named`() {
		val report = check(headBlock = { throw HiveRpc.RpcException("no node had a current head block") })
			.run(username = "someone", derivedPublicKey = PUBLIC_KEY)

		assertFalse(report.ok)
		assertFalse(report.steps[0].ok)
		assertTrue(report.steps[0].detail.contains("no node had a current head block"))
	}

	/** Account lookup is a separate call, so a dead DGP node must not mask it. */
	@Test
	fun `a node failure does not silently fail the account lookup`() {
		val report = check(headBlock = { throw HiveRpc.RpcException("down") })
			.run(username = "someone", derivedPublicKey = PUBLIC_KEY)

		assertTrue("the account lookup was not attempted", report.steps[1].ok)
	}

	@Test
	fun `an account with no posting authority fails the lookup`() {
		val report = check(postingKeys = { emptyList() })
			.run(username = "nobody", derivedPublicKey = PUBLIC_KEY)

		assertFalse(report.ok)
		assertFalse(report.steps[1].ok)
		assertTrue(report.steps[1].detail.contains("@nobody"))
	}

	@Test
	fun `a lookup that throws is a failure rather than an exception`() {
		val report = check(postingKeys = { throw HiveRpc.RpcException("boom") })
			.run(username = "someone", derivedPublicKey = PUBLIC_KEY)

		assertFalse(report.steps[1].ok)
		assertTrue(report.steps[1].detail.contains("boom"))
	}

	@Test
	fun `a key that is not on the posting authority fails, and signing fails with it`() {
		val report = check(postingKeys = { listOf(OTHER_KEY) })
			.run(username = "someone", derivedPublicKey = PUBLIC_KEY)

		assertFalse(report.ok)
		assertTrue("the account itself exists", report.steps[1].ok)
		assertFalse(report.steps[2].ok)
		assertFalse("signing cannot pass on a key the chain rejects", report.steps[3].ok)
	}

	@Test
	fun `with no key saved the account and key steps both say so`() {
		val report = check().run(username = null, derivedPublicKey = null)

		assertFalse(report.ok)
		assertFalse(report.steps[1].ok)
		assertFalse(report.steps[2].ok)
		assertFalse(report.steps[3].ok)
	}

	/** Nothing here may print, log or return the private key. */
	@Test
	fun `the report never carries anything secret`() {
		val report = check().run(username = "someone", derivedPublicKey = PUBLIC_KEY)
		val text = report.steps.joinToString(" ") { "${it.name} ${it.detail}" } + report.summary
		assertFalse(text.contains("5J"))
		assertFalse(text.contains("5K"))
		assertFalse(text.contains("wif", ignoreCase = true))
		assertFalse(text.contains("private", ignoreCase = true))
	}

	@Test
	fun `the summary counts the failures`() {
		assertEquals(
			"All 4 checks passed.",
			check().run("someone", PUBLIC_KEY).summary,
		)
		assertEquals(
			"1 of 4 checks failed.",
			check(headBlock = { throw HiveRpc.RpcException("down") })
				.run("someone", PUBLIC_KEY).summary,
		)
	}

	private companion object {
		const val PUBLIC_KEY = "STM5jZtLoazuLDoy5VbHKKqLnA6D4Pu4rqSJnJUgP5N9YbYzWNAdQ"
		const val OTHER_KEY = "STM7CFhTGB5Y7pTGRZ5FvLZgLXVJ8fJvJdBQXBTPjHkuqSHKKJqbw"
	}
}
