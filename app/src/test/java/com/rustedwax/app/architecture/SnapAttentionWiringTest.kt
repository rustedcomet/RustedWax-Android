package com.rustedwax.app.architecture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shape of Stage 6, asserted where behaviour cannot reach.
 *
 * Two claims live here that no unit test can make. The first is a **negative**:
 * that nothing on the notification path can cause a Hive write. A test can show
 * that today's code does not broadcast; only reading the source can show there
 * is no publisher, no port, no key and no broadcaster anywhere behind the bell
 * for a later edit to reach for. The second is that Stage 6 reads the Stage 5
 * attention seams rather than keeping a copy of them — a duplicated state
 * machine passes its own tests perfectly while disagreeing with the card the
 * user is looking at.
 *
 * Read from source for the same reason the rest of this package is: these are
 * decisions about what is wired to what, and a Compose test would need an
 * instrumentation host to assert argument passing.
 */
class SnapAttentionWiringTest {

	private val root: File by lazy {
		generateSequence(File(checkNotNull(System.getProperty("user.dir"))).absoluteFile) {
			it.parentFile
		}.firstOrNull { File(it, "settings.gradle.kts").isFile }
			?: error("repository root was not found")
	}

	private fun text(path: String): String = File(root, path).let {
		assertTrue("production source missing: $path", it.isFile)
		it.readText()
	}

	private fun stripComments(source: String): String = source
		.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
		.lines()
		.joinToString("\n") { it.substringBefore("//") }

	private val ui = "app/src/main/java/com/rustedwax/app/ui"

	private val notices: String get() = stripComments(text("$ui/snaps/SnapNotices.kt"))
	private val model: String get() = stripComments(text("$ui/snaps/SnapAttention.kt"))
	private val bell: String get() = stripComments(text("$ui/snaps/SnapAttentionBell.kt"))
	private val notifier: String get() = stripComments(text("$ui/snaps/SnapNoticeNotifier.kt"))
	private val screen: String get() = stripComments(text("$ui/MainScreen.kt"))
	private val activity: String get() =
		stripComments(text("app/src/main/java/com/rustedwax/app/MainActivity.kt"))

	/**
	 * Everything that can put a transaction on Hive, by the names it goes by in
	 * this codebase.
	 *
	 * Deliberately includes the *ports* and not only the verbs. A file that
	 * cannot name a publisher cannot be edited into calling one without the edit
	 * being visible here.
	 */
	private val writeMachinery = listOf(
		"SnapPublisher",
		"SnapHivePort",
		"SnapLikePort",
		"HiveBroadcaster",
		"HiveSnapPort",
		"HiveSnapLikePort",
		"broadcast",
		"loadKey",
		"KeyVault",
		"TxSerializer",
		"PreparedHiveTransaction",
		"custom_json",
	)

	@Test
	fun `nothing on the notification path can reach a Hive write`() {
		mapOf(
			"SnapNotices.kt" to notices,
			"SnapAttention.kt" to model,
			"SnapAttentionBell.kt" to bell,
			"SnapNoticeNotifier.kt" to notifier,
		).forEach { (name, source) ->
			writeMachinery.forEach { token ->
				assertFalse(
					"$name must have no way to sign or send anything, and it names " +
						"`$token` — Stage 6 introduces no Hive write",
					source.contains(token),
				)
			}
		}
	}

	@Test
	fun `the notice controller is built with a store and an account and nothing else`() {
		val ctor = notices
			.substringAfter("class SnapNoticeController internal constructor(")
			.substringBefore(") {")

		assertTrue("it needs somewhere to remember", ctor.contains("store: SnapNoticeStore"))
		assertTrue("and it needs to know whose bell it is", ctor.contains("account: () -> String?"))
		assertTrue("and somewhere to announce", ctor.contains("notifier: SnapNoticeNotifier"))
		assertEquals(
			"a fourth dependency would be the seam a write could arrive through",
			4,
			ctor.split(",").count { it.contains(":") },
		)
	}

