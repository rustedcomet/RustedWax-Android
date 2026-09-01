package com.rustedwax.app.ui

import com.rustedwax.app.enrich.WatchHistoryHealth

/**
 * Says, on screen, when the watch-history route cannot work — and what to do.
 *
 * ## The condition this exists for
 *
 * Without a connected session, a native YouTube video played outside a playlist
 * can only be identified by search. Search routinely cannot separate two
 * uploads of the same thing, identity fails closed, and **nothing is logged**.
 * That is the app working exactly as designed and producing no entries, which
 * from the outside is indistinguishable from the app being broken. Nothing said
 * so: the only hint was one line inside a settings row nobody had scrolled to.
 *
 * Using RustedWax without a connection is a legitimate choice — browser YouTube
 * has the address bar and needs none of this. It just has to be a *visible*
 * choice rather than a silent default.
 *
 * ## What it must never do
 *
 * It never names, guesses at, or claims to have read the account the phone's
 * YouTube app is signed into. Android does not expose that, and
 * [WatchHistoryHealth] cannot infer it — a feed that does not contain what this
 * phone played is equally consistent with signed out, a different account, and
 * incognito. So the mismatch copy offers all three and asserts none, and this
 * type takes no account parameter of any kind.
 *
 * The refusal arrives as [WatchHistoryHealth.Refusal] rather than as the prose
 * in `refusedBecause`: production behaviour may not branch on a diagnostic
 * string. The prose is still shown — it is written for a person to read — but
 * which button appears is decided by the typed cause.
 */
object YouTubeConnectionWarning {

	/** What the warning offers, and what the screen has to wire it to. */
	enum class Action {
		/** Open the app's own sign-in screen. */
		SIGN_IN,

		/** Open YouTube's history page, where pausing is undone. */
		OPEN_YOUTUBE_HISTORY,

		/** Nothing the user can do from here. Say so rather than offer a button. */
		NONE,
	}

	data class State(
		val title: String,
		val body: String,
		val action: Action,
		/** Null exactly when [action] is [Action.NONE]. */
		val actionLabel: String?,
	)

	fun evaluate(connected: Boolean, refusal: WatchHistoryHealth.Refusal?): State? {
		// Not connected outranks any refusal. A refusal recorded against a
		// session that has since been forgotten must not replace "you have not
		// connected an account" with "your account has a problem" — the first is
		// true and actionable, the second is about a session that is gone.
		if (!connected) {
			return State(
				title = "No YouTube account connected",
				body = "A video played in the YouTube app outside a playlist can then " +
					"only be identified by search, which often cannot tell two uploads " +
					"of the same thing apart — and RustedWax logs nothing rather than " +
					"guess. Browser playback is unaffected; it has the address bar.",
				action = Action.SIGN_IN,
				actionLabel = "Sign in",
			)
		}

		return when (refusal) {
			null -> null

			WatchHistoryHealth.Refusal.SIGNED_OUT -> State(
				title = "YouTube sign-in has expired",
				body = "The stored session is no longer accepted by YouTube, so watch " +
					"history is not being read and native videos are back to being " +
					"identified by search alone. Signing in again restores it.",
				action = Action.SIGN_IN,
				actionLabel = "Sign in",
			)

			WatchHistoryHealth.Refusal.HISTORY_PAUSED -> State(
				title = "Watch history is paused",
				body = "This account records nothing it plays, so there is no history " +
					"for RustedWax to read. Turn watch history back on in YouTube's own " +
					"settings and it will be picked up again.",
				action = Action.OPEN_YOUTUBE_HISTORY,
				actionLabel = "Open YouTube history",
			)

			WatchHistoryHealth.Refusal.ACCOUNT_MISMATCH -> State(
				title = "Nothing played here is reaching this account's history",
				body = "Several native tracks in a row were absent from the history of " +
					"the account RustedWax is connected to. That happens when the " +
					"YouTube app is signed out, is signed into a different account, or " +
					"is playing in incognito — the feed cannot tell which, and RustedWax " +
					"will not guess. Make the YouTube app use the same account, or " +
					"connect the one it is using.",
				action = Action.SIGN_IN,
				actionLabel = "Sign in",
			)

			// Signing in again cannot fix a page that changed shape, so this does
			// not offer a button that would look like it might.
			WatchHistoryHealth.Refusal.MARKUP_CHANGED -> State(
				title = "The watch history page cannot be read",
				body = "YouTube's history page no longer has the shape RustedWax reads. " +
					"Nothing was guessed from it and history lookups have stopped; " +
					"native videos fall back to search until RustedWax is updated.",
				action = Action.NONE,
				actionLabel = null,
			)
		}
	}
}
