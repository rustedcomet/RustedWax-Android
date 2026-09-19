package com.rustedwax.hive

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger

/**
 * The signed-in account's own vote on one comment, as the chain describes it.
 *
 * Deliberately five cases rather than a nullable percentage. The difference
 * between "there is no vote", "there is a vote worth nothing" and "there is
 * something here I could not read" is the whole safety question a Like has to
 * answer, and a nullable number collapses all three into the same answer — the
 * optimistic one, which is the one that overwrites somebody's existing vote.
 *
 * Only the **viewer's own** vote is ever modelled. The rest of `active_votes`
 * is other people's business, it is unbounded in size, and nothing RustedWax
 * draws or decides needs it.
 */
sealed interface ViewerVote {

	/**
	 * No row for this voter. Eligible to Like.
	 *
	 * Reachable **only** from a response that was wholly well formed — see
	 * [HiveVotes.viewerVote]. This is the one value that authorizes a write, so
	 * it is the one value that may never be produced by giving up on something.
	 */
	data object None : ViewerVote

	/**
	 * A vote that counts for something. **Never overwritten**: v1 has no
	 * Unlike and no re-strength control, so there is no user intent a second
	 * broadcast could express, and re-voting at the app's default is exactly how
	 * a 100% vote cast on another frontend becomes a 10% one.
	 *
	 * [percent] is the chain's own `percent` where the response carried one —
	 * `bridge.get_discussion` does not — and is never invented.
	 */
	data class Positive(val percent: Int?, val rshares: Long?) : ViewerVote

	/** A vote that was removed, or cast at zero. Eligible to Like. */
	data object Zero : ViewerVote

	/**
	 * A downvote, from some other frontend. v1 has no concept of this state, so
	 * the control goes inert rather than quietly turning a deliberate downvote
	 * into a 10% upvote.
	 */
	data class Negative(val percent: Int?, val rshares: Long?) : ViewerVote

	/**
	 * The response could not be trusted to answer the question.
	 *
	 * Distinct from [None] on purpose, and the distinction is the entire point
	 * of this type: anything unreadable may be hiding a vote, so it refuses the
	 * write rather than looking like a clean slate.
	 */
	data class Unreadable(val reason: String) : ViewerVote
}

/**
 * The result of the safety read that stands between a Like tap and a signature.
 *
 * [Fresh] is the **only** thing that may authorize a vote, and it can only be
 * built by a node that was proven current immediately before it answered — see
 * [HiveRpc.findViewerVote]. [Unavailable] refuses, always: "RustedWax could not
 * establish the current vote state" and "there is no vote" are different facts,
 * and treating the first as the second is how a stale node causes an overwrite.
 */
sealed interface HiveVoteRead {
	data class Fresh(val vote: ViewerVote, val node: String) : HiveVoteRead
	data class Unavailable(val reason: String) : HiveVoteRead
}

/**
 * Reads one voter's own vote out of an untrusted votes array, **fail-closed**.
 *
 * Pure, and separate from [HiveRpc], so the parsing rules can be pinned without
 * a network. Two response shapes are supported and they carry different amounts
 * of information:
 *
 *  - **`condenser_api.get_active_votes`** — a top-level array of
 *    `{time, voter, weight, percent, rshares, reputation}`. This is the shape
 *    the safety read uses. Verified identical on `api.hive.blog`,
 *    `api.deathwing.me`, `api.syncad.com` and `api.openhive.network` on
 *    2026-09-19, including the empty-array answer for an unvoted comment;
 *  - **`active_votes`**, as carried by `bridge.get_discussion` — `{voter,
 *    rshares}` and nothing else. Used for **display only**: it is enough to
 *    draw an outline or a filled heart, and deliberately never enough to
 *    authorize a write.
 *
 * ## The one rule
 *
 * [ViewerVote.None] is the only answer that lets a vote be cast, so on the
 * authoritative path it may be returned **only** when all of this holds:
 *
 *  - the top-level result really is an array;
 *  - every row is a JSON object;
 *  - every row's `voter` is a string **and a syntactically valid Hive account
 *    name** — see [HiveAccountName], which is the same validator the rest of
 *    the app judges account names with;
 *  - no two rows claim the viewer;
 *  - if a viewer row exists, both its required numbers are present and exact;
 *  - and after all of that, none of the rows was the viewer's.
 *
 * Anything else is [ViewerVote.Unreadable].
 *
 * This is the opposite of the usual parser instinct. Skipping a row that does
 * not make sense and carrying on is exactly how a malformed row *belonging to
 * this voter* becomes an absence, and an absence becomes a vote that overwrites
 * the one that row described. `{"voter": "", "percent": 2500, "rshares": 5}` is
 * the shape that made the point: a valid `String`, so a type check waved it
 * through, and unequal to any real account name, so it was skipped — and the
 * array it sat in then answered "you have not voted here".
 */