	@Test
	fun `no notification path retries, resends or re-strengthens`() {
		val path = listOf(
			"SnapNotices.kt" to notices,
			"SnapAttention.kt" to model,
			"SnapAttentionBell.kt" to bell,
			"SnapNoticeNotifier.kt" to notifier,
		)

		// The verbs. `publish` and `vote` are how something reaches Hive;
		// `retry`, `recheck` and `resumePending` are the three ways an attempt
		// already made is resumed, and all three are the user's to ask for on
		// the card itself.
		listOf("retry(", "recheck(", "resumePending", "publish(", "vote(").forEach { call ->
			path.forEach { (name, source) ->
				assertFalse(
					"$name must not be able to start an attempt: it names `$call`",
					source.contains(call),
				)
			}
		}

		// And the objects. `post(` and `send(` are deliberately *not* checked as
		// bare words: this stack posts notifications as well as Snaps, and an
		// assertion on a word that means two things either passes vacuously or
		// fails for the wrong reason. What is checked instead is stronger — no
		// file on this path may so much as name the three controllers that can
		// publish, so there is nothing for `posts.post` or `threads.send` to be
		// called on.
		listOf("SnapPostController", "SnapThreadController", "SnapLikeController").forEach { type ->
			path.forEach { (name, source) ->
				assertFalse(
					"$name must have no route to the publishing controllers, and " +
						"it names `$type`",
					source.contains(type),
				)
			}
		}
	}

	@Test
	fun `tapping a bell row only navigates`() {
		val handler = screen
			.substringAfter("SnapAttentionBell(")
			.substringBefore("\t\t\t\t},\n\t\t\t\tcolors")

		assertTrue(
			"a row moves the user to History, because that is where every Snap " +
				"and every conversation lives",
			handler.contains("chosen = Destination.HISTORY"),
		)
		assertTrue(
			"a row that knows its conversation opens it",
			handler.contains("threads.open(target.root, null)"),
		)
		assertTrue(
			"a row that names a comment resolves the conversation at the tap, and " +
				"opens at the comment itself when nothing has been read yet",
			handler.contains("threads.rootOf(target.comment) ?: target.comment"),
		)
		assertTrue(
			"a Snap row asks History for that card",
			handler.contains("focusEventId = target.eventId"),
		)
		listOf("posts.retry", "posts.recheck", "posts.post", "threads.send", "likes.like")
			.forEach {
				assertFalse(
					"the handler must not act on the user's behalf: it calls `$it`",
					handler.contains(it),
				)
			}
	}

	@Test
	fun `the bell reads the Stage 5 attention seams rather than a copy of them`() {
		assertTrue(
			"an unfinished root Snap comes from the controller that owns that state",
			screen.contains(Regex("""rootAttention = posts\.needsAttention\(\)""")),
		)
		assertTrue(
			"an unfinished reply comes from the thread controller",
			screen.contains(Regex("""replyAttention = threads\.needsAttention\(\)""")),
		)
		assertTrue(
			"an unsettled Like comes from the Like controller",
			screen.contains(Regex("""likeAttention = likes\.needsAttention\(\)""")),
		)
		assertFalse(
			"and none of it is persisted by Stage 6 — a Like's durable record is " +
				"the chain's, and a second copy could only ever disagree",
			notices.contains("SnapLikeState") || notices.contains("SnapPostStatus"),
		)
	}

	@Test
	fun `reaching a conversation answers its notifications, in one place`() {
		assertEquals(
			"every route into the sheet — the bell, the Thread button, the preview " +
				"strip — must pass through one mark-read, or one of them will be " +
				"added without it",
			1,
			Regex("""notices\.markThreadRead\(""").findAll(screen).count(),
		)
		assertTrue(
			"and it belongs to the sheet being on screen, not to a click handler",
			screen.contains(
				Regex("""threads\.openThread\?\.let \{ root ->[\s\S]{0,400}?notices\.markThreadRead\(root\)"""),
			),
		)
	}

	@Test
	fun `the bell is fed by reads that already happen`() {
		assertTrue(
			"its whole data supply is the thread read History was already doing",
			activity.contains(Regex("""onChainRead = notices::record""")),
		)
		assertEquals(
			"one hook, at the one place a fresh chain answer is accepted",
			1,
			Regex("""onChainRead\(""")
				.findAll(stripComments(text("$ui/snaps/SnapThreading.kt")))
				.count(),
		)
	}

