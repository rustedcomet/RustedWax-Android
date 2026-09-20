package com.rustedwax.app.ui.snaps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rustedwax.app.snaps.PostedSnap
import com.rustedwax.app.snaps.SnapAge
import com.rustedwax.app.snaps.SnapReply
import com.rustedwax.app.snaps.SnapReplyTarget
import com.rustedwax.app.snaps.SnapThreadNode
import com.rustedwax.app.snaps.SnapThreadPreview
import com.rustedwax.app.ui.WaxIcons
import com.rustedwax.app.ui.WaxOutlinedButton
import com.rustedwax.hive.ViewerVote

/**
 * The two replies a History card is allowed to show, and the way out to the
 * rest.
 *
 * Bounded by [SnapThreadPreview] before it gets here — at most two items, each
 * already clamped — and bounded again on the way out by `maxLines`, so a card
 * cannot grow with the conversation under it however long that conversation
 * gets. The second bound is not redundant: the first limits characters, and it
 * is lines that make a card tall.
 */
@Composable
internal fun SnapThreadPreviewStrip(
	preview: SnapThreadPreview.Preview,
	onOpenThread: () -> Unit,
	modifier: Modifier = Modifier,
) {
	if (preview.items.isEmpty()) return
	Column(modifier.fillMaxWidth().padding(start = 34.dp, bottom = 6.dp)) {
		preview.items.forEach { item ->
			Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
				Text(
					"@${item.author}",
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
				Spacer(Modifier.width(6.dp))
				Text(
					// Plain characters and only plain characters: `Text` has no
					// markup, no HTML and no link handling, so nothing a stranger
					// wrote can be anything but text on this card.
					if (item.truncated) item.text + "…" else item.text,
					style = MaterialTheme.typography.bodySmall,
					maxLines = 2,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.weight(1f),
				)
			}
		}
		if (preview.hasMore) {
			TextButton(
				onClick = onOpenThread,
				contentPadding = androidx.compose.foundation.layout.PaddingValues(
					horizontal = 0.dp,
					vertical = 2.dp,
				),
			) {
				Text(
					"View replies (${preview.total})",
					style = MaterialTheme.typography.labelMedium,
				)
			}
		}
	}
}

