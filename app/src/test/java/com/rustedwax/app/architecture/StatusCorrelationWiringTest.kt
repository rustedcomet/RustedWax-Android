package com.rustedwax.app.architecture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two boundaries the status card (#4) depends on and cannot test on the JVM.
 *
 * The card may say "Hive confirmed" only for an operation the transport said
 * it wrote for that listen, so every way the transport can stop *without* an
 * operation has to say so too — otherwise the card waits on a row that will
 * never exist. And it may show a result only to the account that owns it, so
 * saving or forgetting a key has to be heard the moment it happens.
 */
class StatusCorrelationWiringTest {

	private val root: File by lazy {
		generateSequence(File(checkNotNull(System.getProperty("user.dir"))).absoluteFile) {
			it.parentFile
		}.first { File(it, "settings.gradle.kts").isFile }
	}

	private fun text(path: String): String = File(root, path).readText()

	@Test
	fun `every transport exit before a queue operation reports that none exists`() {
		val send = text("app/src/main/java/com/rustedwax/app/scrobble/FinalizationRuntime.kt")
			.substringAfter("private fun enqueueAndSend(")
			.substringBefore("when (val preparation = broadcaster.prepareJson")
		val returns = Regex("""\n\s*return(@withLock)?\n""").findAll(send).count()
		val reports = Regex("""ended\("[^"]+"\)""").findAll(send).count()
		// The manual path reports before handing off to broadcastDirect, then returns.
		assertEquals("an exit without an operation does not report it", returns, reports)
		val created = send.indexOf("onOperationCreated(it, entry.operationId, account.username)")
		val enqueued = send.indexOf("val entry = queue.enqueue(")
		assertTrue("the operation is reported before it exists", created > enqueued)
	}

	@Test
	fun `both dispatch sites carry their listen and payload index`() {
		val orchestrator = text("app/src/main/java/com/rustedwax/app/scrobble/ProductionFinalizationOrchestrator.kt")
		assertEquals(2, Regex("""DispatchLink\((track|session)\.trackInstance, index, """).findAll(orchestrator).count())
	}

	@Test
	fun `saving and forgetting a key both signal an account change`() {
		val vault = text("app/src/main/java/com/rustedwax/app/storage/KeyVault.kt")
		val save = vault.substringAfter("fun save(").substringBefore("\n\t}")
		val forget = vault.substringAfter("fun forget(").substringBefore("\n\t}")
		assertTrue("save does not signal", save.contains("HiveAccountChanges.changed()"))
		assertTrue("forget does not signal", forget.contains("HiveAccountChanges.changed()"))
	}

	@Test
	fun `the card listens for account changes, not for History`() {
		val card = text("app/src/main/java/com/rustedwax/app/status/ScrobbleStatusNotification.kt")
		assertTrue(card.contains("HiveAccountChanges.generation.collect"))
		val history = card.substringAfter("FinalizationRuntime.recent.collect").substringBefore("\n\t\t}")
		assertTrue("History still drives account reads", !history.contains("accountChanged"))
	}
}