	/**
	 * Stage 6 posts notifications, and adds nothing that can run on its own.
	 *
	 * The permission is real and required from Android 13, so it is declared.
	 * What must *not* appear beside it is anything that schedules, wakes or
	 * listens: a notification here is only ever raised by the app itself, on the
	 * thread that has just finished reading a conversation it was already going
	 * to read. A receiver, a worker, a fourth service or a boot permission would
	 * each be a new thing that could run while the app is closed, and none is
	 * needed to announce a reply the app is looking at.
	 */
	@Test
	fun `notifications are declared, and nothing that could run on its own is`() {
		val manifest = text("app/src/main/AndroidManifest.xml")

		assertTrue(
			"Android 13 stopped granting this at install, so it has to be asked for",
			manifest.contains("android.permission.POST_NOTIFICATIONS"),
		)
		listOf(
			"RECEIVE_BOOT_COMPLETED",
			"FOREGROUND_SERVICE",
			"WAKE_LOCK",
			"SCHEDULE_EXACT_ALARM",
			"<receiver",
			"androidx.work",
		).forEach {
			assertFalse(
				"a bell fed by reads that already happen needs no `$it`",
				manifest.contains(it),
			)
		}
		assertEquals(
			"the three services that were already there, and no fourth",
			3,
			Regex("""<service""").findAll(manifest).count(),
		)
	}

	@Test
	fun `the runtime permission is asked for without breaking Android 12`() {
		assertTrue(
			"minSdk is 26, where POST_NOTIFICATIONS is not a runtime permission " +
				"at all — asking there would be a dialog the platform has no " +
				"answer for",
			activity.contains("Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU"),
		)
		assertTrue(
			"and it is asked through the result contract rather than a bare " +
				"requestPermissions call",
			activity.contains("ActivityResultContracts.RequestPermission()"),
		)
		assertTrue(
			"asked once, when there is first an account that could receive a reply",
			activity.contains("settings.notificationsAsked"),
		)
	}

	/**
	 * The shade is told what is new; it does not work it out.
	 *
	 * Every rule about who is notified — not your own words, not a Like, not a
	 * conversation's first read, not a reply already on file — lives in the
	 * store, and the notifier is handed only what the store has just written
	 * unread. A second copy of those rules behind the notification would be a
	 * second copy free to disagree with the bell.
	 */
	@Test
	fun `only what the store found genuinely new reaches the shade`() {
		assertTrue(
			"the announcement reads the store's answer rather than the scan's",
			notices.contains(Regex("""val fresh = store\.record\(""")),
		)
		assertTrue(
			"and posts exactly that",
			notices.contains(Regex("""fresh\.forEach \{ notifier\.post\(who, it\) \}""")),
		)
		assertFalse(
			"the notifier must not be handed the raw scan result",
			notices.contains("found.forEach { notifier"),
		)
		assertFalse(
			"and must not re-implement the rules it is protected by",
			notifier.contains("parentAuthor") || notifier.contains("SnapNoticeScan"),
		)
	}

	@Test
	fun `a notification is durable before it is shown`() {
		assertTrue(
			"commit(), not apply(): the shade is disturbed the moment record() " +
				"returns, and a notification is a promise the record behind it " +
				"survives",
			notices.contains(Regex("""edit\.commit\(\)[\s\S]{0,400}?return fresh""")),
		)
	}

	/**
	 * The synchronous write must not be on the path of an ordinary read.
	 *
	 * `record` runs on the main thread, and History reads the conversation under
	 * every posted Snap on screen. Almost all of those reads find a thread this
	 * account already knows about and have nothing to store — so the uneventful
	 * case has to return before the editor is opened, or a `commit()` lands on
	 * the UI thread once per visible card.
	 */
	@Test
	fun `an uneventful read does not touch the disk`() {
		assertTrue(
			"a seeded conversation with nothing new in it must return before the " +
				"editor, not merely commit an empty edit",
			notices.contains(
				Regex("""if \(!seeding && unseen\.isEmpty\(\)\) return emptyList\(\)[\s\S]{0,900}?prefs\.edit\(\)"""),
			),
		)
	}

	/**
	 * The three Codex blockers, pinned where behaviour cannot see them.
	 *
	 * Each is a case where the code looked right and was wrong about something
	 * the platform or the filesystem decides, so each assertion names the thing
	 * that must keep being true rather than the symptom.
	 */
	@Test
	fun `a durable write that failed cannot become a notification`() {
		assertTrue(
			"commit() answers whether the bytes landed, and the answer has to be " +
				"read — announcing regardless is a notification with no record " +
				"behind it",
			notices.contains(Regex("""if \(!edit\.commit\(\)\) \{""")),
		)
		assertTrue(
			"and the caller treats that null as `nothing happened`, so no bell row " +
				"either",
			notices.contains(
				Regex("""store\.record\([\s\S]{0,80}?\) \?: return"""),
			),
		)
	}

