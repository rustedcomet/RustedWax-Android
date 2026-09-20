package com.rustedwax.app.ui.snaps

import com.rustedwax.app.snaps.SnapReplyTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What a tapped notification is allowed to open.
 *
 * Everything tested here arrived from outside the process. A notification sits
 * in the system shade for as long as the user leaves it there — across a
 * sign-out, an account switch, a reinstall, a version change — and the
 * `PendingIntent` behind it can be replayed by anything on the device holding
 * it. So the extras are treated exactly like a chain response: re-validated on
 * the way in, and refused rather than repaired.
 *
 * The rule that matters most is the one that looks like a formality. A
 * notification raised for Alice and tapped after Bob signs in must open
 * **nothing**: opening it anyway would put one account's conversation inside
 * another's session, which is the single thing the account scoping through this
 * whole stack exists to prevent.
 */
class SnapNoticeIntentTest {

	private val alice = "alice"
	private val permlink = "rustedwax-snap-1000-aaaaaa"

	@Test
	fun `a tap by the account it was raised for opens that conversation`() {
		val target = SnapNoticeIntent.target(
			signedIn = alice,
			account = alice,
			rootAuthor = alice,
			rootPermlink = permlink,
		)

		assertEquals(SnapReplyTarget.of(alice, permlink), target)
	}

	@Test
	fun `a tap after an account switch opens nothing`() {
		assertNull(
			"a stale notification is the ordinary case, not the exception",
			SnapNoticeIntent.target(
				signedIn = "bob",
				account = alice,
				rootAuthor = alice,
				rootPermlink = permlink,
			),
		)
	}

	@Test
	fun `a tap with nobody signed in opens nothing`() {
		assertNull(
			SnapNoticeIntent.target(
				signedIn = null,
				account = alice,
				rootAuthor = alice,
				rootPermlink = permlink,
			),
		)
		assertNull(
			SnapNoticeIntent.target(
				signedIn = "   ",
				account = alice,
				rootAuthor = alice,
				rootPermlink = permlink,
			),
		)
	}

	@Test
	fun `an intent carrying no account opens nothing`() {
		assertNull(
			"an unsigned tap must not be treated as one for whoever happens to " +
				"be signed in",
			SnapNoticeIntent.target(
				signedIn = alice,
				account = null,
				rootAuthor = alice,
				rootPermlink = permlink,
			),
		)
	}

	@Test
	fun `a malformed conversation opens nothing`() {
		// A slash would split wrong in a store key and wrong again in a content
		// id; an empty permlink names nothing at all.
		listOf("has/slash", "", "UPPER", "has space", "has|bar").forEach {
			assertNull(
				"a permlink of `$it` must be refused rather than repaired",
				SnapNoticeIntent.target(alice, alice, alice, it),
			)
		}
		listOf("Not An Account", "", "a").forEach {
			assertNull(
				"an author of `$it` must be refused",
				SnapNoticeIntent.target(alice, alice, it, permlink),
			)
		}
		assertNull(SnapNoticeIntent.target(alice, alice, null, permlink))
		assertNull(SnapNoticeIntent.target(alice, alice, alice, null))
	}

	@Test
	fun `an account that is not a Hive account name opens nothing`() {
		// Both sides agree, and both are nonsense — agreement is not validity.
		assertNull(
			SnapNoticeIntent.target("Not An Account", "Not An Account", alice, permlink),
		)
	}

	// ── PendingIntent identity ─────────────────────────────────────────

	/**
	 * Two ordinary Hive ids that collide in 32 bits.
	 *
	 * Both are valid permlinks under `bob`, and `String.hashCode` maps them to
	 * the same `Int`. That is the whole hazard: `PendingIntent` matching ignores
	 * extras, so when the request code was the only thing separating two
	 * notifications, `FLAG_UPDATE_CURRENT` let the second one rewrite the
	 * first's target.
	 */
	private val collidingA = "bob/tr9apgbmtu"
	private val collidingB = "bob/wgcmnbuajy"

	@Test
	fun `the collision pair really does collide`() {
		assertEquals(
			"if this ever stops being true the regression below is testing nothing",
			SnapNoticeIntent.idOf(collidingA),
			SnapNoticeIntent.idOf(collidingB),
		)
		assertNotEquals(collidingA, collidingB)
	}

	@Test
	fun `colliding replies get distinct PendingIntent identities`() {
		val a = SnapNoticeIntent.dataUriOf(alice, collidingA, "$alice/$permlink")
		val b = SnapNoticeIntent.dataUriOf(alice, collidingB, "$alice/$permlink")

		assertNotEquals(
			"a hash is a bucket; the data URI is the identity spelled out, and " +
				"it is the field Android actually matches on",
			a,
			b,
		)
	}

	@Test
	fun `the same reply under two roots is two identities`() {
		val a = SnapNoticeIntent.dataUriOf(alice, collidingA, "$alice/$permlink")
		val b = SnapNoticeIntent.dataUriOf(alice, collidingA, "$alice/rustedwax-snap-2000-bbbbbb")

		assertNotEquals(
			"the root is where the tap lands, so it has to be part of what makes " +
				"one request different from another",
			a,
			b,
		)
	}

	@Test
	fun `the same reply under two accounts is two identities`() {
		assertNotEquals(
			SnapNoticeIntent.dataUriOf(alice, collidingA, "$alice/$permlink"),
			SnapNoticeIntent.dataUriOf("carol", collidingA, "$alice/$permlink"),
		)
	}

	@Test
	fun `one notice always produces the same identity`() {
		assertEquals(
			"a re-post of the same reply must replace its own notification, not " +
				"open a second slot",
			SnapNoticeIntent.dataUriOf(alice, collidingA, "$alice/$permlink"),
			SnapNoticeIntent.dataUriOf(alice, collidingA, "$alice/$permlink"),
		)
	}

	@Test
	fun `the identity is a single unambiguous URI`() {
		val uri = SnapNoticeIntent.dataUriOf(alice, "bob/r1", "$alice/$permlink")

		assertEquals("rustedwax://notice/alice/bob/r1/alice/$permlink", uri)
		// Every segment is validated before it reaches here, so none of them can
		// end the path early or start a query. Asserted rather than assumed,
		// because the day one of those validators loosens is the day this stops
		// being true quietly.
		listOf("?", "#", " ", "\\").forEach {
			assertFalse("a segment must never introduce `$it`", uri.contains(it))
		}
	}

	@Test
	fun `a notification identity is stable and per reply`() {
		assertEquals(
			"the same reply must occupy one slot in the shade, not stack",
			SnapNoticeIntent.idOf("bob/r1"),
			SnapNoticeIntent.idOf("bob/r1"),
		)
		assertEquals("alice|bob/r1", SnapNoticeIntent.tagOf(alice, "bob/r1"))
		assertEquals(
			"and two accounts' notifications about one reply are different " +
				"notifications",
			2,
			setOf(
				SnapNoticeIntent.tagOf(alice, "bob/r1"),
				SnapNoticeIntent.tagOf("carol", "bob/r1"),
			).size,
		)
	}
}
