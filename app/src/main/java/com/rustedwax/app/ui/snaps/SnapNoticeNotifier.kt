package com.rustedwax.app.ui.snaps

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.rustedwax.app.R
import com.rustedwax.app.snaps.HiveAccountName
import com.rustedwax.app.snaps.SnapReplies
import com.rustedwax.app.snaps.SnapReplyTarget

/**
 * Puts one incoming reply in the system shade, or does not.
 *
 * An interface for the reason the rest of this stack uses them: what an object
 * *can* do is the only reliable statement about what it will do. Nothing behind
 * this can sign, vote or broadcast — there is no key, no port and no publisher
 * anywhere in the implementation — and nothing behind it can decide *whether* a
 * reply is worth announcing either. That decision is made once, by
 * [SnapNoticeStore], and this is only told the answer.
 */
internal interface SnapNoticeNotifier {
	/**
	 * Announce one genuinely new incoming reply.
	 *
	 * Called only for notices the store has just written **unread**: a seeded
	 * first read, a re-read of a conversation, a reply already on file and the
	 * user's own words never reach here, because none of them is a new notice.
	 * De-duplication is therefore not this object's job and is deliberately not
	 * re-implemented in it.
	 */
	fun post(account: String, notice: SnapNotice)

	/** Take one announcement back, because the user has now seen the reply. */
	fun cancel(account: String, contentId: String)
}

/**
 * The tap contract, with no Android in it.
 *
 * Split out so the one part with a rule in it — deciding whether a tap may be
 * acted on — is testable on the JVM. What arrives back through an `Intent` has
 * been outside this process: it sat in the system shade, possibly for days,
 * across a sign-out, an account switch, a reinstall or a version change, and
 * anything on the device holding the `PendingIntent` can replay it. So it is
 * treated exactly like a chain response: re-validated, and refused rather than
 * repaired.
 *
 * **It fails closed.** A tap whose account is not the account signed in now
 * navigates nowhere at all. That is the whole point of carrying the account in
 * the extras: without it, a notification raised for Alice and tapped after Bob
 * signs in would open Alice's conversation inside Bob's session, which is the
 * one thing the account scoping in this stack exists to prevent. Opening
 * nothing is a slightly confusing tap; opening it anyway is a data leak.
 */
internal object SnapNoticeIntent {

	const val EXTRA_ACCOUNT = "com.rustedwax.app.notice.account"
	const val EXTRA_ROOT_AUTHOR = "com.rustedwax.app.notice.rootAuthor"
	const val EXTRA_ROOT_PERMLINK = "com.rustedwax.app.notice.rootPermlink"
	const val EXTRA_REPLY = "com.rustedwax.app.notice.reply"

	/**
	 * The conversation this tap may open, or null when it may open none.
	 *
	 * [signedIn] is the account now; [account] is the account the notification
	 * was raised for. Every other argument came out of the same untrusted
	 * bundle and is re-validated through [SnapReplyTarget.of], so a permlink
	 * that has been altered to contain a slash — which would split wrong in a
	 * store key and wrong again in a content id — produces no target rather than
	 * a plausible one.
	 */
	fun target(
		signedIn: String?,
		account: String?,
		rootAuthor: String?,
		rootPermlink: String?,
	): SnapReplyTarget? {
		val who = signedIn?.takeIf { it.isNotBlank() } ?: return null
		val raised = account?.takeIf { it.isNotBlank() } ?: return null
		// Not a courtesy check. A stale notification is the ordinary case — the
		// shade outlives the session — and acting on one is how another
		// account's conversation ends up on screen.
		if (who != raised) return null
		if (!HiveAccountName.isValid(raised)) return null
		val author = rootAuthor?.takeIf { HiveAccountName.isValid(it) } ?: return null
		val permlink = rootPermlink?.takeIf { SnapReplies.isPermlink(it) } ?: return null
		return SnapReplyTarget.of(author, permlink)
	}

	/**
	 * A stable, per-reply notification id.
	 *
	 * Derived from the reply's own content id so the same reply can only ever
	 * occupy one slot in the shade: if the same notice were somehow posted
	 * twice, the second would replace the first rather than stack beside it.
	 * The tag carries the full id as well, because a hash can collide and a tag
	 * cannot — the pair is what actually identifies the notification, and the
	 * integer alone is never used to cancel one.
	 */
	fun idOf(contentId: String): Int = contentId.hashCode()

