package com.rustedwax.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** How long a button takes to travel between its resting and pressed colour. */
private const val PRESS_MILLIS = 190

/**
 * The press transition the design asks for: a white control that warms to light
 * pink while your finger is on it, and cools back when you let go.
 *
 * The ripple alone says only "a tap landed somewhere". On a screen that is
 * mostly white cards holding white buttons, the thing worth showing is *which*
 * control has you — so the whole surface moves, and it moves at a speed you can
 * see rather than a flash you can't.
 *
 * Every button in the app goes through here or [WaxButton]; a bare
 * `OutlinedButton` would be the one control on screen that doesn't answer.
 */
@Composable
fun WaxOutlinedButton(
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
	enabled: Boolean = true,
	selected: Boolean = false,
	icon: ImageVector? = null,
	/**
	 * Holds the glyph at one colour while the label travels.
	 *
	 * Null keeps the original behaviour — icon and label are the same colour and
	 * move together — which is what every caller outside the History action row
	 * wants. Don't scrobble is the exception the mockup draws: a pink mark
	 * against a plain label, so the destructive one is legible as itself without
	 * being shouted in pink from end to end.
	 */
	iconTint: Color? = null,
	content: @Composable RowScope.() -> Unit,
) {
	val dark = LocalWaxDark.current
	val interaction = remember { MutableInteractionSource() }
	val pressed by interaction.collectIsPressedAsState()

	val resting = MaterialTheme.colorScheme.surface
	val washed = if (dark) Wax.PinkWashDark else Wax.PinkWash
	val container by animateColorAsState(
		targetValue = if (pressed) washed else resting,
		animationSpec = tween(PRESS_MILLIS),
		label = "container",
	)
	// Selected already sits in the accent, so only the unselected ones travel.
	val accent = MaterialTheme.colorScheme.primary
	val onSurface = MaterialTheme.colorScheme.onSurface
	val outline = MaterialTheme.colorScheme.outline
	val label by animateColorAsState(
		targetValue = if (selected || pressed) accent else onSurface,
		animationSpec = tween(PRESS_MILLIS),
		label = "label",
	)
	val edge by animateColorAsState(
		targetValue = if (selected || pressed) accent else outline,
		animationSpec = tween(PRESS_MILLIS),
		label = "edge",
	)

	OutlinedButton(
		onClick = onClick,
		modifier = modifier,
		enabled = enabled,
		shape = MaterialTheme.shapes.small,
		interactionSource = interaction,
		border = BorderStroke(if (selected) 1.4.dp else 1.dp, edge),
		colors = ButtonDefaults.outlinedButtonColors(
			containerColor = container,
			contentColor = label,
			disabledContainerColor = resting,
			disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
		),
		contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
	) {
		if (icon != null) {
			if (iconTint != null && enabled) {
				Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(17.dp))
			} else {
				Icon(icon, contentDescription = null, modifier = Modifier.size(17.dp))
			}
			Spacer(Modifier.size(7.dp))
		}
		content()
	}
}

/**
 * The filled counterpart, for the one action a screen is actually about.
 *
 * Travels the same distance in the same time as [WaxOutlinedButton], in the
 * other direction — pink toward the wash — so pressing anything on a screen
 * feels like one gesture rather than two conventions.
 */
@Composable
fun WaxButton(
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
	enabled: Boolean = true,
	destructive: Boolean = false,
	icon: ImageVector? = null,
	content: @Composable RowScope.() -> Unit,
) {
	val interaction = remember { MutableInteractionSource() }
	val pressed by interaction.collectIsPressedAsState()

	val base = if (destructive) {
		MaterialTheme.colorScheme.error
	} else {
		MaterialTheme.colorScheme.primary
	}
	val container by animateColorAsState(
		targetValue = if (pressed) base.lightenTowardWash() else base,
		animationSpec = tween(PRESS_MILLIS),
		label = "container",
	)

	Button(
		onClick = onClick,
		modifier = modifier,
		enabled = enabled,
		shape = MaterialTheme.shapes.small,
		interactionSource = interaction,
		colors = ButtonDefaults.buttonColors(
			containerColor = container,
			contentColor = if (destructive) {
				MaterialTheme.colorScheme.onError
			} else {
				MaterialTheme.colorScheme.onPrimary
			},
		),
		contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
	) {
		if (icon != null) {
			Icon(icon, contentDescription = null, modifier = Modifier.size(17.dp))
			Spacer(Modifier.size(7.dp))
		}
		content()
	}
}

/** Halfway to white, which is where a pressed pink reads as "held". */
private fun Color.lightenTowardWash() = Color(
	red = red + (1f - red) * 0.28f,
	green = green + (1f - green) * 0.28f,
	blue = blue + (1f - blue) * 0.28f,
	alpha = alpha,
)

/**
 * The pink glyph in its tinted rounded square, left of a settings row.
 *
 * Decoration, which is why it carries no content description — the row's own
 * title is the label, and announcing the icon separately would read every
 * setting out twice.
 */
@Composable
fun WaxRowIcon(icon: ImageVector, modifier: Modifier = Modifier, alert: Boolean = false) {
	val tint = if (alert) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
	Box(
		modifier = modifier
			.size(34.dp)
			.background(color = tint.copy(alpha = 0.10f), shape = RoundedCornerShape(10.dp)),
		contentAlignment = Alignment.Center,
	) {
		Icon(
			icon,
			contentDescription = null,
			tint = tint,
			modifier = Modifier.size(19.dp),
		)
	}
}

/**
 * Pink track, white thumb, in both schemes.
 *
 * Material would draw the thumb in `onPrimary`, which on the dark scheme is a
 * near-black maroon — correct contrast, wrong object: the design's thumb is a
 * physical white cap that moves, and it has to stay the same cap when the
 * lights go out.
 */
@Composable
fun waxSwitchColors(): SwitchColors = SwitchDefaults.colors(
	checkedThumbColor = Color.White,
	checkedTrackColor = MaterialTheme.colorScheme.primary,
	checkedBorderColor = MaterialTheme.colorScheme.primary,
	uncheckedThumbColor = MaterialTheme.colorScheme.outline,
	uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
	uncheckedBorderColor = MaterialTheme.colorScheme.outline,
	disabledCheckedThumbColor = Color.White.copy(alpha = 0.6f),
	disabledCheckedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
	disabledCheckedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
	disabledUncheckedThumbColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
	disabledUncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
	disabledUncheckedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
)

/**
 * A row of controls that keeps the design's spacing without repeating it.
 *
 * Wraps rather than clips. Two buttons whose labels are full sentences — which
 * is this app's house style — do not both fit across a 360dp phone, and the
 * one that loses is always the second one.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WaxButtonRow(
	modifier: Modifier = Modifier,
	// Deliberately not `FlowRowScope.() -> Unit`: that scope is experimental,
	// and exposing it here would make every call site opt in to an annotation
	// for a layout detail none of them use.
	content: @Composable () -> Unit,
) {
	FlowRow(
		modifier = modifier,
		horizontalArrangement = Arrangement.spacedBy(8.dp),
		verticalArrangement = Arrangement.spacedBy(8.dp),
	) {
		content()
	}
}