object HiveVotes {

	/**
	 * Which response this is, and therefore what the viewer's row must carry.
	 *
	 * The two shapes are not equally trusted and must not be parsed as though
	 * they were. An authoritative row is about to authorize an irreversible
	 * write, so every field it needs has to be there; a display row only decides
	 * how a heart is drawn, and the one it is missing is missing by design.
	 */
	internal enum class Shape {
		/**
		 * `condenser_api.get_active_votes`. **Both** `percent` and `rshares` are
		 * required on the viewer's row, and **every** row must name a valid Hive
		 * account — see [viewerVote] for why an incomplete or unattributable row
		 * here can never be allowed to mean "no vote".
		 */
		AUTHORITATIVE,

		/**
		 * `bridge.get_discussion`'s `active_votes`, which carries `voter` and
		 * `rshares` and no `percent` at all. Absent is therefore normal, and the
		 * sign of `rshares` is all there is to go on. Nothing read this way ever
		 * authorizes anything, so a row naming no valid account is passed over
		 * rather than blanking the conversation — it simply can never match the
		 * viewer, which is the only thing that would matter.
		 */
		DISPLAY,
	}

	/**
	 * The voter's own vote in a `condenser_api.get_active_votes` response.
	 *
	 * The authoritative shape, and the one the Like safety read is built on.
	 */
	fun fromActiveVotes(votes: JSONArray?, voter: String): ViewerVote =
		viewerVote(votes, voter, Shape.AUTHORITATIVE)

	/**
	 * The voter's own vote in one `bridge` comment object. **Display only.**
	 *
	 * A comment with no `active_votes` key is [ViewerVote.Unreadable], not
	 * [ViewerVote.None]. The responses RustedWax reads always carry the field,
	 * so its absence means the object is not the shape this code understands —
	 * and guessing "nobody voted" about an object that was never parsed is the
	 * same optimistic mistake everywhere else here refuses to make.
	 */
	fun inComment(comment: JSONObject, voter: String): ViewerVote {
		val raw = comment.opt("active_votes")
		if (raw == null || raw === JSONObject.NULL) {
			return ViewerVote.Unreadable("no active_votes in the comment")
		}
		val list = raw as? JSONArray
			?: return ViewerVote.Unreadable("active_votes was not a list")
		// `bridge` omits `percent` entirely, so the key is simply absent and the
		// classification falls through to the sign of `rshares`. One parser, told
		// which shape it is reading, rather than two that can drift.
		return viewerVote(list, voter, Shape.DISPLAY)
	}

	/**
	 * How many **positive** votes one `bridge` comment carries. Display only.
	 *
	 * The social half of a heart: the heart says whether *you* liked something,
	 * this says how many people did. Two different questions, and this one is
	 * never allowed to answer the first.
	 *
	 * Deliberately **not** `active_votes.size`. That list holds every vote of
	 * every sign — a downvote and a vote whose weight rounded to nothing are
	 * both rows in it, and neither is a Like. So each row is classified the
	 * same way the viewer's own row is, and only [ViewerVote.Positive] counts.
	 *
	 * Where it differs from [viewerVote] is what a bad row costs. There, an
	 * unreadable row can mean the viewer's own vote went unseen, so the whole
	 * read fails closed — it is about to authorize an irreversible write. Here
	 * nothing is authorized: a row this cannot read is simply not counted, and
	 * the number beside a heart is one too low rather than the conversation
	 * being unreadable. The result is presentation, it reaches no decision, and
	 * it never replaces the authoritative `get_active_votes` read that a Like
	 * is gated on.
	 *
	 * Returns zero for a comment with no readable `active_votes` at all, for
	 * the same reason: a count is a thing to draw, and drawing none is the
	 * honest answer when there is nothing to count from.
	 */
	fun positiveCount(comment: JSONObject): Int {
		val list = comment.opt("active_votes") as? JSONArray ?: return 0
		var positives = 0
		for (i in 0 until list.length()) {
			val row = list.opt(i) as? JSONObject ?: continue
			// A row that cannot name an account is not counted. It may well be
			// a real vote, but a count built partly from rows nobody can
			// attribute is a number that cannot be checked against anything.
			val voter = row.opt("voter") as? String ?: continue
			if (!HiveAccountName.isValid(voter)) continue
			if (viewerRowVote(row, Shape.DISPLAY) is ViewerVote.Positive) positives++
		}
		return positives
	}

