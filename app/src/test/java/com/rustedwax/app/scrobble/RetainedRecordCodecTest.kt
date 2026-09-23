package com.rustedwax.app.scrobble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stored form of History and Not Logged.
 *
 * Issue #31: both lists were process-memory only, so an ordinary Android
 * process death emptied them. These are the terms of the store that fixes it —
 * what survives a round trip, what the cap and the order are, and what happens
 * to bytes this code did not write.
 *
 * The rule the codec must never break is that a restored row says exactly what
 * the row it came from said. A field quietly lost here is a claim about what
 * the user listened to, or about who it belonged to.
 */
class RetainedRecordCodecTest {

	@Test
	fun `a history row survives the round trip with every field intact`() {
		val row = FinalizationRuntime.ScrobbleRecord(
			title = "Never Gonna Give You Up",
			artist = "Rick Astley",
			percentPlayed = 91,
			atEpochSec = 1_700_000_000L,
			status = "confirmed in block",
			txId = "tx-1",
			queued = true,
			videoId = "dQw4w9WgXcQ",
			eventId = "e-1",
			account = "alice",
		)

		assertEquals(
			listOf(row),
			RetainedRecordCodec.decodeHistory(RetainedRecordCodec.encodeHistory(listOf(row))),
		)
	}

	@Test
	fun `a history row with no artist and no transaction survives as nulls`() {
		// A queued row has no `txId` yet, and plenty of listens never resolve an
		// artist. Both have to come back as null rather than as the string
		// "null", which is what `optString` would produce and what would then be
		// drawn to the user as the artist's name.
		val row = FinalizationRuntime.ScrobbleRecord(
			title = "Untitled",
			artist = null,
			percentPlayed = 0,
			atEpochSec = 1L,
			status = "queued",
			txId = null,
			queued = true,
			videoId = "dQw4w9WgXcQ",
			eventId = "e-1",
			account = "alice",
		)

		val back = RetainedRecordCodec.decodeHistory(
			RetainedRecordCodec.encodeHistory(listOf(row)),
		).single()

		assertNull(back.artist)
		assertNull(back.txId)
		assertEquals(row, back)
	}

	@Test
	fun `a not-logged row survives the round trip with every field intact`() {
		val row = FinalizationRuntime.SkipRecord(
			title = "Some Short",
			artist = "Somebody",
			reason = "watched 12% — below your 70% threshold",
			atEpochSec = 1_700_000_000L,
			playedSeconds = 12,
			durationSeconds = 100,
			videoId = "dQw4w9WgXcQ",
			account = "alice",
		)

		assertEquals(
			listOf(row),
			RetainedRecordCodec.decodeSkipped(RetainedRecordCodec.encodeSkipped(listOf(row))),
		)
	}

	@Test
	fun `the signed-out stamp survives as null and not as a name`() {
		// The whole of `skippedFor`'s signed-out rule rests on this. A null that
		// came back as "null", or as a missing key read later as "unknown",
		// would hide the signed-out device's own rows from it — and could show
		// them to whoever signs in next.
		val row = FinalizationRuntime.SkipRecord(
			title = "Some Short",
			artist = null,
			reason = "no posting key",
			atEpochSec = 1L,
			playedSeconds = 3,
			durationSeconds = null,
			videoId = null,
			account = null,
		)

		val back = RetainedRecordCodec.decodeSkipped(
			RetainedRecordCodec.encodeSkipped(listOf(row)),
		).single()

		assertNull("a signed-out row came back owned", back.account)
		assertNull(back.durationSeconds)
		assertNull(back.videoId)
		assertEquals(row, back)
	}

