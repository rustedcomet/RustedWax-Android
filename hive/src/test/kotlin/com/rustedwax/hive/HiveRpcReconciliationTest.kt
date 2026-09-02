package com.rustedwax.hive

import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetSocketAddress
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Wire-level coverage for the fail-closed transaction reconciliation request. */
class HiveRpcReconciliationTest {

	@Test
	fun `saved expiration is sent with the transaction id`() {
		StubHiveNode(status = "unknown").use { node ->
			val expiration = 1_800_000_000L
			val txId = "0123456789abcdef0123456789abcdef01234567"

			assertEquals(
				HiveRpc.TransactionEvidence.UNAVAILABLE,
				HiveRpc(listOf(node.url)).observeTransaction(txId, expiration),
			)

			val params = node.statusRequest().getJSONObject("params")
			assertEquals(txId, params.getString("transaction_id"))
			assertEquals(HiveBroadcaster.formatExpiration(expiration), params.getString("expiration"))
		}
	}

	@Test
	fun `independent expired irreversible responses establish absence over rpc`() {
		StubHiveNode(status = "expired_irreversible").use { first ->
			StubHiveNode(status = "expired_irreversible").use { second ->
				assertEquals(
					HiveRpc.TransactionEvidence.ABSENT,
					HiveRpc(listOf(first.url, second.url)).observeTransaction(
						"0123456789abcdef0123456789abcdef01234567",
						1_800_000_000L,
					),
				)
			}
		}
	}

	@Test
	fun `rpc failure mixed with authoritative absence remains unavailable`() {
		StubHiveNode(status = "expired_irreversible").use { first ->
			StubHiveNode(status = "expired_irreversible").use { second ->
				StubHiveNode(rpcFailure = true).use { failed ->
					assertEquals(
						HiveRpc.TransactionEvidence.UNAVAILABLE,
						HiveRpc(listOf(first.url, second.url, failed.url)).observeTransaction(
							"0123456789abcdef0123456789abcdef01234567",
							1_800_000_000L,
						),
					)
				}
			}
		}
	}

	private class StubHiveNode(
		private val status: String? = null,
		private val rpcFailure: Boolean = false,
	) : AutoCloseable {
		private val requests = Collections.synchronizedList(mutableListOf<JSONObject>())
		private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
			createContext("/") { exchange ->
				val request = JSONObject(exchange.requestBody.bufferedReader().use { it.readText() })
				requests += request
				val response = when (request.getString("method")) {
					"condenser_api.get_dynamic_global_properties" -> JSONObject()
						.put("jsonrpc", "2.0")
						.put("id", 1)
						.put(
							"result",
							JSONObject()
								.put("head_block_number", 1)
								.put("head_block_id", "00".repeat(20))
								.put("time", currentChainTime()),
						)

					"transaction_status_api.find_transaction" -> if (rpcFailure) {
						JSONObject()
							.put("jsonrpc", "2.0")
							.put("id", 1)
							.put("error", JSONObject().put("message", "status unavailable"))
					} else {
						JSONObject()
							.put("jsonrpc", "2.0")
							.put("id", 1)
							.put("result", JSONObject().put("status", status))
					}

					else -> error("unexpected RPC method")
				}
				val bytes = response.toString().toByteArray(Charsets.UTF_8)
				exchange.responseHeaders.add("Content-Type", "application/json")
				exchange.sendResponseHeaders(200, bytes.size.toLong())
				exchange.responseBody.use { it.write(bytes) }
			}
			start()
		}

		val url: String get() = "http://127.0.0.1:${server.address.port}"

		fun statusRequest(): JSONObject = requests.single {
			it.getString("method") == "transaction_status_api.find_transaction"
		}

		override fun close() = server.stop(0)

		private fun currentChainTime(): String {
			val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
			format.timeZone = TimeZone.getTimeZone("UTC")
			return format.format(Date())
		}
	}
}
