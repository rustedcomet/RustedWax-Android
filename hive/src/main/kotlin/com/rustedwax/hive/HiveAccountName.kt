package com.rustedwax.hive

/**
 * Whether a string is shaped like a Hive account name.
 *
 * An earlier revision asked only whether every character was drawn from
 * `[a-z0-9.-]`. That is a *charset* test wearing a validator's name: `...`,
 * `---`, `.alice`, `alice.` and `a..b` all pass it, and each one becomes a
 * request to an image host for something that cannot be an account. The point of
 * checking at all is to not ask.
 *
 * So this checks the structure Hive actually defines:
 *
 *  - three to sixteen characters in total;
 *  - one or more dot-separated segments, each at least three characters;
 *  - a segment starts with a lowercase letter and ends with a letter or digit;
 *  - inside, only lowercase letters, digits and hyphens.
 *
 * Which rejects a leading or trailing dot (it makes an empty segment), two dots
 * in a row (likewise), a segment of pure punctuation, and anything with an
 * uppercase letter, a slash, a space, a control character or a URL in it.
 *
 * Consecutive interior hyphens are **allowed**. An earlier revision refused
 * `ab--cd`, which is not one of Hive's rules — the grammar constrains the first
 * and last character of a label and the alphabet in between, and nothing more.
 * Inventing an extra restriction here means refusing to show a real account
 * their own avatar.
 *
 * Two callers, and they want it for opposite reasons. Stage 3 asks before
 * fetching an avatar, where a refusal only means "don't make that request".
 * [HiveVotes] asks about every voter in an authoritative vote list, where a
 * refusal means the whole response is untrustworthy — because a row that
 * cannot name an account might be a damaged copy of the viewer's own.
 *
 * It is still never a reason to refuse an account at sign-in; [KeyValidator]
 * decides that, against the chain.
 */
object HiveAccountName {

	fun isValid(name: String): Boolean {
		if (name.length !in MIN_LENGTH..MAX_LENGTH) return false
		// `split` on a name that starts or ends with a dot yields an empty
		// segment, which fails the length rule below — so those need no case of
		// their own, and neither does `..`.
		return name.split('.').all { it.isValidSegment() }
	}

	private fun String.isValidSegment(): Boolean {
		if (length < MIN_SEGMENT) return false
		if (!first().isLowerLetter()) return false
		if (!(last().isLowerLetter() || last().isDigit())) return false
		return all { it.isLowerLetter() || it.isDigit() || it == '-' }
	}

	private fun Char.isLowerLetter() = this in 'a'..'z'

	private fun Char.isDigit() = this in '0'..'9'

	private const val MIN_LENGTH = 3
	private const val MAX_LENGTH = 16
	private const val MIN_SEGMENT = 3
}