/**
 * The whole conversation under one Snap, with room to read it.
 *
 * This is the screen the 200-character limit does **not** apply to. RustedWax's
 * limit binds what RustedWax creates; a reply written in any other Hive client
 * may be as long as Hive allows, and here it is shown complete — no `maxLines`,
 * no ellipsis, no "read more". The History card is where a long reply is
 * summarised, and this is where the summary is redeemed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SnapThreadSheet(
	root: SnapReplyTarget,
	rootSnap: PostedSnap?,
	threads: SnapThreadController,
	likes: SnapLikeController,
	nowEpochSec: Long,
	onDismiss: () -> Unit,
) {
	val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
	ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
		Column(
			Modifier
				.fillMaxWidth()
				.heightIn(max = 560.dp)
				.padding(horizontal = 16.dp),
		) {
			Text("Thread", style = MaterialTheme.typography.titleMedium)
			Spacer(Modifier.height(8.dp))

			// The root Snap, and the one place to reply to it directly.
			rootSnap?.let {
				CommentBlock(
					author = it.author,
					createdAtEpochSec = it.createdAtEpochSec,
					body = it.userText,
					depth = 0,
					nowEpochSec = nowEpochSec,
					root = root,
					target = root,
					threads = threads,
					likes = likes,
					// The root of a History thread is always this user's own
					// Snap — `PostedSnaps` refuses to draw a confirmed row whose
					// author is anybody else — so it never gets a heart, and
					// `SnapLikeController.showsHeart` is what enforces that
					// rather than this argument. No vote state is carried for it
					// because the thread builder drops the root from the tree.
					viewerVote = ViewerVote.Unreadable("the root Snap is your own"),
					// The root's own Like count *is* carried: it is the Snap
					// this user posted, and how many people liked it is the
					// part they most want to see. The heart beside it stays
					// non-interactive — see [SocialLikeCount].
					likeCount = threads.thread(root)?.rootLikeCount ?: 0,
				)
				HorizontalDivider(Modifier.padding(vertical = 8.dp))
			}

			when (val load = threads.state(root)) {
				null, SnapThreadLoad.Loading -> Text(
					"Loading replies…",
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)

				is SnapThreadLoad.Unavailable -> Column {
					Text(
						load.message,
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
					Spacer(Modifier.height(6.dp))
					WaxOutlinedButton(
						onClick = { threads.load(root, force = true) },
						icon = WaxIcons.Send,
					) {
						Text("Try again")
					}
				}

				is SnapThreadLoad.Ready -> {
					// Hive is authoritative. A conversation that has just been
					// re-read describes these comments more recently than any
					// local answer from a previous tap, so the freshly-read vote
					// state retires it — except for an attempt still in flight,
					// and an ambiguous one the read did not answer. See
					// [SnapLikeController.reconcile].
					LaunchedEffect(load.thread) { likes.reconcile(load.thread) }
					// The complete conversation, every valid reply of it. The list
					// is whole and `LazyColumn` is what makes reading it
					// incremental: it composes the rows on screen and no more, so
					// a thread of five thousand costs what a thread of five does
					// until the reader scrolls.
					val nodes = load.thread.rows
					if (nodes.isEmpty()) {
						Text(
							"No replies yet.",
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
					} else {
						LazyColumn(
							verticalArrangement = Arrangement.spacedBy(4.dp),
							// Bounded, so an enormous conversation scrolls inside
							// the sheet instead of measuring itself against
							// infinity.
							modifier = Modifier.weight(1f, fill = false),
						) {
							// Keyed by the comment's own chain identity, which the
							// builder has already de-duplicated, so a refresh that
							// adds a reply cannot hand one comment's composition to
							// another comment.
							items(nodes, key = { it.reply.contentId }) { node ->
								ReplyBlock(node, root, threads, likes, nowEpochSec)
							}
						}
					}
				}
			}
			Spacer(Modifier.height(16.dp))
		}
	}
}

@Composable
private fun ReplyBlock(
	node: SnapThreadNode,
	root: SnapReplyTarget,
	threads: SnapThreadController,
	likes: SnapLikeController,
	nowEpochSec: Long,
) {
	val reply: SnapReply = node.reply
	CommentBlock(
		likeCount = reply.positiveLikeCount,
		author = reply.author,
		createdAtEpochSec = reply.createdAtEpochSec,
		body = reply.body,
		depth = node.depth,
		nowEpochSec = nowEpochSec,
		root = root,
		// Null only for a comment whose own identity did not survive validation,
		// which cannot happen for a node the builder produced — but the Reply
		// control is driven by the value rather than by that reasoning, so a
		// future loosening of the reader cannot silently produce a reply aimed
		// at a parent nobody checked.
		target = SnapReplyTarget.of(reply),
		threads = threads,
		likes = likes,
		// Straight off the `bridge.get_discussion` response this reply was
		// parsed from — no extra request, and never an authorization. Tapping
		// the heart re-reads the chain before anything is signed.
		viewerVote = reply.viewerVote,
	)
}

/**
 * One comment: who, when, what — then a way to answer it.
 *
 * Every field is drawn defensively because every field came off the chain. A
 * missing avatar is a grey circle, an unreadable timestamp prints no age at
 * all, and an empty body simply draws nothing; none of the three can stop the
 * rest of the thread rendering.
 */
