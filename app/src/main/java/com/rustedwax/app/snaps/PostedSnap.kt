package com.rustedwax.app.snaps

/**
 * A root Snap that is on chain, as the History card shows it back.
 *
 * Deliberately carries only what the posted card draws. There is no artist, no
 * title, no media kind and no link: the card is a picture of *what the user
 * said*, and every other field would be RustedWax restating something it either
 * generated itself or already decided elsewhere.
 *
 * [fromChain] records where [userText] and [createdAtEpochSec] came from. It is
 * not shown to anyone — it exists so a refresh that could not read Hive is
 * distinguishable from one that did, in tests and in reasoning, rather than
 * being guessed at from the values.
 */
data class PostedSnap(
	val author: String,
	val permlink: String,
	/** Exactly the text the user typed. Never trimmed, never rewritten. */
	val userText: String,
	val createdAtEpochSec: Long,
	val fromChain: Boolean,
) {
	val contentId: String get() = "$author/$permlink"
}

/**
 * Recovers the user's own words from a stored or on-chain Snap body.
 *
 * The v1 body is frozen as
 *
 * ```
 * {user text}
 *
 * https://youtu.be/{videoId}
 *
 * #scrobblelife #scrobble #rustedwax
 * ```
 *
 * so getting back to `{user text}` is subtraction, not parsing. **Only the
 * generated tail is removed**, and only when the body really ends with exactly
 * that tail: everything before it is the user's, byte for byte, including the
 * leading and trailing whitespace [SnapPayloadBuilder] was careful not to touch
 * on the way out.
 *
 * Two mistakes this is shaped to avoid:
 *
 *  - **stripping the user's own link or hashtags.** The tail is matched at the
 *    very end and removed once. A user who typed `look https://youtu.be/x
 *    #scrobble` keeps every character of it; what goes is the copy RustedWax
 *    appended after them.
 *  - **inventing a tail that isn't there.** A body that does not end in the
 *    frozen shape is returned untouched and reported as unrecognised, so the
 *    caller can prefer a body it *does* recognise rather than showing generated
 *    text it half-removed.
 *
 * The pieces are read from [SnapPayloadBuilder] rather than spelled out again,
 * so the two cannot drift apart silently — and a test pins that they agree.
 */
object PostedSnapBody {

	/**
	 * The user's text, or null when [body] is not a v1 Snap body.
	 *
	 * Null is the useful answer: it means "this string contains something this
	 * code did not generate and cannot account for", which is exactly when the
	 * caller must not display it.
	 */
	fun userText(body: String): String? {
		val tail = SEPARATOR + SnapPayloadBuilder.TAG_LINE
		// Must be the end of the body, not merely present in it.
		if (!body.endsWith(tail)) return null
		val head = body.dropLast(tail.length)

		val urlMark = SEPARATOR + URL_PREFIX
		val urlStart = head.lastIndexOf(urlMark)
		if (urlStart < 0) return null

		// What sits between `https://youtu.be/` and the hashtag line has to be a
		// plausible video id and nothing else. Without this check a user text
		// ending in a stray `\n\nhttps://youtu.be/` would be read as the anchor
		// and their words would lose their last line.
		val videoId = head.substring(urlStart + urlMark.length)
		if (videoId.isEmpty() || !VIDEO_ID.matches(videoId)) return null

		return head.substring(0, urlStart)
	}

	// There is deliberately no display-length limit here, and no caller that
	// shortens what this returns.
	//
	// An earlier revision cut the text at 1000 UTF-16 units before drawing it.
	// That unit is the wrong one twice over: the composer's rule is 200 grapheme
	// clusters, and a cluster can be many units — 200 family emoji are a legal
	// Snap and about 2200 units, so a perfectly valid Snap was being cut in
	// half, and cut at a boundary that has nothing to do with where characters
	// begin and end. Whatever the composer accepted, the card shows. All of it.

	private const val SEPARATOR = "\n\n"
	private const val URL_PREFIX = "https://youtu.be/"

	/** YouTube's own id alphabet, the same one the builder will put in a URL. */
	private val VIDEO_ID = Regex("^[A-Za-z0-9_-]+$")
}

/**
 * How old a posted Snap is, in the shortest honest form.
 *
 * `now`, `4m`, `2h`, `3d` — and nothing else. This is not a date feature: there
 * is no calendar, no locale format and no absolute timestamp anywhere, because
 * the card needs one glanceable token and every additional form is a decision
 * about presentation that Stage 3 was not asked to make.
 *
 * A Snap that appears to be from the future — the device clock and the chain
 * disagreeing by a few seconds is ordinary — reads as `now` rather than as a
 * negative age.
 */
object SnapAge {

