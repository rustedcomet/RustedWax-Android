package com.rustedwax.app.detect

import com.rustedwax.core.*
import com.rustedwax.core.PlaybackSourceCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForeignSourceReducerTest {

	/**
	 * A capability set no YouTube source has.
	 *
	 * `SourceProfile.playbackCapabilitiesFor` currently answers all four questions
	 * with the same browser/native split. Picking a combination outside that split
	 * is what proves the reducer reads the declarations rather than inferring the
	 * platform from one of them.
	 */
	private val foreign = PlaybackSourceCapabilities(
		republishesShorterDurations = true,
		requiresExactIdToCarryProgress = false,
		usesStoppedReplacementGrace = false,
		supportsPictureInPictureInference = false,
	)

	private val reducer = PlaybackReducer(foreign)

	private val itemId = "spotify:track:4cOdK2wGLETKBW3PvgPWqT"

	private fun start() = ListenState(
		trackIdentity = TrackIdentity(
			title = "Never Gonna Give You Up",
			artist = "Rick Astley",
			album = "Whenever You Need Somebody",
			durationMs = 213_000,
		),
		instanceToken = 1,
		instanceEstablishedAtMillis = 0,
		startedAtEpochSec = 1_786_233_600,
	)

	@Test
	fun `a foreign source item id is stored without being parsed or validated`() {
		val after = reducer.reduce(start(), PlaybackInput.ExactIdEstablished(itemId)).state

		assertEquals(
			"the reducer rejected or rewrote an id that was not a YouTube video id",
			itemId,
			after.trackIdentity.sourceItemId,
		)
		assertTrue(after.trackIdentity.hasExactSourceItemId)
	}

	@Test
	fun `a foreign source measures playback on the shared accumulator`() {
		var state = start()
		state = reducer.reduce(
			state,
			PlaybackInput.TransportChanged(
				transport = TransportState.PLAYING,
				speed = 1.0,
				previousPositionMs = null,
				newPositionMs = 0,
				rawPositionMs = 0,
				durationMs = 213_000,
				transportHasExactId = true,
				elapsedRealtimeMs = 10_000,
			),
		).state

		assertEquals(
			"a non-YouTube source measured nothing over sixty seconds of playback",
			60_000,
			state.playedMsAt(70_000),
		)
	}

	@Test
	fun `a foreign source finalizes through the shared lifecycle`() {
		// The transport has to have named something first: `onFinalizeRequested`
		// refuses to finalize a listen that never published metadata, which is how
		// an empty session is stopped from producing a scrobble. A foreign source
		// gets that guard for free, and has to satisfy it the same way.
		var state = reducer.reduce(
			start(),
			PlaybackInput.MetadataPublished(
				identity = TrackIdentity(
					title = "Never Gonna Give You Up",
					artist = "Rick Astley",
					album = "Whenever You Need Somebody",
					durationMs = 213_000,
					sourceItemId = itemId,
				),
				namesTabOnly = false,
				outgoingTransportHasExactId = false,
				hasPreResolvedNativeId = false,
				outgoingTitle = null,
				nowMillis = 1_786_233_600_000,
				elapsedRealtimeMs = 10_000,
				nextInstanceToken = 2,
			),
		).state
		state = reducer.reduce(
			state,
			PlaybackInput.TransportChanged(
				transport = TransportState.PLAYING,
				speed = 1.0,
				previousPositionMs = null,
				newPositionMs = 0,
				rawPositionMs = 0,
				durationMs = 213_000,
				transportHasExactId = true,
				elapsedRealtimeMs = 10_000,
			),
		).state

		val transition = reducer.reduce(
			state,
			PlaybackInput.FinalizeRequested("stopped", elapsedRealtimeMs = 210_000),
		)

		assertTrue(
			"a non-YouTube listen did not reach the shared freeze-and-report effect: " +
				"${transition.before} / ${transition.effects}",
			transition.effects.any { it is PlaybackEffect.FreezeAndReport },
		)
		assertTrue("the listen was not marked finalized", transition.state.finalized)
	}

	@Test
	fun `the reducer source contains no Android package name or platform brand`() {
		val source = java.io.File("../core/src/main/kotlin/com/rustedwax/core/PlaybackReducer.kt")
		assertTrue("PlaybackReducer.kt not found at ${source.absolutePath}", source.isFile)

		val offenders = source.readLines()
			.withIndex()
			.filter { (_, line) ->
				val code = line.substringBefore("//").trim()
				// Comments and KDoc legitimately discuss the field history that
				// produced these rules — "measured on Brave", "the native app
				// republishes". The ban is on *code* naming a platform.
				code.isNotEmpty() &&
					!code.startsWith("*") &&
					!code.startsWith("/*") &&
					Regex(
						"com\\.google\\.android|com\\.brave|com\\.android\\.chrome|" +
							"YouTubeProbe|isNative",
					).containsMatchIn(code)
			}

		assertEquals(
			"the shared reducer named a platform in code: " +
				offenders.joinToString("; ") { "line ${it.index + 1}: ${it.value.trim()}" },
			emptyList<String>(),
			offenders.map { it.value },
		)
	}
}

