package com.rustedwax.app.ui.snaps

import android.content.Context
import android.content.SharedPreferences
import com.rustedwax.app.snaps.SnapReplyIntent
import org.json.JSONObject

/**
 * One unsent reply, and the publication attempt it has already become.
 *
 * The two fields are stored **together, in one preference entry**, and that is
 * the whole point of this type existing rather than the reply draft being a
 * string like the root-Snap draft is.
 *
 * [intentId] is the durable identity of a *reply intent*: the thing the user
 * meant to say, once. It is minted at the moment Send is tapped, committed
 * before a byte leaves the device, and it is what the pending-Snap record for
 * that attempt is filed under. While it is present, every further attempt on
 * this draft resolves against the **same** record — so a confirmed attempt
 * answers "already published" instead of minting a second permlink.
 *
 * Keeping text and intent in one entry is what makes the crash window safe.
 * Two entries cannot be cleared atomically, and either order loses:
 *
 *  - clearing the intent first, then the text, leaves a draft with no intent
 *    after a crash in between — and the next Send would mint a fresh intent, a
 *    fresh permlink, and post the same words a second time;
 *  - clearing the text first, then the intent, leaves an intent with no text,
 *    which silently swallows whatever the user types next.
 *
 * One entry, removed in one commit, has no in-between.
 */
internal data class SnapReplyDraft(
	val text: String,
	/** Null until this draft has been sent once. */
	val intentId: String?,
) {
	val isEmpty: Boolean get() = text.isEmpty() && intentId == null

	fun toJson(): String = JSONObject()
		.put("text", text)
		.put("intentId", intentId ?: JSONObject.NULL)
		.toString()

	companion object {
		val NONE = SnapReplyDraft("", null)

		/**
		 * Parse a stored entry, or null when it is not **exactly** the record
		 * this class writes.
		 *
		 * Strict on purpose, and strict in the one direction that matters: a
		 * record whose intent is missing must not read as a draft that never had
		 * one. `{"text":"nice one"}` is valid JSON and used to parse as
		 * `intentId = null`, because `isNull` cannot tell an absent key from an
		 * explicit null — so a draft that had already been sent under intent X,
		 * damaged in storage, came back looking brand new. The next Send would
		 * mint intent Y, a second permlink and a duplicate permanent comment.
		 *
		 * So both keys must be present, and each value must be the type it
		 * claims:
		 *
		 *  - `text` must be an actual JSON string. No coercion — `optString`
		 *    turns the number `42` into `"42"`, and a body is not a number;
		 *  - `intentId` must be present, and either an explicit JSON `null`
		 *    (a genuinely unsent draft) or an actual string that still looks like
		 *    an intent id [SnapReplyIntent] would have minted. A malformed one is
		 *    no more usable than a missing one: it cannot name a pending record.
		 *
		 * Everything else is null, which the store reports as
		 * [SnapReplyDraftRead.Corrupt] and every caller fails closed on.
		 */
		fun fromJson(raw: String): SnapReplyDraft? = runCatching {
			val o = JSONObject(raw)
			// `has` is true for an explicit null and false for an absent key,
			// which is the distinction the old parser could not make.
			if (!o.has("text") || !o.has("intentId")) return@runCatching null
			val text = o.opt("text") as? String ?: return@runCatching null
			val intentId = if (o.isNull("intentId")) {
				null
			} else {
				(o.opt("intentId") as? String)?.takeIf { SnapReplyIntent.isValid(it) }
					?: return@runCatching null
			}
			SnapReplyDraft(text, intentId)
		}.getOrNull()
	}
}

/**
 * What the draft store found at one key.
 *
 * The three cases must stay distinct, and [Corrupt] in particular must never
 * collapse into [Absent] or into an empty [Present].
 *
 * An entry that exists but cannot be decoded may be a draft that has **already
 * been sent**, whose intent id is the only thing that would have matched it to
 * its pending record. Treating it as absent lets the next Send mint a fresh
 * intent and a fresh permlink for words that may already be live on Hive; so
 * does inventing a replacement intent for it, which an earlier revision did.
 * There is no safe way to recover such an entry automatically, so the only
 * thing this type offers is the truth, and the caller fails closed on it.
 */
internal sealed interface SnapReplyDraftRead {
	data object Absent : SnapReplyDraftRead

	data class Present(val draft: SnapReplyDraft) : SnapReplyDraftRead

	/** Present on disk, undecodable. Locked: not sendable, not overwritable. */
	data class Corrupt(val reason: String) : SnapReplyDraftRead
}

