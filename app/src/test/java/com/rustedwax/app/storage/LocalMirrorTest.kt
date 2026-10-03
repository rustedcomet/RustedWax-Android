package com.rustedwax.app.storage

import com.rustedwax.app.storage.db.LegacyImportState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

/**
 * Issue #41 Slice 3: the mirror every Snap local store runs on — provisional
 * legacy view, database adoption, shadow writes, fallback — against an
 * in-memory database and legacy file.
 */
class LocalMirrorTest {

	private data class Row(val text: String, val at: Long)

	private val inline = Executor { it.run() }

	/** Keys are `owner|id`; `-` is the signed-out owner; `UPPER|…` breaks the owner rule. */
	private fun storable(key: String) = key.contains('|') && key.substringBefore('|').let { it == "-" || it == it.lowercase() }

	private fun owner(key: String) = key.substringBefore('|')

	private fun mirror(
		legacy: InMemoryKeyedSource<Row>,
		writer: Executor = inline,
		max: Int? = null,
	) = LocalMirror(
		source = "test_source",
		legacy = legacy,
		storable = ::storable,
		retention = max?.let { LocalMirror.Retention(it, ::owner) { row: Row -> row.at } },
		writer = writer,
		nowMillis = { 1L },
	)

	/** One "process": construct over the legacy file, then adopt [rows]. */
	private fun boot(legacy: InMemoryKeyedSource<Row>, rows: InMemoryMirrorRows<Row>, max: Int? = null) =
		mirror(legacy, max = max).also { it.start { rows } }

	// ---- migration ---------------------------------------------------------------

	@Test
	fun `first migration imports every storable entry and verifies it`() {
		val legacy = InMemoryKeyedSource(mapOf("alice|e1" to Row("a", 1), "-|e2" to Row("signed out", 2)))
		val rows = InMemoryMirrorRows<Row>()

		val m = boot(legacy, rows)

		assertEquals(legacy.entries, rows.rows)
		assertEquals(legacy.entries, m.entries())
		val record = rows.record!!
		assertEquals(LegacyImportState.VERIFIED, record.state)
		assertEquals(2, record.imported)
		assertEquals(LocalMirror.PARSER_VERSION, record.parserVersion)
		assertEquals(0, legacy.writes)
	}

	@Test
	fun `a repeated start is a no-op and a reopen restores every entry exactly once`() {
		val legacy = InMemoryKeyedSource(mapOf("alice|e1" to Row("a", 1), "bob|e2" to Row("b", 2)))
		val rows = InMemoryMirrorRows<Row>()
		boot(legacy, rows)
		val record = rows.record

		repeat(3) {
			val again = boot(legacy, rows)
			assertEquals(mapOf("alice|e1" to Row("a", 1), "bob|e2" to Row("b", 2)), again.entries())
		}
		assertEquals(2, rows.rows.size)
		assertEquals(record!!.generation, rows.record!!.generation)
		assertEquals("unchanged legacy is not re-imported", record.imported, rows.record!!.imported)
	}

	@Test
	fun `an interrupted import leaves nothing behind, falls back, and the next start completes`() {
		val legacy = InMemoryKeyedSource(mapOf("alice|e1" to Row("a", 1), "alice|e2" to Row("b", 2)))
		val rows = InMemoryMirrorRows<Row>().apply { failNextTransactions = 1 }

		val m = boot(legacy, rows)
		assertTrue(rows.rows.isEmpty())
		assertNull(rows.record)
		assertEquals("the legacy view stays on screen", legacy.entries, m.entries())

		boot(legacy, rows)
		assertEquals(legacy.entries, rows.rows)
	}

	@Test
	fun `undecodable and unstorable legacy entries never remove valid ones`() {
		val legacy = InMemoryKeyedSource(
			mapOf("alice|e1" to Row("ok", 1), "ALICE|e2" to Row("owner rule", 2)),
			undecodable = 3,
		)
		val rows = InMemoryMirrorRows<Row>()

		val m = boot(legacy, rows)

		assertEquals(mapOf("alice|e1" to Row("ok", 1)), rows.rows)
		assertEquals("the unstorable entry stays visible from the legacy file", Row("owner rule", 2), m.get("ALICE|e2"))
		assertEquals(4, rows.record!!.rejected)
		assertEquals(5, rows.record!!.seen)
	}

	// ---- authority -----------------------------------------------------------------

