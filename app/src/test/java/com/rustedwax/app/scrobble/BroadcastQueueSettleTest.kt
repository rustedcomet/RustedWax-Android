package com.rustedwax.app.scrobble

import com.rustedwax.hive.PreparedHiveTransaction
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Real-file proof for the queued-send write-ahead state machine. */
class BroadcastQueueSettleTest {

	@get:Rule
	val folder = TemporaryFolder()

	private fun queueFile(): File = folder.root.resolve("broadcast-queue.json")
	private fun queue(): BroadcastQueue = BroadcastQueue(queueFile())

	private fun prepared(number: Int = 1) = PreparedHiveTransaction(
		signedTransactionJson = """{"signed":"transaction-$number"}""",
		txId = "transaction-$number",
		expirationEpochSec = 4_000_000_000L,
	)

	private fun BroadcastQueue.addOne(
		json: String = """{"title":"a listen","timestamp":"2026-09-01T21:16:00.000Z"}""",
		label: String = "HDCYT — a listen",
	): BroadcastQueue.Entry = requireNotNull(
		enqueue("rustedwax-test", json, label, 96, "_OBlgSz8sSM"),
	)

	private fun breakWrites(file: File) {
		assertTrue(File(file.parentFile, "${file.name}.tmp").mkdirs())
	}

	@Test
	fun `a new queued operation has a stable random identity across restart`() {
		val entry = queue().addOne()
		assertTrue(entry.operationId.isNotBlank())

		val reopened = queue().all().single()
		assertEquals(entry.operationId, reopened.operationId)
		assertEquals(BroadcastQueue.State.QUEUED, reopened.state)
	}

	@Test
	fun `distinct operations with identical payloads never share an identity`() {
		val first = queue().addOne()
		val second = queue().addOne()

		assertNotEquals(first.operationId, second.operationId)
	}

	@Test
	fun `stable operation identity isolates colliding legacy numeric ids`() {
		val queue = queue()
		val first = queue.addOne(label = "a")
		val second = queue.addOne(label = "b")
		val collidingReference = second.copy(id = first.id)

		assertEquals(
			BroadcastQueue.PrepareOutcome.STORED,
			queue.markInFlight(collidingReference, prepared()),
		)
		val reopened = queue.all()
		assertEquals(BroadcastQueue.State.QUEUED, reopened.single { it.operationId == first.operationId }.state)
		assertEquals(BroadcastQueue.State.IN_FLIGHT, reopened.single { it.operationId == second.operationId }.state)
	}

	@Test
	fun `exact prepared transaction is durable before broadcast`() {
		val queue = queue()
		val entry = queue.addOne()
		val prepared = prepared()

		assertEquals(
			BroadcastQueue.PrepareOutcome.STORED,
			queue.markInFlight(entry.id, prepared),
		)

		val reopened = queue().all().single()
		assertEquals(BroadcastQueue.State.IN_FLIGHT, reopened.state)
		assertEquals(prepared, reopened.preparedTransaction())
	}

	@Test
	fun `pre-send persistence failure leaves the operation unsent and queued`() {
		val queue = queue()
		val entry = queue.addOne()
		breakWrites(queueFile())

		assertEquals(
			BroadcastQueue.PrepareOutcome.STORAGE_ERROR,
			queue.markInFlight(entry.id, prepared()),
		)

		val unchanged = queue.all().single()
		assertEquals(BroadcastQueue.State.QUEUED, unchanged.state)
		assertEquals(null, unchanged.preparedTransaction())
	}

	@Test
	fun `settling an accepted prepared transaction retires it`() {
		val queue = queue()
		val entry = queue.addOne()
		assertEquals(BroadcastQueue.PrepareOutcome.STORED, queue.markInFlight(entry.id, prepared()))

		assertEquals(BroadcastQueue.SettleOutcome.RETIRED, queue.settle(queue.all().single()))
		assertEquals(emptyList<BroadcastQueue.Entry>(), queue.all())
		assertEquals(0, queue.size())
	}

	@Test
	fun `post-send settlement failure retains exact in-flight state after restart`() {
		val queue = queue()
		val entry = queue.addOne()
		val prepared = prepared()
		assertEquals(BroadcastQueue.PrepareOutcome.STORED, queue.markInFlight(entry.id, prepared))
		breakWrites(queueFile())

		assertEquals(
			BroadcastQueue.SettleOutcome.IN_FLIGHT_RETAINED,
			queue.settle(queue.all().single()),
		)

		val reopened = queue().all().single()
		assertEquals(BroadcastQueue.State.IN_FLIGHT, reopened.state)
		assertEquals(prepared, reopened.preparedTransaction())
		assertEquals(listOf(reopened), queue().due(nowMs = Long.MAX_VALUE))
	}

