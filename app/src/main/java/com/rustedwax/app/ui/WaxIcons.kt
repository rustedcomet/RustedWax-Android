package com.rustedwax.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The line icons from the mockup, drawn here rather than pulled from a font.
 *
 * `material-icons-extended` is a five-thousand-icon dependency for the nine
 * glyphs this app shows, and half of them (a Shorts bolt, a record, a
 * picture-in-picture frame) aren't in it anyway. These are stroked outlines at
 * a single weight so a settings card reads as one drawn set — `Icon` tints
 * them, so each one is declared once and works in both schemes.
 */
object WaxIcons {

	/** Native YouTube: the player frame. */
	val PlayBox = stroked(
		"M4 7.2a3.2 3.2 0 0 1 3.2-3.2h9.6A3.2 3.2 0 0 1 20 7.2v9.6a3.2 3.2 0 0 1" +
			"-3.2 3.2H7.2A3.2 3.2 0 0 1 4 16.8z",
		fills = listOf("M10.3 8.9 15 12l-4.7 3.1z"),
	)

	/** Foreground Shorts evidence: the Shorts bolt. */
	val Bolt = stroked(
		"M13.9 2.9 7.2 12.2h4.3l-1.4 8.9 6.7-9.3h-4.3z",
	)

	/** Native YouTube Music. */
	val MusicNote = stroked(
		"M9.4 17.6V5.2l9.2-2v12.4",
		"M6.9 20.1a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5z",
		"M16.1 17.6a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5z",
	)

	/** Automatic scrobbling: a record, still turning. */
	val Record = stroked(
		"M12 3.2a8.8 8.8 0 1 0 0 17.6 8.8 8.8 0 0 0 0-17.6z",
		"M12 7.4a4.6 4.6 0 1 0 0 9.2 4.6 4.6 0 0 0 0-9.2z",
		fills = listOf("M12 10.4a1.6 1.6 0 1 0 0 3.2 1.6 1.6 0 0 0 0-3.2z"),
	)

	/** Browser evidence access: reading what is on screen. */
	val Search = stroked(
		"M10.8 3.8a7 7 0 1 0 0 14 7 7 0 0 0 0-14z",
		"M15.9 15.9 20.6 20.6",
	)

	/** Look videos up: the same lens, over a video. */
	val SearchPlay = stroked(
		"M10.8 3.8a7 7 0 1 0 0 14 7 7 0 0 0 0-14z",
		"M15.9 15.9 20.6 20.6",
		fills = listOf("M9.2 8.1 13 10.8 9.2 13.5z"),
	)

	/** YouTube watch history. */
	val History = stroked(
		"M12 4a8 8 0 1 0 0 16 8 8 0 0 0 0-16z",
		"M12 7.6V12l3.1 1.9",
	)

	/** Short clips: a strip of film, cut short. */
	val FilmStrip = stroked(
		"M3.4 7.6a2.2 2.2 0 0 1 2.2-2.2h12.8a2.2 2.2 0 0 1 2.2 2.2v8.8a2.2 2.2 0" +
			" 0 1-2.2 2.2H5.6a2.2 2.2 0 0 1-2.2-2.2z",
		"M8.2 5.4v13.2",
		"M15.8 5.4v13.2",
	)

	/** Count picture-in-picture. */
	val PictureInPicture = stroked(
		"M3.4 6.6a2.2 2.2 0 0 1 2.2-2.2h12.8a2.2 2.2 0 0 1 2.2 2.2v10.8a2.2 2.2 0" +
			" 0 1-2.2 2.2H5.6a2.2 2.2 0 0 1-2.2-2.2z",
		"M12.4 12.4h6.2v4.6h-6.2z",
	)

	/** Appearance: the light/dark switch. */
	val Contrast = stroked(
		"M12 3.6a8.4 8.4 0 1 0 0 16.8 8.4 8.4 0 0 0 0-16.8z",
		fills = listOf("M12 3.6a8.4 8.4 0 0 1 0 16.8z"),
	)