@Composable
private fun CommentBlock(
	author: String,
	createdAtEpochSec: Long?,
	body: String,
	depth: Int,
	nowEpochSec: Long,
	root: SnapReplyTarget,
	target: SnapReplyTarget?,
	threads: SnapThreadController,
	likes: SnapLikeController,
	viewerVote: ViewerVote,
	/** Positive votes the chain last showed here. Presentation only. */
	likeCount: Int,
) {
	val key = target?.let { threads.replyKey(it) }
	val replying = key != null && threads.isReplying(key)
	val status = key?.let { threads.status(it) } ?: SnapPostStatus.Idle
	// Bound to the draft it is asking about. This is the one control that
	// destroys typed text, so a recycled composition in the thread list must
	// not be able to carry a live dialog onto a different comment's draft.
	var confirmDiscard by remember(key) { mutableStateOf(false) }
	var confirmDiscardCorrupt by remember(key) { mutableStateOf(false) }

	Row(Modifier.fillMaxWidth().padding(start = (depth * 14).dp, top = 4.dp)) {
		HiveAvatar(account = author, size = 22.dp)
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
				createdAtEpochSec?.let {
					Spacer(Modifier.width(6.dp))
					Text(
						SnapAge.label(nowEpochSec, it),
						style = MaterialTheme.typography.labelSmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						maxLines = 1,
					)
				}
			}
			body.takeIf { it.isNotEmpty() }?.let {
				// Complete, however long. See [SnapThreadSheet].
				Text(it, style = MaterialTheme.typography.bodyMedium)
			}

			// Both, in this order: a comment whose identity did not survive
			// validation gets no Reply control at all.
			if (target != null && key != null) {
				when (val st = status) {
					is SnapPostStatus.Failed -> ThreadNotice(st.message)
					is SnapPostStatus.Uncertain -> ThreadNotice(st.message)
					// Written, frozen, and never sent. The reply is already in
					// the thread below — it is this device's, with a permlink
					// nothing can mint twice — so this says the one true thing
					// about it rather than taking it off the screen.
					is SnapPostStatus.Interrupted -> ThreadNotice(
						"This reply hasn't reached Hive yet.",
					)
					else -> Unit
				}

				val corrupt = threads.corruptReason(key)
				if (replying && corrupt != null) {
					// A draft whose saved state cannot be decoded may already be
					// on Hive under an intent that was lost with it, so there is
					// no composer here and no Send: the only ways out are leaving
					// it alone and throwing it away on purpose.
					ThreadNotice(corrupt)
					Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
						TextButton(onClick = { confirmDiscardCorrupt = true }) {
							Text(
								"Discard draft",
								style = MaterialTheme.typography.labelMedium,
								color = MaterialTheme.colorScheme.error,
							)
						}
						TextButton(onClick = { threads.cancelReply() }) {
							Text("Leave it", style = MaterialTheme.typography.labelMedium)
						}
					}
				} else if (replying) {
					val draft = threads.draft(key)
					SnapComposer(
						text = draft,
						// Closing is not discarding. The composer collapses and
						// the draft stays exactly as typed, here and on the
						// History card — throwing text away needs the explicit
						// control beside Send.
						onTextChange = { threads.edit(key, it) },
						onClose = { threads.cancelReply() },
					)
					Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
						WaxOutlinedButton(
							onClick = { threads.send(root, target) },
							// One explicit send. Locked while an attempt owns
							// this slot, so a second tap cannot start a second
							// broadcast — though in practice the composer is
							// already gone by then.
							enabled = SnapText.isValid(draft) && !threads.isBusy(key),
							icon = WaxIcons.Send,
						) {
							// No "Sending…". The composer closes on the durable
							// write and the reply appears below it; what Hive
							// does after that is not something to sit and watch.
							Text("Send")
						}
						// Offered only for a draft with something in it: asking
						// about nothing is the kind of dialog people learn to
						// dismiss without reading.
						if (draft.isNotEmpty() && !threads.isBusy(key)) {
							TextButton(onClick = { confirmDiscard = true }) {
								Text(
									"Discard",
									style = MaterialTheme.typography.labelMedium,
									color = MaterialTheme.colorScheme.error,
								)
							}
						}
					}
				} else {
					// Whatever the last Like attempt had to say. Ambiguity reads
					// differently from a refusal on purpose: one offers a re-read
					// and the other does not.
					likes.notice(target)?.let { ThreadNotice(it) }
					Row(
						horizontalArrangement = Arrangement.spacedBy(8.dp),
						verticalAlignment = Alignment.CenterVertically,
					) {
						// The heart sits beside Reply, in the one action row both
						// the root Snap and every reply already go through — so
						// there is a single Like control rather than two that can
						// drift apart. Absent entirely on this user's own
						// comments.
						if (likes.showsHeart(author)) {
							LikeHeart(
								heart = likes.heart(target, viewerVote),
								count = likes.likeCount(target, viewerVote, likeCount),
								pending = likes.isPending(target),
								onLike = { likes.like(target) },
								onRecheck = { likes.recheck(target) },
							)
						} else {
							// This user's own comment. They cannot Like it, so
							// there is no control — but how many other people
							// did is theirs to see, and it is the one number a
							// Snap's author actually wants.
							SocialLikeCount(likeCount)
						}
						// An ambiguous reply offers a *read* and nothing else:
						// sending again could duplicate a live comment.
						if (status is SnapPostStatus.Uncertain) {
							TextButton(onClick = { threads.recheck(root, target) }) {
								Text("Check again", style = MaterialTheme.typography.labelMedium)
							}
						} else if (status is SnapPostStatus.Interrupted) {
							// Safe to offer precisely because the reply was
							// never built: no transaction exists, so nothing
							// can be duplicated. Finishing it reuses the frozen
							// permlink and the frozen words — this cannot write
							// a second comment.
							TextButton(onClick = { threads.send(root, target) }) {
								Text("Finish reply", style = MaterialTheme.typography.labelMedium)
							}
						} else {
							TextButton(onClick = { threads.startReply(key) }) {
								Icon(
									WaxIcons.SpeechBubble,
									contentDescription = null,
									modifier = Modifier.size(13.dp),
								)
								Spacer(Modifier.width(4.dp))
								Text("Reply", style = MaterialTheme.typography.labelMedium)
							}
						}
					}
				}
			}
		}
	}
	Box(Modifier.height(2.dp))

	if (confirmDiscard && target != null) {
		DiscardSnapDialog(
			onKeepEditing = { confirmDiscard = false },
			onDiscard = {
				confirmDiscard = false
				// Scoped to this account and this parent comment, and refused
				// outright while the reply's outcome is unknown — see
				// [SnapThreadController.discard].
				threads.discard(target)
			},
		)
	}

	if (confirmDiscardCorrupt && target != null) {
		DiscardCorruptDraftDialog(
			onKeep = { confirmDiscardCorrupt = false },
			onDiscard = {
				confirmDiscardCorrupt = false
				threads.discard(target)
			},
		)
	}
}