	/**
	 * Scan for the one row that belongs to [voter], refusing anything doubtful.
	 *
	 * **Every** row is structurally checked, not just the one that matches. A
	 * row that is not an object, or whose `voter` is not a string, cannot be
	 * attributed to anybody — so it might be this voter's, and the whole read
	 * has to fail rather than conclude an absence around it. Rows that are
	 * well formed and belong to somebody else are passed over without their
	 * numbers being read at all: whatever is wrong with a stranger's `rshares`
	 * cannot change the answer to this question.
	 *
	 * The voter field is read with `opt(...) as? String` and **never**
	 * `optString`. `optString` coerces: a response carrying `"voter": 42` comes
	 * back as the string `"42"`, and `"voter": true` as `"true"`. A wrong-typed
	 * identity field is a malformed response, not an account.
	 *
	 * Matched case-insensitively, the same way [HiveBroadcaster] refuses a
	 * self-Like: Hive account names are lowercase, and a response that disagrees
	 * about case is still talking about the same account.
	 *
	 * Internal so the rule is pinned by tests rather than by this comment.
	 */
	internal fun viewerVote(
		votes: JSONArray?,
		voter: String,
		shape: Shape,
	): ViewerVote {
		// Normalised before it is judged: Hive account names are lowercase, the
		// vault already stores them that way, and a caller that happened to hand
		// over `ALICE` is naming a real account rather than a malformed one.
		val viewer = voter.trim().lowercase()
		if (!HiveAccountName.isValid(viewer)) {
			return ViewerVote.Unreadable("no readable signed-in account to look a vote up for")
		}
		if (votes == null) return ViewerVote.Unreadable("vote list missing or malformed")

		var found: JSONObject? = null
		for (i in 0 until votes.length()) {
			val row = votes.opt(i) as? JSONObject
				?: return ViewerVote.Unreadable("a vote row was not an object")
			val rowVoter = row.opt("voter") as? String
				?: return ViewerVote.Unreadable("a vote row had no readable voter")

			if (!HiveAccountName.isValid(rowVoter)) {
				// An identity that is not an account name cannot be attributed to
				// anybody — so on the authoritative path it might be a damaged
				// copy of the viewer's own row, and an absence measured around it
				// would be an absence this response never proved.
				//
				// `""`, `"   "`, `"Alice"`, `"a..b"` and anything with a `/` in it
				// all land here. Each is a perfectly good `String`, which is
				// exactly why checking the *type* was not enough.
				if (shape == Shape.AUTHORITATIVE) {
					return ViewerVote.Unreadable("a vote row named no valid Hive account")
				}
				// Display cannot authorize anything, so it stays legible rather
				// than blanking a whole conversation over one odd row — but the
				// row is still never allowed to *match*, so it can never render as
				// this viewer's Like.
				continue
			}

			if (!rowVoter.equals(viewer, ignoreCase = true)) continue
			// `(voter, author, permlink)` is unique on chain, so a second row for
			// one account is evidence the response is wrong — and picking one of
			// the two would be choosing which untrusted number to believe.
			if (found != null) return ViewerVote.Unreadable("two vote rows for one account")
			found = row
		}

		// Reached only after every row was understood. This is the sole route to
		// an answer that authorizes a write.
		//
		// Note what is deliberately *not* checked above: the numbers on rows
		// belonging to other voters. Their identity has to be a real account,
		// because an unattributable row might be this viewer's — but whatever is
		// wrong with a named stranger's `rshares` cannot change the answer to
		// this question, and refusing over it would let any third party's
		// malformed row stop this user Liking anything.
		val row = found ?: return ViewerVote.None

		return viewerRowVote(row, shape)
	}

