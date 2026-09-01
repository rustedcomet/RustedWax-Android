package com.rustedwax.app.enrich

/**
 * Decides when the watch-history route must stop running, and says why.
 *
 * ## The condition this exists for
 *
 * The route reads the history of the account **RustedWax** is signed into. If
 * the YouTube app on the phone is signed out, signed into a *different* account,
 * or playing in its incognito mode, then nothing this phone plays is ever
 * written to the feed RustedWax can read. Every lookup then costs a page fetch
 * and returns an entry belonging to some other listening session, which the
 * corroborator correctly refuses — quietly, one track at a time, forever.
 *
 * The owner's instruction is that the route must refuse outright in that state
 * rather than keep trying. So the miss itself is the evidence: a *successful*
 * feed read that does not contain the track that just played is one data point,
 * and [MISSES_BEFORE_REFUSING] consecutive ones are a diagnosis. That is
 * measurable from the feed alone — no guessing at which account the YouTube app
 * is using, and no reading of a surface Android does not offer us.
 *
 * The three causes are not separable from the feed, so the message names all of
 * them. Two of them *are* separable and are reported exactly instead:
 * `SIGNED_OUT` and `HISTORY_PAUSED` come from YouTube's own answer
 * ([WatchHistoryParser.Reason]) and refuse immediately, without waiting for a
 * pattern.
 *
 * ## Recovery
 *
 * A refusal that can never be revisited would need an app restart to clear
 * after the user fixes the account, so a refused route re-probes once every
 * [RETRY_INTERVAL_MS]. One track landing in the feed clears the state entirely.
 */