/**
 * A non-YouTube identity through the Phase 2 domain objects.
 *
 * The reducer half is above. This is the identity half: `IdentityEvidence` used
 * to declare `YouTubeProbe.Identity`, which meant a Spotify adapter could not
 * build one without inventing a `videoId`. It declares `ItemIdentity` now, so
 * this fixture compiles — and the fact that it compiles is most of the point.
 */
class ForeignSourceIdentityTest {

	/** A source that has never heard of YouTube, answering the shared contract. */
	private data class SpotifyIdentity(
		override val sourceItemId: String?,
		override val canonicalLink: String?,
		override val isSourceProven: Boolean = true,
		override val source: String = "spotify connect",
	) : com.rustedwax.core.ItemIdentity

	private val identified = SpotifyIdentity(
		sourceItemId = "spotify:track:4cOdK2wGLETKBW3PvgPWqT",
		canonicalLink = "https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT",
	)

	@Test
	fun `a foreign identity builds the shared evidence object`() {
		val evidence = IdentityEvidence(identity = identified)

		assertEquals("spotify:track:4cOdK2wGLETKBW3PvgPWqT", evidence.sourceItemId)
		assertEquals(
			"https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT",
			evidence.canonicalLink,
		)
		assertTrue(evidence.isSourceProven)
		assertEquals("no YouTube verdict should be readable here", null, evidence.confirmed)
	}

	/**
	 * The fail-closed rule holds for a source it was not written for.
	 *
	 * It used to read `(identity as? YouTubeProbe.Identity.Confirmed)?.videoId`,
	 * so a foreign identity naming an already-disproven item would have passed
	 * straight through the check that exists to stop exactly that.
	 */
	@Test
	fun `a foreign identity cannot confirm an item this listen already disproved`() {
		val disproved = ResolverContext(
			rejectedVideoIds = setOf("spotify:track:4cOdK2wGLETKBW3PvgPWqT"),
		)

		val failure = runCatching { IdentityEvidence(identified, resolverContext = disproved) }
		assertTrue(
			"the fail-closed identity rule did not apply to a non-YouTube source",
			failure.isFailure,
		)
	}

	/**
	 * A source-proven-but-unidentified foreign listen is representable.
	 *
	 * The Chromium-shaped state — site proven, item unknown — is not
	 * YouTube-specific, and a contract that could not express it would force
	 * every future adapter to choose between "identified" and "nothing".
	 */
	@Test
	fun `a foreign source can be proven without an item id`() {
		val evidence = IdentityEvidence(
			identity = SpotifyIdentity(sourceItemId = null, canonicalLink = null),
		)

		assertTrue(evidence.isSourceProven)
		assertEquals(null, evidence.sourceItemId)
	}
}