	/**
	 * The viewer's own row, read whole or not at all.
	 *
	 * ## Why an incomplete row can never authorize a vote
	 *
	 * [ViewerVote.Zero] is *eligible to Like* — it flows straight through to
	 * `SnapLikeDecision.Cast`. So every route to it has to be a positive
	 * statement that the chain holds a vote worth nothing, and never an artefact
	 * of a field this parser could not find.
	 *
	 * An earlier revision treated each field as independently optional, and two
	 * incomplete rows reached `Zero` through the gap:
	 *
	 *  - `percent` missing with `rshares: 0` — the percent was skipped, the
	 *    rshares decided, and a row that never said what it was became eligible;
	 *  - `percent: 0` with `rshares` missing — the percent decided alone, and a
	 *    row with no weight at all became eligible.
	 *
	 * Either could describe a real vote whose other half simply did not survive
	 * the response, and Liking over it would overwrite that vote. So on the
	 * [Shape.AUTHORITATIVE] path both fields must be **present and exactly
	 * parseable** or the row is [ViewerVote.Unreadable], which refuses.
	 *
	 * `bridge` responses are [Shape.DISPLAY] and genuinely carry no `percent`,
	 * so absence there is normal and the sign of `rshares` decides. That path
	 * authorizes nothing — an unreadable display row draws an inert heart, and
	 * the tap that follows re-reads the chain authoritatively before anything is
	 * signed.
	 *
	 * `rshares` is required on **both** paths. Every real row of either shape
	 * carries it, so its absence means the row is not the shape this understands.
	 */
	private fun viewerRowVote(row: JSONObject, shape: Shape): ViewerVote {
		val percent = when (val p = row.integer(PERCENT_KEY)) {
			is Integral.Malformed -> return ViewerVote.Unreadable("vote percent: ${p.reason}")
			is Integral.Absent -> if (shape == Shape.AUTHORITATIVE) {
				return ViewerVote.Unreadable("the vote row carried no percent")
			} else {
				null
			}
			is Integral.Value -> p.value.toIntExactOrNull()
				?: return ViewerVote.Unreadable("vote percent out of range: ${p.value}")
		}
		val rshares = when (val r = row.integer(RSHARES_KEY)) {
			is Integral.Malformed -> return ViewerVote.Unreadable("vote weight: ${r.reason}")
			is Integral.Absent -> return ViewerVote.Unreadable("the vote row carried no rshares")
			is Integral.Value -> r.value
		}

		// A vote's rshares is its percent scaled by voting power and stake, so
		// the two can never point opposite ways — the scale is non-negative.
		// Verified rather than assumed: across 197 rows from 24 live comments on
		// 2026-09-19, no row had strictly opposite signs, while a downvote read
		// `percent: -25, rshares: -1775976786` — agreeing.
		//
		// Zero rshares is explicitly *not* a contradiction and must not be
		// treated as one. The same sample held 23 rows with a positive percent
		// and 25 rows in total whose rshares had rounded to zero, including two
		// **negative** percents at zero rshares. A small vote from a drained or
		// low-stake account is an ordinary thing, and refusing it would refuse a
		// real vote.
		if (percent != null && contradicts(percent, rshares)) {
			return ViewerVote.Unreadable(
				"vote percent $percent disagrees with rshares $rshares",
			)
		}

		return classify(percent, rshares)
	}

	/** Strictly opposite signs. Zero on either side is never a contradiction. */
	private fun contradicts(percent: Int, rshares: Long): Boolean =
		(percent > 0 && rshares < 0) || (percent < 0 && rshares > 0)

	/**
	 * Which way a vote points, given whichever of the two numbers was readable.
	 *
	 * The declared percentage wins where there is one. It is the user's stated
	 * intent, whereas `rshares` is that intent multiplied by whatever voting
	 * power they happened to have — a genuine 10% vote cast on a drained account
	 * can round to zero rshares, and reading that as "no vote" would let
	 * RustedWax overwrite it.
	 *
	 * [rshares] is not nullable: by the time this is called it has been proven
	 * present and exact on both paths, so there is no "neither was readable"
	 * case left to get wrong here. The presence rules live in [viewerRowVote].
	 *
	 * A zero-rshares vote keeps whatever sign its percent declared — those are
	 * real votes, not removed ones, and the live sample is full of them.
	 *
	 * Internal so the precedence is pinned by a test rather than by this
	 * comment.
	 */
	internal fun classify(percent: Int?, rshares: Long): ViewerVote = when {
		percent != null && percent > 0 -> ViewerVote.Positive(percent, rshares)
		percent != null && percent < 0 -> ViewerVote.Negative(percent, rshares)
		percent != null -> ViewerVote.Zero
		rshares > 0 -> ViewerVote.Positive(null, rshares)
		rshares < 0 -> ViewerVote.Negative(null, rshares)
		else -> ViewerVote.Zero
	}

	/** What reading one numeric field found. */
	private sealed interface Integral {
		/** The key is not present at all — legitimate for `percent` on `bridge`. */
		data object Absent : Integral

		/** An exact whole number. */
		data class Value(val value: Long) : Integral

