package com.rustedwax.app.status

import com.rustedwax.app.detect.ResolverContext
import com.rustedwax.app.detect.SessionSnapshot
import com.rustedwax.app.detect.YouTubeProbe
import com.rustedwax.app.scrobble.DispatchLink
import com.rustedwax.app.scrobble.FinalizationOutcome
import com.rustedwax.app.scrobble.FinalizationRuntime.ScrobbleRecord
import com.rustedwax.app.ui.snaps.AndroidSnapNoticeNotifier
import com.rustedwax.core.TrackInstanceId
import com.rustedwax.hive.HiveScrobblePayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the scrobbling-status card (#4) may say, and for which listen.
 *
 * Every word is a reading of an engine decision; these pin that no state is
 * claimed stronger than its evidence and that one listen's result can never
 * be drawn on another.
 */
class ScrobbleStatusTest {

	private val now = 1_800_000_000_000L

	// ── live ──────────────────────────────────────────────────────────

	@Test
	fun `monitoring with nothing playing waits for media`() {
		val view = present(live = emptyList())
		assertEquals("Waiting for media", view.headline)
		assertEquals("RustedWax active · Waiting for media", view.status.label)
		assertNull(view.track)
		assertFalse(view.crediting)
	}

	@Test
	fun `progress is the reducer's credited time, never the published snapshot or seek position`() {
		val s = session(playedMs = 10_000, positionMs = 200_000, durationMs = 250_000)
		val view = present(live = listOf(LiveListen(s, creditedMs = 128_000, videoId = VID_A, effectiveDurationMs = 250_000)))
		val track = view.track!!
		assertEquals("2:08 credited", track.credited)
		assertEquals("51%", track.percent)
		assertEquals(512, track.progressPermille)
		assertEquals("Listening · Scrobbles at 60%", view.status.label)
		assertTrue(view.crediting)
	}

	@Test
	fun `unknown length draws no bar and no percentage`() {
		val s = session(durationMs = null)
		val view = present(live = listOf(LiveListen(s, 30_000, VID_A, null)))
		assertNull(view.track!!.percent)
		assertNull(view.track!!.progressPermille)
		assertEquals("Listening · Length unknown", view.status.label)
	}

	@Test
	fun `threshold reached without identity is verifying, not ready`() {
		val s = session(durationMs = 100_000)
		val view = present(live = listOf(LiveListen(s, 70_000, videoId = null, effectiveDurationMs = 100_000)))
		assertEquals("Threshold reached · Verifying", view.status.label)
		assertNoClaim(view.status.label)
		assertEquals("Threshold reached", view.status.pill)
	}

	@Test
	fun `threshold reached with identity still defers to finalization`() {
		val s = session(durationMs = 100_000)
		val view = present(live = listOf(LiveListen(s, 70_000, VID_A, 100_000)))
		assertEquals("Threshold reached · Checks at end", view.status.label)
		assertNoClaim(view.status.label)
		assertNotEquals(Tone.GREEN, view.status.tone)
	}

	@Test
	fun `paused only when the transport is paused and PiP inference is not crediting`() {
		val paused = session(isPlaying = false)
		assertEquals("Paused", present(live = listOf(LiveListen(paused, 5_000, VID_A, 200_000))).status.label)
		assertFalse(present(live = listOf(LiveListen(paused, 5_000, VID_A, 200_000))).crediting)

		val pip = paused.copy(pipInferredPlaying = true)
		val view = present(live = listOf(LiveListen(pip, 5_000, VID_A, 200_000)))
		assertNotEquals("Paused", view.status.label)
		assertTrue(view.crediting)
	}

	@Test
	fun `an advertisement is not counted`() {
		val s = session().copy(explicitAdSignal = "Skip ad")
		assertEquals("Ad · Not counted", present(live = listOf(LiveListen(s, 5_000, VID_A, 200_000))).status.label)
	}

	@Test
	fun `a browser names YouTube only once the session is proven YouTube`() {
		val proven = session(pkg = "com.brave.browser")
		assertEquals("Brave · YouTube", ScrobbleStatusPresenter.sourceLine(proven))
		val unproven = proven.copy(identity = YouTubeProbe.Identity.Unconfirmed("no host yet"))
		assertEquals("Brave", ScrobbleStatusPresenter.sourceLine(unproven))
		assertEquals("YouTube Music", ScrobbleStatusPresenter.sourceLine(session()))
	}

	@Test
	fun `the playing listen is primary over a paused one`() {
		val paused = session(title = "Old", token = 1, isPlaying = false, startedAt = 2_000)
		val playing = session(title = "New", token = 2, startedAt = 1_000)
		val view = present(live = listOf(LiveListen(paused, 1, VID_A, 1), LiveListen(playing, 1, VID_B, 200_000)))
		assertEquals("New", view.headline)
	}

	// ── artwork ───────────────────────────────────────────────────────

	@Test
	fun `artwork is keyed to the primary listen and its own title`() {
		val s = session(title = "Que sera de mi Ex", token = 7)
		val key = present(live = listOf(LiveListen(s, 1_000, VID_A, 200_000))).artwork!!
		assertEquals(TrackInstanceId(PKG_MUSIC, 7), key.listen)
		assertEquals("Que sera de mi Ex", key.title)
		assertEquals(VID_A, key.videoId)
		assertTrue(key.live)
	}

	@Test
	fun `no thumbnail lookup without consent`() {
		val s = session()
		val key = present(live = listOf(LiveListen(s, 1_000, VID_A, 200_000)), thumbnails = false).artwork!!
		assertNull(key.videoId)
	}

	@Test
	fun `an untitled listen offers no artwork at all`() {
		val s = session(title = null)
		assertNull(present(live = listOf(LiveListen(s, 1_000, null, null))).artwork)
	}

	// ── finished: truthful Hive states ───────────────────────────────

	@Test
	fun `finalization running is verifying`() {
		val ledger = FinishedListenLedger()
		ledger.finalized(finished(LISTEN_A))
		assertEquals("Verifying · Final checks", idle(ledger).status.label)
	}

	@Test
	fun `eligible with no row yet is sending, not confirmed`() {
		val ledger = eligible(LISTEN_A, VID_A)
		val view = idle(ledger)
		assertEquals("Eligible · Sending to Hive", view.status.label)
		assertNoConfirmation(view.status.label)
	}

	@Test
	fun `each row status maps to its own evidence and only a block is confirmed`() {
		val cases = mapOf(
			"confirmed in block" to "Scrobbled · Hive confirmed",
			"seen relaying in mempool" to "Sent · Awaiting block",
			"sent from queue" to "Sent to Hive · Block not verified",
			"reconciled after ambiguous retry" to "Sent to Hive · Block not verified",
			"${ScrobbleRecord.ACCEPTED_UNCONFIRMED}; not retried" to "Accepted · Confirmation unavailable",
			"rejected: bad" to "Not logged · Hive did not take it",
			"${ScrobbleRecord.QUEUE_FAILED_PERMANENTLY}: x" to "Not logged · Hive did not take it",
		)
		for ((status, label) in cases) {
			val ledger = eligible(LISTEN_A, VID_A)
			ledger.history(listOf(row("op1", VID_A, status)))
			assertEquals(status, label, idle(ledger).status.label)
		}
		val queued = eligible(LISTEN_A, VID_A)
		queued.history(listOf(row("op1", VID_A, "queued — offline", queued = true)))
		assertEquals("Queued · Will retry", idle(queued).status.label)
	}

	@Test
	fun `a queued row updated in place follows the same operation`() {
		val ledger = eligible(LISTEN_A, VID_A)
		ledger.history(listOf(row("op1", VID_A, "queued — offline", queued = true)))
		assertEquals("Queued · Will retry", idle(ledger).status.label)
		ledger.history(listOf(row("op1", VID_A, "sent from queue", txId = "tx")))
		assertEquals("Sent to Hive · Block not verified", idle(ledger).status.label)
	}

	@Test
	fun `a row for an operation this listen never created never binds`() {
		val ledger = eligible(LISTEN_A, VID_A, op = null)
		ledger.history(listOf(row("old", VID_A, "confirmed in block")))
		assertEquals("Eligible · Sending to Hive", idle(ledger).status.label)
	}

	@Test
	fun `two plays of one video each follow only their own operation`() {
		val ledger = FinishedListenLedger()
		ledger.finalized(finished(LISTEN_A, finishedAt = now - 2_000))
		ledger.finalized(finished(LISTEN_B, finishedAt = now - 1_000))
		ledger.outcome(LISTEN_A, FinalizationOutcome.Eligible(listOf(payload(VID_A))), now - 2_000)
		ledger.outcome(LISTEN_B, FinalizationOutcome.Eligible(listOf(payload(VID_A))), now - 1_000)
		// B's operation was written first; order must not matter.
		ledger.operationCreated(link(LISTEN_B), "op2", "alice")
		ledger.operationCreated(link(LISTEN_A), "op1", "alice")
		ledger.history(listOf(row("op2", VID_A, "seen relaying in mempool"), row("op1", VID_A, "confirmed in block")))
		val byListen = ledger.all().associateBy { it.listen }
		assertEquals(FinishedListen.State.CONFIRMED, byListen.getValue(LISTEN_A).state(now))
		assertEquals(FinishedListen.State.MEMPOOL, byListen.getValue(LISTEN_B).state(now))
	}

	@Test
	fun `a double listen is confirmed only when both of its own rows are`() {
		val ledger = FinishedListenLedger()
		ledger.finalized(finished(LISTEN_A))
		ledger.outcome(LISTEN_A, FinalizationOutcome.Eligible(listOf(payload(VID_A), payload(VID_A))), now)
		ledger.operationCreated(link(LISTEN_A, 0, 2), "op1", "alice")
		ledger.history(listOf(row("op1", VID_A, "confirmed in block")))
		assertEquals("Eligible · Sending to Hive", idle(ledger).status.label)
		ledger.operationCreated(link(LISTEN_A, 1, 2), "op2", "alice")
		ledger.history(listOf(row("op2", VID_A, "seen relaying in mempool"), row("op1", VID_A, "confirmed in block")))
		assertEquals("Sent · Awaiting block", idle(ledger).status.label)
		ledger.history(listOf(row("op2", VID_A, "confirmed in block"), row("op1", VID_A, "confirmed in block")))
		assertEquals("Scrobbled · Hive confirmed", idle(ledger).status.label)
	}

	@Test
	fun `an unlinked row never binds whatever video it names`() {
		val ledger = eligible(LISTEN_A, VID_A)
		ledger.history(listOf(row("op9", VID_A, "confirmed in block")))
		assertEquals("Eligible · Sending to Hive", idle(ledger).status.label)
	}

	@Test
	fun `a visible refusal is not logged with its reason`() {
		val ledger = FinishedListenLedger()
		ledger.finalized(finished(LISTEN_A))
		ledger.outcome(LISTEN_A, FinalizationOutcome.Refused("played 12% — below the 60% threshold"), now)
		assertEquals("Not logged · played 12%", idle(ledger).status.label)
		assertEquals(Tone.RED, idle(ledger).status.tone)
	}

	@Test
	fun `a refusal Not logged would hide is hidden here too`() {
		val ledger = FinishedListenLedger()
		ledger.finalized(finished(LISTEN_A).copy(refusalVisible = false))
		ledger.outcome(LISTEN_A, FinalizationOutcome.Refused("metadata blip"), now)
		assertEquals("Waiting for media", idle(ledger).headline)
	}

	@Test
	fun `an ignored listen is invisible`() {
		val ledger = FinishedListenLedger()
		ledger.finalized(finished(LISTEN_A))
		ledger.outcome(LISTEN_A, FinalizationOutcome.Ignored("auto-scrobble off"), now)
		assertEquals("Waiting for media", idle(ledger).headline)
	}

	@Test
	fun `only the first verdict counts`() {
		val ledger = eligible(LISTEN_A, VID_A)
		ledger.outcome(LISTEN_A, FinalizationOutcome.Refused("late"), now)
		assertEquals("Eligible · Sending to Hive", idle(ledger).status.label)
	}

	@Test
	fun `a verdict for a listen the card never saw is dropped`() {
		val ledger = FinishedListenLedger()
		ledger.outcome(LISTEN_A, FinalizationOutcome.Refused("x"), now)
		assertTrue(ledger.all().isEmpty())
	}

	@Test
	fun `a settled result leaves after its hold and a pending one stays longer`() {
		val ledger = eligible(LISTEN_A, VID_A)
		ledger.history(listOf(row("op1", VID_A, "confirmed in block")))
		val later = now + ScrobbleStatusPresenter.SETTLED_HOLD_MS + 1
		assertEquals("Waiting for media", idle(ledger, at = later).headline)

		val queued = eligible(LISTEN_B, VID_B)
		queued.history(listOf(row("op2", VID_B, "queued — offline", queued = true)))
		assertEquals("Queued · Will retry", idle(queued, at = later).status.label)
	}

	// ── late A→B ─────────────────────────────────────────────────────

	@Test
	fun `a late confirmation for A never touches B's card or artwork`() {
		val ledger = eligible(LISTEN_A, VID_A)
		val b = session(title = "Song B", token = 2)
		val live = listOf(LiveListen(b, 20_000, VID_B, 200_000))
		val before = present(live = live, finished = ledger.all())
		ledger.history(listOf(row("op1", VID_A, "confirmed in block")))
		val after = present(live = live, finished = ledger.all())

		assertEquals("Song B", after.headline)
		assertEquals(before.track, after.track)
		assertEquals(before.status, after.status)
		assertEquals(before.artwork, after.artwork)
		assertEquals(TrackInstanceId(PKG_MUSIC, 2), after.artwork!!.listen)
		assertEquals("Last: Song A · Scrobbled · Hive confirmed", after.footnote)
	}

	@Test
	fun `a fresh result outranks a stale paused session, which is footnoted`() {
		val ledger = eligible(LISTEN_A, VID_A)
		ledger.history(listOf(row("op1", VID_A, "confirmed in block")))
		val stale = session(title = "Old tab", token = 9, isPlaying = false)
		val view = present(live = listOf(LiveListen(stale, 1, VID_B, 200_000)), finished = ledger.all())
		assertEquals("Song A", view.headline)
		assertEquals("Scrobbled · Hive confirmed", view.status.label)
		assertEquals("Paused: Old tab", view.footnote)
		assertEquals(LISTEN_A, view.artwork!!.listen)
	}

	@Test
	fun `once the result has been held, the paused session is the card again`() {
		val ledger = eligible(LISTEN_A, VID_A)
		ledger.history(listOf(row("op1", VID_A, "confirmed in block")))
		val stale = session(title = "Old tab", token = 9, isPlaying = false)
		val later = now + ScrobbleStatusPresenter.SETTLED_HOLD_MS + 1
		val view = present(live = listOf(LiveListen(stale, 1, VID_B, 200_000)), finished = ledger.all(), at = later)
		assertEquals("Old tab", view.headline)
		assertEquals("Paused", view.status.label)
	}

	@Test
	fun `a listen still live is not footnoted under itself`() {
		val ledger = eligible(LISTEN_A, VID_A)
		val a = session(title = "Song A", token = 1)
		assertNull(present(live = listOf(LiveListen(a, 1, VID_A, 200_000)), finished = ledger.all()).footnote)
	}

	// ── account isolation ────────────────────────────────────────────

	@Test
	fun `another account's listen is never shown`() {
		val ledger = eligible(LISTEN_A, VID_A)
		ledger.history(listOf(row("op1", VID_A, "confirmed in block")))
		assertEquals("Waiting for media", idle(ledger, viewer = "bob").headline)
		assertEquals("Waiting for media", idle(ledger, viewer = null).headline)
		assertEquals("Scrobbled · Hive confirmed", idle(ledger, viewer = "ALICE").status.label)
	}

	@Test
	fun `a row stamped for another account than its operation is never evidence`() {
		val ledger = eligible(LISTEN_A, VID_A)
		ledger.history(listOf(row("op1", VID_A, "confirmed in block", account = "bob")))
		assertNoConfirmation(idle(ledger).status.label)
		assertTrue(ledger.all().single().rows.isEmpty())
	}

	@Test
	fun `an operation written for another account hides the listen`() {
		val ledger = eligible(LISTEN_A, VID_A, account = "bob")
		ledger.history(listOf(row("op1", VID_A, "confirmed in block", account = "bob")))
		assertEquals("Waiting for media", idle(ledger, viewer = "alice").headline)
		assertEquals("Waiting for media", idle(ledger, viewer = "bob").headline)
	}

	@Test
	fun `a signed-out refusal is seen only while signed out`() {
		val ledger = FinishedListenLedger()
		ledger.finalized(finished(LISTEN_A).copy(account = null))
		ledger.outcome(LISTEN_A, FinalizationOutcome.Refused("no proven identity"), now)
		assertEquals("Not logged · no proven identity", idle(ledger, viewer = null).status.label)
		assertEquals("Waiting for media", idle(ledger, viewer = "alice").headline)
	}

	// ── P1: Codex's four reproductions — association must be authoritative ──

	@Test
	fun `P1-1 B's confirmed row is never assigned to A`() {
		val ledger = FinishedListenLedger()
		ledger.finalized(finished(LISTEN_A, finishedAt = now - 2_000))
		ledger.finalized(finished(LISTEN_B, finishedAt = now - 1_000))
		ledger.outcome(LISTEN_A, FinalizationOutcome.Eligible(listOf(payload(VID_A))), now - 2_000)
		ledger.outcome(LISTEN_B, FinalizationOutcome.Eligible(listOf(payload(VID_A))), now - 1_000)
		ledger.dispatchEnded(link(LISTEN_A), "source was reset")
		ledger.operationCreated(link(LISTEN_B), "opB", "alice")
		ledger.history(listOf(row("opB", VID_A, "confirmed in block")))
		val byListen = ledger.all().associateBy { it.listen }
		assertEquals(FinishedListen.State.NOT_SENT, byListen.getValue(LISTEN_A).state(now))
		assertEquals(FinishedListen.State.CONFIRMED, byListen.getValue(LISTEN_B).state(now))
		assertTrue(byListen.getValue(LISTEN_A).rows.isEmpty())
	}

	@Test
	fun `P1-2 a listen that wrote no operation never claims a later confirmation`() {
		val ledger = FinishedListenLedger()
		ledger.finalized(finished(LISTEN_A, finishedAt = now - 300_000))
		ledger.outcome(LISTEN_A, FinalizationOutcome.Eligible(listOf(payload(VID_A))), now - 300_000)
		// A's dispatch never reported anything (e.g. its coroutine was cancelled).
		ledger.finalized(finished(LISTEN_B))
		ledger.outcome(LISTEN_B, FinalizationOutcome.Eligible(listOf(payload(VID_A))), now)
		ledger.operationCreated(link(LISTEN_B), "opB", "alice")
		ledger.history(listOf(row("opB", VID_A, "confirmed in block")))
		val a = ledger.all().first { it.listen == LISTEN_A }
		assertEquals(FinishedListen.State.UNVERIFIED, a.state(now))
		assertEquals("Unverified · See History", a.statusLine(now).label)
		assertTrue(a.rows.isEmpty())
	}

	@Test
	fun `P1-3 a multi-payload listen never claims another listen's row`() {
		val ledger = FinishedListenLedger()
		ledger.finalized(finished(LISTEN_A, finishedAt = now - 2_000))
		ledger.finalized(finished(LISTEN_B, finishedAt = now - 1_000))
		ledger.outcome(LISTEN_A, FinalizationOutcome.Eligible(listOf(payload(VID_A), payload(VID_A))), now - 2_000)
		ledger.outcome(LISTEN_B, FinalizationOutcome.Eligible(listOf(payload(VID_A))), now - 1_000)
		ledger.operationCreated(link(LISTEN_A, 0, 2), "opA1", "alice")
		ledger.operationCreated(link(LISTEN_B), "opB", "alice")
		// A's second payload claims B's operation: refused, one operation is one payload.
		ledger.operationCreated(link(LISTEN_A, 1, 2), "opB", "alice")
		ledger.history(listOf(row("opB", VID_A, "confirmed in block"), row("opA1", VID_A, "confirmed in block")))
		val byListen = ledger.all().associateBy { it.listen }
		assertEquals(FinishedListen.State.SENDING, byListen.getValue(LISTEN_A).state(now))
		assertEquals(setOf("opA1"), byListen.getValue(LISTEN_A).rows.keys)
		assertEquals(FinishedListen.State.CONFIRMED, byListen.getValue(LISTEN_B).state(now))
	}

	@Test
	fun `P1-4 an older queued operation never binds to a newer listen`() {
		val ledger = eligible(LISTEN_A, VID_A, op = null)
		ledger.history(listOf(row("opOld", VID_A, "queued — offline", queued = true)))
		ledger.history(listOf(row("opOld", VID_A, "confirmed in block")))
		assertEquals("Eligible · Sending to Hive", idle(ledger).status.label)
		ledger.operationCreated(link(LISTEN_A), "opA", "alice")
		ledger.history(listOf(row("opA", VID_A, "queued — offline", queued = true), row("opOld", VID_A, "confirmed in block")))
		assertEquals("Queued · Will retry", idle(ledger).status.label)
	}

	@Test
	fun `a row that arrived before its operation report still binds by id`() {
		val ledger = eligible(LISTEN_A, VID_A, op = null)
		ledger.history(listOf(row("op1", VID_A, "confirmed in block")))
		ledger.operationCreated(link(LISTEN_A), "op1", "alice")
		assertEquals("Scrobbled · Hive confirmed", idle(ledger).status.label)
	}

	@Test
	fun `a payload that never became an operation is not sent, not sending`() {
		val ledger = eligible(LISTEN_A, VID_A, op = null)
		ledger.dispatchEnded(link(LISTEN_A), "source was reset")
		val view = idle(ledger)
		assertEquals("Not sent · source was reset", view.status.label)
		assertEquals(Tone.RED, view.status.tone)
	}

	@Test
	fun `a half-sent double listen is not sent and never confirmed`() {
		val ledger = FinishedListenLedger()
		ledger.finalized(finished(LISTEN_A))
		ledger.outcome(LISTEN_A, FinalizationOutcome.Eligible(listOf(payload(VID_A), payload(VID_A))), now)
		ledger.operationCreated(link(LISTEN_A, 0, 2), "op1", "alice")
		ledger.dispatchEnded(link(LISTEN_A, 1, 2), "key unreadable")
		ledger.history(listOf(row("op1", VID_A, "confirmed in block")))
		assertEquals("Not sent · key unreadable", idle(ledger).status.label)
	}

	@Test
	fun `sending with no operation is not claimed forever`() {
		val ledger = eligible(LISTEN_A, VID_A, op = null)
		assertEquals("Eligible · Sending to Hive", idle(ledger).status.label)
		val later = now + FinishedListen.SEND_WAIT_MS + 1
		assertEquals("Unverified · See History", idle(ledger, at = later).status.label)
	}

	@Test
	fun `operation reports that do not fit the verdict are dropped`() {
		val ledger = eligible(LISTEN_A, VID_A, op = null)
		ledger.operationCreated(link(LISTEN_A, 0, 2), "wrongShape", "alice")
		ledger.operationCreated(link(LISTEN_A, 3, 1), "wrongIndex", "alice")
		ledger.operationCreated(link(LISTEN_B), "otherListen", "alice")
		assertTrue(ledger.all().single().links.isEmpty())
		ledger.operationCreated(link(LISTEN_A), "op1", "alice")
		ledger.operationCreated(link(LISTEN_A), "late", "alice")
		assertEquals("op1", ledger.all().single().links.getValue(0).operationId)
	}

	@Test
	fun `an operation report before the verdict is dropped`() {
		val ledger = FinishedListenLedger()
		ledger.finalized(finished(LISTEN_A))
		ledger.operationCreated(link(LISTEN_A), "op1", "alice")
		assertTrue(ledger.all().single().links.isEmpty())
	}

	// ── P2: ownership ────────────────────────────────────────────────

	@Test
	fun `a null-owner listen with any operation or row is shown to nobody`() {
		val ledger = FinishedListenLedger()
		ledger.finalized(finished(LISTEN_A).copy(account = null))
		ledger.outcome(LISTEN_A, FinalizationOutcome.Eligible(listOf(payload(VID_A))), now)
		ledger.operationCreated(link(LISTEN_A), "op1", "bob")
		ledger.history(listOf(row("op1", VID_A, "confirmed in block", account = "bob")))
		assertEquals("Waiting for media", idle(ledger, viewer = null).headline)
		assertEquals("Waiting for media", idle(ledger, viewer = "bob").headline)
		assertEquals("Waiting for media", idle(ledger, viewer = "alice").headline)
	}

	@Test
	fun `a listen whose owner was unknown at finalization is shown to nobody`() {
		val ledger = FinishedListenLedger()
		ledger.finalized(finished(LISTEN_A).copy(account = null, ownerKnown = false))
		ledger.outcome(LISTEN_A, FinalizationOutcome.Refused("played 10%"), now)
		assertEquals("Waiting for media", idle(ledger, viewer = null).headline)
		assertEquals("Waiting for media", idle(ledger, viewer = "alice").headline)
	}

	@Test
	fun `a key change makes the account unknown at once, before any read`() {
		val gate = AccountGate()
		gate.invalidate(1)
		assertTrue(gate.resolve(1, "alice"))
		assertEquals("alice", gate.account)
		gate.invalidate(2) // save or forget — no History involved
		assertFalse(gate.known)
		assertNull(gate.account)
	}

	@Test
	fun `a slow read from before a newer change is discarded`() {
		val gate = AccountGate()
		gate.invalidate(1) // A saved
		gate.invalidate(2) // B saved
		gate.invalidate(3) // forgotten
		assertFalse(gate.resolve(1, "alice"))
		assertFalse(gate.resolve(2, "bob"))
		assertFalse(gate.known)
		assertTrue(gate.resolve(3, null))
		assertTrue(gate.known)
		assertNull(gate.account)
	}

	@Test
	fun `forgetting the key hides the previous account's confirmed result`() {
		val ledger = eligible(LISTEN_A, VID_A)
		ledger.history(listOf(row("op1", VID_A, "confirmed in block")))
		val gate = AccountGate().apply { invalidate(1); resolve(1, "alice") }
		assertEquals("Scrobbled · Hive confirmed", idle(ledger, viewer = gate.account).status.label)
		gate.invalidate(2)
		// Unknown: the card gets no finished listens at all.
		val unknown = present(live = emptyList(), finished = if (gate.known) ledger.all() else emptyList(), viewer = gate.account)
		assertEquals("Waiting for media", unknown.headline)
		gate.resolve(2, null)
		assertEquals("Waiting for media", idle(ledger, viewer = gate.account).headline)
	}

	@Test
	fun `a late queued completion after an account switch stays hidden`() {
		val ledger = eligible(LISTEN_A, VID_A)
		ledger.history(listOf(row("op1", VID_A, "queued — offline", queued = true)))
		ledger.history(listOf(row("op1", VID_A, "sent from queue")))
		assertEquals("Waiting for media", idle(ledger, viewer = "bob").headline)
		assertEquals("Sent to Hive · Block not verified", idle(ledger, viewer = "alice").status.label)
	}

	@Test
	fun `the account signal only counts upward`() {
		val before = com.rustedwax.app.storage.HiveAccountChanges.generation.value
		com.rustedwax.app.storage.HiveAccountChanges.changed()
		assertEquals(before + 1, com.rustedwax.app.storage.HiveAccountChanges.generation.value)
	}

	// ── odds and ends ────────────────────────────────────────────────

	@Test
	fun `payload video ids are read from canonical URLs only`() {
		assertEquals(VID_A, FinishedListenLedger.payloadVideoId("https://www.youtube.com/watch?v=$VID_A"))
		assertEquals(VID_A, FinishedListenLedger.payloadVideoId("https://music.youtube.com/watch?v=$VID_A&list=x"))
		assertEquals(VID_A, FinishedListenLedger.payloadVideoId("https://youtu.be/$VID_A"))
		assertNull(FinishedListenLedger.payloadVideoId("https://www.youtube.com/watch?v=${VID_A}x"))
		assertNull(FinishedListenLedger.payloadVideoId(null))
	}

	@Test
	fun `the collapsed line drops its detail rather than clip`() {
		assertEquals("Listening · Scrobbles at 60%", StatusLine("Listening", "Scrobbles at 60%", Tone.PINK).compact)
		assertEquals("Threshold reached", StatusLine("Threshold reached", "Checks at end", Tone.PINK).compact)
		assertEquals("Paused", StatusLine("Paused", null, Tone.NEUTRAL).compact)
	}

	@Test
	fun `the status channel is its own, apart from Snap replies`() {
		assertNotEquals(AndroidSnapNoticeNotifier.CHANNEL, ScrobbleStatusNotification.CHANNEL)
	}

	@Test
	fun `the ledger is bounded`() {
		val ledger = FinishedListenLedger(capacity = 3)
		repeat(5) { ledger.finalized(finished(TrackInstanceId(PKG_MUSIC, it + 1L))) }
		assertEquals(listOf(3L, 4L, 5L), ledger.all().map { it.listen.instanceToken })
	}

	// ── helpers ──────────────────────────────────────────────────────

	private fun assertNoClaim(label: String) {
		assertFalse(label, label.contains("Ready", ignoreCase = true))
		assertNoConfirmation(label)
	}

	private fun assertNoConfirmation(label: String) {
		assertFalse(label, label.contains("confirmed", ignoreCase = true))
	}

	private fun present(
		live: List<LiveListen>,
		finished: List<FinishedListen> = emptyList(),
		viewer: String? = "alice",
		thumbnails: Boolean = true,
		at: Long = now,
	) = ScrobbleStatusPresenter.present(
		live = live,
		finished = finished,
		account = viewer,
		thresholdPercent = 60,
		autoScrobble = true,
		thumbnailsAllowed = thumbnails,
		nowMs = at,
	)

	private fun idle(ledger: FinishedListenLedger, viewer: String? = "alice", at: Long = now) =
		present(live = emptyList(), finished = ledger.all(), viewer = viewer, at = at)

	/** Eligible with one payload whose operation [op] was written for [account]; null: none yet. */
	private fun eligible(
		listen: TrackInstanceId,
		videoId: String,
		op: String? = if (listen == LISTEN_A) "op1" else "op2",
		account: String = "alice",
	) = FinishedListenLedger().also {
		it.finalized(finished(listen))
		it.outcome(listen, FinalizationOutcome.Eligible(listOf(payload(videoId))), now)
		if (op != null) it.operationCreated(link(listen), op, account)
	}

	private fun link(listen: TrackInstanceId, index: Int = 0, count: Int = 1) = DispatchLink(listen, index, count)

	private fun finished(listen: TrackInstanceId, finishedAt: Long = now) = FinishedListen(
		listen = listen,
		title = if (listen == LISTEN_A) "Song A" else "Song B",
		artist = "Boza",
		source = "Brave · YouTube",
		creditedMs = 150_000,
		durationMs = 200_000,
		account = "alice",
		finishedAtMs = finishedAt,
	)

	private fun payload(videoId: String) = HiveScrobblePayload(
		title = "t",
		timestamp = "2026-10-11T00:00:00Z",
		url = "https://www.youtube.com/watch?v=$videoId",
	)

	private fun row(
		op: String,
		videoId: String,
		status: String,
		queued: Boolean = false,
		txId: String? = null,
		account: String = "alice",
	) = ScrobbleRecord(
		title = "t",
		artist = null,
		percentPlayed = 75,
		atEpochSec = 1,
		status = status,
		txId = txId,
		queued = queued,
		videoId = videoId,
		eventId = "event-$op",
		account = account,
		queueOperationId = op,
	)

	private fun session(
		title: String? = "Song",
		pkg: String = PKG_MUSIC,
		token: Long = 1,
		durationMs: Long? = 200_000,
		playedMs: Long = 0,
		positionMs: Long = 0,
		isPlaying: Boolean = true,
		startedAt: Long = 1_700_000_000,
	) = SessionSnapshot(
		packageName = pkg,
		appLabel = "app",
		isTarget = true,
		title = title,
		artist = "Boza",
		album = null,
		durationMs = durationMs,
		positionMs = positionMs,
		playedMs = playedMs,
		loopDetected = false,
		playbackState = if (isPlaying) "PLAYING" else "PAUSED",
		isPlaying = isPlaying,
		percentPlayed = durationMs?.let { playedMs.toDouble() / it },
		identity = YouTubeProbe.Identity.SiteOnly(host = "youtube.com", isMusic = false, source = "test"),
		resolverContext = ResolverContext(),
		notificationHint = null,
		metadataLines = emptyList(),
		trackStartedAtEpochSec = startedAt,
		trackInstanceToken = token,
	)

	private companion object {
		const val PKG_MUSIC = YouTubeProbe.YOUTUBE_MUSIC_PACKAGE
		const val VID_A = "AAAAAAAAAAA"
		const val VID_B = "BBBBBBBBBBB"
		val LISTEN_A = TrackInstanceId(PKG_MUSIC, 1)
		val LISTEN_B = TrackInstanceId(PKG_MUSIC, 2)
	}
}
