package com.rustedwax.app.ui.snaps

import com.rustedwax.app.snaps.SnapReplyKey
import com.rustedwax.app.snaps.SnapReplyTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the bell says, and where each line of it leads.
 *
 * The three unresolved-publication categories are read straight out of the
 * `needsAttention()` seams, so what is worth pinning here is not *whether* an
 * unfinished Snap is unfinished — its own controller's tests decide that — but
 * that the bell names it correctly, sends the user to the one place they can
 * resolve it, and never offers to send anything again.
 */
class SnapAttentionModelTest {

	private val alice = "alice"
	private val root = SnapReplyTarget.of(alice, "rustedwax-snap-1000-aaaaaa")!!
	private val nested = SnapReplyTarget.of("bob", "bobs-comment")!!

	private fun rows(
		notices: List<SnapNotice> = emptyList(),
		rootAttention: List<SnapAttention> = emptyList(),
		replyAttention: List<SnapAttention> = emptyList(),
		likeAttention: List<Pair<SnapReplyTarget, SnapLikeState>> = emptyList(),
	) = SnapAttentionModel.of(notices, rootAttention, replyAttention, likeAttention)

	private fun notice(permlink: String) = SnapNotice(
		author = "bob",
		permlink = permlink,
		rootAuthor = root.author,
		rootPermlink = root.permlink,
		text = "nice one",
		createdAtEpochSec = 1_000L,
	)

	private fun rootRow(status: SnapPostStatus, eventId: String = "event-1") =
		SnapAttention(SnapAttention.Kind.ROOT, "$alice|$eventId", status)

	private fun replyRow(status: SnapPostStatus, target: SnapReplyTarget = root) =
		SnapAttention(
			SnapAttention.Kind.REPLY,
			"$alice|${SnapReplyKey.slot(target)}",
			status,
		)

	// ── the four categories ────────────────────────────────────────────

	@Test
	fun `an unfinished root Snap becomes a row pointing at its History card`() {
		val row = rows(rootAttention = listOf(rootRow(SnapPostStatus.Interrupted("a/b")))).single()

		assertEquals(SnapAttentionKind.ROOT_SNAP, row.kind)
		assertEquals("Snap didn't finish posting", row.title)
		assertEquals(SnapAttentionTarget.HistoryEvent("event-1"), row.target)
	}

	@Test
	fun `a failed root Snap carries the reason the card shows`() {
		val row = rows(rootAttention = listOf(rootRow(SnapPostStatus.Failed("no signal")))).single()

		assertEquals("Snap didn't post", row.title)
		assertEquals("no signal", row.detail)
	}

	@Test
	fun `an unfinished reply becomes a row pointing at its conversation`() {
		val row = rows(replyAttention = listOf(replyRow(SnapPostStatus.Failed("no signal"))))
			.single()

		assertEquals(SnapAttentionKind.REPLY, row.kind)
		assertEquals("Reply didn't post", row.title)
		assertEquals(
			"a reply to a Snap names that Snap, which is the conversation itself",
			SnapAttentionTarget.Comment(root),
			row.target,
		)
	}

	@Test
	fun `a pending Like becomes a row pointing at its conversation`() {
		val row = rows(
			likeAttention = listOf(nested to SnapLikeState.Pending("couldn't confirm")),
		).single()

		assertEquals(SnapAttentionKind.LIKE, row.kind)
		assertEquals("Like not confirmed", row.title)
		assertEquals("couldn't confirm", row.detail)
		assertEquals(SnapAttentionTarget.Comment(nested), row.target)
	}

	@Test
	fun `a refused Like is named as a refusal rather than as a maybe`() {
		val row = rows(
			likeAttention = listOf(nested to SnapLikeState.Refused("already voted")),
		).single()

		assertEquals("Like didn't go through", row.title)
	}

	@Test
	fun `an incoming reply becomes a row pointing at its conversation`() {
		val row = rows(notices = listOf(notice("r1"))).single()

		assertEquals(SnapAttentionKind.INCOMING_REPLY, row.kind)
		assertEquals("@bob replied", row.title)
		assertEquals("nice one", row.detail)
		assertEquals(SnapAttentionTarget.Thread(root), row.target)
	}

	// ── navigating to the right place ──────────────────────────────────

