package com.rustedwax.app.detect

import com.rustedwax.core.*
import com.rustedwax.core.PlaybackSourceCapabilities
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `reduce(state, input)` is a function, and this is what says so.
 *
 * ## Why it needed proving rather than declaring
 *
 * [ListenState] was written as a `data class` of `val`s and read as immutable on
 * that basis — but it carried a mutable `PipPlaybackInference`, and the
 * picture-in-picture branch advanced it by calling `observe()` on the *caller's*
 * state. So `reduce` changed its own argument. The visible consequence is that
 * replaying an event stream
 * twice from the same starting state produced two different answers, and a
 * reducer that cannot be replayed cannot be a reference for anything.
 *
 * Two independent guards, because either alone is escapable:
 *
 * 1. **Behavioural.** Every input shape, against every interesting state, run
 *    twice. Same transition, and the state handed in is the state afterwards.
 * 2. **Structural.** Reflection over the field graph, so a future field of a
 *    mutable type fails here rather than in a field report months later. The
 *    behavioural half cannot catch a mutation nothing currently triggers.
 */
class ReducerPurityTest {

	private val nativeApp = PlaybackSourceCapabilities(
		republishesShorterDurations = true,
		requiresExactIdToCarryProgress = true,
		usesStoppedReplacementGrace = true,
		supportsPictureInPictureInference = true,
	)

	private val browser = PlaybackSourceCapabilities(
		republishesShorterDurations = false,
		requiresExactIdToCarryProgress = false,
		usesStoppedReplacementGrace = false,
		supportsPictureInPictureInference = false,
	)

	private val identity = TrackIdentity("Sleepwalking", "Bring Me The Horizon", null, 213_000)

	private fun baseState() = ListenState(
		trackIdentity = identity,
		instanceToken = 7,
		instanceEstablishedAtMillis = 1_700_000_000_000,
		startedAtEpochSec = 1_700_000_000,
		everPublishedMetadata = true,
	)

	/**
	 * The states worth running every input against.
	 *
	 * Each one reaches a different family of branches — the suppressed and
	 * tab-only states short-circuit almost everything, the picture-in-picture
	 * state is the one that used to mutate, and the finalized state is the guard
	 * that must not be rolled back.
	 */
	private fun states(): List<Pair<String, ListenState>> = listOf(
		"idle" to baseState(),
		"playing" to baseState().copy(
			transport = TransportState.PLAYING,
			playingSinceElapsedMs = 1_000,
			speed = 1.5,
			playedMs = 40_000,
			firstSeenPositionMs = 0,
		),
		"paused" to baseState().copy(transport = TransportState.PAUSED, playedMs = 40_000),
		"finalized" to baseState().copy(finalized = true, playedMs = 90_000),
		"tab title only" to baseState().copy(
			describingTabOnly = true,
			trackIdentity = TrackIdentity(null, null, null, null),
		),
		"suppressed by a foreground Short" to baseState().copy(
			suppressedByForegroundShort = true,
			transport = TransportState.PLAYING,
			playingSinceElapsedMs = 1_000,
		),
		"picture-in-picture, anchored" to baseState().copy(
			transport = TransportState.PAUSED,
			pipInference = PipPlaybackInference(durationMs = 60_000, measuredMs = 5_000)
				.observe(10_000, playing = true).next,
			pipInferredMs = 0,
			playedMs = 5_000,
		),
		"picture-in-picture, credited" to baseState().copy(
			transport = TransportState.PAUSED,
			pipInference = PipPlaybackInference(durationMs = 60_000, measuredMs = 5_000)
				.observe(10_000, playing = true).next
				.observe(11_000, playing = true).next,
			pipInferredMs = 1_000,
			playedMs = 5_000,
		),
		"never published metadata" to baseState().copy(everPublishedMetadata = false),
		"looped, longest duration held" to baseState().copy(
			loopDetected = true,
			longestDurationMs = 301_000,
			fastestSpeedSeen = 2.0,
		),
	)

