package com.rustedwax.app.ui.snaps

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rustedwax.app.snaps.PostedSnap
import com.rustedwax.app.snaps.SnapAge

/**
 * A posted root Snap, as it sits under the History row that produced it.
 *
 * Four things and no more: the avatar, the handle, how long ago, and the exact
 * words the user typed. What is absent is the point —
 *
 *  - **no YouTube link and no hashtags.** They are genuinely on chain, appended
 *    by [com.rustedwax.app.snaps.SnapPayloadBuilder], and showing them back
 *    would turn the user's one-line reaction into a machine-written post with
 *    their sentence at the top of it;
 *  - **no artist, title or media header.** The History row directly above
 *    already says what was playing. Repeating it inside the Snap would be
 *    RustedWax asserting a classification a second time, in the one place that
 *    is supposed to be quoting the user rather than the engine.
 *
 * Everything it draws degrades on its own. A Snap with no readable text shows
 * the header alone; an avatar that never arrives leaves a neutral circle; an
 * unknown age simply is not printed. No branch of this can fail to render.
 */
@Composable
internal fun PostedSnapCard(
	/**
	 * A posted Snap that has already been checked against the signed-in account.
	 *
	 * Non-null on purpose. An earlier revision accepted null and fell back to the
	 * author half of `author/permlink`, which quietly reintroduced the one thing
	 * [com.rustedwax.app.snaps.PostedSnaps] refuses to do: a stored record whose
	 * author is not the signed-in account would have had that stranger's handle —
	 * and an avatar request for it — drawn on this user's History card. There is
	 * now one route to a handle, and it has been through that check.
	 */
	posted: PostedSnap,
	nowEpochSec: Long,
	modifier: Modifier = Modifier,
) {
	val author = posted.author

	Row(
		modifier = modifier
			.fillMaxWidth()
			.padding(top = 2.dp, bottom = 8.dp),
	) {
		HiveAvatar(account = author)
		Spacer(Modifier.width(8.dp))
		Column(Modifier.weight(1f)) {
			Row(verticalAlignment = Alignment.CenterVertically) {
				Text(
					"@$author",
					style = MaterialTheme.typography.labelLarge,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.weight(1f, fill = false),
				)
				Spacer(Modifier.width(6.dp))
				Text(
					SnapAge.label(nowEpochSec, posted.createdAtEpochSec),
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 1,
				)
			}
			posted.userText.takeIf { it.isNotEmpty() }?.let {
				Text(
					// The whole Snap, exactly as published.
					//
					// No `maxLines`, no ellipsis and no truncation before it gets
					// here: every one of those hides text the composer accepted,
					// and a Snap the user is allowed to write is a Snap they are
					// allowed to read back. Two hundred clusters of emoji or
					// twenty short lines both simply make the card taller, which
					// is the correct outcome and not a layout problem.
					//
					// `Text` renders it as text and only as text — no markup, no
					// HTML and no link handling anywhere in this card — so nothing
					// in it can be anything but characters.
					it,
					style = MaterialTheme.typography.bodyMedium,
					modifier = Modifier.padding(top = 2.dp),
				)
			}
		}
	}
}
