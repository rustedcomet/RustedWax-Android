package com.rustedwax.app.detect

class VisibleActivities(private val maxTracked: Int = MAX_TRACKED) {

	private val started = LinkedHashSet<String>()

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
