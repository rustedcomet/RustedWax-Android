package com.rustedwax.app.ui

/**
 * The places the main screen can be, named rather than numbered.
 *
 * ## Why the number had to go
 *
 * The screen held `var tab by remember { mutableIntStateOf(0) }` and dispatched
 * on `when (tab)`. That is fine exactly as long as the list of tabs never
 * changes — and hiding `Log` when the event log is off changes it. With indexes,
 * removing the fifth entry silently renumbers everything after it, so the same
 * stored selection means `Log` on one install and `Settings` on another, and a
 * selection made before the switch was touched points somewhere else after it.
 *
 * A destination is its own identity. Adding or removing one cannot move the
 * others, because there is nothing to move: `SETTINGS` is `SETTINGS` whether or
 * not `LOG` exists beside it.
 *
 * The order of the enum is the order on screen, and [AppNavigation.destinations]
 * decides which of them exist right now.
 *
 * ## Why `Log` is last rather than beside the other lists
 *
 * It is the only conditional destination. Anywhere but the end, its appearing
 * and disappearing shifts every destination after it — which is survivable now
 * that the selection is named rather than numbered, but still means the strip
 * rearranges itself under the finger of whoever just touched the switch. At the
 * end it comes and goes without moving anything: `Now`, `History`, `Not logged`
 * and `Settings` hold the same four positions either way.
 *
 * It also reads correctly. `Log` is the raw record behind everything the other
 * destinations summarise, and it exists only because somebody switched it on in
 * `Settings` — so it belongs after the screen that turned it on, not wedged in
 * front of it.
 */
enum class Destination {
	NOW,
	HISTORY,
	NOT_LOGGED,
	SETTINGS,
	LOG,
}

/**
 * Which destinations exist, and how a tap and a swipe move between them.
 *
 * Pure and total, so the tab strip and the pager cannot disagree: they are two
 * renderings of one list rather than two lists kept in step.
 */
object AppNavigation {

	/**
	 * `Log` exists only while a log is being written.
	 *
	 * Off means off — no file, no ring buffer, nothing to draw — so a `Log`
	 * destination in that state is an empty screen with an `Export` button that
	 * can only send an empty file. It is not disabled or emptied; it is absent,
	 * which is the honest rendering of a feature that is switched off.
	 */
	fun destinations(eventLogEnabled: Boolean): List<Destination> =
		Destination.entries.filter { eventLogEnabled || it != Destination.LOG }

	/**
	 * The destination to show, given one that may no longer exist.
	 *
	 * Deliberately **not** a clamp of the old index. Clamping is what produced
	 * the drift this type exists to remove: the neighbour of a destination that
	 * has gone is an arbitrary screen. Turning the log off from `Log` lands on
	 * `Settings` because that is where the switch that did it lives — the user
	 * ends up looking at the control they just used, not at a random tab. With
	 * `Log` last, `Settings` is also the destination immediately before it, so
	 * the answer is now both the right one and the obvious one.
	 */
	fun resolve(selected: Destination, destinations: List<Destination>): Destination =
		if (selected in destinations) selected else Destination.SETTINGS

	/** One destination along, in either direction, stopping at both ends. */
	fun swipe(selected: Destination, destinations: List<Destination>, delta: Int): Destination {
		if (destinations.isEmpty()) return selected
		val from = destinations.indexOf(resolve(selected, destinations))
		return destinations[(from + delta).coerceIn(0, destinations.size - 1)]
	}

	/** Where the destination sits in the strip, for the pager to scroll to. */
	fun indexOf(selected: Destination, destinations: List<Destination>): Int =
		destinations.indexOf(resolve(selected, destinations)).coerceAtLeast(0)

	// `exportVisible` used to live here, deciding when the tab strip was allowed
	// to carry a trailing `Export` button. `Export` now sits inside the `Log`
	// page beside `Clear log`, where the guarantee it enforced holds by
	// construction: the page exists only while a log does, so a button on it
	// cannot attach an empty file. A rule that restates what the list above
	// already decides is a second answer waiting to disagree with the first.

	/**
	 * The strip's label, with the live count where one exists.
	 *
	 * The counts are why the strip cannot measure its own labels ahead of
	 * layout — they change as sessions come and go.
	 */
	fun label(
		destination: Destination,
		sessions: Int,
		history: Int,
		notLogged: Int,
	): String = when (destination) {
		Destination.NOW -> "Now ($sessions)"
		Destination.HISTORY -> "History ($history)"
		Destination.NOT_LOGGED -> "Not logged ($notLogged)"
		Destination.LOG -> "Log"
		Destination.SETTINGS -> "Settings"
	}
}