/**
 * What settling a confirmed reply did to the stored draft.
 *
 * Four outcomes, because "the reply published" does not on its own say what
 * should happen to the entry it was written from. The entry may still be that
 * draft, or it may be something the user typed while the network was busy, or
 * it may belong to a different attempt entirely.
 */
internal sealed interface SnapReplySettlement {
	/** The entry was exactly the published draft. It is gone. */
	data object Retired : SnapReplySettlement

	/**
	 * Same intent, different text: somebody typed while the send was in flight.
	 *
	 * The text is kept exactly as found and the intent is released, which turns
	 * the entry back into an ordinary unsent draft. That is the only correct
	 * answer: those words were never published, and leaving them attached to a
	 * confirmed intent would make the next Send resolve as "already posted" and
	 * delete them instead of publishing them.
	 */
	data class Released(val draft: SnapReplyDraft) : SnapReplySettlement

	/** Absent, undecodable, or another intent's draft. Nothing was changed. */
	data object Untouched : SnapReplySettlement

	/** The store could not be written. Nothing changed, and nothing is claimed. */
	data object Failed : SnapReplySettlement
}

/**
 * Where an unsent reply lives between the moment you stop typing and the moment
 * it is published — or abandoned.
 *
 * Separate from [SnapDraftStore], which holds root-Snap drafts and is untouched
 * by Stage 4. Root drafts are strings with no publication identity attached;
 * reply drafts carry the intent id that stops a crash becoming a duplicate, and
 * that difference is worth a type rather than a convention.
 */
internal interface SnapReplyDraftStore {
	fun read(key: String): SnapReplyDraftRead

	/**
	 * Save the typed text, preserving whatever intent the draft already carries.
	 *
	 * Ordinary typing, so this may be lazy. Losing the last keystroke to a crash
	 * costs a character; it cannot cost an intent id, because an intent is only
	 * ever *added* by [beginIntent], which commits.
	 *
	 * A [SnapReplyDraftRead.Corrupt] entry is never overwritten. The unreadable
	 * bytes are the only evidence that something went wrong here, and replacing
	 * them with a clean draft would quietly convert a locked slot back into a
	 * sendable one.
	 */
	fun writeText(key: String, text: String)

	/**
	 * Attach a publication intent to this draft, **durably**.
	 *
	 * Returns true only when the record is on disk and reads back identical.
	 * The caller must not cross the network boundary on false: a reply broadcast
	 * whose intent was never stored is a reply whose outcome can never be
	 * matched to a draft, which is the exact situation that produces a duplicate
	 * permanent comment. A corrupt entry always answers false.
	 */
	fun beginIntent(key: String, intentId: String): Boolean

	/**
	 * Remove this draft — **only if what is stored is still exactly [expected]**.
	 *
	 * Compare and remove happen inside this one call, against the store itself,
	 * because the caller cannot hold the two together: cleanup runs after a
	 * network round trip, and in that time the user may have typed something new
	 * into the same slot. An unconditional clear would delete those words. So
	 * the caller says what it believes is there, and a mismatch is a refusal
	 * rather than a deletion.
	 *
	 * `expected == null` means "I believe this entry is present and still
	 * unreadable" — the only way to remove a [SnapReplyDraftRead.Corrupt] entry,
	 * and one that cannot touch a valid draft, because a valid draft parses.
	 *
	 * Returns true **only when the entry matched and is durably gone**. False
	 * covers every other outcome — no entry, a different entry, a commit that
	 * failed — and the caller must treat all of them as "the draft is still
	 * here" rather than as success.
	 */
	fun removeIf(key: String, expected: SnapReplyDraft?): Boolean

	/**
	 * Retire the draft a confirmed reply was written from — **atomically**.
	 *
	 * The one operation the confirmed path uses, and deliberately one operation
	 * rather than a check followed by a mutation. Settlement runs after a network
	 * round trip; between reading the entry and changing it the user may have
	 * typed, so a caller that decided outside the store would be acting on a
	 * draft that no longer exists. Comparison and mutation happen together, here,
	 * under the same lock.
	 *
	 * Given [published] — the exact text and intent that reached Hive:
	 *
	 *  - **the entry is that draft** → removed, committed and verified gone;
	 *  - **same intent, different text** → the *current* text is kept byte for
	 *    byte and the intent is replaced with an explicit null, so the entry
	 *    becomes a fresh unsent draft that a later Send will publish under an
	 *    intent of its own. Nothing is published here;
	 *  - **absent, undecodable, or a different intent** → untouched. A corrupt
	 *    entry stays locked and somebody else's attempt stays theirs;
	 *  - **the write did not stick** → [SnapReplySettlement.Failed], and the
	 *    caller must not report the draft as settled.
	 */
	fun settle(key: String, published: SnapReplyDraft): SnapReplySettlement
}