	@Test
	fun `once open the database is authoritative over an older shadow`() {
		val rows = InMemoryMirrorRows<Row>()
		val legacy = InMemoryKeyedSource(mapOf("alice|e1" to Row("v1", 1), "alice|gone" to Row("deleted", 1)))
		boot(legacy, rows).apply {
			put("alice|e1", Row("v2", 2))
			remove("alice|gone")
		}
		// Simulate the shadow writes that never reached disk before a crash.
		legacy.entries["alice|e1"] = Row("v1", 1)
		legacy.entries["alice|gone"] = Row("deleted", 1)
		legacy.generationValue -= 1

		val reopened = boot(legacy, rows)

		assertEquals(Row("v2", 2), reopened.get("alice|e1"))
		assertNull("a deleted entry is not resurrected by a lagging shadow", reopened.get("alice|gone"))
		assertEquals("and the shadow catches up", mapOf("alice|e1" to Row("v2", 2)), legacy.entries)
	}

	@Test
	fun `a write made while the database opens is never rolled back`() {
		val rows = InMemoryMirrorRows<Row>()
		val legacy = InMemoryKeyedSource(mapOf("alice|draft" to Row("typed earlier", 1)))
		boot(legacy, rows)
		val held = ArrayDeque<Runnable>()

		val m = mirror(legacy, writer = { held.addLast(it) })
		m.start { rows }
		m.put("alice|draft", Row("typed while opening", 5))
		m.put("alice|new", Row("new", 6))
		m.remove("alice|old").also { rows.rows["alice|old"] = Row("stored only", 0) }
		while (held.isNotEmpty()) held.removeFirst().run()

		assertEquals(Row("typed while opening", 5), m.get("alice|draft"))
		assertEquals(Row("new", 6), m.get("alice|new"))
		assertNull(m.get("alice|old"))
		assertEquals(Row("typed while opening", 5), rows.rows["alice|draft"])
		assertEquals(Row("new", 6), rows.rows["alice|new"])
		assertFalse(rows.rows.containsKey("alice|old"))
	}

	@Test
	fun `writes go to the database first, then the shadow at the recorded generation`() {
		val rows = InMemoryMirrorRows<Row>()
		val legacy = InMemoryKeyedSource<Row>()
		val m = boot(legacy, rows)

		m.put("alice|e1", Row("x", 1))
		m.remove("alice|e1")
		m.put("alice|e2", Row("y", 2))

		assertEquals(mapOf("alice|e2" to Row("y", 2)), rows.rows)
		assertEquals(rows.rows, legacy.entries)
		assertEquals(rows.record!!.generation, legacy.generationValue)
		assertEquals(3L, legacy.generationValue)
	}

	// ---- fallback --------------------------------------------------------------------

	@Test
	fun `a database that cannot open leaves the legacy file in charge, and its writes win next time`() {
		val rows = InMemoryMirrorRows<Row>()
		val legacy = InMemoryKeyedSource(mapOf("alice|e1" to Row("a", 1), "alice|e2" to Row("b", 1)))
		boot(legacy, rows)
		val generation = legacy.generationValue

		val fallback = mirror(legacy).apply { start { throw IllegalStateException("unavailable") } }
		fallback.put("alice|e1", Row("changed in fallback", 2))
		fallback.remove("alice|e2")
		assertTrue(legacy.generationValue > generation)
		assertEquals(Row("a", 1), rows.rows["alice|e1"])

		val next = boot(legacy, rows)
		assertEquals(Row("changed in fallback", 2), next.get("alice|e1"))
		assertNull("the fallback run's removal is honoured", next.get("alice|e2"))
		assertEquals(mapOf("alice|e1" to Row("changed in fallback", 2)), rows.rows)
	}

	@Test
	fun `a write made before a failed open is marked newer for the next run`() {
		val rows = InMemoryMirrorRows<Row>()
		val legacy = InMemoryKeyedSource(mapOf("alice|e1" to Row("a", 1)))
		boot(legacy, rows)
		val held = ArrayDeque<Runnable>()

		val m = mirror(legacy, writer = { held.addLast(it) })
		m.start { throw IllegalStateException("unavailable") }
		m.put("alice|e1", Row("typed", 2))
		val before = legacy.generationValue
		while (held.isNotEmpty()) held.removeFirst().run()
		assertTrue(legacy.generationValue > before)

		assertEquals(Row("typed", 2), boot(legacy, rows).get("alice|e1"))
	}

	// ---- isolation and retention -------------------------------------------------------

