package com.rustedwax.app.storage.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The version 1 schema as text: what it creates, who owns each row, and what
 * it refuses to do. Executing it against real SQLite is the instrumented
 * suite's job ([LocalDatabaseDeviceTest]).
 */
class LocalDatabaseSchemaTest {

	private val statements = LocalDatabaseSchema.CREATE_V1

	private fun createTable(table: String): String =
		statements.single { it.startsWith("CREATE TABLE $table (") }

	/** Column name to its full definition, in declaration order. */
	private fun columns(table: String): Map<String, String> =
		createTable(table)
			.substringAfter("(")
			.substringBeforeLast(")")
			.lines()
			.map { it.trim().removeSuffix(",") }
			.filter { it.isNotEmpty() && !it.startsWith("PRIMARY KEY") }
			.associateBy { it.substringBefore(' ') }

	private fun primaryKey(table: String): List<String> {
		val sql = createTable(table)
		Regex("PRIMARY KEY \\(([^)]*)\\)").find(sql)?.let { match ->
			return match.groupValues[1].split(",").map { it.trim() }
		}
		return columns(table).filterValues { "PRIMARY KEY" in it }.keys.toList()
	}

	private fun indexes(table: String): List<String> =
		statements.filter { Regex("^CREATE (UNIQUE )?INDEX \\w+ ON $table ").containsMatchIn(it) }

	@Test
	fun `version 1 lives in its own file`() {
		assertEquals(1, LocalDatabaseSchema.VERSION)
		assertEquals("rustedwax.db", LocalDatabaseSchema.NAME)
		// Not the name of any store the legacy data lives in.
		val legacy = setOf(
			"rustedwax_retained", "rustedwax_snap_drafts", "rustedwax_snap_reply_drafts",
			"rustedwax_pending_snaps", "rustedwax_pending_snap_replies",
			"rustedwax_posted_snap_cache", "rustedwax_snap_thread_previews",
			"rustedwax_snap_notices", "broadcast-queue.json", "rustedwax_dedup",
		)
		assertFalse(LocalDatabaseSchema.NAME.removeSuffix(".db") in legacy)
		assertFalse(LocalDatabaseSchema.NAME in legacy)
	}

	@Test
	fun `every approved table is created exactly once, before its indexes`() {
		val expected = listOf(
			"history", "not_logged", "snap_draft", "posted_snap_cache", "snap_refresh",
			"thread_preview", "seen_reply", "reply_notice", "thread_seed",
			"sync_checkpoint", "legacy_import",
		)
		assertEquals(expected, LocalDatabaseSchema.TABLES)
		val created = statements.mapNotNull {
			Regex("^CREATE TABLE (\\w+) \\(").find(it)?.groupValues?.get(1)
		}
		assertEquals(expected, created)
		statements.forEachIndexed { i, sql ->
			val table = Regex("^CREATE (?:UNIQUE )?INDEX \\w+ ON (\\w+) ").find(sql)?.groupValues?.get(1)
				?: return@forEachIndexed
			val tableAt = statements.indexOfFirst { it.startsWith("CREATE TABLE $table (") }
			assertTrue("index on $table precedes its table", tableAt in 0 until i)
		}
	}

	@Test
	fun `version 1 creates and never drops or deletes`() {
		statements.forEach { sql ->
			assertTrue(sql, sql.startsWith("CREATE "))
			assertFalse(sql, Regex("(?i)\\b(DROP|DELETE|ALTER|REPLACE)\\b").containsMatchIn(sql))
		}
	}

	@Test
	fun `history keys rows by row_id and keeps event_id as a separate plain column`() {
		assertEquals(
			listOf(
				"row_id", "event_id", "owner", "title", "artist", "percent", "at", "status",
				"tx_id", "queued", "video_id", "queue_op_id", "service",
			),
			columns("history").keys.toList(),
		)
		assertEquals(listOf("row_id"), primaryKey("history"))
		val eventId = columns("history").getValue("event_id")
		assertFalse("event_id must not be a key", "PRIMARY KEY" in eventId || "UNIQUE" in eventId)
		indexes("history").forEach {
			assertFalse("event_id must not be indexed as an identity: $it", "event_id" in it)
		}
		assertTrue("service is nullable", "NOT NULL" !in columns("history").getValue("service"))
	}

