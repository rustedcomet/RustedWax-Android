package com.rustedwax.core

/**
 * The source quirks the playback state machine has to branch on.
 *
 * The reducer knows events, measurement, and lifecycle, but not source package
 * names. Each field asks a behavioral question so a future source can declare
 * its own evidence and transport semantics without source-name branches.
 *
 * Per-source adapters own evidence branches such as browser notifications,
 * address-bar observations, and browser ad checks. This type describes the
 * measurement and lifecycle capabilities shared with the reducer.
 */
data class PlaybackSourceCapabilities(
	/**
	 * The source republishes materially different lengths for material that has
	 * not changed, mid-playback.
	 *
	 * A measured long-form source reported 415 s, then 11 s, then 6 s while still
	 * playing the same thing. Another reported 2,183 s total, short 27 s/44 s
	 * transition surfaces, then a 1,192 s remaining-time surface under the same
	 * organic title. The same native route also published a 2,163 s sponsored
	 * surface after a 1,192 s organic presentation. Taking either direction at
	 * face value chops one watch into scraps or counts the replacement interval as
	 * organic progress. The field name is retained for constructor compatibility;
	 * the capability governs material duration churn in both directions.
	 *
	 * False for sources whose transport refines a duration upward and never
	 * does this.
	 */
	val republishesShorterDurations: Boolean,

	/**
	 * Progress may only be carried to a replacement transport when the session
	 * publishes an exact item id.
	 *
	 * A page-backed source can keep identity outside the transport, which survives it
	 * being rebuilt, so title/artist/duration is enough to re-attach a listen.
	 * A transport-only source that cannot name the item has only resolver-derived or
	 * site metadata, which is not strong enough to hand one listen's progress to
	 * a different controller.
	 */
	val requiresExactIdToCarryProgress: Boolean,

	/**
	 * A `STOPPED` transport with no exact id gets a grace period for a metadata
	 * replacement rather than ending the track immediately.
	 *
	 * Some transports announce `STOPPED` and then publish the next item's
	 * metadata; finalizing on the stop alone splits one transition into a
	 * fragment that can never be identified.
	 */
	val usesStoppedReplacementGrace: Boolean,

	/**
	 * Elapsed wall-clock may be credited when the source keeps playing with no
	 * readable progress surface at all.
	 *
	 * Only a source with an independent visible-playback signal reaches this state:
	 * accessibility tree keeps the player but loses the seekbar, and the
	 * the transport can report no state and zero position. Other sources in the
	 * background keeps publishing position and must be measured, never inferred.
	 */
	val supportsPictureInPictureInference: Boolean,

	/**
	 * One selected work can be republished as alternate media presentations with
	 * materially different lengths while playback continues.
	 *
	 * A source may publish the same work as two presentations with different
	 * lengths. Both directions are presentation changes. The reducer may
	 * retain the longest established length only after the source adapter has
	 * reduced both presentations to the same work identity.
	 */
	val republishesAlternateMediaDurations: Boolean = false,
)