	@Test
	fun `missing required fields are not replaced with invented defaults`() {
		val skippedWithoutAccount = """
			[{"title":"declined","artist":null,"reason":"offline","at":1,"played":3,
			  "duration":null,"videoId":null}]
		""".trimIndent()
		val skippedWithoutPlayed = """
			[{"title":"declined","artist":null,"reason":"offline","at":1,
			  "duration":null,"videoId":null,"account":null}]
		""".trimIndent()
		val historyWithoutQueued = """
			[{"title":"t","artist":null,"percent":1,"at":1,"status":"s","tx":null,
			  "videoId":"dQw4w9WgXcQ","eventId":"e-1","account":"alice"}]
		""".trimIndent()

		assertTrue(RetainedRecordCodec.decodeSkipped(skippedWithoutAccount).isEmpty())
		assertTrue(RetainedRecordCodec.decodeSkipped(skippedWithoutPlayed).isEmpty())
		assertTrue(RetainedRecordCodec.decodeHistory(historyWithoutQueued).isEmpty())
	}

	@Test
	fun `order is preserved exactly, newest first`() {
		val rows = (1..5).map { historyRow("alice", "e-$it") }

		assertEquals(
			rows.map { it.eventId },
			RetainedRecordCodec.decodeHistory(RetainedRecordCodec.encodeHistory(rows))
				.map { it.eventId },
		)
	}

	@Test
	fun `the fifty-row cap is applied on the way out and on the way back`() {
		val rows = (1..80).map { historyRow("alice", "e-$it") }

		val encoded = RetainedRecordCodec.encodeHistory(rows)
		val back = RetainedRecordCodec.decodeHistory(encoded)

		assertEquals(RETAINED_ROWS, back.size)
		// The cap keeps the head of the list, because the list is newest-first.
		assertEquals("e-1", back.first().eventId)
		assertEquals("e-50", back.last().eventId)
	}

	@Test
	fun `a stored blob longer than the cap is still truncated on read`() {
		// The cap could also be crossed by a file written before the cap
		// changed, or by a file that was edited. Reading is bounded on its own
		// rather than trusting whoever wrote it.
		val overlong = "[" + (1..90).joinToString(",") {
			"""{"title":"t","artist":null,"percent":1,"at":1,"status":"s",""" +
				""""tx":null,"queued":false,"videoId":"dQw4w9WgXcQ",""" +
				""""eventId":"e-$it","account":"alice"}"""
		} + "]"

		assertEquals(RETAINED_ROWS, RetainedRecordCodec.decodeHistory(overlong).size)
	}

	@Test
	fun `the not-logged cap holds too`() {
		val rows = (1..80).map { skipRow("alice", "t-$it") }

		assertEquals(
			RETAINED_ROWS,
			RetainedRecordCodec.decodeSkipped(RetainedRecordCodec.encodeSkipped(rows)).size,
		)
	}

	@Test
	fun `account stamps survive so the boundary still selects after a restore`() {
		// The end of the chain the fix has to keep intact: rows go to disk, come
		// back, and `recentFor` still hands each viewer only their own.
		val rows = listOf(
			historyRow("alice", "a-1"),
			historyRow("bob", "b-1"),
			historyRow("alice", "a-2"),
		)

		val back = RetainedRecordCodec.decodeHistory(RetainedRecordCodec.encodeHistory(rows))

		assertEquals(
			listOf("a-1", "a-2"),
			FinalizationRuntime.recentFor(back, "alice").map { it.eventId },
		)
		assertEquals(
			listOf("b-1"),
			FinalizationRuntime.recentFor(back, "bob").map { it.eventId },
		)
	}

	@Test
	fun `not-logged stamps survive, including the signed-out set`() {
		val rows = listOf(
			skipRow("alice", "a-1"),
			skipRow(null, "nobody-1"),
			skipRow("bob", "b-1"),
			skipRow(null, "nobody-2"),
		)

		val back = RetainedRecordCodec.decodeSkipped(RetainedRecordCodec.encodeSkipped(rows))

		assertEquals(
			listOf("a-1"),
			FinalizationRuntime.skippedFor(back, "alice").map { it.title },
		)
		assertEquals(
			listOf("nobody-1", "nobody-2"),
			FinalizationRuntime.skippedFor(back, null).map { it.title },
		)
	}