/**
 * The Like control: one heart, filled the instant it is tapped.
 *
 * There is no busy state and no "Liking…", on purpose. The pipeline behind a
 * Like is two authoritative reads, a signature and a broadcast confirmation,
 * and narrating that to the user made a tap feel like a request for permission.
 * The heart fills inside the tap; the work carries on behind it; and if it
 * turns out the Like could not be given, the heart goes back to an outline and
 * says why. Nothing about the safety of the pipeline changed — only the moment
 * the user is told it has begun.
 *
 * Tappable only while there is a Like to give. A filled heart is **not** a
 * button — v1 has no Unlike, so a control that responded to a tap would be
 * promising something it cannot do — and neither is an inert one, which is what
 * a downvote cast elsewhere or a vote state RustedWax could not establish looks
 * like. Drawing it greyed rather than hiding it matters: an absent heart reads
 * as "this comment cannot be Liked", which is a different and wrong claim.
 *
 * An attempt whose outcome is unknown keeps its **filled** heart and gains a
 * **Check Like** control beside it. That control reads the chain and never
 * sends, which is the only safe move on a vote that may already exist.
 */
@Composable
private fun LikeHeart(
	heart: SnapHeart,
	count: Int,
	pending: Boolean,
	onLike: () -> Unit,
	onRecheck: () -> Unit,
) {
	val filled = heart == SnapHeart.FILLED
	TextButton(
		onClick = onLike,
		// Only an outline heart is a live control. Filled has nothing left to
		// do, and inert never had anything to do.
		enabled = heart == SnapHeart.OUTLINE,
	) {
		Icon(
			if (filled) WaxIcons.HeartFilled else WaxIcons.Heart,
			contentDescription = if (filled) "Liked" else "Like",
			tint = if (filled) {
				MaterialTheme.colorScheme.primary
			} else {
				MaterialTheme.colorScheme.onSurfaceVariant
			},
			modifier = Modifier.size(13.dp),
		)
		Spacer(Modifier.width(4.dp))
		Text("Like", style = MaterialTheme.typography.labelMedium)
		// Two facts, side by side and never conflated: the heart is whether
		// *you* liked this, the number is how many people did. Nothing is
		// drawn at zero — "0" beside every new comment is noise, and an
		// absence already reads as none.
		if (count > 0) {
			Spacer(Modifier.width(4.dp))
			Text("$count", style = MaterialTheme.typography.labelMedium)
		}
	}
	if (pending) {
		// Named rather than a bare "Check again": the reply beside it has a
		// re-check of its own, and on a comment where both a reply and a Like
		// ended up ambiguous, two identically-labelled buttons would be a
		// choice nobody can make.
		TextButton(onClick = onRecheck) {
			Text("Check Like", style = MaterialTheme.typography.labelMedium)
		}
	}
}