	fun tagOf(account: String, contentId: String): String = "$account|$contentId"

	/**
	 * The identity Android actually matches a `PendingIntent` on.
	 *
	 * ## Why a URI and not the request code
	 *
	 * `PendingIntent` equality **ignores extras**. Two requests match when their
	 * request code and their intent's action, data, type, component, categories
	 * and flags agree — so an earlier revision, which distinguished them only by
	 * `requestCode = contentId.hashCode()`, was one 32-bit collision away from a
	 * real failure. `"bob/tr9apgbmtu"` and `"bob/wgcmnbuajy"` are both perfectly
	 * ordinary Hive ids and both hash to the same `Int`; with `FLAG_UPDATE_CURRENT`
	 * the second notification would have quietly rewritten the first one's extras,
	 * and tapping the older notification would have opened the newer one's
	 * conversation. A hash is a bucket, and identity is not something a bucket can
	 * carry.
	 *
	 * So the distinguishing field is the intent's **data URI**, which Android does
	 * compare, and it is built from the whole of what the tap uses: the account
	 * the notification was raised for, the reply it is about, and the root it
	 * opens. Two different notices cannot produce the same URI, because the URI
	 * *is* their identity spelled out rather than summarised.
	 *
	 * ## Why this is safe to build by concatenation
	 *
	 * Every segment has already been validated before it can reach here — a Hive
	 * account name and a permlink RustedWax will act on are both restricted to
	 * lowercase letters, digits, `-`, `.` and `_`, and in particular neither may
	 * contain `/`, `?`, `#` or whitespace. So no segment can end the path early,
	 * open a query, or otherwise mean something other than itself.
	 *
	 * Returned as a `String` rather than an `android.net.Uri` on purpose: the
	 * rule is worth testing on the JVM, and `Uri` is one of the Android classes
	 * that answers with stubs there.
	 */
	fun dataUriOf(account: String, contentId: String, rootId: String): String =
		"$SCHEME://notice/$account/$contentId/$rootId"

	private const val SCHEME = "rustedwax"
}

/**
 * The real notifier: one channel, one notification per new reply.
 *
 * The smallest thing that works. No group, no summary, no custom layout, no
 * service and no receiver — a notification is built, handed to
 * [NotificationManagerCompat], and forgotten about. Nothing here runs unless
 * this app is already running and has just read a conversation.
 *
 * Two ways it declines to post, both silent and neither an error:
 *
 *  - **notifications are off**, either by the Android 13 permission never being
 *    granted or by the user switching the channel off. `notify` would be a
 *    no-op anyway; asking first means the app does not depend on that;
 *  - **the notice names something that could not be navigated to.** A
 *    notification whose tap can only fail closed is worse than no notification:
 *    it promises a conversation and then does nothing.
 */