	@Test
	fun `history indexes by owner and time and has one row per queue operation per owner`() {
		val history = indexes("history")
		assertTrue(history.contains("CREATE INDEX history_owner_at ON history (owner, at DESC)"))
		assertTrue(
			history.contains(
				"CREATE UNIQUE INDEX history_owner_queue_op ON history (owner, queue_op_id) " +
					"WHERE queue_op_id IS NOT NULL",
			),
		)
	}

	@Test
	fun `not logged has its own row identity and an owner-time index`() {
		assertEquals(
			listOf("row_id", "owner", "title", "artist", "reason", "at", "played", "duration", "video_id"),
			columns("not_logged").keys.toList(),
		)
		assertEquals(listOf("row_id"), primaryKey("not_logged"))
		assertTrue(indexes("not_logged").contains("CREATE INDEX not_logged_owner_at ON not_logged (owner, at DESC)"))
	}

	@Test
	fun `every account-scoped table has a checked owner leading its primary key or index`() {
		LocalDatabaseSchema.TABLES.filter { it != "legacy_import" }.forEach { table ->
			val owner = columns(table)["owner"] ?: return@forEach fail("$table has no owner column")
			assertTrue("$table owner unchecked", "NOT NULL" in owner && "owner = lower(trim(owner))" in owner)
			val key = primaryKey(table)
			val leadsKey = key.firstOrNull() == "owner"
			val leadsIndex = indexes(table).any { Regex("ON $table \\(owner\\b").containsMatchIn(it) }
			assertTrue("$table: owner leads neither its key $key nor an index", leadsKey || leadsIndex)
		}
		assertNull("legacy_import is bookkeeping, not account data", columns("legacy_import")["owner"])
	}

	@Test
	fun `only tables the signed-out device can own accept the signed-out owner`() {
		val signedOutAllowed = setOf("not_logged", "snap_draft", "thread_preview", "sync_checkpoint")
		LocalDatabaseSchema.TABLES.filter { it != "legacy_import" }.forEach { table ->
			val refuses = "owner <> '${LocalOwner.SIGNED_OUT}'" in columns(table).getValue("owner")
			assertEquals("$table signed-out rule", table !in signedOutAllowed, refuses)
		}
	}

	@Test
	fun `remaining tables use the approved keys`() {
		assertEquals(listOf("owner", "event_id"), primaryKey("snap_draft"))
		assertEquals(listOf("owner", "content_id"), primaryKey("posted_snap_cache"))
		assertEquals(listOf("owner"), primaryKey("snap_refresh"))
		assertEquals(listOf("owner", "root_id"), primaryKey("thread_preview"))
		assertEquals(listOf("owner", "content_id"), primaryKey("seen_reply"))
		assertEquals(listOf("owner", "content_id"), primaryKey("reply_notice"))
		assertEquals(listOf("owner", "root_id"), primaryKey("thread_seed"))
		assertEquals(listOf("owner", "scope"), primaryKey("sync_checkpoint"))
		assertEquals(listOf("source"), primaryKey("legacy_import"))
	}

	@Test
	fun `legacy import records state, parser version, fingerprint, generation and counts`() {
		assertEquals(
			listOf(
				"source", "state", "parser_version", "fingerprint", "generation",
				"seen", "imported", "rejected", "updated_at",
			),
			columns("legacy_import").keys.toList(),
		)
		val state = columns("legacy_import").getValue("state")
		LegacyImportState.entries.forEach { assertTrue(state, "'${it.name}'" in state) }
	}

	@Test
	fun `there is no upgrade path the schema does not define`() {
		listOf(0 to 1, 1 to 1, 1 to 2, 2 to 1, 2 to 3).forEach { (from, to) ->
			try {
				LocalDatabaseSchema.upgradeSteps(from, to)
				fail("upgrade $from -> $to should be refused")
			} catch (expected: IllegalArgumentException) {
			}
		}
	}

	@Test
	fun `owners are lowercase accounts or the signed-out device`() {
		assertEquals("alice", LocalOwner.account(" Alice "))
		assertNull(LocalOwner.account(null))
		assertNull(LocalOwner.account("  "))
		assertNull("the signed-out marker is not an account", LocalOwner.account("-"))
		assertEquals("alice", LocalOwner.of("ALICE"))
		assertEquals("-", LocalOwner.of(null))
		assertEquals("-", LocalOwner.of(""))
	}
}