		/** Present and not usable. Never treated as absent. */
		data class Malformed(val reason: String) : Integral
	}

	/**
	 * One field as an **exact** whole number, or a refusal.
	 *
	 * Nothing here narrows, rounds or truncates. `toInt()` on a `Double` and
	 * `toLong()` on a `BigDecimal` both discard the fractional part silently, and
	 * a `Double` may already have rounded before this code ever sees it, so
	 * a response carrying `"percent": 0.9` would have become a `0` percent —
	 * which classifies as [ViewerVote.Zero], which is *eligible to Like*, which
	 * is a vote cast over whatever that row really said. Fractional values are
	 * therefore refused outright rather than read approximately.
	 *
	 * Accepted: JSON **integer** tokens, however the runtime boxes them —
	 * `Int`, `Long`, `Short`, `Byte`, and a `BigInteger` inside `Long` range;
	 * and a numeric **string** of digits with an optional leading sign, which
	 * is read exactly and so carries none of the rounding hazard below.
	 *
	 * Refused: **every decimal type** (`Double`, `Float`, `BigDecimal`),
	 * anything wider than `Long`, an explicit `null`, a boolean, an object, an
	 * array, an empty string, a string with surrounding whitespace, and a
	 * numeric string in any other notation — `"1e3"`, `"0x10"`, `" 25"`,
	 * `"2500.0"`.
	 */
	private fun JSONObject.integer(name: String): Integral {
		val raw = opt(name) ?: return Integral.Absent
		if (raw === JSONObject.NULL) return Integral.Malformed("was null")
		return when (raw) {
			is Int -> Integral.Value(raw.toLong())
			is Long -> Integral.Value(raw)
			is Short -> Integral.Value(raw.toLong())
			is Byte -> Integral.Value(raw.toLong())
			// Range-checked by hand rather than with `longValueExact()`, which
			// is API 31 while this runs from 26. `bitLength()` excludes the
			// sign bit, so anything under 64 fits a signed `Long` exactly.
			is BigInteger -> if (raw.bitLength() < Long.SIZE_BITS) {
				Integral.Value(raw.toLong())
			} else {
				Integral.Malformed("too large to be a vote value: $raw")
			}
			is String -> if (INTEGER_TEXT.matches(raw)) {
				raw.toLongOrNull()?.let { Integral.Value(it) }
					?: Integral.Malformed("too large to be a vote value: $raw")
			} else {
				Integral.Malformed("not a whole number: \"$raw\"")
			}
			// Everything else, and the decimal types are the point.
			//
			// Android's `JSONTokener` boxes any token containing `.`, `e` or
			// `E` as a `Double`, and a `Double` has already discarded what
			// would be needed to trust it: `9007199254740993.0` arrives as
			// `9007199254740992.0`, byte-identical to the token one below it.
			// No test applied afterwards recovers the original — `toLong()`,
			// a floor check, a `% 1.0` check and a 2^53 bound all agree the
			// rounded value is "whole", because it is. They simply cannot see
			// that it is the wrong whole number.
			//
			// That mattered here more than anywhere: a row whose `rshares`
			// rounds and whose `percent` reads 0 classifies as
			// [ViewerVote.Zero], which is *eligible*, which authorizes an
			// irreversible vote. `BigDecimal` is refused for the same reason —
			// the reference `org.json` boxes the same tokens that way, and its
			// `scale()` cannot separate `9.007199254740992E15` from
			// `9007199254740992` either.
			//
			// Nothing real is lost: across 197 authoritative rows sampled from
			// all four default nodes, every `percent` and every `rshares` came
			// back as a JSON integer.
			else -> Integral.Malformed("was a ${raw.javaClass.simpleName}")
		}
	}

	/**
	 * A `Long` narrowed to `Int`, or null when it does not fit.
	 *
	 * Used only for `percent`, whose range the chain already bounds far inside
	 * `Int`. A value that does not fit is not a percent this can read back, so
	 * it refuses rather than truncating.
	 */
	private fun Long.toIntExactOrNull(): Int? =
		if (this in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) toInt() else null

	/** Digits, with an optional sign. No exponent, no radix prefix, no padding. */
	private val INTEGER_TEXT = Regex("^[+-]?\\d+$")

	/**
	 * Both shapes spell the declared strength `percent` where they carry one.
	 * `bridge` omits it, which reads as [Integral.Absent] — normal on
	 * [Shape.DISPLAY] and a refusal on [Shape.AUTHORITATIVE].
	 */
	private const val PERCENT_KEY = "percent"

	private const val RSHARES_KEY = "rshares"
}
