package com.rustedwax.app.detect

/**
 * Whether an app still has a visible activity, from its lifecycle events.
 *
 * ## Why the last event is not the answer
 *
 * The obvious implementation — remember the most recent `ACTIVITY_*` event for
 * the package — is wrong, and measured wrong on the device 2026-08-06. Opening
 * a Short by URL produces this, all within the same second:
 *
 * ```
 * ACTIVITY_PAUSED   MainActivity
 * ACTIVITY_RESUMED  Shell_UrlActivity
 * ACTIVITY_PAUSED   Shell_UrlActivity
 * ACTIVITY_RESUMED  MainActivity        ← what is actually on screen
 * ACTIVITY_STOPPED  Shell_UrlActivity   ← arrives last
 * ```
 *
 * The departing activity's `STOPPED` lands *after* the arriving activity's
 * `RESUMED`, so a single last-event field concludes the app is gone while it is
 * plainly on screen. [PipPlaybackProbe] read it that way, which meant a Short
 * opened through an activity transition could be refused picture-in-picture
 * credit for as long as it played, in silence.
 *
 * ## What this does instead
 *
 * It tracks which activities are currently started. `RESUMED` and `PAUSED` both
 * mean visible — a picture-in-picture window leaves its activity paused, and
 * that case is the whole reason the probe exists — and only `STOPPED` removes
 * one. The app is visible while any remain.
 *
 * Pure, so the ordering above is a test rather than a field observation that has
 * to be made twice.
 *
 * **Known limit, accepted:** activities are keyed by class name, because
 * `UsageEvents.Event.getInstanceId()` is not public API. Two live instances of
 * one class therefore share a key, and stopping either reads as stopping both.
 * That fails toward "not visible", which under-reports rather than over-credits.
 */
class VisibleActivities(private val maxTracked: Int = MAX_TRACKED) {

	private val started = LinkedHashSet<String>()

	/** @return whether the app has any visible activity after applying this event. */
	fun onEvent(className: String?, event: Lifecycle): Boolean {
		val key = className?.takeIf { it.isNotBlank() } ?: UNNAMED
		when (event) {
			Lifecycle.RESUMED, Lifecycle.PAUSED -> {
				started.remove(key)
				started.add(key)
				while (started.size > maxTracked) {
					started.remove(started.first())
				}
			}

			Lifecycle.STOPPED -> started.remove(key)
		}
		return visible
	}

	val visible: Boolean get() = started.isNotEmpty()

	fun clear() = started.clear()

	enum class Lifecycle { RESUMED, PAUSED, STOPPED }

	private companion object {
		/** A key for events that arrive without a class name. */
		const val UNNAMED = "<unnamed>"

		/**
		 * An app with more live activities than this is not a case this needs to
		 * be exact about, and the bound keeps a long session from accumulating.
		 */
		const val MAX_TRACKED = 16
	}
}