internal class SharedPreferencesSnapReplyDraftStore(
	context: Context,
) : SnapReplyDraftStore {

	private val prefs: SharedPreferences =
		context.getSharedPreferences(REPLY_DRAFTS, Context.MODE_PRIVATE)

	override fun read(key: String): SnapReplyDraftRead {
		val raw = prefs.getString(key, null) ?: return SnapReplyDraftRead.Absent
		return SnapReplyDraft.fromJson(raw)?.let(SnapReplyDraftRead::Present)
			?: SnapReplyDraftRead.Corrupt("this reply draft's saved state could not be read")
	}

	// The three mutators are synchronized against each other so that each one's
	// read-then-write is a single step from any other caller's point of view.
	// This is one small object guarding its own preference file, not a locking
	// scheme: the store is the only writer, and the window being closed is the
	// one between deciding what is stored and changing it.

	@Synchronized
	override fun writeText(key: String, text: String) {
		val current = read(key)
		// Never overwrite evidence. See [SnapReplyDraftStore.writeText].
		if (current is SnapReplyDraftRead.Corrupt) return
		val existing = (current as? SnapReplyDraftRead.Present)?.draft ?: SnapReplyDraft.NONE
		val next = existing.copy(text = text)
		if (next.isEmpty) {
			prefs.edit().remove(key).apply()
		} else {
			prefs.edit().putString(key, next.toJson()).apply()
		}
	}

	@Synchronized
	override fun beginIntent(key: String, intentId: String): Boolean {
		val current = read(key)
		// A corrupt slot gets no intent, so it can never reach a signature.
		if (current is SnapReplyDraftRead.Corrupt) return false
		val existing = (current as? SnapReplyDraftRead.Present)?.draft ?: SnapReplyDraft.NONE
		val serialized = existing.copy(intentId = intentId).toJson()
		// commit(), not apply(): this record is the only thing standing between a
		// lost response and a duplicate public comment, so it has to be on disk
		// before the network call starts — and its result has to be checked.
		val committed = runCatching {
			prefs.edit().putString(key, serialized).commit()
		}.getOrDefault(false)
		if (!committed) return false
		// Read back. A commit that returned true but stored something else — a
		// full disk, a corrupted preference file — is not durable state.
		return prefs.getString(key, null) == serialized
	}

	@Synchronized
	override fun removeIf(key: String, expected: SnapReplyDraft?): Boolean {
		val raw = prefs.getString(key, null) ?: return false
		// `null == null` is what lets a still-unreadable entry be removed, and
		// what stops that same call removing a draft that parses.
		if (SnapReplyDraft.fromJson(raw) != expected) return false
		val committed = runCatching { prefs.edit().remove(key).commit() }.getOrDefault(false)
		if (!committed) return false
		// Prove it is gone. A commit that reported success but left the entry
		// behind must not be reported to the caller as a cleared draft.
		return prefs.getString(key, null) == null
	}

	@Synchronized
	override fun settle(key: String, published: SnapReplyDraft): SnapReplySettlement {
		val raw = prefs.getString(key, null) ?: return SnapReplySettlement.Untouched
		// Undecodable: locked, and settlement is not the thing that unlocks it.
		val current = SnapReplyDraft.fromJson(raw) ?: return SnapReplySettlement.Untouched
		// Another attempt's draft, or one that has never been sent. Not ours.
		if (current.intentId == null || current.intentId != published.intentId) {
			return SnapReplySettlement.Untouched
		}

		if (current == published) {
			val committed = runCatching { prefs.edit().remove(key).commit() }.getOrDefault(false)
			if (!committed) return SnapReplySettlement.Failed
			// Prove it is gone. A commit that reported success but left the entry
			// behind must not be reported as a retired draft.
			return if (prefs.getString(key, null) == null) {
				SnapReplySettlement.Retired
			} else {
				SnapReplySettlement.Failed
			}
		}

		// Same intent, newer words. Keep the words, let the intent go.
		val released = current.copy(intentId = null)
		val serialized = released.toJson()
		val committed = runCatching {
			prefs.edit().putString(key, serialized).commit()
		}.getOrDefault(false)
		if (!committed) return SnapReplySettlement.Failed
		return if (prefs.getString(key, null) == serialized) {
			SnapReplySettlement.Released(released)
		} else {
			SnapReplySettlement.Failed
		}
	}

	companion object {
		const val REPLY_DRAFTS = "rustedwax_snap_reply_drafts"
	}
}