	/** Stop monitoring. */
	val StopSquare = stroked(
		"M7.6 8.6a1 1 0 0 1 1-1h6.8a1 1 0 0 1 1 1v6.8a1 1 0 0 1-1 1H8.6a1 1 0 0" +
			" 1-1-1z",
	)

	/** Start monitoring. */
	val PlayTriangle = stroked(
		fills = listOf("M8.4 5.6 18.4 12l-10 6.4z"),
	)

	/** Clear the log. */
	val Trash = stroked(
		"M4.6 6.9h14.8",
		"M9.4 6.9V5.4a1.6 1.6 0 0 1 1.6-1.6h2a1.6 1.6 0 0 1 1.6 1.6v1.5",
		"M6.4 6.9l.8 12.1a1.6 1.6 0 0 0 1.6 1.5h6.4a1.6 1.6 0 0 0 1.6-1.5l.8-12.1",
	)

	/** Send the log somewhere else. */
	val Export = stroked(
		"M12 3.8v10.4",
		"M8.2 10.6 12 14.4l3.8-3.8",
		"M4.6 17v1.8a1.8 1.8 0 0 0 1.8 1.8h11.2a1.8 1.8 0 0 0 1.8-1.8V17",
	)

	/** Broadcast a test scrobble: a waveform going out. */
	val Waveform = stroked(
		"M3.6 10.4v3.2",
		"M7.2 6.8v10.4",
		"M10.8 3.8v16.4",
		"M14.4 8.4v7.2",
		"M18 5.6v12.8",
		"M20.4 10.4v3.2",
	)

	/** Confirmed in a block. */
	val CheckCircle = stroked(
		"M12 3.6a8.4 8.4 0 1 0 0 16.8 8.4 8.4 0 0 0 0-16.8z",
		"M8.2 12.2 11 15l5-5.6",
	)

	/** The overflow affordance in the top bar and on a list row. */
	val MoreVert = stroked(
		fills = listOf(
			"M12 4.4a1.7 1.7 0 1 0 0 3.4 1.7 1.7 0 0 0 0-3.4z",
			"M12 10.3a1.7 1.7 0 1 0 0 3.4 1.7 1.7 0 0 0 0-3.4z",
			"M12 16.2a1.7 1.7 0 1 0 0 3.4 1.7 1.7 0 0 0 0-3.4z",
		),
	)

	/** The posting key. */
	val Key = stroked(
		"M15.4 4.4a4.6 4.6 0 1 0 0 9.2 4.6 4.6 0 0 0 0-9.2z",
		"M11.9 12.1 4.4 19.6",
		"M6.9 17.1 9 19.2",
	)

	/** A grant that stopped on its own. */
	val Alert = stroked(
		"M12 3.6a8.4 8.4 0 1 0 0 16.8 8.4 8.4 0 0 0 0-16.8z",
		"M12 7.8v5",
		fills = listOf("M12 15.1a1.2 1.2 0 1 0 0 2.4 1.2 1.2 0 0 0 0-2.4z"),
	)

	/**
	 * Builds a 24dp icon from SVG path data.
	 *
	 * Stroke width, cap and join are fixed here on purpose: a set is only a set
	 * while every glyph in it is drawn with the same pen.
	 */
	private fun stroked(vararg strokes: String, fills: List<String> = emptyList()) =
		ImageVector.Builder(
			defaultWidth = 24.dp,
			defaultHeight = 24.dp,
			viewportWidth = 24f,
			viewportHeight = 24f,
		).apply {
			strokes.forEach { d ->
				addPath(
					pathData = PathParser().parsePathString(d).toNodes(),
					fill = null,
					stroke = SolidColor(Color.Black),
					strokeLineWidth = 1.7f,
					strokeLineCap = StrokeCap.Round,
					strokeLineJoin = StrokeJoin.Round,
				)
			}
			fills.forEach { d ->
				addPath(
					pathData = PathParser().parsePathString(d).toNodes(),
					fill = SolidColor(Color.Black),
				)
			}
		}.build()
}