internal class AndroidSnapNoticeNotifier(
	private val context: Context,
	/**
	 * The screen a tap opens, handed in rather than looked up.
	 *
	 * An earlier revision resolved it from a class name with `Class.forName`, to
	 * keep this file free of a reference back to the Activity. That bought
	 * nothing and cost the compiler: renaming or moving the Activity would have
	 * left a string that still compiled and a notification that silently opened
	 * nothing. Passing it in makes the same decoupling a matter of the caller
	 * choosing, and makes a rename a build error.
	 */
	private val target: Class<*>,
) : SnapNoticeNotifier {

	private val manager = NotificationManagerCompat.from(context)

	override fun post(account: String, notice: SnapNotice) {
		// Built and checked before anything is shown: the same validation the
		// tap will apply, applied now, so a notification is never raised that
		// could only refuse itself later.
		val root = notice.root() ?: return
		if (!HiveAccountName.isValid(account)) return
		// Inline, and inline on purpose. From API 33 `notify` is guarded by
		// POST_NOTIFICATIONS, and both the platform and lint want the check in
		// the same method as the call — behind a helper it still worked and lint
		// still flagged it, which is a warning worth keeping rather than
		// suppressing. On 26–32 the permission is granted at install and this is
		// skipped entirely, so nothing here changes on Android 12.
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
			ContextCompat.checkSelfPermission(
				context,
				Manifest.permission.POST_NOTIFICATIONS,
			) != PackageManager.PERMISSION_GRANTED
		) {
			return
		}
		// A separate question from the permission, and asked on every version:
		// notifications can be switched off for the app or for this channel
		// whether or not a grant exists.
		if (!manager.areNotificationsEnabled()) return

		ensureChannel()

		val intent = Intent(context, target).apply {
			// The field that makes this request distinct from every other
			// notice's. Explicit component *and* data: the component keeps the
			// intent unexported and unambiguous, the data keeps two notices from
			// sharing one PendingIntent. No intent filter matches this URI and
			// none is added — nothing outside the app can launch it.
			data = Uri.parse(
				SnapNoticeIntent.dataUriOf(account, notice.contentId, notice.rootId),
			)
			// Reuse the running app rather than stacking a second copy of it,
			// and deliver the extras to the instance already on screen through
			// `onNewIntent`. Without SINGLE_TOP, CLEAR_TOP would destroy and
			// rebuild the activity, throwing away the composition — and with it
			// an open composer and a half-typed reply.
			flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
			putExtra(SnapNoticeIntent.EXTRA_ACCOUNT, account)
			putExtra(SnapNoticeIntent.EXTRA_ROOT_AUTHOR, root.author)
			putExtra(SnapNoticeIntent.EXTRA_ROOT_PERMLINK, root.permlink)
			putExtra(SnapNoticeIntent.EXTRA_REPLY, notice.contentId)
		}
		val pending = PendingIntent.getActivity(
			context,
			// Kept, but no longer load-bearing: the data URI above is what makes
			// two notices distinct requests, so a hash collision here cannot
			// merge them any more.
			SnapNoticeIntent.idOf(notice.contentId),
			intent,
			// IMMUTABLE is required from API 31 and correct everywhere: nothing
			// outside this app has any business filling in fields on an intent
			// that opens somebody's conversation. UPDATE_CURRENT so a re-post of
			// the same reply carries the newer extras rather than the older.
			PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
		)

		val notification = NotificationCompat.Builder(context, CHANNEL)
			.setSmallIcon(R.drawable.ic_notification_snap_reply)
			.setContentTitle("@${notice.author} replied")
			// Already clamped when the notice was built, and clamped again by the
			// shade. A stranger's words, shown as written.
			.setContentText(notice.text.ifBlank { "(no text)" })
			.setStyle(NotificationCompat.BigTextStyle().bigText(notice.text))
			.setContentIntent(pending)
			.setAutoCancel(true)
			.setCategory(NotificationCompat.CATEGORY_SOCIAL)
			.setPriority(NotificationCompat.PRIORITY_DEFAULT)
			.build()

		// Guarded as well as checked: the permission can be revoked between
		// [permitted] above and this line, and the platform throws rather than
		// ignoring it. A missing notification is not worth a crash on the thread
		// that has just finished reading a conversation.
		runCatching {
			manager.notify(
				SnapNoticeIntent.tagOf(account, notice.contentId),
				SnapNoticeIntent.idOf(notice.contentId),
				notification,
			)
		}
	}

	override fun cancel(account: String, contentId: String) {
		runCatching {
			manager.cancel(
				SnapNoticeIntent.tagOf(account, contentId),
				SnapNoticeIntent.idOf(contentId),
			)
		}
	}

	/**
	 * The channel, created every time rather than remembered.
	 *
	 * `createNotificationChannel` is idempotent and cheap, and the alternative —
	 * a flag saying it has been done — is a second thing that can be wrong after
	 * the user clears the app's data. minSdk is 26, so there is no version to
	 * branch on: a channel is simply required.
	 */
	private fun ensureChannel() {
		val channel = NotificationChannel(
			CHANNEL,
			context.getString(R.string.snap_replies_channel_name),
			NotificationManager.IMPORTANCE_DEFAULT,
		).apply {
			description = context.getString(R.string.snap_replies_channel_description)
		}
		manager.createNotificationChannel(channel)
	}

	companion object {
		const val CHANNEL = "rustedwax_snap_replies"
	}
}