	@Test
	fun `live in-flight state never expires with wall clock age`() {
		val queue = queue()
		val entry = queue.addOne()
		val prepared = prepared()
		assertEquals(BroadcastQueue.PrepareOutcome.STORED, queue.markInFlight(entry.id, prepared))

		val reopened = queue()
		val afterArbitraryAge = reopened.due(nowMs = Long.MAX_VALUE).single()
		assertEquals(BroadcastQueue.State.IN_FLIGHT, afterArbitraryAge.state)
		assertEquals(prepared, afterArbitraryAge.preparedTransaction())
	}

	@Test
	fun `more than sixty four live operations are never pruned`() {
		val queue = queue()
		repeat(70) { index ->
			val entry = queue.addOne(label = "listen-$index")
			assertEquals(
				BroadcastQueue.PrepareOutcome.STORED,
				queue.markInFlight(entry.id, prepared(index)),
			)
		}

		val reopened = queue().all()
		assertEquals(70, reopened.size)
		assertTrue(reopened.all { it.state == BroadcastQueue.State.IN_FLIGHT })
		assertEquals(70, reopened.map { it.operationId }.distinct().size)
	}

	@Test
	fun `corrupt durable state fails closed and is not overwritten`() {
		val file = queueFile()
		file.writeText("not-json")
		val original = file.readText()
		val queue = queue()

		assertEquals(emptyList<BroadcastQueue.Entry>(), queue.due(nowMs = Long.MAX_VALUE))
		assertEquals(0, queue.size())
		assertFalse(queue.add("user", "{}", "label", null, null))
		assertEquals(original, file.readText())
	}

	@Test
	fun `incomplete in-flight durable state fails closed`() {
		queueFile().writeText(
			"""[{"id":1,"operationId":"operation-1","username":"user","json":"{}",""" +
				""""label":"listen","attempts":0,"nextAttemptAtMs":0,"state":"IN_FLIGHT"}]""",
		)

		assertEquals(emptyList<BroadcastQueue.Entry>(), queue().due(nowMs = Long.MAX_VALUE))
		assertEquals(0, queue().size())
	}

	@Test
	fun `altered prepared transaction bytes fail closed`() {
		val queue = queue()
		val entry = queue.addOne()
		assertEquals(BroadcastQueue.PrepareOutcome.STORED, queue.markInFlight(entry.id, prepared()))

		val durable = JSONArray(queueFile().readText())
		durable.getJSONObject(0).put("preparedTransactionJson", "{\"signed\":\"altered\"}")
		queueFile().writeText(durable.toString())
		val corrupt = queueFile().readText()

		assertEquals(emptyList<BroadcastQueue.Entry>(), queue().due(nowMs = Long.MAX_VALUE))
		assertEquals(0, queue().size())
		assertFalse(queue().add("user", "{}", "label", null, null))
		assertEquals(corrupt, queueFile().readText())
	}

	@Test
	fun `altered prepared transaction id fails closed`() {
		val queue = queue()
		val entry = queue.addOne()
		assertEquals(BroadcastQueue.PrepareOutcome.STORED, queue.markInFlight(entry.id, prepared()))

		val durable = JSONArray(queueFile().readText())
		durable.getJSONObject(0).put("preparedTransactionId", "different-transaction")
		queueFile().writeText(durable.toString())

		assertEquals(emptyList<BroadcastQueue.Entry>(), queue().due(nowMs = Long.MAX_VALUE))
		assertEquals(0, queue().size())
	}

	@Test
	fun `altered durable state enum fails closed`() {
		val queue = queue()
		val entry = queue.addOne()
		assertEquals(BroadcastQueue.PrepareOutcome.STORED, queue.markInFlight(entry, prepared()))

		val durable = JSONArray(queueFile().readText())
		durable.getJSONObject(0).put("state", "QUEUED")
		queueFile().writeText(durable.toString())

		assertEquals(emptyList<BroadcastQueue.Entry>(), queue().due(nowMs = Long.MAX_VALUE))
		assertEquals(0, queue().size())
	}

	@Test
	fun `legacy queued row is upgraded atomically before network`() {
		queueFile().writeText(
			"""[{"id":1,"username":"user","json":"{}","label":"listen",""" +
				""""attempts":0,"nextAttemptAtMs":0,"lastError":""}]""",
		)
		val queue = queue()
		val legacy = queue.all().single()
		assertEquals("", legacy.operationId)

		assertEquals(BroadcastQueue.PrepareOutcome.STORED, queue.markInFlight(legacy.id, prepared()))
		val upgraded = queue().all().single()
		assertTrue(upgraded.operationId.isNotBlank())
		assertNotNull(upgraded.preparedTransaction())
	}
}
