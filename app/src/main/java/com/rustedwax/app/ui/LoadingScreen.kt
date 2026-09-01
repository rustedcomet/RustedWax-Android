package com.rustedwax.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.rustedwax.app.R

/** One turn of the record, in milliseconds. 33⅓ rpm is 1800; this is brisker. */
private const val TURN_MILLIS = 2600

/**
 * A floor on how long the loading screen stays up, for looking at it.
 *
 * **This is a deliberate delay to startup and nothing else.** Set to `0` to
 * restore the intended behaviour, where the screen lasts exactly as long as the
 * work behind it — around 640 ms on the field device, which is not long enough
 * to judge an animation by.
 *
 * It does not affect the `ready in <n>ms` line: that still measures the work,
 * so the number stays honest about what startup costs while this is set.
 */
const val LOADING_MINIMUM_MILLIS = 3_000L

/**
 * The logo, with the record turning under it, while the app wakes up.
 *
 * Shown only for as long as there is something to wait for — the key vault, the
 * dedup ledger and the send queue all open files, and on a cold start that is
 * the couple of hundred milliseconds the window would otherwise sit empty. It
 * is not a timed splash: [com.rustedwax.app.MainActivity] shows it while that
 * work runs and swaps it out the moment the work is done, so on a warm start it
 * may not be seen at all. That is the intended outcome, not a failure of it.
 *
 * ## Two layers, because the lettering must not turn
 *
 * `rustedwax_mark.png` is one flat image with the wordmark baked into the
 * record, so it was split into a pair by `design/split_logo.py`:
 *
 *  - `rustedwax_disc` — the vinyl: grooves, rings, the pink label and the gloss
 *    on it. This is what rotates.
 *  - `rustedwax_lettering` — the furniture standing on it: "RustedWax", the
 *    tonearm, the wax drips and the rim. This does not move.
 *
 * Drawn one over the other at rest they reproduce the original mark pixel for
 * pixel, which is the property the split script asserts. Nothing was redrawn.
 *
 * The gloss on the label is doing more work than it looks: a groove pattern is
 * a solid of revolution, so a record made only of grooves would spin without
 * anything on screen appearing to move at all.
 */
@Composable
fun LoadingScreen(modifier: Modifier = Modifier) {
	val spin = rememberInfiniteTransition(label = "record")
	val angle by spin.animateFloat(
		initialValue = 0f,
		targetValue = 360f,
		animationSpec = infiniteRepeatable(
			// Linear and restarting, not eased: a record reaching 360° and easing
			// back into the next turn is a record slowing down twice a second.
			animation = tween(TURN_MILLIS, easing = LinearEasing),
			repeatMode = RepeatMode.Restart,
		),
		label = "angle",
	)

	Box(
		modifier = modifier
			.fillMaxSize()
			.semantics { contentDescription = "RustedWax is starting" },
		contentAlignment = Alignment.Center,
	) {
		Box(contentAlignment = Alignment.Center) {
			Image(
				painter = painterResource(R.drawable.rustedwax_disc),
				contentDescription = null,
				modifier = Modifier
					.size(LOGO_SIZE)
					.rotate(angle),
			)
			Image(
				painter = painterResource(R.drawable.rustedwax_lettering),
				contentDescription = null,
				modifier = Modifier.size(LOGO_SIZE),
			)
		}
	}
}

/**
 * The mark is 288px square; this is a shade under that on a 3x screen, so it is
 * never upscaled and the grooves stay grooves rather than turning into moiré.
 */
private val LOGO_SIZE = 132.dp