	/**
	 * A row names the comment, and the conversation is resolved when it is
	 * tapped.
	 *
	 * Deliberately not resolved here. The conversations this process has read
	 * are not snapshot state, so answering during composition would read
	 * something Compose is not watching — and the tap is the later, better
	 * informed moment anyway. See the handler in `MainScreen`, which is pinned
	 * by [com.rustedwax.app.architecture.SnapAttentionWiringTest].
	 */
	@Test
	fun `a reply deeper in a thread names the comment it answers`() {
		val row = rows(
			replyAttention = listOf(replyRow(SnapPostStatus.Failed("no signal"), nested)),
		).single()

		assertEquals(SnapAttentionTarget.Comment(nested), row.target)
	}

	@Test
	fun `a notice whose stored root is unusable has nowhere to go and says so`() {
		val broken = notice("r1").copy(rootPermlink = "has/slash")

		val row = rows(notices = listOf(broken)).single()

		assertNull(
			"a row that cannot name a conversation must be inert rather than " +
				"open something plausible",
			row.target,
		)
	}

	@Test
	fun `a reply row whose slot cannot be read is dropped entirely`() {
		val row = rows(
			replyAttention = listOf(
				SnapAttention(SnapAttention.Kind.REPLY, "$alice|not-a-slot", SnapPostStatus.Failed("x")),
			),
		)

		assertEquals(emptyList<SnapAttentionRow>(), row)
	}

	// ── what the bell must never say ───────────────────────────────────

	/**
	 * The one wording rule with a transaction behind it.
	 *
	 * An unknown outcome is resolved by a read and never by another broadcast —
	 * posting again could duplicate a comment that is already live — so the row
	 * must not invite one. It carries the same message the card carries and
	 * nothing that reads as "try again".
	 */
	@Test
	fun `an uncertain row never invites another send`() {
		val uncertain = rows(
			rootAttention = listOf(rootRow(SnapPostStatus.Uncertain("it may have posted"))),
			replyAttention = listOf(replyRow(SnapPostStatus.Uncertain("it may have posted"))),
		)

		assertEquals(
			listOf("Snap may not have posted", "Reply may not have posted"),
			uncertain.map { it.title },
		)
		uncertain.forEach {
			val text = "${it.title} ${it.detail}".lowercase()
			assertFalse(
				"an uncertain row must not suggest retrying: ${it.title} / ${it.detail}",
				listOf("retry", "try again", "post again", "send again").any(text::contains),
			)
		}
	}

	// ── ordering and bounds ────────────────────────────────────────────

	@Test
	fun `work only the user can finish sorts above conversation`() {
		val ordered = rows(
			notices = listOf(notice("r1")),
			rootAttention = listOf(rootRow(SnapPostStatus.Interrupted("a/b"))),
			replyAttention = listOf(replyRow(SnapPostStatus.Failed("no signal"))),
			likeAttention = listOf(nested to SnapLikeState.Pending("unknown")),
		)

		assertEquals(
			listOf(
				SnapAttentionKind.ROOT_SNAP,
				SnapAttentionKind.REPLY,
				SnapAttentionKind.LIKE,
				SnapAttentionKind.INCOMING_REPLY,
			),
			ordered.map { it.kind },
		)
	}

	@Test
	fun `row ids are stable and distinct so a rebuild does not churn`() {
		val build = {
			rows(
				notices = listOf(notice("r1"), notice("r2")),
				rootAttention = listOf(rootRow(SnapPostStatus.Failed("x"), "event-1")),
				replyAttention = listOf(replyRow(SnapPostStatus.Failed("x"))),
				likeAttention = listOf(nested to SnapLikeState.Pending("x")),
			).map { it.id }
		}

		val ids = build()
		assertEquals("no two rows may share an id", ids.size, ids.toSet().size)
		assertEquals("and the same state builds the same ids", ids, build())
	}

	@Test
	fun `an empty bell is empty rather than a row saying nothing is wrong`() {
		assertEquals(emptyList<SnapAttentionRow>(), rows())
	}

	@Test
	fun `a notice with no text still draws a readable row`() {
		// A deleted comment arrives with an empty body. It is still a reply, and
		// still worth knowing about.
		val row = rows(notices = listOf(notice("r1").copy(text = ""))).single()

		assertTrue(row.detail.isNotBlank())
	}
}