/**
 * How many people liked something this user cannot Like: their own comment.
 *
 * Drawn as an **outline** heart and a number, and it is not a button. A filled
 * heart here would claim this account voted for itself, which Hive refuses and
 * which nothing in RustedWax can ever have done; a tappable one would offer an
 * action that cannot exist. So it is a label, and it is absent entirely until
 * somebody has actually liked the comment.
 */
@Composable
private fun SocialLikeCount(count: Int) {
	if (count <= 0) return
	Row(verticalAlignment = Alignment.CenterVertically) {
		Icon(
			WaxIcons.Heart,
			contentDescription = "Likes",
			tint = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier.size(13.dp),
		)
		Spacer(Modifier.width(4.dp))
		Text(
			"$count",
			style = MaterialTheme.typography.labelMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}
}

/**
 * "Discard this draft?" for a draft RustedWax cannot read.
 *
 * Worded differently from [DiscardSnapDialog] on purpose. That one can promise
 * the draft was never posted; this one cannot — the whole problem is that the
 * record which would have said so is unreadable. So it says what is actually
 * true, and leaves the choice with the person who knows what they typed.
 */
@Composable
private fun DiscardCorruptDraftDialog(onKeep: () -> Unit, onDiscard: () -> Unit) {
	AlertDialog(
		onDismissRequest = onKeep,
		title = { Text("Discard this draft?") },
		text = {
			Text(
				"RustedWax can't read this draft's saved state, so it can't tell whether " +
					"the reply was already posted to Hive. Discarding it here only removes " +
					"the draft from this device — it can't take back a reply that did post. " +
					"Check the thread first if you're not sure.",
			)
		},
		confirmButton = {
			TextButton(onClick = onDiscard) {
				Text("Discard", color = MaterialTheme.colorScheme.error)
			}
		},
		dismissButton = { TextButton(onClick = onKeep) { Text("Keep it") } },
	)
}

@Composable
private fun ThreadNotice(message: String) {
	Text(
		message,
		style = MaterialTheme.typography.bodySmall,
		color = MaterialTheme.colorScheme.error,
		modifier = Modifier.padding(top = 4.dp),
	)
}