	@Test
	fun `bytes this code did not write restore as nothing`() {
		// Every one of these is a real shape a preferences file can end up in:
		// absent, blanked, truncated mid-write, or replaced by something else
		// entirely. None of them may throw, and none may invent a row.
		listOf(
			null,
			"",
			"   ",
			"not json at all",
			"[",
			"""{"history":[]}""",
			"[1,2,3]",
			"[null]",
		).forEach { blob ->
			assertEquals("history from <$blob>", emptyList<Any>(), RetainedRecordCodec.decodeHistory(blob))
			assertEquals("not logged from <$blob>", emptyList<Any>(), RetainedRecordCodec.decodeSkipped(blob))
		}
	}

	@Test
	fun `a damaged row is dropped and its neighbours are kept`() {
		// Partial damage is the interesting case: one unreadable row must not
		// cost the user the other forty-nine.
		val blob = """
			[
			  {"title":"ok","artist":null,"percent":1,"at":1,"status":"s","tx":null,
			   "queued":false,"videoId":"dQw4w9WgXcQ","eventId":"e-1","account":"alice"},
			  {"title":"no video id","artist":null,"percent":1,"at":1,"status":"s"},
			  {"title":"bad video id","artist":null,"percent":1,"at":1,"status":"s","tx":null,
			   "queued":false,"videoId":"nope","eventId":"e-3","account":"alice"},
			  {"title":"no account","artist":null,"percent":1,"at":1,"status":"s","tx":null,
			   "queued":false,"videoId":"dQw4w9WgXcQ","eventId":"e-4","account":""},
			  {"title":"also ok","artist":null,"percent":1,"at":1,"status":"s","tx":null,
			   "queued":false,"videoId":"dQw4w9WgXcQ","eventId":"e-5","account":"alice"}
			]
		""".trimIndent()

		assertEquals(
			listOf("e-1", "e-5"),
			RetainedRecordCodec.decodeHistory(blob).map { it.eventId },
		)
	}

	@Test
	fun `a not-logged row whose link no longer validates keeps the row`() {
		// Unlike History. A refusal without a working link still answers "why
		// wasn't this scrobbled", which is the whole job of the tab; dropping it
		// would make the commonest offline refusal invisible again.
		val blob = """
			[{"title":"declined","artist":null,"reason":"offline","at":1,"played":3,
			  "duration":null,"videoId":"nope","account":null}]
		""".trimIndent()

		val back = RetainedRecordCodec.decodeSkipped(blob).single()

		assertEquals("declined", back.title)
		assertNull("an unusable id was kept as a link", back.videoId)
	}

	@Test
	fun `an empty list round-trips as an empty list`() {
		assertTrue(
			RetainedRecordCodec.decodeHistory(
				RetainedRecordCodec.encodeHistory(emptyList()),
			).isEmpty(),
		)
		assertTrue(
			RetainedRecordCodec.decodeSkipped(
				RetainedRecordCodec.encodeSkipped(emptyList()),
			).isEmpty(),
		)
	}

	private fun historyRow(account: String, eventId: String) =
		FinalizationRuntime.ScrobbleRecord(
			title = "Never Gonna Give You Up",
			artist = "Rick Astley",
			percentPlayed = 91,
			atEpochSec = 1_700_000_000L,
			status = "confirmed in block",
			txId = "tx-$eventId",
			videoId = "dQw4w9WgXcQ",
			eventId = eventId,
			account = account,
		)

	private fun skipRow(account: String?, title: String) =
		FinalizationRuntime.SkipRecord(
			title = title,
			artist = null,
			reason = "watched 12% — below your 70% threshold",
			atEpochSec = 1_700_000_000L,
			playedSeconds = 12,
			durationSeconds = 100,
			videoId = null,
			account = account,
		)
}