	/** One instance of every [PlaybackInput] shape. */
	private fun inputs(): List<Pair<String, PlaybackInput>> = listOf(
		"metadata, same track" to PlaybackInput.MetadataPublished(
			identity = identity,
			namesTabOnly = false,
			outgoingTransportHasExactId = false,
			hasPreResolvedNativeId = false,
			outgoingTitle = "Sleepwalking",
			nowMillis = 1_700_000_100_000,
			elapsedRealtimeMs = 100_000,
			nextInstanceToken = 8,
		),
		"metadata, a different track" to PlaybackInput.MetadataPublished(
			identity = TrackIdentity("Drown", "Bring Me The Horizon", null, 260_000),
			namesTabOnly = false,
			outgoingTransportHasExactId = false,
			hasPreResolvedNativeId = false,
			outgoingTitle = "Sleepwalking",
			nowMillis = 1_700_000_100_000,
			elapsedRealtimeMs = 100_000,
			nextInstanceToken = 8,
		),
		"metadata, a shorter length for the same title" to PlaybackInput.MetadataPublished(
			identity = identity.copy(durationMs = 13_000),
			namesTabOnly = false,
			outgoingTransportHasExactId = false,
			hasPreResolvedNativeId = false,
			outgoingTitle = "Sleepwalking",
			nowMillis = 1_700_000_100_000,
			elapsedRealtimeMs = 100_000,
			nextInstanceToken = 8,
		),
		"metadata, empty" to PlaybackInput.MetadataPublished(
			identity = TrackIdentity(null, null, null, null),
			namesTabOnly = false,
			outgoingTransportHasExactId = false,
			hasPreResolvedNativeId = false,
			outgoingTitle = "Sleepwalking",
			nowMillis = 1_700_000_100_000,
			elapsedRealtimeMs = 100_000,
			nextInstanceToken = 8,
		),
		"metadata, the tab's own title" to PlaybackInput.MetadataPublished(
			identity = TrackIdentity("YouTube", "youtube.com", null, null),
			namesTabOnly = true,
			outgoingTransportHasExactId = false,
			hasPreResolvedNativeId = false,
			outgoingTitle = "Sleepwalking",
			nowMillis = 1_700_000_100_000,
			elapsedRealtimeMs = 100_000,
			nextInstanceToken = 8,
		),
		"transport, playing" to PlaybackInput.TransportChanged(
			transport = TransportState.PLAYING,
			speed = 1.0,
			previousPositionMs = 200_000,
			newPositionMs = 1_000,
			rawPositionMs = 1_000,
			durationMs = 213_000,
			transportHasExactId = false,
			elapsedRealtimeMs = 100_000,
		),
		"transport, stopped" to PlaybackInput.TransportChanged(
			transport = TransportState.STOPPED,
			speed = 1.0,
			previousPositionMs = 200_000,
			newPositionMs = 213_000,
			rawPositionMs = 213_000,
			durationMs = 213_000,
			transportHasExactId = false,
			elapsedRealtimeMs = 100_000,
		),
		"position seen" to PlaybackInput.PositionSeen(94_000),
		"session destroyed" to PlaybackInput.SessionDestroyed(
			elapsedRealtimeMs = 100_000,
			continuationOpen = false,
		),
		"disposed, finalizing" to PlaybackInput.Disposed(
			finalize = true,
			allowContinuation = false,
			elapsedRealtimeMs = 100_000,
			continuationOpen = false,
		),
		"disposed, continuing" to PlaybackInput.Disposed(
			finalize = true,
			allowContinuation = true,
			elapsedRealtimeMs = 100_000,
			continuationOpen = false,
		),
		"finalize requested" to PlaybackInput.FinalizeRequested("stopped", 100_000),
		"track frozen" to PlaybackInput.TrackFrozen,
		"progress carried" to PlaybackInput.ProgressCarried(
			playedMs = 58_000,
			startedAtEpochSec = 1_699_999_900,
			fastestSpeedSeen = 1.25,
			loopDetected = false,
			instanceToken = 3,
			lastPositionMs = 210_000,
			currentPositionMs = 1_000,
			durationMs = 213_000,
			nowMillis = 1_700_000_100_000,
		),
		"exact id established" to PlaybackInput.ExactIdEstablished("dQw4w9WgXcQ"),
		"foreground Short took over" to PlaybackInput.ForegroundShortTookOver(
			shortDescribesSameItem = false,
			elapsedRealtimeMs = 100_000,
		),
		"foreground Short released" to PlaybackInput.ForegroundShortReleased(
			identity = identity,
			nowMillis = 1_700_000_100_000,
			elapsedRealtimeMs = 100_000,
			nextInstanceToken = 9,
		),
		"foreground surface observed" to PlaybackInput.ForegroundSurfaceObserved(
			identity = TrackIdentity("Short", "@owner", null, 20_000),
			positionMs = 5_000,
			durationMs = 20_000,
			nowMillis = 12_000,
			sourceEpoch = 3,
			playing = true,
		),
		"foreground surface unavailable" to PlaybackInput.ForegroundSurfaceUnavailable(
			reason = "surface lost",
			nowMillis = 12_000,
			progressSurfaceLost = true,
			playing = true,
		),
		"foreground surface connected" to PlaybackInput.ForegroundSurfaceConnected(12_000),
		"picture-in-picture, playing" to PlaybackInput.PictureInPictureObserved(
			nowMillis = 12_000,
			playing = true,
			durationMs = 60_000,
		),
		"picture-in-picture, not playing" to PlaybackInput.PictureInPictureObserved(
			nowMillis = 12_000,
			playing = false,
			durationMs = 60_000,
		),
		"idle deadline, length known" to PlaybackInput.IdleDeadlineReached(
			durationMs = 213_000,
			elapsedRealtimeMs = 400_000,
		),
		"idle deadline, no length anywhere" to PlaybackInput.IdleDeadlineReached(
			durationMs = null,
			elapsedRealtimeMs = 400_000,
		),
	)