class WatchHistoryHealth(
	private val missesBeforeRefusing: Int = MISSES_BEFORE_REFUSING,
	private val retryIntervalMs: Long = RETRY_INTERVAL_MS,
) {
	/**
	 * A detached breaker with the exact state visible at this instant.
	 *
	 * Shadow finalization has to make the same gate/probe/hit decisions as live
	 * finalization without consuming or clearing any of them. Skipping mutations
	 * would change decisions within the run; copying the state lets the shadow
	 * run evolve normally while the live breaker remains untouched.
	 */
	@Synchronized
	internal fun isolatedCopy(): WatchHistoryHealth =
		WatchHistoryHealth(missesBeforeRefusing, retryIntervalMs).also { copy ->
			copy.missKeys.addAll(missKeys)
			copy.anonymousMisses = anonymousMisses
			copy.mismatch = mismatch
			copy.mismatchAtMillis = mismatchAtMillis
			copy.mismatchProbeMillis = mismatchProbeMillis
			copy.routeFault = routeFault
			copy.routeFaultAtMillis = routeFaultAtMillis
			copy.routeProbeMillis = routeProbeMillis
			copy.routeFaultKind = routeFaultKind
		}

	/**
	 * The distinct things that have gone missing in this run, newest last.
	 *
	 * A set, not a counter with a one-deep memory. It used to be
	 * `consecutiveMisses` plus `lastMissKey`, which only rejected a repeat that
	 * arrived *immediately* after itself: `A, B, A` counted as three tracks and
	 * so did `<empty feed>, A, <empty feed>`. Two things going missing while one
	 * of them is retried is not three things going missing, and a Shorts feed
	 * revisits the same items constantly. Bounded by [missesBeforeRefusing],
	 * because it refuses the moment it is full and there is nothing to remember
	 * after that.
	 */
	private val missKeys = LinkedHashSet<String>()

	/** Distinguishes an anonymous observation from a repeat of a named one. */
	private var anonymousMisses = 0

	/**
	 * The native-account mismatch diagnosis: three distinct, health-eligible
	 * ordinary tracks from the **native YouTube app** absent from fresh history.
	 *
	 * Browser playback is neither evidence for it nor evidence against it, so it
	 * neither gates a browser lookup nor is spent by one.
	 */
	@Volatile
	private var mismatch: String? = null

	private var mismatchAtMillis = 0L
	private var mismatchProbeMillis = 0L

	/**
	 * The global route/session fault: YouTube said `SIGNED_OUT` or
	 * `HISTORY_PAUSED`, or the page no longer parses.
	 *
	 * A statement about the stored session, not about which account the phone's
	 * YouTube app uses, so it fails closed for *every* consumer — and every
	 * consumer may therefore make the recovery probe that ends it. It used to be
	 * a flag on the one shared refusal, which meant a browser-only user who fixed
	 * their sign-in stayed blocked forever: nothing they could play was allowed
	 * to prove the route healthy again.
	 */
	@Volatile
	private var routeFault: String? = null

	private var routeFaultAtMillis = 0L
	private var routeProbeMillis = 0L

	/** The exact reason the route is not running, or null when it is. */
	val refusedBecause: String? get() = routeFault ?: mismatch

	/**
	 * The same refusal as a typed cause.
	 *
	 * [refusedBecause] is prose written for a person to read, and prose is not
	 * something production control flow may branch on. A surface that has to
	 * offer the *right* recovery — sign in again, un-pause history, make the
	 * phone's YouTube app match — needs to know which of the four states it is
	 * in, so the state is recorded when it is declared rather than recovered by
	 * reading the sentence afterwards.
	 */
	@Volatile
	private var routeFaultKind: Refusal? = null

	/**
	 * Which condition stood the route down, or null while it is running.
	 *
	 * Derived in the same precedence [refusedBecause] uses rather than stored
	 * alongside it, so the two can never name different states: a declared route
	 * fault outranks an inferred mismatch, and clearing either one cannot leave
	 * the other's cause behind.
	 */
	@get:Synchronized
	val refusedAs: Refusal?
		get() = when {
			routeFault != null -> routeFaultKind
			mismatch != null -> Refusal.ACCOUNT_MISMATCH
			else -> null
		}

	/**
	 * The four ways the watch-history route can be unusable.
	 *
	 * [ACCOUNT_MISMATCH] is deliberately the widest: the feed cannot tell a
	 * signed-out YouTube app from one on a different account or one in
	 * incognito, so this names the condition it can actually prove — that what
	 * this phone plays is not landing in the history RustedWax reads — and
	 * never guesses which account the YouTube app is using.
	 */
	enum class Refusal { SIGNED_OUT, HISTORY_PAUSED, ACCOUNT_MISMATCH, MARKUP_CHANGED }

	/**
	 * Whether a lookup may be attempted now.
	 *
	 * @param accountEvidence whether this lookup is health-eligible native
	 * playback — the only thing the mismatch diagnosis is about, and the only
	 * thing that can disprove it. A browser lookup is neither: it runs straight
	 * through a mismatch pause, and asking must not spend the one probe that
	 * pause allows native playback.
	 *
	 * A declared route fault is different in kind and gates both: it blocks
	 * everything until the interval is up, and then lets *whichever* consumer
	 * asks first make one probe. That probe is what a browser-only user has to
	 * recover with.
	 */
	@Synchronized
	fun mayRun(nowMillis: Long, accountEvidence: Boolean = true): Boolean {
		if (routeFault != null) {
			if (nowMillis - maxOf(routeFaultAtMillis, routeProbeMillis) < retryIntervalMs) {
				return false
			}
			routeProbeMillis = nowMillis
			return true
		}
		if (!accountEvidence || mismatch == null) return true
		if (nowMillis - maxOf(mismatchAtMillis, mismatchProbeMillis) < retryIntervalMs) return false
		mismatchProbeMillis = nowMillis
		return true
	}

	/**
	 * A history response was fetched and parsed into a usable feed.
	 *
	 * That disproves a declared route fault and nothing else. It is deliberately
	 * *not* a native hit: the feed parsing does not say the phone's playback is
	 * in it, so the mismatch run is left exactly as it was — which is what stops
	 * a browser probe from quietly clearing native evidence.
	 */
	@Synchronized
	fun recordRouteHealthy() {
		routeFault = null
		routeFaultAtMillis = 0
		routeProbeMillis = 0
		routeFaultKind = null
	}

	/** A track was found in the feed: whatever was wrong is not wrong now. */
	@Synchronized
	fun recordHit() {
		// A native track found in the feed proves both: the route works and this
		// account is recording this phone.
		recordRouteHealthy()
		missKeys.clear()
		anonymousMisses = 0
		mismatch = null
		mismatchAtMillis = 0
		mismatchProbeMillis = 0
	}

	/**
	 * The feed read fine and simply did not contain the track that just played.
	 *
	 * @param trackKey identifies the track this miss is about. Misses of the
	 * *same* track are one data point however they are interleaved — measured
	 * 2026-08-06, "See Every TIME Cover From 2025" was looked up three times in
	 * three minutes as it was replayed, each absence counted separately, and the
	 * route stood itself down on a diagnosis ("the last 3 native tracks were not
	 * written to the watch history") that was true of exactly one track. Two
	 * untitled Shorts were then refused during the fifteen-minute pause, in
	 * silence, having already earned their listens. The diagnosis this exists
	 * for — the YouTube app is on another account — shows up as *different*
	 * tracks going missing, so requiring different tracks costs it nothing.
	 *
	 * A null key is an observation with nothing to name it by, so it can never
	 * be recognised as a repeat and each one counts.
	 */
	@Synchronized
	fun recordMiss(nowMillis: Long, trackKey: String? = null) {
		if (mismatch != null) return
		val key = trackKey ?: "<anonymous ${anonymousMisses++}>"
		if (!missKeys.add(key)) return
		if (missKeys.size < missesBeforeRefusing) return
		refuse(
			"the last ${missKeys.size} native tracks were not written to the watch " +
				"history of the signed-in account. The YouTube app is signed out, signed " +
				"into a different account, or playing in incognito. History lookups are " +
				"paused until a track appears there again.",
			nowMillis,
		)
	}

	/** YouTube answered with an exact fault of its own. */
	@Synchronized
	fun recordUnavailable(
		reason: WatchHistoryParser.Reason,
		detail: String,
		nowMillis: Long,
		/** Whether the consumer that saw this is health-eligible native playback. */
		accountEvidence: Boolean = true,
	) {
		when (reason) {
			WatchHistoryParser.Reason.SIGNED_OUT -> declare(
				"the stored YouTube session is no longer signed in ($detail). " +
					"Sign in again to use watch history.",
				Refusal.SIGNED_OUT,
				nowMillis,
			)

			WatchHistoryParser.Reason.HISTORY_PAUSED -> declare(
				"watch history is paused for this account ($detail), so nothing " +
					"played is recorded. Turn it back on in YouTube settings.",
				Refusal.HISTORY_PAUSED,
				nowMillis,
			)

			// An empty feed on a live session is the signed-out-app case again:
			// the account records nothing because this phone is not playing into
			// it. Counted, not declared, for the same reason a single miss is.
			//
			// Keyed, because it used to pass no key at all and so slipped past the
			// same-observation rule the track misses obey: three reads of one empty
			// feed were three data points about one fact. An empty feed carries no
			// track to key on, so the observation itself is the key — repeated
			// emptiness is one data point, and the diagnosis still needs two other
			// distinct ones before it stands the route down.
			WatchHistoryParser.Reason.EMPTY ->
				if (accountEvidence) recordMiss(nowMillis, EMPTY_FEED_KEY)

			WatchHistoryParser.Reason.MARKUP_CHANGED -> declare(
				"the watch-history page no longer has the shape RustedWax reads " +
					"($detail). Nothing was guessed.",
				Refusal.MARKUP_CHANGED,
				nowMillis,
			)
		}
	}

	/** The network failed. Not evidence about the account; nothing is recorded. */
	@Synchronized
	fun recordFetchFailure() = Unit

	/** Sign-in, sign-out, opt-out and monitoring stop all start over. */
	@Synchronized
	fun reset() {
		recordRouteHealthy()
		missKeys.clear()
		anonymousMisses = 0
		mismatch = null
		mismatchAtMillis = 0
		mismatchProbeMillis = 0
	}

	private fun refuse(reason: String, nowMillis: Long) {
		mismatch = reason
		mismatchAtMillis = nowMillis
		mismatchProbeMillis = nowMillis
	}

	/**
	 * A fault YouTube stated about the stored session, rather than one this class
	 * inferred about the native app's account.
	 *
	 * Fails closed for every route, browser included: no lookup of any kind can
	 * work through a signed-out session or an unreadable page.
	 */
	private fun declare(reason: String, kind: Refusal, nowMillis: Long) {
		routeFault = reason
		routeFaultAtMillis = nowMillis
		routeProbeMillis = nowMillis
		routeFaultKind = kind
	}

	companion object {
		/**
		 * Three, for the same reason the quiet-address-bar warning uses three:
		 * one miss is a video that genuinely has not landed yet, three in a row
		 * is a configuration.
		 */
		const val MISSES_BEFORE_REFUSING = 3

		/** A refused route costs one page read per this interval, not one per track. */
		const val RETRY_INTERVAL_MS = 15 * 60 * 1000L

		/**
		 * The stand-in track key for "the feed itself was empty".
		 *
		 * There is no track to name, and the [recordMiss] rule needs a name to tell
		 * one observation from several of the same thing.
		 */
		const val EMPTY_FEED_KEY = "<empty watch-history feed>"
	}
}
