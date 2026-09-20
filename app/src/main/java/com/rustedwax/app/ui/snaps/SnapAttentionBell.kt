package com.rustedwax.app.ui.snaps

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rustedwax.app.ui.WaxIcons

/**
 * The top bar's bell: what needs reading, and what needs finishing.
 *
 * **Absent when there is nothing to say.** Not disabled, not an empty menu — a
 * bell that is always there teaches people that tapping it is usually a waste,
 * which is the fastest way to make the one time it matters invisible. Its
 * appearing *is* the notification.
 *
 * Every row is a label and a destination. Nothing in this file, or anywhere
 * below it, can post, vote, retry or resend: [onOpen] is handed a
 * [SnapAttentionTarget], which is a place, and the screen decides what to do
 * with it. The rows the user does not tap are left exactly as they were.
 */
@Composable
internal fun SnapAttentionBell(
	rows: List<SnapAttentionRow>,
	onOpen: (SnapAttentionRow) -> Unit,
	modifier: Modifier = Modifier,
) {
	if (rows.isEmpty()) return
	var open by remember { mutableStateOf(false) }

	Box(modifier) {
		IconButton(onClick = { open = true }) {
			Box(contentAlignment = Alignment.TopEnd) {
				Icon(
					WaxIcons.Bell,
					// The count is in the description rather than only in the
					// badge: a screen reader announcing "notifications" on a
					// control whose whole meaning is the number beside it would
					// be reading the decoration and not the content.
					contentDescription = "${rows.size} needing attention",
					tint = MaterialTheme.colorScheme.onBackground,
					modifier = Modifier.size(22.dp),
				)
				Badge(rows.size)
			}
		}

		DropdownMenu(
			expanded = open,
			onDismissRequest = { open = false },
			modifier = Modifier.widthIn(max = 320.dp),
		) {
			// Bounded, and honest about the bound. `DropdownMenu` scrolls, but a
			// menu long enough to need scrolling is an inbox, and Stage 6 is
			// deliberately not one.
			rows.take(SnapAttentionModel.MAX_ROWS).forEachIndexed { index, row ->
				if (index > 0) HorizontalDivider()
				AttentionRow(
					row = row,
					onClick = {
						open = false
						onOpen(row)
					},
				)
			}
			if (rows.size > SnapAttentionModel.MAX_ROWS) {
				HorizontalDivider()
				Text(
					"and ${rows.size - SnapAttentionModel.MAX_ROWS} more",
					style = MaterialTheme.typography.labelMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
				)
			}
		}
	}
}

/**
 * The count, drawn over the corner of the bell.
 *
 * Capped in its *rendering* at 99+ because three digits do not fit on a 22dp
 * icon — never in the number itself, which the row list still holds in full.
 */
@Composable
private fun Badge(count: Int) {
	Text(
		if (count > MAX_BADGE) "$MAX_BADGE+" else "$count",
		style = MaterialTheme.typography.labelSmall,
		color = MaterialTheme.colorScheme.onError,
		modifier = Modifier
			.defaultMinSize(minWidth = 15.dp)
			.clip(RoundedCornerShape(percent = 50))
			.background(MaterialTheme.colorScheme.error)
			.padding(horizontal = 4.dp),
	)
}

private const val MAX_BADGE = 99

/**
 * One line of the menu.
 *
 * Not a `DropdownMenuItem`: that draws a single-line label, and these rows are
 * two lines — what happened, and the words or the reason underneath. The
 * detail is clamped to two lines because it can be a stranger's reply, and a
 * menu item is not allowed to grow with somebody else's text.
 */
@Composable
private fun AttentionRow(row: SnapAttentionRow, onClick: () -> Unit) {
	Row(
		verticalAlignment = Alignment.Top,
		modifier = Modifier
			.clickable(enabled = row.target != null, onClick = onClick)
			.padding(horizontal = 14.dp, vertical = 10.dp),
	) {
		Icon(
			when (row.kind) {
				SnapAttentionKind.INCOMING_REPLY -> WaxIcons.SpeechBubble
				SnapAttentionKind.LIKE -> WaxIcons.Heart
				else -> WaxIcons.Alert
			},
			contentDescription = null,
			tint = when (row.kind) {
				SnapAttentionKind.INCOMING_REPLY -> MaterialTheme.colorScheme.onSurfaceVariant
				else -> MaterialTheme.colorScheme.error
			},
			modifier = Modifier
				.padding(top = 2.dp)
				.size(16.dp),
		)
		Spacer(Modifier.width(10.dp))
		Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
			Text(
				row.title,
				style = MaterialTheme.typography.titleSmall,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			if (row.detail.isNotBlank()) {
				Text(
					row.detail,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 2,
					overflow = TextOverflow.Ellipsis,
				)
			}
		}
	}
}
