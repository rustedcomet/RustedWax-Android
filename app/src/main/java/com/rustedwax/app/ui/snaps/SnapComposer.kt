package com.rustedwax.app.ui.snaps

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rustedwax.app.ui.LocalWaxDark
import com.rustedwax.app.ui.Wax
import com.rustedwax.app.ui.WaxIcons

/**
 * The quick strip from the spec, not an emoji browser.
 *
 * Seventeen of them, chosen in the plan, and the keyboard is still right there
 * for everything else — the point is one tap for the reaction people actually
 * leave, not a catalogue RustedWax would then have to maintain.
 */
internal val QUICK_EMOJI = listOf(
	"😂", "❤️", "🔥", "😍", "😭", "😮", "😡", "🤣", "👍",
	"👎", "👀", "🤔", "🙌", "💯", "🚨", "🎵", "🤘",
)

/**
 * The inline composer, living inside the History card that opened it.
 *
 * Stage 1: everything here is real except publication. This file has no way to
 * reach Hive at all — the Post control lives in the card's action row, and what
 * it does there is say so.
 */
@Composable
internal fun SnapComposer(
	text: String,
	onTextChange: (String) -> Unit,
	onClose: () -> Unit,
	modifier: Modifier = Modifier,
) {
	var emojiOpen by remember { mutableStateOf(false) }
	val overflowing = SnapText.isOverflowing(text)

	Column(modifier.fillMaxWidth()) {
		// The close control sits above the field's top-right corner rather than
		// on top of it. The spec draws it inside the box, level with the
		// placeholder; there it would cover the end of the first line of a
		// 200-character draft, and text disappearing under a button is a worse
		// failure than the icon sitting a few dp higher than the sketch.
		Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
			Box(Modifier.weight(1f))
			IconButton(
				onClick = onClose,
				modifier = Modifier.size(44.dp),
			) {
				Icon(
					WaxIcons.Close,
					contentDescription = "Close Snap composer",
					tint = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.size(16.dp),
				)
			}
		}
		OutlinedTextField(
			value = text,
			onValueChange = onTextChange,
			placeholder = { Text("Write a Snap...") },
			// Three rows of usable height, and it stops growing there: the card
			// is a History row, not a text editor. Past that it scrolls.
			//
			// 92 = three 20dp lines of bodyMedium plus the field's own 16dp top
			// and bottom padding. 108 fitted 3.8 lines, which the A12 drew as
			// four — near enough on paper, one row too tall on the device.
			modifier = Modifier
				.fillMaxWidth()
				.heightIn(min = 92.dp, max = 92.dp),
			shape = MaterialTheme.shapes.small,
			textStyle = MaterialTheme.typography.bodyMedium,
			colors = OutlinedTextFieldDefaults.colors(
				// Pink whether or not it has focus — in the mockup the open
				// composer is the lit thing on the card.
				focusedBorderColor = MaterialTheme.colorScheme.primary,
				unfocusedBorderColor = MaterialTheme.colorScheme.primary,
				cursorColor = MaterialTheme.colorScheme.primary,
			),
		)

		Spacer(Modifier.height(4.dp))
		Row(verticalAlignment = Alignment.CenterVertically) {
			IconButton(
				onClick = { emojiOpen = !emojiOpen },
				modifier = Modifier.size(44.dp),
			) {
				Icon(
					WaxIcons.EmojiFace,
					contentDescription = if (emojiOpen) "Hide emoji" else "Add emoji",
					tint = if (emojiOpen) {
						MaterialTheme.colorScheme.primary
					} else {
						MaterialTheme.colorScheme.onSurfaceVariant
					},
					modifier = Modifier.size(19.dp),
				)
			}
			Spacer(Modifier.width(1.dp))
			Box(Modifier.weight(1f))
			// The counter turns into the amount owed, in red, once it is over.
			Text(
				SnapText.counterLabel(text),
				style = MaterialTheme.typography.labelMedium,
				fontFamily = FontFamily.Monospace,
				fontSize = 11.sp,
				color = when {
					overflowing -> MaterialTheme.colorScheme.error
					else -> MaterialTheme.colorScheme.onSurfaceVariant
				},
			)
		}

		AnimatedVisibility(
			visible = emojiOpen,
			enter = fadeIn(tween(120)) + expandVertically(tween(140)),
			exit = fadeOut(tween(100)) + shrinkVertically(tween(120)),
		) {
			QuickEmojiStrip(
				onPick = { onTextChange(text + it) },
				modifier = Modifier.padding(top = 2.dp, bottom = 2.dp),
			)
		}
	}
}

/** The compact reaction row. Taps append; the keyboard still does everything else. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickEmojiStrip(onPick: (String) -> Unit, modifier: Modifier = Modifier) {
	val dark = LocalWaxDark.current
	Surface(
		modifier = modifier.fillMaxWidth(),
		shape = MaterialTheme.shapes.small,
		color = if (dark) Wax.PinkWashDark.copy(alpha = 0.35f) else Wax.PinkWash.copy(alpha = 0.5f),
		border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
	) {
		FlowRow(
			modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
			horizontalArrangement = Arrangement.spacedBy(2.dp),
		) {
			QUICK_EMOJI.forEach { emoji ->
				TextButton(
					onClick = { onPick(emoji) },
					shape = RoundedCornerShape(8.dp),
					contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
					modifier = Modifier.defaultMinSize(minWidth = 40.dp, minHeight = 40.dp),
				) {
					Text(emoji, fontSize = 19.sp)
				}
			}
		}
	}
}

/**
 * "Discard Snap?" — only ever raised for a draft with something in it.
 *
 * An empty composer closes on the first tap; asking about nothing would be the
 * kind of dialog people learn to dismiss without reading.
 */
@Composable
internal fun DiscardSnapDialog(onKeepEditing: () -> Unit, onDiscard: () -> Unit) {
	AlertDialog(
		onDismissRequest = onKeepEditing,
		title = { Text("Discard Snap?") },
		text = { Text("This draft will be deleted. It hasn't been posted to Hive.") },
		confirmButton = {
			TextButton(onClick = onDiscard) {
				Text("Discard", color = MaterialTheme.colorScheme.error)
			}
		},
		dismissButton = {
			TextButton(onClick = onKeepEditing) { Text("Keep editing") }
		},
	)
}
