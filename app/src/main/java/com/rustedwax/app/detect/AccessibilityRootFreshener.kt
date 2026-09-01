package com.rustedwax.app.detect

/**
 * Obtains an accessibility root only after invalidating the service cache.
 *
 * YouTube can keep an outgoing Shorts footer in AccessibilityNodeInfo's cache
 * after the pixels have already switched to the next Short.  Reading that root
 * can splice the outgoing owner handle onto the incoming seekbar/duration.  A
 * fresh obtain plus [refresh] makes each capture describe one current surface;
 * one retry covers a root that became stale while the window was changing.
 */
object AccessibilityRootFreshener {

	fun <T> acquire(
		clearCache: () -> Unit,
		obtain: () -> T?,
		refresh: (T) -> Boolean,
		recycle: (T) -> Unit,
	): T? {
		repeat(2) {
			clearCache()
			val root = obtain() ?: return@repeat
			if (refresh(root)) return root
			recycle(root)
		}
		return null
	}
}
