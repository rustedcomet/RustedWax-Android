package com.rustedwax.app.ui

/**
 * The places the main screen can be, named rather than numbered.
 *
 * ## Why the number had to go
 *
 * The screen held `var tab by remember { mutableIntStateOf(0) }` and dispatched
 * on `when (tab)`. That is fine exactly as long as the list of tabs never
 * changes. With indexes, adding or removing one entry silently renumbers
 * everything after it, so the same stored selection points at a different
 * screen. A destination is its own identity: `SETTINGS` is `SETTINGS` wherever
 * it is reached from.
 *
 * ## Primary and overflow (Issue #47)
 *
 * The enum's order is the order on screen. The first four are the **primary**
 * destinations — the strip, and what a swipe moves between:
 *
 * **Now → History → My Snaps → Not logged**
 *
 * What RustedWax is tracking, what it scrobbled, what the user published
 * around that media, and what it did not scrobble. [SETTINGS], [LOG] and
 * [ABOUT] are utility destinations reached from the top bar's overflow menu
 * instead, so they never cost a place in the strip or the swipe sequence. All
 * three always exist: the Event Log is reachable even while logging is off,
 * and says so on its page.
 */
enum class Destination {
	NOW,
	HISTORY,
	MY_SNAPS,
	NOT_LOGGED,
	SETTINGS,
	LOG,
	ABOUT,
}

/**
 * Which destinations the strip shows, which the overflow holds, and how a tap
 * and a swipe move between them.
 *
 * Pure and total, so the tab strip and the swipe cannot disagree: they are two
 * renderings of one list rather than two lists kept in step.
 */
object AppNavigation {

	/** The overflow menu's entries, in menu order. Never a primary destination. */
	val overflow: List<Destination> = listOf(Destination.SETTINGS, Destination.LOG, Destination.ABOUT)

	/** The primary destinations, exactly four and in this order. */
	fun destinations(): List<Destination> = Destination.entries.filter { it !in overflow }

	fun isPrimary(destination: Destination): Boolean = destination !in overflow

	/**
	 * One primary destination along, in either direction, stopping at both ends.
	 *
	 * An overflow page is not part of that sequence, so a swipe there stays put
	 * rather than jumping to an arbitrary neighbour.
	 */
	fun swipe(selected: Destination, destinations: List<Destination>, delta: Int): Destination {
		val from = destinations.indexOf(selected)
		if (from < 0) return selected
		return destinations[(from + delta).coerceIn(0, destinations.size - 1)]
	}

	// `exportVisible` used to live here, deciding when the tab strip was allowed
	// to carry a trailing `Export` button. `Export` now sits inside the `Log`
	// page beside `Clear log`, so no rule about it is needed here.

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
		Destination.MY_SNAPS -> "My Snaps"
		Destination.NOT_LOGGED -> "Not logged ($notLogged)"
		Destination.LOG -> "Event Log"
		Destination.SETTINGS -> "Settings"
		Destination.ABOUT -> "About"
	}
}
