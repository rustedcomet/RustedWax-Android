package com.rustedwax.app.detect

import com.rustedwax.core.ItemIdentity

data class IdentityEvidence(
	/**
	 * The verdict the probe reached and froze; never re-derived at finalization.
	 *
	 * Typed as the source-neutral [ItemIdentity], not as `YouTubeProbe.Identity`.
	 * A shared finalized-track type that can only hold a YouTube verdict is one a Spotify
	 * adapter cannot reach without inventing a `videoId`. YouTube's identity
	 * implements the interface, so every existing caller is unchanged and the
	 * YouTube-specific accessors below still work by narrowing.
	 */
	val identity: ItemIdentity,
	/**
	 * Resolver inputs frozen while the track was active.
	 *
	 * The freeze is the architectural strength the audit says to keep:
	 * asynchronous finalization must never consult the package's later
	 * foreground URL, or an earlier track acquires a later one's identity.
	 */
	val resolverContext: ResolverContext = ResolverContext(),
	/** Literal observer provenance; never inferred from a resolver result. */
	val sourceProof: SourceProof = SourceProof.MEDIA_SESSION,
	/** The browser media notification bound to this session, if any. */
	val notificationHint: NotificationHints.Hint? = null,
	/** A fresh successful visible-YouTube-root scan frozen for this exact track. */
	val accessibilityCoverage: MediaSessionAccessibilityEvidence.Coverage? = null,
	/**
	 * The exact visible label that marked this track instance an advertisement.
	 *
	 * Literal, and bound to one track instance. A watch-page pre-roll label sits
	 * over the real video's URL, so treating the package alone as sufficient
	 * would veto the content the user actually chose.
	 */
	val explicitAdSignal: String? = null,
	/** Exact accessibility owner handle for the native foreground-Short route. */
	val ownerHandle: String? = null,
) {
	init {
		// The fail-closed property, stated where it can be checked rather than
		// left to the several routes that are each supposed to preserve it. A
		// frozen verdict naming a video that the same frozen context says was
		// disproven is the exact shape of the identity defects this migration is
		// meant to make impossible: the rejection travelled and the verdict did
		// not, and downstream spends the verdict.
		// Asked of the shared contract, so the fail-closed property holds for a
		// source that has never heard of `YouTubeProbe.Identity.Confirmed`.
		val confirmedId = identity.sourceItemId
		require(confirmedId == null || confirmedId !in resolverContext.rejectedVideoIds) {
			"identity confirms $confirmedId, which this listen already disproved"
		}
		require(explicitAdSignal?.isBlank() != true) {
			"an ad veto must carry the label it was read from"
		}
		require(ownerHandle?.isBlank() != true) { "a blank owner handle must be null" }
	}

	val confirmed: YouTubeProbe.Identity.Confirmed?
		get() = identity as? YouTubeProbe.Identity.Confirmed

	/**
	 * The item this listen was, in terms any source can answer.
	 *
	 * What shared policy, payload construction and history are entitled to read.
	 * `confirmed` stays for the YouTube-specific questions — Short-ness, URL
	 * generation, exact-id route — which only YouTube can answer.
	 */
	val sourceItemId: String? get() = identity.sourceItemId
	val canonicalLink: String? get() = identity.canonicalLink

	/** The source itself is proven, with or without an item id. */
	val isSourceProven: Boolean get() = identity.isSourceProven

	/** True when the site is proven YouTube, with or without a video id. */
	val isYouTube: Boolean
		get() = identity is YouTubeProbe.Identity.Confirmed ||
			identity is YouTubeProbe.Identity.SiteOnly

	val isForegroundShort: Boolean
		get() = sourceProof == SourceProof.NATIVE_FOREGROUND_SHORT

	/** Browser path proof, or the separately proven native foreground player. */
	val hasShortSourceProof: Boolean
		get() = isForegroundShort || confirmed?.isShort == true
}
