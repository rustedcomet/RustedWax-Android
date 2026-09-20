package com.rustedwax.app.snaps

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural proof that Snap writes and scrobble writes stay apart.
 *
 * Asserted against the source rather than behaviour because the property is an
 * *absence*: no test can observe a Snap failing to enter the retry queue, but a
 * test can observe that the code which would put it there does not exist. A
 * scrobble may be rebuilt and resent at will; a Snap rebuilt and resent is a
 * duplicate permanent comment, so the two must never share a path.
 */
class SnapQueueIsolationTest {

	private val root: File = generateSequence(
		File(checkNotNull(System.getProperty("user.dir"))).absoluteFile,
	) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }

	private fun text(path: String) = File(root, path).readText()

	/**
	 * Source with comments removed.
	 *
	 * The bans below are on what the code *does*, not on what it is allowed to
	 * explain. The prose in these files names the scrobble queue precisely
	 * because staying out of it is the point, and a check that could not tell
	 * those apart would push the explanation out of the code to stay green.
	 */
	private fun code(source: String): String = source
		.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
		.lines().joinToString("\n") { it.substringBefore("//") }

	private val snapSources: List<Pair<String, String>> =
		File(root, "app/src/main/java/com/rustedwax/app/snaps").walkTopDown()
			.filter { it.isFile && it.extension == "kt" }
			.map { it.name to it.readText() }
			.toList()

	@Test
	fun `the snap package exists and was scanned`() {
		assertTrue(snapSources.map { it.first }.containsAll(
			listOf("SnapPayload.kt", "SnapPublisher.kt", "PendingSnap.kt"),
		))
	}

	/** Nothing in the Snap path may reach the automatic scrobble machinery. */
	@Test
	fun `snap publication never touches the scrobble queue`() {
		val forbidden = listOf(
			"BroadcastQueue",
			"PayloadBroadcaster",
			"FinalizationRuntime",
			"broadcastJson",
			"prepareJson",
			"HiveScrobblePayload.CUSTOM_JSON_ID",
		)
		snapSources.forEach { (name, src) ->
			forbidden.forEach { symbol ->
				assertTrue(
					"$name must not reference $symbol",
					!code(src).contains(symbol),
				)
			}
		}
	}

	/**
	 * Production posting goes prepare → persist → broadcastPrepared.
	 * `broadcastComment` cannot persist anything before it sends, so it must not
	 * appear on this path at all.
	 */
	@Test
	fun `production posting does not use the one-shot broadcast helper`() {
		val publisher = text("app/src/main/java/com/rustedwax/app/snaps/SnapPublisher.kt")
		assertTrue("must use prepareComment", publisher.contains("prepareComment"))
		assertTrue("must use broadcastPrepared", publisher.contains("broadcastPrepared"))
		assertTrue("must not use broadcastComment", !code(publisher).contains("broadcastComment("))
	}

	/** The Stage 2A doc wording that pointed at the wrong call is corrected. */
	@Test
	fun `the broadcaster documents the persisted path correctly`() {
		val broadcaster = text("hive/src/main/kotlin/com/rustedwax/hive/HiveBroadcaster.kt")
		val doc = broadcaster.substringAfter("fun prepareComment").let {
			broadcaster.substringBefore("fun prepareComment")
		}
		assertTrue(
			"prepareComment's contract must name broadcastPrepared",
			doc.contains("[broadcastPrepared], so an ambiguous result"),
		)
	}

	/** Conversely, the scrobble engine must not have grown a Snap dependency. */
	@Test
	fun `the scrobble engine knows nothing about Snaps`() {
		listOf(
			"app/src/main/java/com/rustedwax/app/scrobble/FinalizationRuntime.kt",
			"app/src/main/java/com/rustedwax/app/scrobble/EnginePorts.kt",
			"app/src/main/java/com/rustedwax/app/scrobble/BroadcastQueue.kt",
		).forEach { path ->
			val src = text(path)
			listOf(
				"SnapPublisher", "PendingSnap", "SnapPayload", "CommentOp", "VoteOp",
				"SnapLikePort", "SnapLikeDecisions", "HiveVotes",
			)
				.forEach { symbol ->
					assertTrue("$path must not reference $symbol", !code(src).contains(symbol))
				}
		}
	}

	/** No private key material may be written into a pending record. */
	@Test
	fun `the pending record stores no key material`() {
		val src = text("app/src/main/java/com/rustedwax/app/snaps/PendingSnap.kt")
		listOf("wif", "WIF", "privateKey", "loadKey", "HiveKey").forEach {
			assertTrue("PendingSnap must not mention $it", !code(src).contains(it))
		}
	}

	/** The container is resolved live; no fixture permlink is baked in. */
	@Test
	fun `no snap container permlink is hardcoded anywhere`() {
		val all = (
			snapSources.map { it.second } +
				File(root, "hive/src/main/kotlin").walkTopDown()
					.filter { it.isFile && it.extension == "kt" }.map { it.readText() }
			)
		val offenders = all.filter { Regex("""snap-container-\d""").containsMatchIn(it) }
		assertEquals("a fixture container leaked into production code", emptyList<String>(), offenders)
	}

	// ── fixture sanitation ─────────────────────────────────────────────

	/**
	 * No complete WIF-shaped private key may appear in anything Stage 2B adds.
	 *
	 * A 51-character `5…` literal is indistinguishable at a glance from a real
	 * posting key. Tests that need to sign build their key at runtime from a
	 * published scalar instead, so nothing in the candidate can be mistaken for,
	 * or lifted as, an account credential.
	 */
	@Test
	fun `stage 2B sources contain no WIF-shaped private key`() {
		val wif = Regex("""["']5[HJK][1-9A-HJ-NP-Za-km-z]{20,}""")
		val candidate = listOf(
			"app/src/main/java/com/rustedwax/app/snaps",
			"app/src/test/java/com/rustedwax/app/snaps",
			"app/src/test/java/com/rustedwax/app/ui/snaps",
			"app/src/main/java/com/rustedwax/app/ui/snaps",
			"hive/src/main/kotlin/com/rustedwax/hive/SnapContainer.kt",
			"hive/src/test/kotlin/com/rustedwax/hive/HiveSnapVectorsTest.kt",
			"hive/src/test/kotlin/com/rustedwax/hive/SnapTestKey.kt",
			"hive/src/test/kotlin/com/rustedwax/hive/SnapContainerResolverTest.kt",
		)
		val offenders = candidate.flatMap { path ->
			val file = File(root, path)
			val files = if (file.isDirectory) {
				file.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
			} else {
				listOf(file).filter { it.isFile }
			}
			files.filter { wif.containsMatchIn(it.readText()) }.map { it.name }
		}
		assertEquals("WIF-shaped literal in the Stage 2B candidate", emptyList<String>(), offenders)
	}

	/** The signing key for the vectors is derived, not written down. */
	@Test
	fun `the vector test derives its key at runtime`() {
		val helper = text("hive/src/test/kotlin/com/rustedwax/hive/SnapTestKey.kt")
		assertTrue(helper.contains("fromScalar"))
		assertTrue(
			"the vector test must use the derived key",
			text("hive/src/test/kotlin/com/rustedwax/hive/HiveSnapVectorsTest.kt")
				.contains("SnapTestKey.key"),
		)
	}

	// ── the cancelled association work is gone ─────────────────────────

	/**
	 * History membership is the eligibility gate. Nothing on the Snap path may
	 * re-derive artist, title or media kind, or refuse a row for lacking them.
	 */
	@Test
	fun `the snap path re-derives no media classification`() {
		snapSources.forEach { (name, src) ->
			listOf("zingit", "timed_comment", "artist", "isrc").forEach { symbol ->
				assertTrue(
					"$name must not reference $symbol",
					!code(src).contains(symbol),
				)
			}
		}
		val screen = text("app/src/main/java/com/rustedwax/app/ui/MainScreen.kt")
		assertTrue(
			"the card must pass only the video id",
			code(screen).contains("SnapMedia(videoId = record.videoId)"),
		)
	}

	// ── Likes stay out of the queue too ────────────────────────────────

	/**
	 * A Like is a Hive vote: user-triggered, irreversible, and never rebuilt by
	 * anything automatic. The same structural absence the comment path is held
	 * to, asserted for the same reason — no test can watch a Like fail to enter
	 * the retry queue, but a test can watch the code that would put it there not
	 * exist.
	 *
	 * The Like controller lives outside this package, so it is named explicitly
	 * rather than picked up by the `snaps/` sweep above.
	 */
	@Test
	fun `the like path never touches the scrobble queue`() {
		val forbidden = listOf(
			"BroadcastQueue",
			"PayloadBroadcaster",
			"FinalizationRuntime",
			"broadcastJson",
			"prepareJson",
			"HiveScrobblePayload.CUSTOM_JSON_ID",
		)
		likeSources().forEach { (name, src) ->
			forbidden.forEach { symbol ->
				assertTrue("$name must not reference $symbol", !code(src).contains(symbol))
			}
		}
	}

	/** Nothing retries a Like by itself: no timer, no backoff, no scheduled work. */
	@Test
	fun `nothing in the like path can retry on its own`() {
		val forbidden = listOf(
			"WorkManager",
			"AlarmManager",
			"Handler(",
			"postDelayed",
			"scheduleAtFixedRate",
			"kotlinx.coroutines.delay",
			"repeat(",
		)
		likeSources().forEach { (name, src) ->
			forbidden.forEach { symbol ->
				assertTrue("$name must not reference $symbol", !code(src).contains(symbol))
			}
		}
	}

	/**
	 * Production casting goes prepare → broadcastPrepared. `broadcastVote` is
	 * the one-shot helper that cannot tell a refusal made before signing from
	 * one that came back over the wire, so it must not appear on this path.
	 */
	@Test
	fun `production liking does not use the one-shot broadcast helper`() {
		val port = text("app/src/main/java/com/rustedwax/app/snaps/SnapLikes.kt")
		assertTrue("must use prepareVote", port.contains("prepareVote"))
		assertTrue("must use broadcastPrepared", port.contains("broadcastPrepared"))
		assertTrue("must not use broadcastVote", !code(port).contains("broadcastVote("))
	}

	/**
	 * Signing and sending are separate calls on the port, and must stay that
	 * way.
	 *
	 * A single `cast` that prepared and broadcast in one step cannot be
	 * interrupted, and the second safety read plus the last account check both
	 * live in exactly that gap. Collapsing them back together would remove the
	 * gap without removing a single test that looks at outcomes.
	 */
	@Test
	fun `the like port keeps signing and sending apart`() {
		val port = code(text("app/src/main/java/com/rustedwax/app/snaps/SnapLikes.kt"))
		assertTrue("must expose a prepare step", port.contains("fun prepare("))
		assertTrue("must expose a separate broadcast step", port.contains("fun broadcast("))
		assertTrue(
			"there must be no combined cast step",
			!port.contains("fun cast("),
		)
	}

	/**
	 * The signing boundary checks the account itself, against the vault.
	 *
	 * One Hive posting key can sit in more than one account's posting authority,
	 * so a stale `voter` signed after a switch is not necessarily rejected by
	 * the chain. The caller's account checks cannot see that; this one can.
	 */
	@Test
	fun `the signing boundary verifies the account beside the key`() {
		val port = code(text("app/src/main/java/com/rustedwax/app/snaps/SnapLikes.kt"))
		assertTrue("the port must read the stored account", port.contains("storedAccount()"))
		assertTrue(
			"and must compare it with the voter the vote names",
			port.contains("stored.equals(voter, ignoreCase = true)"),
		)
	}

	/**
	 * The safety read must not be quietly rebuilt on the unchecked read path.
	 *
	 * `HiveRpc.call`/`callAny` walk the node list and return the first answer
	 * they get with no freshness check at all, which is right for a comment body
	 * and wrong for the answer a vote is authorized by.
	 */
	@Test
	fun `the like path reads votes only through the freshness-checked call`() {
		val port = code(text("app/src/main/java/com/rustedwax/app/snaps/SnapLikes.kt"))
		assertTrue("must use findViewerVote", port.contains("findViewerVote"))
		listOf("getContent", "getDiscussion", "condenser_api", "database_api").forEach {
			assertTrue("the like port must not read votes via $it", !port.contains(it))
		}
	}

	/**
	 * The safety read is on the current call, and the obsolete one is gone.
	 *
	 * `database_api.find_votes` was what an earlier revision used. Pinned as an
	 * absence so it cannot drift back in beside the current call and quietly
	 * become the one that answers.
	 */
	@Test
	fun `the safety read uses get_active_votes and not find_votes`() {
		val rpc = text("hive/src/main/kotlin/com/rustedwax/hive/HiveRpc.kt")
		assertTrue(
			"must call condenser_api.get_active_votes",
			rpc.contains("\"condenser_api.get_active_votes\""),
		)
		assertTrue("find_votes is obsolete", !rpc.contains("database_api.find_votes"))
	}

	/**
	 * The vote parser reads no chain value through a coercing accessor.
	 *
	 * `optString` turns `42` into `"42"` and `true` into `"true"`; `optInt` and
	 * `optLong` truncate a fractional value and answer `0` for anything they
	 * cannot read at all. `0` is the percent that classifies as
	 * eligible-to-Like, so a coerced read here is not a parsing nicety, it is a
	 * write over somebody's existing vote.
	 *
	 * The narrowing conversions that remain are each fenced by an explicit
	 * exactness check, asserted below rather than banned by spelling — a blunt
	 * ban on `toInt()` would also forbid the range guard that makes it safe.
	 */
	@Test
	fun `the vote parser reads nothing through a coercing accessor`() {
		val votes = code(text("hive/src/main/kotlin/com/rustedwax/hive/HiveVotes.kt"))
		listOf("optInt(", "optLong(", "optDouble(", "optString(", "optBoolean(")
			.forEach { assertTrue("HiveVotes must not use $it", !votes.contains(it)) }
	}

	/**
	 * The vote parser accepts no decimal type, and narrows nothing unguarded.
	 *
	 * Replaces an earlier guard that required the *opposite* — a `% 1.0` check
	 * and a 2^53 bound, both of which existed to accept "whole-looking"
	 * `Double` values. That was the hole: Android boxes every decimal token as
	 * `Double`, so by the time those checks ran the value could already have
	 * been rounded to a different integer, and each of them would have agreed
	 * it was whole. The only safe rule is to refuse the type outright, which is
	 * what this now pins.
	 */
	@Test
	fun `the vote parser accepts no decimal type and narrows nothing unguarded`() {
		val votes = code(text("hive/src/main/kotlin/com/rustedwax/hive/HiveVotes.kt"))
		val reader = votes.substringAfter("fun JSONObject.integer(").substringBefore("\n\t}")

		// No decimal type may be admitted, and no rounding test may stand in
		// for having read the number exactly.
		listOf("is Double", "is Float", "is BigDecimal", "% 1.0", "toDouble()", "roundToLong")
			.forEach { assertTrue("the reader must not use $it", !reader.contains(it)) }

		// Nor may the API-31 exactness helpers come back; this runs from 26.
		assertTrue("longValueExact is API 31", !votes.contains("longValueExact()"))

		// What must remain: the API-safe range check, and the percent narrowing
		// that is still fenced.
		assertTrue("the BigInteger range check", reader.contains("bitLength() < Long.SIZE_BITS"))
		assertTrue("the percent narrowing guard", votes.contains("toIntExactOrNull()"))
	}

	/**
	 * No local store of any kind stands behind a Like.
	 *
	 * Hive is authoritative for whether a vote exists and answers for free on
	 * every thread read, so a persisted copy could only ever disagree with it —
	 * and a `PendingSnap`-style record would drag the whole comment state
	 * machine, and its cross-lock surface, onto a write that does not need it.
	 */
	@Test
	fun `no like state is persisted anywhere`() {
		likeSources().forEach { (name, src) ->
			listOf(
				"SharedPreferences",
				"getSharedPreferences",
				"PendingSnap",
				"PendingSnapStore",
				"commit()",
			).forEach { symbol ->
				assertTrue("$name must not reference $symbol", !code(src).contains(symbol))
			}
		}
	}

	/** Every Like source, wherever it lives. */
	private fun likeSources(): List<Pair<String, String>> = listOf(
		"app/src/main/java/com/rustedwax/app/snaps/SnapLikes.kt",
		"app/src/main/java/com/rustedwax/app/ui/snaps/SnapLiking.kt",
		"hive/src/main/kotlin/com/rustedwax/hive/HiveVotes.kt",
	).map { File(root, it).name to text(it) }


	/**
	 * One account-name validator, reused — not a second copy in the hive module.
	 *
	 * The vote parser has to judge voter identities, and two validators that can
	 * disagree about who exists is worse than none: whichever is laxer becomes
	 * the one that matters, silently. So `HiveVotes` calls [HiveAccountName] and
	 * carries no account grammar of its own.
	 */
	@Test
	fun `the vote parser reuses the account-name validator rather than copying it`() {
		val votes = code(text("hive/src/main/kotlin/com/rustedwax/hive/HiveVotes.kt"))
		assertTrue(
			"HiveVotes must judge voters with HiveAccountName",
			votes.contains("HiveAccountName.isValid("),
		)
		// The grammar itself must live in exactly one place.
		listOf("'a'..'z'", "MIN_SEGMENT", "split('.')").forEach {
			assertTrue("HiveVotes must not re-implement $it", !votes.contains(it))
		}
		val validator = text("hive/src/main/kotlin/com/rustedwax/hive/HiveAccountName.kt")
		assertTrue("the validator itself must hold the grammar", validator.contains("MIN_SEGMENT"))
		// And the app-side name still resolves to that one object.
		assertTrue(
			"the app package must alias the moved validator rather than redeclare it",
			text("app/src/main/java/com/rustedwax/app/snaps/PostedSnap.kt")
				.contains("typealias HiveAccountName = com.rustedwax.hive.HiveAccountName"),
		)
	}


	/**
	 * Optimistic posting still goes stage → persist → broadcast, in that order.
	 *
	 * The durable checkpoint is the whole basis for showing a Snap early, so
	 * the two halves must stay two halves of one path. Pinned structurally
	 * because the failure — a `deliver` that could build a transaction, or a
	 * `stage` that could send one — would look perfectly ordinary in a diff.
	 */
	@Test
	fun `staging and delivery remain two halves of one publication path`() {
		val src = text("app/src/main/java/com/rustedwax/app/snaps/SnapPublisher.kt")
		val code = code(src)
		assertTrue("must expose a staging half", code.contains("fun stageRoot("))
		assertTrue("must expose a delivery half", code.contains("fun deliver("))

		// `deliver` may never mint, sign or build. Those belong to `stage`.
		val deliver = code.substringAfter("fun deliver(").substringBefore("private fun Outcome.staged()")
		listOf("mintPermlink", "prepareComment", "PendingSnap(", "destination(").forEach {
			assertTrue("deliver must not $it", !deliver.contains(it))
		}
		// And the one-shot path still composes the two, so there is no second
		// publication route for replies or anyone else.
		assertTrue(
			"publish must still compose stage then deliver",
			code.contains("is Staged.Ready -> deliver(account, eventId, kind)"),
		)
		// And it hands the delivery half the kind it staged under, so the last
		// store read before the wire is the same kind-checked read.
		assertTrue(
			"deliver must be given the kind to check against",
			code.contains("fun deliver(account: String, eventId: String, kind: PendingSnapKind)"),
		)
	}

	/**
	 * The two "durably staged" definitions agree.
	 *
	 * One decides which rows a reopened app redraws; the other decides which
	 * rows will hand back a card. If they drifted, a Snap would be restored
	 * into a state that renders nothing, or drawn from a state nobody restores.
	 */
	@Test
	fun `the staged-state sets agree between the publisher and the card source`() {
		fun states(path: String, after: String): List<String> {
			val body = code(text(path)).substringAfter(after).substringAfter("setOf(")
				.substringBefore(")")
			return Regex("""PendingSnapState\.(\w+)""").findAll(body).map { it.groupValues[1] }
				.toList().sorted()
		}
		val publisher = states(
			"app/src/main/java/com/rustedwax/app/snaps/SnapPublisher.kt",
			"val STAGED_STATES",
		)
		val cards = states(
			"app/src/main/java/com/rustedwax/app/snaps/PostedSnaps.kt",
			"val STAGED_STATES",
		)
		assertEquals(
			listOf("ACCEPTED_UNCONFIRMED", "BROADCASTING", "INTENT", "PREPARED", "UNRESOLVED"),
			publisher,
		)
		assertEquals(publisher, cards)
	}

	/**
	 * `local()` still means "proven on chain", and only that.
	 *
	 * The optimistic card got its own accessor rather than a loosening of this
	 * one, because a great deal of the Snap design rests on `local()` meaning
	 * exactly one thing.
	 */
	@Test
	fun `the proven-card accessor still requires CONFIRMED`() {
		val cards = code(text("app/src/main/java/com/rustedwax/app/snaps/PostedSnaps.kt"))
		val local = cards.substringAfter("fun local(").substringBefore("fun staged(")
		assertTrue(
			"local must still gate on CONFIRMED",
			local.contains("record.state != PendingSnapState.CONFIRMED"),
		)
	}

	/**
	 * The irreversible identity is assembled from the durable record.
	 *
	 * Pinned structurally because it cannot be pinned behaviourally: the
	 * identity gate refuses any record whose author is not the account acting
	 * on it, so a *valid* record's author and its caller are always the same
	 * string, and a substitution would pass every runtime test while quietly
	 * moving the source of the one field that cannot be taken back. What the
	 * gate makes impossible to observe, this makes impossible to introduce.
	 */
	@Test
	fun `preparation takes its author from the record, not from the caller`() {
		val code = code(text("app/src/main/java/com/rustedwax/app/snaps/SnapPublisher.kt"))
		val stage = code.substringAfter("private fun stage(").substringBefore("data class StagedSnap")

		assertTrue(
			"the author must be read off the existing record, falling back to the " +
				"caller only when there is no record yet",
			stage.contains(Regex("""val author = existing\?\.author \?: account""")),
		)
		// Both the operation and the binding the port checks it against.
		assertTrue(
			"CommentOp.author must be that frozen name",
			stage.contains(Regex("""author = author,[\s\S]*?permlink = permlink,""")),
		)
		assertTrue(
			"and the record written back must carry it too",
			stage.contains(Regex("""author = author,[\s\S]*?state = PendingSnapState\.PREPARED""")),
		)
		assertTrue(
			"nothing in stage may sign as the caller instead",
			!stage.contains(Regex("""author = account[,)]""")),
		)
	}
}