	@Test
	fun `a failed commit is undone in memory, not merely reported`() {
		assertTrue(
			"commit() is memory-first, so reading false and returning would leave " +
				"the notice looking already-seen for the life of the process",
			notices.contains(Regex("""if \(!edit\.commit\(\)\) \{[\s\S]{0,80}?restore\(prior\)""")),
		)
		assertTrue(
			"the prior state is captured before the editor runs, since the editor " +
				"is what destroys it",
			notices.contains(Regex("""val prior[\s\S]{0,80}?snapshot\[it\][\s\S]{0,80}?prefs\.edit\(\)""")),
		)
		assertTrue(
			"and restoring puts values back rather than removing every touched key",
			notices.contains("is String -> edit.putString(key, value)") &&
				notices.contains("is Long -> edit.putLong(key, value)"),
		)
	}

	@Test
	fun `pruning collapses a notice and never removes it`() {
		assertTrue(
			"the stored row is also the only record that this reply has been " +
				"seen; removing it is what let a months-old comment be announced " +
				"again",
			notices.contains(Regex("""edit\.putString\(it, SnapNoticeCodec\.TOMBSTONE\)""")),
		)
		// Scoped to the function it is about: `restore` also removes keys, and
		// does so correctly and for an unrelated reason.
		val prune = notices.substringAfter("private fun prune()").substringBefore("\n\t}")
		assertFalse(
			"nothing on the pruning path may remove a notice key",
			prune.contains("edit::remove") || prune.contains("edit.remove("),
		)
		assertTrue(
			"and a collapsed row still answers `contains`, which is the only " +
				"question de-duplication asks",
			notices.contains("prefs.contains(SnapNoticeCodec.noticeKey("),
		)
	}

	@Test
	fun `two notices cannot share one PendingIntent`() {
		assertTrue(
			"PendingIntent matching ignores extras, so identity has to live in a " +
				"field it does compare",
			notifier.contains(
				Regex("""data = Uri\.parse\([\s\S]{0,120}?SnapNoticeIntent\.dataUriOf\("""),
			),
		)
		assertTrue(
			"built from the whole of what the tap uses",
			notifier.contains("dataUriOf(account, notice.contentId, notice.rootId)"),
		)
		val manifest = text("app/src/main/AndroidManifest.xml")
		assertFalse(
			"the intent stays explicit — no filter is added, so nothing outside " +
				"the app can launch this URI",
			manifest.contains("rustedwax://") || manifest.contains("android:scheme"),
		)
	}

	@Test
	fun `a tapped notification cannot be filled in by anything else`() {
		assertTrue(
			"IMMUTABLE is required from API 31 and correct everywhere: nothing " +
				"outside this app may fill in fields on an intent that opens " +
				"somebody's conversation",
			notifier.contains("PendingIntent.FLAG_IMMUTABLE"),
		)
		assertFalse(
			"a mutable PendingIntent here would be the whole leak",
			notifier.contains("FLAG_MUTABLE"),
		)
		assertTrue(
			"and it reuses the running app rather than rebuilding it, which would " +
				"throw away an open composer",
			notifier.contains("FLAG_ACTIVITY_SINGLE_TOP"),
		)
	}

	@Test
	fun `a tapped notification is re-validated and fails closed`() {
		assertTrue(
			"the account it was raised for travels with it, and is checked",
			notifier.contains(Regex("""if \(who != raised\) return null""")),
		)
		assertTrue(
			"the activity routes through that check rather than reading the " +
				"extras directly",
			activity.contains("SnapNoticeIntent.target("),
		)
		assertTrue(
			"a tap is a one-time request: leaving the extras on the intent would " +
				"re-open the conversation on every rotation",
			activity.contains("removeExtra"),
		)
		assertTrue(
			"and a delivered tap reaches the screen as an already-validated " +
				"conversation, not as a bundle",
			screen.contains("openThreadRequest: SnapReplyTarget?"),
		)
	}

	@Test
	fun `a notification opens a thread the same way the bell does`() {
		val handler = screen
			.substringAfter("LaunchedEffect(openThreadRequest)")
			.substringBefore("\n\tScaffold(")

		assertTrue(handler.contains("chosen = Destination.HISTORY"))
		assertTrue(handler.contains("threads.open(target, null)"))
		listOf("posts.retry", "posts.recheck", "threads.send", "likes.like", "notify(")
			.forEach {
				assertFalse(
					"opening a conversation from the shade must not act for the " +
						"user: it calls `$it`",
					handler.contains(it),
				)
			}
	}
}