	fun label(nowEpochSec: Long, createdAtEpochSec: Long): String {
		val elapsed = (nowEpochSec - createdAtEpochSec).coerceAtLeast(0)
		return when {
			elapsed < MINUTE -> "now"
			elapsed < HOUR -> "${elapsed / MINUTE}m"
			elapsed < DAY -> "${elapsed / HOUR}h"
			else -> "${elapsed / DAY}d"
		}
	}

	private const val MINUTE = 60L
	private const val HOUR = 60L * MINUTE
	private const val DAY = 24L * HOUR
}

/**
 * Where a Hive account's avatar image lives.
 *
 * Built from the account *name* and nothing else. The obvious alternative —
 * `profile.profile_image` out of the account's `posting_json_metadata` — is an
 * arbitrary URL supplied by whatever wrote that metadata, and fetching it would
 * be RustedWax following a link out of untrusted chain content. The name-based
 * form on Hive's own image host asks a host RustedWax already chose, for a
 * picture keyed to a name it already knows.
 *
 * Returns null for anything that is not a Hive account name, so a malformed or
 * hostile value can never be pasted into a URL. The card draws its neutral
 * placeholder on null, exactly as it does when the image simply fails to load.
 */
object HiveAvatarUrl {

	fun of(account: String): String? =
		if (HiveAccountName.isValid(account)) {
			"https://images.hive.blog/u/$account/avatar/small"
		} else {
			null
		}
}

/**
 * Whether a string is shaped like a Hive account name.
 *
 * Moved to [com.rustedwax.hive.HiveAccountName] and aliased here so every
 * existing caller reads the same way. The move was forced by reuse rather than
 * chosen for tidiness: `HiveVotes` has to reject an authoritative vote row whose
 * voter is not a real account name, it lives in the `hive` module, and `hive`
 * cannot see `app`. The alternative was a second copy of Hive's account grammar
 * in the module that talks to Hive, which is exactly the duplication that lets
 * two validators disagree about who exists.
 *
 * The rules themselves are unchanged, and `HiveAccountNameTest` still pins them.
 */
typealias HiveAccountName = com.rustedwax.hive.HiveAccountName

/**
 * Chain timestamps, which arrive as `2026-09-17T12:36:00` in UTC and no other
 * form.
 *
 * Fails to null rather than throwing. A Snap whose `created` field is missing,
 * truncated or nonsense still has a perfectly good local time to fall back on,
 * and there is no version of "History crashed" that is better than showing an
 * age one second out.
 */
object ChainTime {

	/**
	 * Seconds since the epoch, or null for anything that is not exactly
	 * `yyyy-MM-dd'T'HH:mm:ss`.
	 *
	 * `SimpleDateFormat` was the wrong tool and was replaced. Even with leniency
	 * off it parses a *prefix* and ignores the rest, so `2026-09-17T12:36:00junk`
	 * came back as a valid time; and its field widths are advisory, so
	 * `2026-9-7T1:2:3` parsed too. Both are strings the chain does not produce,
	 * and reading a timestamp out of one is inventing an age for a Snap rather
	 * than admitting the field could not be read.
	 *
	 * So the shape is checked character by character — every separator in its
	 * exact place, every field its exact width, nothing before and nothing after
	 * — and only then are the numbers handed to a non-lenient calendar, which is
	 * what rejects the 30th of February, a 13th month and a 25th hour. Null sends
	 * the card back to the locally stored time, which is always available and
	 * within seconds of the truth.
	 */
	fun epochSec(iso: String?): Long? {
		if (iso == null || iso.length != CANONICAL_LENGTH) return null
		if (iso[4] != '-' || iso[7] != '-' || iso[10] != 'T') return null
		if (iso[13] != ':' || iso[16] != ':') return null

		val year = iso.digits(0, 4) ?: return null
		val month = iso.digits(5, 7) ?: return null
		val day = iso.digits(8, 10) ?: return null
		val hour = iso.digits(11, 13) ?: return null
		val minute = iso.digits(14, 16) ?: return null
		val second = iso.digits(17, 19) ?: return null

		return runCatching {
			val calendar = java.util.GregorianCalendar(
				java.util.TimeZone.getTimeZone("UTC"),
				java.util.Locale.US,
			).apply {
				clear()
				// Non-lenient: out-of-range fields throw here rather than rolling
				// over into a neighbouring month, which is how `2026-02-30` used
				// to become the 2nd of March.
				isLenient = false
				set(year, month - 1, day, hour, minute, second)
			}
			calendar.timeInMillis / 1000
		}.getOrNull()
	}

	/** The digits between [from] and [to], or null if any of them is not a digit. */
	private fun String.digits(from: Int, to: Int): Int? {
		var value = 0
		for (i in from until to) {
			val c = this[i]
			if (c !in '0'..'9') return null
			value = value * 10 + (c - '0')
		}
		return value
	}

	/** `2026-09-17T12:36:00` — nineteen characters, and no other length. */
	private const val CANONICAL_LENGTH = 19
}
