package com.rustedwax.core

/**
 * One monitored generation of one playback source.
 *
 * `<redacted-private-path>` §2 names the defect this exists to fix: the evidence
 * stores are "keyed primarily by package", and "a package is not a unique track
 * and is not necessarily a unique source session, particularly during browser
 * tab changes, transport recreation, and asynchronous finalization."
 *
 * The second half of the key is the source epoch — the generation counter
 * source registration bumps whenever an opt-in changes, the user presses
 * Stop, or the listener service is rebuilt. It is already the value the engine
 * re-checks before dedup and signing, so nothing new is being invented here:
 * this names the pair that check has always compared, so later phases can key
 * state on it rather than on the package alone.
 *
 * Deliberately **not** a transport session token. A listen survives a source
 * tearing its session down and building a replacement — that is what
 * `TrackProgressCarry` is for — so a transport-scoped identity would split one
 * source session in two exactly where continuity matters. The transport is
 * identified by [TrackInstanceId] on the listen instead.
 *
 * A null [sourceEpoch] is a browser session, which carries no epoch of its own.
 * That is a real distinction rather than missing data, and it is preserved
 * rather than defaulted so a browser session and a native one can never compare
 * equal by accident.
 */
data class SourceSessionId(
	val packageName: String,
	val sourceEpoch: Long?,
) {
	init {
		// A session with no package is not a session. It would compare unequal to
		// every real one, so a staleness check against it can only ever say "stale"
		// — which is a listen silently dropped rather than an error anyone sees.
		require(packageName.isNotBlank()) { "a source session must name its package" }
		require(sourceEpoch == null || sourceEpoch > 0) {
			"a source epoch is a generation counter and starts at 1: $sourceEpoch"
		}
	}

	override fun toString(): String =
		if (sourceEpoch == null) packageName else "$packageName@$sourceEpoch"
}
