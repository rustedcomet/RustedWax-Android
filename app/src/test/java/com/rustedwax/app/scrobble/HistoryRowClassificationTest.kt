package com.rustedwax.app.scrobble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #9 B2 — what the History header and card say about each row.
 *
 * The header reads "Confirmed on Hive" only when no row is pending or failed,
 * and each card is green, amber or red. An accepted send whose confirmation was
 * unavailable used to fall through to green and to "Confirmed on Hive" although
 * nobody could say it was on chain. The rules below are MainScreen's, and the
 * last test pins that MainScreen still uses exactly these expressions.
 */
class HistoryRowClassificationTest {

	private enum class Card { CONFIRMED, PENDING, ERROR }

	private fun row(status: String, queued: Boolean = false, txId: String? = null) =
		FinalizationRuntime.ScrobbleRecord(
			title = "Song",
			artist = "Artist",
			percentPlayed = 100,
			atEpochSec = 1_790_000_000,
			status = status,
			txId = txId,
			queued = queued,
			videoId = "_OBlgSz8sSM",
			eventId = "event-$status",
			account = "listener",
		)

	/** MainScreen's header rule: rows that keep the header from saying "Confirmed on Hive". */
	private fun notConfirmed(r: FinalizationRuntime.ScrobbleRecord) =
		r.queued || r.status.startsWith("rejected") || r.queueFailed || r.acceptedUnconfirmed

	/** MainScreen's card colour rule, in its branch order. */
	private fun card(r: FinalizationRuntime.ScrobbleRecord) = when {
		r.status.startsWith("rejected") || r.queueFailed -> Card.ERROR
		r.queued || r.acceptedUnconfirmed -> Card.PENDING
		else -> Card.CONFIRMED
	}

	private val accepted = FinalizationRuntime.ScrobbleRecord.ACCEPTED_UNCONFIRMED

	@Test
	fun `accepted-unconfirmed is never presented or counted as confirmed`() {
		// Both statuses the engine writes: from the queue, and on a first attempt.
		listOf(row(accepted, txId = "tx"), row("$accepted; not retried", txId = "tx")).forEach {
			assertTrue(it.status, it.acceptedUnconfirmed)
			assertTrue("counted as confirmed: ${it.status}", notConfirmed(it))
			assertEquals("shown as confirmed: ${it.status}", Card.PENDING, card(it))
		}
		// With a settlement suffix too.
		assertTrue(row("$accepted — settled cleanup remains pending").acceptedUnconfirmed)
	}

	@Test
	fun `sent and confirmed rows remain confirmed`() {
		listOf(
			"sent from queue",
			"confirmed in block",
			"seen relaying in mempool",
			"reconciled after ambiguous retry",
		).forEach {
			val r = row(it, txId = "tx")
			assertFalse(it, r.acceptedUnconfirmed)
			assertFalse("not counted as confirmed: $it", notConfirmed(r))
			assertEquals(it, Card.CONFIRMED, card(r))
		}
	}

	@Test
	fun `queued rows remain pending`() {
		listOf("queued — offline", "waiting to retry — node busy").forEach {
			val r = row(it, queued = true)
			assertTrue(notConfirmed(r))
			assertEquals(it, Card.PENDING, card(r))
		}
	}

	@Test
	fun `rejected and permanent queue failures remain not on chain and shown as errors`() {
		listOf(
			"rejected: missing posting authority",
			"${FinalizationRuntime.ScrobbleRecord.QUEUE_FAILED_PERMANENTLY}: missing posting authority",
			"${FinalizationRuntime.ScrobbleRecord.QUEUE_ATTEMPTS_EXHAUSTED}: Unable to resolve host",
		).forEach {
			val r = row(it)
			assertFalse(r.acceptedUnconfirmed)
			assertTrue("counted as confirmed: $it", notConfirmed(r))
			assertEquals(it, Card.ERROR, card(r))
		}
	}

	@Test
	fun `MainScreen applies exactly these rules to the header and the card`() {
		val screen = java.io.File("src/main/java/com/rustedwax/app/ui/MainScreen.kt").readText()
		val header = screen.substringAfter("val unsettled = recent.count {").substringBefore("}")
		listOf("it.queued", "it.status.startsWith(\"rejected\")", "it.queueFailed", "it.acceptedUnconfirmed")
			.forEach { assertTrue("header does not count `$it`", it in header) }
		val colour = screen.substringAfter("\"\${r.percentPlayed}% · \${r.status}\"")
			.substringBefore("else -> if (dark) Wax.SuccessGreenLight else Wax.SuccessGreen")
		assertTrue(
			"failures are not shown as errors",
			Regex("""r\.status\.startsWith\("rejected"\) \|\| r\.queueFailed ->\s+MaterialTheme\.colorScheme\.error""")
				.containsMatchIn(colour),
		)
		assertTrue(
			"accepted-unconfirmed is not shown as pending",
			Regex("""r\.queued \|\| r\.acceptedUnconfirmed ->\s+if \(dark\) Wax\.AmberLight else Wax\.Amber""")
				.containsMatchIn(colour),
		)
	}
}