	@Test
	fun `owners never see or evict each other`() {
		val rows = InMemoryMirrorRows<Row>()
		val legacy = InMemoryKeyedSource<Row>()
		val m = boot(legacy, rows, max = 3)

		(1..3L).forEach { m.put("alice|a$it", Row("a", it)) }
		(1..5L).forEach { m.put("bob|b$it", Row("b", 10 + it)) }
		m.put("-|n1", Row("signed out", 99))

		assertEquals(setOf("alice|a1", "alice|a2", "alice|a3"), m.keys().filter { owner(it) == "alice" }.toSet())
		assertEquals(setOf("bob|b3", "bob|b4", "bob|b5"), m.keys().filter { owner(it) == "bob" }.toSet())
		assertEquals(m.entries(), rows.rows)
		assertEquals(rows.rows, legacy.entries)

		// Forget Key changes nothing here; the same account's entries are simply read again.
		val again = boot(legacy, rows, max = 3)
		assertEquals(m.entries(), again.entries())
	}

	@Test
	fun `a write over the limit never evicts the entry just written`() {
		val m = boot(InMemoryKeyedSource(), InMemoryMirrorRows(), max = 2)
		m.put("alice|new", Row("x", 100))
		m.put("alice|older", Row("y", 1))
		m.put("alice|oldest", Row("z", 0))
		assertEquals(setOf("alice|new", "alice|oldest"), m.keys())
	}

	// ---- durable batches (Slice 4) ---------------------------------------------------------

	@Test
	fun `a durable batch lands whole in the database before it answers`() {
		val rows = InMemoryMirrorRows<Row>()
		val legacy = InMemoryKeyedSource<Row>()
		val m = boot(legacy, rows)

		assertTrue(m.putAll(mapOf("alice|a" to Row("a", 1), "alice|b" to Row("b", 2)), durable = true))

		assertEquals(mapOf("alice|a" to Row("a", 1), "alice|b" to Row("b", 2)), rows.rows)
		assertEquals(rows.rows, legacy.entries)
	}

	@Test
	fun `a durable batch the database refuses changes nothing anywhere`() {
		val rows = InMemoryMirrorRows<Row>()
		val legacy = InMemoryKeyedSource(mapOf("alice|a" to Row("old", 1)))
		val m = boot(legacy, rows)
		rows.failNextTransactions = 1

		assertFalse(m.putAll(mapOf("alice|a" to Row("new", 2), "alice|b" to Row("b", 2)), durable = true))

		assertEquals(Row("old", 1), m.get("alice|a"))
		assertNull(m.get("alice|b"))
		assertEquals(mapOf("alice|a" to Row("old", 1)), rows.rows)
		assertEquals(mapOf("alice|a" to Row("old", 1)), legacy.entries)
	}

	@Test
	fun `in the legacy phases a durable batch is a synchronous commit, and a failed one is undone`() {
		val legacy = InMemoryKeyedSource(mapOf("alice|a" to Row("old", 1)))
		val m = mirror(legacy).apply { start { throw IllegalStateException("unavailable") } }
		legacy.failCommits = 1

		assertFalse(m.putAll(mapOf("alice|a" to Row("new", 2)), durable = true))
		assertEquals(Row("old", 1), m.get("alice|a"))
		assertEquals(Row("old", 1), legacy.entries["alice|a"])

		assertTrue(m.putAll(mapOf("alice|a" to Row("new", 2)), durable = true))
		assertEquals(Row("new", 2), legacy.entries["alice|a"])
	}
	@Test
	fun `a later successful key write also commits an earlier failed edit and delete`() {
		val legacy = InMemoryKeyedSource(mapOf("alice|gone" to Row("old", 1)))
		val rows = InMemoryMirrorRows<Row>()
		val m = boot(legacy, rows)
		rows.failNextTransactions = 2
		m.put("alice|edited", Row("kept", 2))
		m.remove("alice|gone")
		m.put("bob|other", Row("other", 3))

		val reopened = boot(legacy, rows)
		assertEquals(Row("kept", 2), reopened.get("alice|edited"))
		assertNull(reopened.get("alice|gone"))
		assertEquals(legacy.entries, rows.rows)
	}

	@Test
	fun `a newer malformed legacy source cannot delete valid database entries`() {
		val rows = InMemoryMirrorRows<Row>()
		boot(InMemoryKeyedSource(mapOf("alice|a" to Row("stored", 1))), rows)
		val prior = rows.record
		val malformed = InMemoryKeyedSource<Row>(generationValue = 1, undecodable = 1, rejectedKeys = setOf("alice|a"))
		boot(malformed, rows)

		assertEquals(mapOf("alice|a" to Row("stored", 1)), rows.rows)
		assertEquals(prior!!.generation + 1, rows.record!!.generation)
		assertEquals(0, malformed.writes)
	}

}
