package com.rustedwax.app.ui.snaps

import android.content.Context
import android.content.SharedPreferences

/**
 * Where an unsent Snap lives between the moment you stop typing and the moment
 * you come back to it.
 *
 * Its own preference file rather than a corner of [com.rustedwax.app.storage.Settings]:
 * drafts are per-account user content with their own lifetime, and the settings
 * store carries a migration that runs before every read of a *setting*. Keeping
 * them apart means a draft can never be the reason a scrobbling preference is
 * read wrong, and clearing one has nothing to say about the other.
 */
internal interface SnapDraftStore {
	fun read(key: String): String
	fun write(key: String, text: String)
	fun clear(key: String)
	/** Every stored draft key, so the UI can mark collapsed cards. */
	fun keys(): Set<String>
}

/**
 * The identity a draft hangs on: the History row's own `eventId`.
 *
 * A History row is a *scrobble*, not a video — playing the same track tomorrow is
 * a new row with its own Snap. This used to be spelled `videoId@atEpochSec`, which
 * said that but did not guarantee it: the timestamp is whole seconds, so two
 * queued attempts on one video inside the same second produced the same key and
 * two rows shared one draft. `eventId` is minted per row and cannot collide.
 *
 * Scoped by Hive account as well, because drafts are one of the things the spec
 * forbids leaking between accounts. A logged-out user still gets a slot of their
 * own rather than writing into whoever logs in next.
 */
internal object SnapDraftKey {

	fun of(account: String?, eventId: String): String =
		"${account?.takeIf { it.isNotBlank() } ?: ANONYMOUS}|$eventId"

	/** Drafts written while nobody was signed in. */
	private const val ANONYMOUS = "-"
}

/** The real store: a small private preference file, same shape as the rest of the app. */
internal class SharedPreferencesSnapDraftStore(context: Context) : SnapDraftStore {

	private val prefs: SharedPreferences =
		context.getSharedPreferences("rustedwax_snap_drafts", Context.MODE_PRIVATE)

	override fun read(key: String): String = prefs.getString(key, "") ?: ""

	override fun write(key: String, text: String) {
		// An empty draft is an absent draft: storing "" would light the Draft
		// indicator on a card the user cleared out and walked away from.
		if (text.isEmpty()) clear(key) else prefs.edit().putString(key, text).apply()
	}

	override fun clear(key: String) {
		prefs.edit().remove(key).apply()
	}

	override fun keys(): Set<String> = prefs.all.keys.toSet()
}