	/**
	 * The property the whole replay corpus rests on.
	 *
	 * `reduce` is called twice on one state. Not on two equal states — on the
	 * same instance — because that is the shape the defect had: the first call
	 * left the second with a different world to read.
	 */
	@Test
	fun `reduce returns the same transition twice and leaves its argument alone`() {
		for (capabilities in listOf(browser to "browser", nativeApp to "native")) {
			val reducer = PlaybackReducer(capabilities.first)
			for ((stateName, state) in states()) {
				for ((inputName, input) in inputs()) {
					val where = "${capabilities.second} / $stateName / $inputName"
					// An independently constructed equal value, so "unchanged" is
					// checked against something the reduction cannot have touched.
					val untouched = state.copy()
					val first = reducer.reduce(state, input)
					val second = reducer.reduce(state, input)

					assertEquals("$where: state after the transition", first.state, second.state)
					assertEquals("$where: work before the state install", first.before, second.before)
					assertEquals("$where: work after it", first.effects, second.effects)
					assertEquals("$where: the argument was mutated", untouched, state)
				}
			}
		}
	}

	/**
	 * The same, over a whole sequence rather than a single step.
	 *
	 * A per-step check can still pass while a run diverges, because a mutation
	 * only shows up once something later reads what was written. Two identical
	 * runs from one starting value have to end in one place.
	 */
	@Test
	fun `replaying a whole event stream from one state gives one answer`() {
		val reducer = PlaybackReducer(nativeApp)
		val stream = inputs().map { it.second }

		fun run(from: ListenState): List<PlaybackReducer.Transition> {
			var state = from
			return stream.map { input ->
				reducer.reduce(state, input).also { state = it.state }
			}
		}

		val start = baseState().copy(
			transport = TransportState.PAUSED,
			pipInference = PipPlaybackInference(durationMs = 60_000, measuredMs = 0)
				.observe(0, playing = true).next,
		)
		assertEquals(run(start), run(start))
	}

	/**
	 * Nothing reachable from [ListenState] can be written to.
	 *
	 * Java's own view of the fields, so a Kotlin `var`, a mutable collection or a
	 * class with mutable internals fails regardless of how it is declared. The
	 * walk stops at the JDK — `String` and boxed numbers are immutable, and
	 * descending into them would report `String.hash` as a mutable field.
	 */
	@Test
	fun `every field reachable from the reducer's state is final`() {
		val mutable = mutableListOf<String>()
		walk(ListenState::class.java, "ListenState", mutableSetOf(), mutable)
		assertTrue(
			"mutable state reachable from ListenState: ${mutable.joinToString("\n")}",
			mutable.isEmpty(),
		)
	}

	private fun walk(
		type: Class<*>,
		path: String,
		seen: MutableSet<Class<*>>,
		findings: MutableList<String>,
	) {
		if (!seen.add(type)) return
		for (field in type.declaredFields) {
			if (Modifier.isStatic(field.modifiers)) continue
			val fieldPath = "$path.${field.name}: ${field.type.name}"
			if (!Modifier.isFinal(field.modifiers)) findings += "$fieldPath is not final"
			if (MutableCollection::class.java.isAssignableFrom(field.type) ||
				MutableMap::class.java.isAssignableFrom(field.type)
			) {
				// `List` and `Map` erase to the same JDK interfaces, so this only
				// catches a declared mutable type; the finality check above is what
				// stops a field from being swapped out underneath a caller.
				findings += "$fieldPath is a mutable collection type"
			}
			val next = field.type
			if (next.name.startsWith("com.rustedwax.") && !next.isEnum) {
				walk(next, fieldPath, seen, findings)
			}
		}
	}
}
