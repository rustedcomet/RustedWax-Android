package com.rustedwax.app.ui

/**
 * What came back when a thumbnail was asked for.
 *
 * Split out of [Thumbnails] and kept free of Android types so the one decision
 * that has a lasting consequence — whether an id is written off permanently —
 * can be tested without a device.
 */
internal sealed interface Fetched {
	/** A readable image. */
	class Body(val bytes: ByteArray) : Fetched

	/**
	 * There is provably no thumbnail: the video is deleted, private, or was
	 * never there. Safe to remember forever.
	 */
	data object Absent : Fetched

	/**
	 * The question could not be asked — no signal, a timeout, a captive portal,
	 * a 5xx. **Never remembered.** Recording one of these as [Absent] blanks
	 * that row's thumbnail for the life of the process, so a minute in a lift
	 * would cost the rest of the session's thumbnails.
	 */
	data object Unavailable : Fetched
}

internal object ThumbnailFetch {

	/** Bodies above this were not read whole, so they are not bodies. */
	const val MAX_BYTES = 512 * 1024

	/**
	 * A thumbnail YouTube does not have is answered with a tiny grey
	 * placeholder and a 200, not a 404, so size is part of the verdict.
	 */
	const val MIN_BYTES = 1_024

	private const val HTTP_OK = 200
	private const val HTTP_FORBIDDEN = 403
	private const val HTTP_NOT_FOUND = 404
	private const val HTTP_GONE = 410

	fun classify(status: Int, byteCount: Int): Fetched = when {
		status == HTTP_OK && byteCount > MAX_BYTES -> Fetched.Unavailable
		status == HTTP_OK && byteCount < MIN_BYTES -> Fetched.Absent
		status == HTTP_OK -> Fetched.Body(ByteArray(0))
		status == HTTP_NOT_FOUND || status == HTTP_GONE || status == HTTP_FORBIDDEN ->
			Fetched.Absent
		// 5xx, a redirect that went nowhere, a portal's 511: ask again later.
		else -> Fetched.Unavailable
	}
}
