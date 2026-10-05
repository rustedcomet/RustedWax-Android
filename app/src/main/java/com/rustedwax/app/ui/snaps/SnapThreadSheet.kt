package com.rustedwax.app.ui.snaps

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.TransferableContent
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.rustedwax.app.snaps.SnapAttachmentBlock
import com.rustedwax.app.snaps.SnapClipIntake
import com.rustedwax.app.snaps.SnapImageFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.IconButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.height
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
import com.rustedwax.app.snaps.SnapEditKind
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
					// wrote can be anything but text on this card. An image/GIF
					// link is not printed as its URL — see [SnapMediaText.previewLine].
					SnapMediaText.previewLine(item.text).let { if (item.truncated) "$it…" else it },
					style = MaterialTheme.typography.bodySmall,
					maxLines = 2,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.weight(1f),
				)
			}
		}
		// No "View replies" here any more. The count moved to the card's own
		// Comments control, which is also what opens the conversation — two
		// controls a tap apart, both saying how many replies there are and both
		// going to the same place, was one more than the card needed.
	}
}

/**
 * Which media a conversation belongs to, in the three fields the header draws.
 *
 * Deliberately not [com.rustedwax.app.snaps.SnapMedia] and deliberately not the
 * History record itself. `SnapMedia` carries the verified video id and nothing
 * else — it is what a Snap is *built from* — and the record carries status,
 * percentage, queue state and a transaction id, none of which belong on a
 * social surface. This is the header's own small contract: what to show, and
 * nothing that would let the sheet reach into scrobbling.
 *
 * Null everywhere it is not known. A thread reached from the bell or from an
 * Android notification has no History row behind it, and the header says less
 * rather than guessing — see [ThreadHeader].
 */
internal data class SnapThreadMedia(
	/** Null when identity never resolved. No thumbnail is drawn for it. */
	val videoId: String?,
	val title: String,
	val artist: String?,
)

/**
 * Writing the **first** Snap for a History row, from inside the sheet.
 *
 * The publication itself stays exactly where it was — this is a handle onto
 * the call the History card has always made, so the draft is still the card's
 * draft, still keyed by account and event, and still destroyed only once Hive
 * confirms. Nothing about posting moved; only where the box is.
 */
internal data class SnapRootComposing(
	val draft: String,
	/** False while the text is invalid, or an attempt already owns this row. */
	val canPost: Boolean,
	val onDraftChange: (String) -> Unit,
	/** Called with every image's hosted address once all are uploaded (Issue 40D). */
	val onPost: (List<String>) -> Unit,
	/** The draft's words read live, for the field. */
	val liveDraft: () -> String = { draft },
	/** This row's images. Null: text only. */
	val attachments: ComposerAttachments? = null,
)

/**
 * How tall the conversation sheet's body is, as a share of the height the
 * sheet hands it (Stage 47D).
 *
 * The share is not the share of the screen. Material3 1.3.1 lifts the whole
 * sheet above the keyboard, adds a 48dp drag handle and pads the bottom by
 * the navigation bar, all outside this body. The old 0.88 therefore made a
 * sheet of about 89% of the window, and because the sheet skips the partial
 * state it opened with its top just under the status bar.
 *
 * [RESTING] makes the whole sheet about three quarters of the window: on the
 * A36 that is 48dp + 48dp + 0.72 × the remaining 795dp ≈ 668dp of 891dp,
 * whatever has been said. While the keyboard is up the window above it is
 * small, so [TYPING] keeps the height every keyboard test so far has passed
 * on, rather than shrinking the conversation to a sliver.
 *
 * The partial state stays skipped. Material's partial anchor is half the
 * window with the body hanging off the bottom, which would put the composer
 * off screen.
 */
internal object SnapThreadSheetHeight {
	const val RESTING = 0.72f
	const val TYPING = 0.88f

	fun fraction(imeVisible: Boolean): Float = if (imeVisible) TYPING else RESTING
}

/**
 * The whole conversation under one Snap, with room to read it.
 *
 * This is the screen the 280-character limit does **not** apply to. RustedWax's
 * limit binds what RustedWax creates; a reply written in any other Hive client
 * may be as long as Hive allows, and here it is shown complete — no `maxLines`,
 * no ellipsis, no "read more". The History card is where a long reply is
 * summarised, and this is where the summary is redeemed.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun SnapThreadSheet(
	/** Null on a row that has not been Snapped yet — see [SnapRootComposing]. */
	root: SnapReplyTarget?,
	rootSnap: PostedSnap?,
	threads: SnapThreadController,
	likes: SnapLikeController,
	nowEpochSec: Long,
	/** The media this conversation belongs to, when anything knows it. */
	media: SnapThreadMedia?,
	/** The viewer's own handle, for the face beside the composer. */
	viewer: String?,
	/** Present only while [root] is null: the first Snap is written here. */
	rootComposer: SnapRootComposing?,
	onDismiss: () -> Unit,
	/**
	 * Whether YouTube previews may fetch their frame from i.ytimg.com — the
	 * same consent History's banners ride. Off still draws the play card.
	 */
	thumbnails: Boolean = false,
	/** Phone images for replies (Issue 40D). Null: replies are text only. */
	attachments: SnapAttachmentController? = null,
) {
	val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
	// Which comment the composer at the bottom is aimed at. Null is the ordinary
	// case and means the root: typing into the bar without choosing anything
	// adds a comment to the conversation, exactly as it reads. Reset when the
	// sheet is pointed at a different conversation so a target cannot survive
	// into a thread it does not belong to.
	var replyTarget by remember(root?.contentId) { mutableStateOf<SnapReplyTarget?>(null) }
	/** The reply slot a Send was just fired for, while its outcome is unknown. */
	var sentKey by remember(root?.contentId) { mutableStateOf<String?>(null) }
	val typing = WindowInsets.isImeVisible
	ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
		Column(
			Modifier
				.fillMaxWidth()
				// A definite height rather than the old 560dp ceiling and rather
				// than wrapping the conversation.
				//
				// Both of those were wrong in the same way: they let the sheet's
				// size be decided by how much had been said. A two-comment thread
				// then drew a stub with the composer stranded in mid-screen, and
				// a busy one was cut off at a number somebody wrote down once.
				// Fixing the height instead gives the header and the composer
				// somewhere to stay while the middle scrolls, and leaves History
				// visible above the sheet — which is what tells the reader which
				// row they are looking at.
				//
				// How much is left visible is [SnapThreadSheetHeight]'s to decide,
				// and the keyboard changes it.
				.fillMaxHeight(SnapThreadSheetHeight.fraction(typing))
				.padding(horizontal = 16.dp)
				// The keyboard lifts the composer instead of covering it.
				.imePadding(),
		) {
			ThreadHeader(media = media)
			Spacer(Modifier.height(8.dp))

			if (root == null) {
				// Nothing has been said about this track yet. The sheet is the
				// whole of it: a line explaining what this box is for, and the
				// box.
				Text(
					"No Snap yet. Write the first one.",
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				// Holds the composer on the bottom edge rather than letting it
				// ride up under the one line above it.
				Spacer(Modifier.weight(1f))
				rootComposer?.let { RootComposerBar(it, viewer) }
				Spacer(Modifier.height(12.dp))
				return@ModalBottomSheet
			}

			// The root Snap and every reply live in **one** scrolling list.
			//
			// The root used to sit above the list, pinned. A long root — and 280
			// characters is long on a phone — then took all the height the
			// keyboard left over: the replies collapsed to nothing, nothing could
			// be scrolled, and the composer's own counter was pushed under the
			// keyboard. As the first item of the same list it scrolls away like
			// any other comment, and the list (weighted) is what gives way.
			val load = threads.state(root)
			if (load is SnapThreadLoad.Ready) {
				// Hive is authoritative. A conversation that has just been
				// re-read describes these comments more recently than any local
				// answer from a previous tap, so the freshly-read vote state
				// retires it — except for an attempt still in flight, and an
				// ambiguous one the read did not answer. See
				// [SnapLikeController.reconcile].
				LaunchedEffect(load.thread) { likes.reconcile(load.thread) }
			}
			LazyColumn(
				verticalArrangement = Arrangement.spacedBy(4.dp),
				// Takes the slack between the header and the composer, so the
				// composer stays on the bottom edge — above the keyboard when it
				// is open — and only the conversation moves.
				modifier = Modifier.weight(1f).fillMaxWidth(),
			) {
				// The root Snap, and the one place to reply to it directly.
				// Keys cannot collide with a reply's: those are `author/permlink`.
				rootSnap?.let {
					item(key = "root") {
						Column {
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
								// The root of a History thread is always this user's
								// own Snap — `PostedSnaps` refuses to draw a confirmed
								// row whose author is anybody else — so it never gets
								// a heart, and `SnapLikeController.showsHeart` is what
								// enforces that rather than this argument.
								viewerVote = ViewerVote.Unreadable("the root Snap is your own"),
								thumbnails = thumbnails,
								// The root's own Like count *is* carried; the heart
								// beside it stays non-interactive — see
								// [SocialLikeCount].
								likeCount = threads.thread(root)?.rootLikeCount ?: 0,
								onReplyTo = { replyTarget = it },
								onEdit = if (threads.canEdit(it.author)) {
									{ threads.startEdit(root, root, SnapEditKind.ROOT, it.userText) }
								} else {
									null
								},
								onDelete = if (threads.canDelete(it.author)) {
									{ threads.requestDelete(root, root, SnapEditKind.ROOT, it.userText) }
								} else {
									null
								},
								attachments = attachments,
							)
							HorizontalDivider(Modifier.padding(vertical = 8.dp))
						}
					}
				}

				when (load) {
					null, SnapThreadLoad.Loading -> item(key = "status") {
						Text(
							"Loading replies…",
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
					}

					is SnapThreadLoad.Unavailable -> item(key = "status") {
						Column {
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
					}

					// The complete conversation, every valid reply of it. The list
					// is whole and `LazyColumn` is what makes reading it
					// incremental: it composes the rows on screen and no more, so a
					// thread of five thousand costs what a thread of five does
					// until the reader scrolls.
					is SnapThreadLoad.Ready -> {
						val nodes = load.thread.rows
						if (nodes.isEmpty()) {
							item(key = "status") {
								Text(
									"No replies yet.",
									style = MaterialTheme.typography.bodySmall,
									color = MaterialTheme.colorScheme.onSurfaceVariant,
								)
							}
						} else {
							// Keyed by the comment's own chain identity, which the
							// builder has already de-duplicated, so a refresh that
							// adds a reply cannot hand one comment's composition to
							// another comment.
							items(nodes, key = { it.reply.contentId }) { node ->
								ReplyBlock(node, root, threads, likes, nowEpochSec, thumbnails, attachments) {
									replyTarget = it
								}
							}
						}
					}
				}
			}
			Spacer(Modifier.height(8.dp))
			// The one composer, at the bottom where the thumb already is, and
			// present whether or not anything has been replied to yet.
			// The band is retired at the **durable** boundary, not on the tap and
			// not on Hive's acknowledgement.
			//
			// Not on the tap, because a draft is keyed by the comment it answers:
			// re-aiming the box at the root before the attempt is recorded would
			// leave the user's words filed under a comment the box is no longer
			// pointing at, and a staging failure would look like losing them.
			// Not on acknowledgement, because that is the wait this whole change
			// removes. `Optimistic` is set one local write after the tap and
			// before any network, which is both immediate and safe.
			//
			// Watched by the slot that was actually sent rather than by whatever
			// is aimed now, so a comment that merely *has* an old Posted status —
			// one replied to earlier in this sitting — can still be aimed at.
			val sentStatus = sentKey?.let { threads.status(it) }
			LaunchedEffect(sentKey, sentStatus) {
				if (sentKey == null) return@LaunchedEffect
				when (sentStatus) {
					// Recorded. The reply is already in the conversation above.
					is SnapPostStatus.Optimistic, is SnapPostStatus.Posted -> {
						replyTarget = null
						sentKey = null
					}
					// Nothing was recorded, or its outcome is unknown. The aim
					// stays exactly where it was so the words come back under the
					// comment they were written for.
					is SnapPostStatus.Failed,
					is SnapPostStatus.Uncertain,
					is SnapPostStatus.Interrupted,
					-> sentKey = null
					else -> Unit
				}
			}
			threads.deleting?.takeIf { it.root == root }?.let { DeleteDialog(it, threads) }
			// Editing borrows the bar: one box at the bottom of the sheet, aimed
			// either at a new comment or at an existing one of the viewer's own.
			val editing = threads.editing?.takeIf { it.root == root }
			if (editing != null) {
				EditComposerBar(editing = editing, threads = threads, viewer = viewer, attachments = attachments)
			} else {
				ThreadComposerBar(
					root = root,
					target = replyTarget ?: root,
					aimed = replyTarget,
					threads = threads,
					viewer = viewer,
					onSent = { sentKey = it },
					onClearTarget = { replyTarget = null },
					attachments = attachments,
				)
			}
			Spacer(Modifier.height(12.dp))
		}
	}
}

/**
 * The composer, anchored under the conversation rather than inside it.
 *
 * One box for the whole sheet. Aimed at the root by default — typing without
 * choosing anything adds a comment to the conversation — and re-aimed at a
 * particular comment by that comment's Reply, which is the only thing Reply
 * does now. The draft still belongs to the *target*, keyed and stored exactly
 * as before, so switching aim swaps which draft is on screen and never merges
 * two of them.
 *
 * A draft RustedWax cannot decode gets no composer and no Send, for the reason
 * it always did: it may already be on Hive under an intent that was lost with
 * it, so the only ways out are leaving it alone and throwing it away on
 * purpose.
 */
@Composable
private fun ThreadComposerBar(
	root: SnapReplyTarget,
	target: SnapReplyTarget,
	/** Non-null only while aimed at a particular comment. */
	aimed: SnapReplyTarget?,
	threads: SnapThreadController,
	viewer: String?,
	/** Fired with the slot just sent, after [SnapThreadController.send] has it. */
	onSent: (String) -> Unit,
	onClearTarget: () -> Unit,
	attachments: SnapAttachmentController?,
) {
	val key = threads.replyKey(target)
	// What the box shows, which empties on the tap. The draft itself is
	// untouched — the discard dialog below still acts on the real words.
	val draft = threads.composerText(key)
	// Bound to the draft it is asking about: this is the one control that
	// destroys typed text, so re-aiming the composer must not carry a live
	// dialog onto a different comment's draft.
	var confirmDiscard by remember(key) { mutableStateOf(false) }
	var confirmDiscardCorrupt by remember(key) { mutableStateOf(false) }
	val corrupt = threads.corruptReason(key)
	val focus = remember { FocusRequester() }
	val focusManager = LocalFocusManager.current
	val keyboard = LocalSoftwareKeyboardController.current

	Column(Modifier.fillMaxWidth()) {
		if (corrupt != null) {
			ThreadNotice(corrupt)
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				TextButton(onClick = { confirmDiscardCorrupt = true }) {
					Text(
						"Discard draft",
						style = MaterialTheme.typography.labelMedium,
						color = MaterialTheme.colorScheme.error,
					)
				}
				if (aimed != null) {
					TextButton(onClick = onClearTarget) {
						Text("Leave it", style = MaterialTheme.typography.labelMedium)
					}
				}
			}
			return@Column
		}

		// Says where the words are going, and offers the way back. Its own thin
		// band directly above the box, so the answer sits where the typing is
		// rather than beside the comment somewhere up the list.
		aimed?.let { at ->
			// Aiming at a comment focuses the box: the keyboard is what the tap
			// on Reply was asking for.
			LaunchedEffect(at.contentId) { runCatching { focus.requestFocus() } }
			Row(
				Modifier
					.fillMaxWidth()
					.background(MaterialTheme.colorScheme.surfaceContainerHighest)
					.padding(horizontal = 12.dp, vertical = 6.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				Text(
					"Replying to @${at.author}",
					style = MaterialTheme.typography.labelMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.weight(1f),
				)
				Icon(
					WaxIcons.Close,
					contentDescription = "Stop replying to @${at.author}",
					tint = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier
						.size(18.dp)
						.clickable(onClickLabel = "Cancel reply", onClick = onClearTarget),
				)
			}
		}

		val busy = threads.isBusy(key)
		val uploading = attachments?.isUploading(key) == true
		val images = attachments?.count(key) ?: 0
		QuickEmojiRow { if (!uploading) threads.edit(key, draft + it) }

		ComposerPill(
			viewer = viewer ?: root.author,
			draft = draft,
			hint = if (aimed != null) "Reply to @${aimed.author}…" else "Join the conversation…",
			canSend = SnapAttachmentBlock.canSend(draft, images) && !busy && !uploading &&
				(attachments?.arriving(key) ?: 0) == 0,
			focus = focus,
			onDraftChange = { threads.edit(key, it) },
			liveDraft = { threads.composerText(key) },
			// The images ride with the words: hidden while an attempt holds them,
			// back if it does not publish, gone once it does.
			attachments = attachments?.let { ComposerAttachments(key, it, visible = !busy) },
			readOnly = uploading,
			// No "Sending…". The box clears on the durable write and the reply
			// appears above it.
			onSend = {
				// `target` is this composable's parameter, captured when the bar
				// was composed and closed over by the coroutine `send` starts —
				// so reporting the slot afterwards cannot reparent anything.
				//
				// Images first (Issue 40D): every one is uploaded, and only then
				// is the reply staged with their addresses. With none, at once.
				val publish = { urls: List<String> ->
					threads.send(root, target, urls)
					onSent(key)
				}
				attachments?.upload(key, publish) ?: publish(emptyList())
				// Ending the IME session is part of sending, not decoration.
				//
				// The box is emptied by `composerText`, but a live IME session
				// still owns a composing region over the words it just had, and
				// the next update it posts calls `onValueChange` with them —
				// which runs `edit`, un-submits the slot and writes the text
				// straight back. That is why the field appeared to hang on to a
				// sent reply for a few seconds: the clear was landing and then
				// being undone, and what finally emptied it was the draft being
				// discarded on confirmation, a whole Hive block later.
				//
				// Dropping focus retires the session, so there is no stale
				// update to arrive. It also puts the conversation back on screen,
				// which is where the reply the user just wrote now is.
				focusManager.clearFocus()
				keyboard?.hide()
			},
			onDiscard = if (draft.isNotEmpty() && !busy && !uploading) {
				{ confirmDiscard = true }
			} else {
				null
			},
		)
	}

	if (confirmDiscard) {
		DiscardSnapDialog(
			onKeepEditing = { confirmDiscard = false },
			onDiscard = {
				confirmDiscard = false
				// Scoped to this account and this parent comment, and refused
				// outright while the reply's outcome is unknown — see
				// [SnapThreadController.discard].
				threads.discard(target)
				attachments?.clear(key)
			},
		)
	}

	if (confirmDiscardCorrupt) {
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
 * The one input row: a face, a pill, and the way to send it.
 *
 * Replaces a bordered multi-line box that carried its own close control, its
 * own counter row and a separate Post button underneath — four rows of chrome
 * for one sentence of typing. At rest this is a single row about as tall as any
 * other input, and it grows a few lines at most as the text does.
 *
 * The 280-character rule is unchanged and simply stops being *narrated*: the
 * count appears only once the limit is actually in sight, and Send is inert
 * until [SnapText.isValid] agrees — the same gate that was there before.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ComposerPill(
	viewer: String,
	draft: String,
	hint: String,
	canSend: Boolean,
	focus: FocusRequester,
	onDraftChange: (String) -> Unit,
	onSend: () -> Unit,
	/** Absent for an empty draft: asking about nothing trains people to tap No. */
	onDiscard: (() -> Unit)?,
	sendLabel: String = "Send",
	/**
	 * The draft's words read live from where they are kept, not from this
	 * composition. The field writes every keystroke through synchronously, so
	 * the only differences this ever sees are changes made elsewhere — the box
	 * emptying on Send, an emoji tapped in, the composer re-aimed.
	 */
	liveDraft: () -> String = { draft },
	/** The images of this draft (Issue 40D). Null: no gallery and no image paste. */
	attachments: ComposerAttachments? = null,
	/** True while the images are uploading: nothing may change under the upload. */
	readOnly: Boolean = false,
	/** Edit only: the already-hosted images being kept, removable, never added to. */
	editImages: EditImages? = null,
) {
	val overflowing = SnapText.isOverflowing(draft)
	val state = remember { TextFieldState(draft) }
	val currentLive by rememberUpdatedState(liveDraft)
	val currentChange by rememberUpdatedState(onDraftChange)
	LaunchedEffect(state) {
		snapshotFlow { currentLive() }.collect { live ->
			if (state.text.toString() != live) state.setTextAndPlaceCursorAtEnd(live)
		}
	}
	Column(Modifier.fillMaxWidth()) {
		attachments?.let { AttachmentStatus(it) }
		Row(
			Modifier.fillMaxWidth().padding(top = 6.dp),
			verticalAlignment = Alignment.Bottom,
		) {
			HiveAvatar(account = viewer, size = 30.dp)
			Spacer(Modifier.width(8.dp))
			Column(Modifier.weight(1f)) {
				// One field, as in Instagram's comment box: the words on top and
				// the attached images inside the same rounded box, under them.
				Column(
					Modifier
						.fillMaxWidth()
						.clip(RoundedCornerShape(22.dp))
						.background(MaterialTheme.colorScheme.surfaceContainerHighest),
				) {
				Row(
					Modifier
						.fillMaxWidth()
						.padding(start = 14.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
					verticalAlignment = Alignment.CenterVertically,
				) {
					BasicTextField(
						state = state,
						readOnly = readOnly,
						// Every user edit is handed to the owner as it happens. If the
						// owner does not take it — a locked slot, an edit mid-save, the
						// keyboard echoing words that were just sent — the field goes
						// back to what the owner holds, as the old controlled field did.
						inputTransformation = InputTransformation {
							val proposed = asCharSequence().toString()
							currentChange(proposed)
							if (currentLive() != proposed) revertAllChanges()
						},
						textStyle = MaterialTheme.typography.bodyMedium.copy(
							color = MaterialTheme.colorScheme.onSurface,
						),
						cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
						// Grows a little, never into a page. Past this the field
						// scrolls rather than pushing the conversation off screen.
						lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 5),
						modifier = Modifier
							.weight(1f)
							.focusRequester(focus)
							.then(attachments?.let { Modifier.imageReceiver(it) } ?: Modifier)
							.padding(vertical = 10.dp),
						decorator = { field ->
							if (state.text.isEmpty()) {
								Text(
									hint,
									style = MaterialTheme.typography.bodyMedium,
									color = MaterialTheme.colorScheme.onSurfaceVariant,
									maxLines = 1,
									overflow = TextOverflow.Ellipsis,
								)
							}
							field()
						},
					)
					// Images, directly: one tap into the photo picker, no menu.
					attachments?.let { GalleryButton(it, enabled = !readOnly) }
					// Inside the pill, on the right, where the thumb already is.
					IconButton(onClick = onSend, enabled = canSend, modifier = Modifier.size(36.dp)) {
						Icon(
							WaxIcons.Send,
							contentDescription = sendLabel,
							tint = if (canSend) {
								MaterialTheme.colorScheme.primary
							} else {
								MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
							},
							modifier = Modifier.size(18.dp),
						)
					}
				}
				if (editImages == null) {
					attachments?.let { AttachmentThumbs(it) }
				} else {
					EditThumbs(editImages, attachments)
				}
				}
				// Only ever shown when it is about to matter, and it is the one
				// thing that must still be said out loud when it does.
				if (overflowing || SnapText.count(draft) > SnapText.LIMIT - 20) {
					Row(
						Modifier.fillMaxWidth().padding(top = 2.dp, end = 4.dp),
						horizontalArrangement = Arrangement.End,
						verticalAlignment = Alignment.CenterVertically,
					) {
						onDiscard?.let {
							TextButton(onClick = it) {
								Text(
									"Discard",
									style = MaterialTheme.typography.labelSmall,
									color = MaterialTheme.colorScheme.error,
								)
							}
							Spacer(Modifier.width(4.dp))
						}
						Text(
							SnapText.counterLabel(draft),
							style = MaterialTheme.typography.labelSmall,
							color = if (overflowing) {
								MaterialTheme.colorScheme.error
							} else {
								MaterialTheme.colorScheme.onSurfaceVariant
							},
						)
					}
				}
			}
		}
	}
}

/** One draft's images, as the composer needs them. */
internal class ComposerAttachments(
	/** The draft key: the same account + slot the words are stored under. */
	val key: String,
	val controller: SnapAttachmentController,
	/** Hidden while an attempt holds them: they reappear only if it does not publish. */
	val visible: Boolean = true,
	/** Images already attached elsewhere that count towards the same four (an Edit's kept ones). */
	val reserved: Int = 0,
)

/**
 * Clipboard and keyboard images, into the same intake the picker uses.
 *
 * Anything that is only text — including a copied image *URL* — is left to
 * the field and pastes as words. An item is taken as an image only when it
 * carries a content URI and the clip says it is an image, or carries nothing
 * else to paste; whatever it really is, the intake decides from its bytes.
 */
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.imageReceiver(attachments: ComposerAttachments): Modifier =
	contentReceiver { content ->
		val origin = if (content.source == TransferableContent.Source.Keyboard) {
			SnapImageOrigin.KEYBOARD
		} else {
			SnapImageOrigin.CLIPBOARD
		}
		val imageClip = content.hasMediaType(MediaType.Image)
		val taken = mutableListOf<SnapImageSource>()
		val rest = content.consume { item ->
			val uri = item.uri?.toString()
			if (uri == null || !SnapClipIntake.takesAsImage(uri, item.text, imageClip)) return@consume false
			taken += SnapImageSource(uri, origin)
			true
		}
		if (taken.isNotEmpty()) attachments.controller.add(attachments.key, taken, attachments.reserved)
		rest
	}

/** The gallery icon on the right of the pill. Goes straight to the photo picker. */
@Composable
private fun GalleryButton(attachments: ComposerAttachments, enabled: Boolean) {
	val c = attachments.controller
	val draftKey = attachments.key
	val room = c.room(draftKey, attachments.reserved)
	val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
	fun sources(uris: List<Uri>) = uris.map { SnapImageSource(it.toString(), SnapImageOrigin.PICKER) }
	val pickOne = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
		uri?.let { c.add(draftKey, sources(listOf(it)), attachments.reserved) }
	}
	// Asks the picker for no more than there is room for; a picker that ignores
	// the cap is trimmed by the controller, with a message, not by losing anything.
	val pickMany = rememberLauncherForActivityResult(
		ActivityResultContracts.PickMultipleVisualMedia(maxOf(2, room)),
	) { uris -> c.add(draftKey, sources(uris), attachments.reserved) }
	val usable = enabled && room > 0 && !c.isUploading(draftKey)
	IconButton(
		onClick = { if (room == 1) pickOne.launch(request) else pickMany.launch(request) },
		enabled = usable,
		modifier = Modifier.size(36.dp),
	) {
		Icon(
			WaxIcons.Gallery,
			contentDescription = if (room > 0) "Add images" else "Image limit reached",
			tint = if (usable) {
				MaterialTheme.colorScheme.onSurfaceVariant
			} else {
				MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
			},
			modifier = Modifier.size(20.dp),
		)
	}
}

/** Selected images side by side inside the field, under the words, each with its own ×. */
@Composable
private fun AttachmentThumbs(attachments: ComposerAttachments) {
	val c = attachments.controller
	val draftKey = attachments.key
	val items = if (attachments.visible) c.items(draftKey) else emptyList()
	val uploading = c.progress(draftKey)
	val arriving = if (attachments.visible) c.arriving(draftKey) else 0
	if (items.isNotEmpty() || arriving > 0) {
		Row(
			Modifier
				.fillMaxWidth()
				.horizontalScroll(rememberScrollState())
				.padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
			horizontalArrangement = Arrangement.spacedBy(8.dp),
		) {
			items.forEach { item ->
				key(item.id) {
					AttachmentThumb(
						item = item,
						removable = uploading == null,
						onRemove = { c.remove(draftKey, item.id) },
					)
				}
			}
			repeat(arriving) {
				Box(
					Modifier
						.size(64.dp)
						.clip(RoundedCornerShape(10.dp))
						.background(MaterialTheme.colorScheme.surfaceContainer),
				)
			}
		}
	}
}

/** Upload progress and intake notices for one draft, just above the field. */
@Composable
private fun AttachmentStatus(attachments: ComposerAttachments) {
	val c = attachments.controller
	val draftKey = attachments.key
	c.progress(draftKey)?.let { p ->
		Column(Modifier.fillMaxWidth().padding(start = 38.dp, top = 6.dp)) {
			Text(
				if (p.total == 1) "Uploading image…" else "Uploading image ${p.index} of ${p.total}…",
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			LinearProgressIndicator(
				progress = { ((p.index - 1) + p.fraction) / p.total },
				modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
			)
		}
	}
	c.notice(draftKey)?.let { message ->
		Text(
			message,
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.error,
			modifier = Modifier
				.fillMaxWidth()
				.padding(start = 38.dp, top = 4.dp)
				.clickable(onClickLabel = "Dismiss") { c.dismissNotice(draftKey) },
		)
	}
}

@Composable
private fun AttachmentThumb(item: SnapAttachment, removable: Boolean, onRemove: () -> Unit) {
	var bitmap by remember(item.file) { mutableStateOf<Bitmap?>(null) }
	LaunchedEffect(item.file) {
		bitmap = withContext(Dispatchers.IO) { SnapAttachmentThumbs.decode(item.file, 192) }
	}
	ThumbFrame(
		image = bitmap?.asImageBitmap(),
		description = if (item.format == SnapImageFormat.GIF) "Attached GIF" else "Attached image",
		removable = removable,
		onRemove = onRemove,
	)
}

/**
 * An Edit's images in one row, in the order they will be published: the
 * already-hosted ones it keeps, then any it adds. Each has its own ×.
 */
@Composable
private fun EditThumbs(kept: EditImages, added: ComposerAttachments?) {
	val c = added?.controller
	val items = added?.let { c!!.items(it.key) }.orEmpty()
	val arriving = added?.let { c!!.arriving(it.key) } ?: 0
	if (kept.urls.isEmpty() && items.isEmpty() && arriving == 0) return
	Row(
		Modifier
			.fillMaxWidth()
			.horizontalScroll(rememberScrollState())
			.padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
		horizontalArrangement = Arrangement.spacedBy(8.dp),
	) {
		// Keyed by position as well as address: the same image may be attached
		// twice, and × removes exactly the one tapped.
		kept.urls.forEachIndexed { i, url ->
			key("kept", i, url) {
				HostedThumb(url, removable = kept.removable, onRemove = { kept.onRemove(i) })
			}
		}
		items.forEach { item ->
			key("new", item.id) {
				AttachmentThumb(item, removable = true, onRemove = { c!!.remove(added!!.key, item.id) })
			}
		}
		repeat(arriving) {
			Box(
				Modifier
					.size(64.dp)
					.clip(RoundedCornerShape(10.dp))
					.background(MaterialTheme.colorScheme.surfaceContainer),
			)
		}
	}
}

/** The images an Edit keeps (Issue 40D), each removable with the composer's ×. */
internal class EditImages(
	val urls: List<String>,
	val removable: Boolean,
	val onRemove: (Int) -> Unit,
)

/** An already-hosted attachment, drawn as the same compact still the composer uses. */
@Composable
private fun HostedThumb(url: String, removable: Boolean, onRemove: () -> Unit) {
	// A still through the same bounded loader and proxy 40C previews use; a
	// GIF shows its first frame here, as History does.
	val ref = remember(url) { (SnapMediaParser.recognise(url) as? SnapMediaRef.Image)?.copy(animated = false) }
	var image by remember(url) { mutableStateOf(ref?.let { SnapMediaLoader.cached(it) }) }
	LaunchedEffect(url) {
		if (image == null && ref != null) image = SnapMediaLoader.load(ref)
	}
	ThumbFrame(
		image = (image as? SnapMediaImage.Still)?.bitmap,
		description = if (url.endsWith(".gif")) "Attached GIF" else "Attached image",
		removable = removable,
		onRemove = onRemove,
	)
}

@Composable
private fun ThumbFrame(
	image: androidx.compose.ui.graphics.ImageBitmap?,
	description: String,
	removable: Boolean,
	onRemove: () -> Unit,
) {
	Box(Modifier.size(64.dp)) {
		Box(
			Modifier
				.fillMaxSize()
				.clip(RoundedCornerShape(10.dp))
				.background(MaterialTheme.colorScheme.surfaceContainer)
				.semantics { contentDescription = description },
		) {
			image?.let {
				Image(
					it,
					contentDescription = null,
					contentScale = ContentScale.Crop,
					modifier = Modifier.fillMaxSize(),
				)
			}
		}
		if (removable) {
			Box(
				Modifier
					.align(Alignment.TopEnd)
					.padding(3.dp)
					.size(22.dp)
					.clip(RoundedCornerShape(50))
					.background(Color.Black.copy(alpha = 0.6f))
					.clickable(onClickLabel = "Remove image", onClick = onRemove),
				contentAlignment = Alignment.Center,
			) {
				Icon(
					WaxIcons.Close,
					contentDescription = "Remove image",
					tint = Color.White,
					modifier = Modifier.size(14.dp),
				)
			}
		}
	}
}

/**
 * The quick reactions, on one line directly above the box.
 *
 * The same seventeen the inline composer offered behind a toggle — the strip is
 * simply always out now, which is what makes a one-tap reaction one tap.
 */
@Composable
private fun QuickEmojiRow(onPick: (String) -> Unit) {
	Row(
		Modifier
			.fillMaxWidth()
			.horizontalScroll(rememberScrollState())
			.padding(vertical = 2.dp),
		horizontalArrangement = Arrangement.spacedBy(2.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		QUICK_EMOJI.forEach { emoji ->
			Text(
				emoji,
				fontSize = 20.sp,
				modifier = Modifier
					.clip(RoundedCornerShape(50))
					.clickable(onClickLabel = "Add $emoji") { onPick(emoji) }
					.padding(horizontal = 6.dp, vertical = 5.dp),
			)
		}
	}
}

/**
 * The bottom bar while one of the viewer's own comments is being edited.
 *
 * The same pill, filled with the comment's current words and held to the same
 * limit. Save is dead until the text is valid *and* different — unchanged
 * words are not an edit. The comment above keeps its old words until Hive has
 * the new ones; a save that fails leaves this box open on what was typed, with
 * the reason above it, so nothing has to be retyped.
 */
@Composable
private fun EditComposerBar(
	editing: SnapThreadController.Editing,
	threads: SnapThreadController,
	viewer: String?,
	attachments: SnapAttachmentController?,
) {
	val focus = remember { FocusRequester() }
	val focusManager = LocalFocusManager.current
	val keyboard = LocalSoftwareKeyboardController.current
	val text = threads.editText
	// Save never waits on Hive any more (Issue 40D): the bar closes on the tap.
	val saving = false
	val what = if (editing.kind == SnapEditKind.ROOT) "Snap" else "reply"
	val imagesKey = threads.editImagesKey(editing.target)

	Column(Modifier.fillMaxWidth()) {
		LaunchedEffect(editing.target.contentId) { runCatching { focus.requestFocus() } }
		when (val st = threads.editStatus) {
			is SnapPostStatus.Failed -> ThreadNotice(st.message)
			is SnapPostStatus.Uncertain -> ThreadNotice(st.message)
			else -> Unit
		}
		Row(
			Modifier
				.fillMaxWidth()
				.background(MaterialTheme.colorScheme.surfaceContainerHighest)
				.padding(horizontal = 12.dp, vertical = 6.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Text(
				if (saving) "Saving your $what…" else "Editing your $what",
				style = MaterialTheme.typography.labelMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.weight(1f),
			)
			if (!saving) {
				Icon(
					WaxIcons.Close,
					contentDescription = "Stop editing",
					tint = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier
						.size(18.dp)
						.clickable(onClickLabel = "Cancel edit", onClick = threads::cancelEdit),
				)
			}
		}
		QuickEmojiRow { if (!saving) threads.editDraft(text + it) }
		// The words, the kept images and any added ones: × removes one from this
		// edit only, and the gallery adds within the same four. Nothing changes
		// on chain unless Save is proven.
		ComposerPill(
			viewer = viewer ?: editing.target.author,
			draft = text,
			hint = "Edit your $what…",
			canSend = threads.canSaveEdit() && (attachments?.arriving(imagesKey) ?: 0) == 0,
			focus = focus,
			onDraftChange = threads::editDraft,
			liveDraft = { threads.editText },
			attachments = attachments?.let {
				ComposerAttachments(imagesKey, it, reserved = threads.editImages.size)
			},
			editImages = EditImages(
				urls = threads.editImages,
				removable = !saving,
				onRemove = threads::removeEditImage,
			),
			onSend = {
				threads.saveEdit()
				// See [ThreadComposerBar]: end the IME session with the tap.
				focusManager.clearFocus()
				keyboard?.hide()
			},
			onDiscard = null,
			sendLabel = "Save edit",
		)
	}
}

/**
 * The box the first Snap for a row is written in.
 *
 * The same pill the conversation uses, for the same reason the sheet is the
 * same sheet: writing the first Snap and answering one are the same act. It
 * owns no draft, no key and no publication of its own — everything arrives
 * through [SnapRootComposing], built at the History call site out of the state
 * and the `posts.post` call the card has always used.
 */
@Composable
private fun RootComposerBar(composing: SnapRootComposing, viewer: String?) {
	val focus = remember { FocusRequester() }
	val focusManager = LocalFocusManager.current
	val keyboard = LocalSoftwareKeyboardController.current
	Column(Modifier.fillMaxWidth()) {
		val images = composing.attachments
		val uploading = images?.controller?.isUploading(images.key) == true
		QuickEmojiRow { if (!uploading) composing.onDraftChange(composing.draft + it) }
		ComposerPill(
			viewer = viewer.orEmpty(),
			draft = composing.draft,
			hint = "Write a Snap…",
			canSend = composing.canPost,
			focus = focus,
			onDraftChange = composing.onDraftChange,
			liveDraft = composing.liveDraft,
			attachments = images,
			readOnly = uploading,
			onSend = {
				// Images first (Issue 40D); the Snap is posted only once every
				// one has its hosted address.
				images?.controller?.upload(images.key, composing.onPost) ?: composing.onPost(emptyList())
				// See [ThreadComposerBar]: the IME session has to end with the
				// send, or its next update writes the words back.
				focusManager.clearFocus()
				keyboard?.hide()
			},
			onDiscard = null,
		)
	}
}

/**
 * The sheet's chrome: what this is, and which media it belongs to.
 *
 * Centred and deliberately small. An earlier draft drew a thumbnail and three
 * stacked lines here, which turned the top of the sheet into a second copy of
 * the History card — the one thing §7 says not to do ("Do not duplicate large
 * History-card metadata"). The card itself is still on screen above the sheet,
 * so repeating it bought nothing and cost the conversation its space.
 *
 * The media is still named, because §7 asks that the reader always know which
 * item the conversation belongs to and because the routes that matter most for
 * that — the bell and an Android notification — open threads with **no** card
 * behind them at all. One quiet line answers that; a picture of the video does
 * not answer it any better.
 */
@Composable
private fun ThreadHeader(media: SnapThreadMedia?) {
	Column(
		Modifier.fillMaxWidth().padding(bottom = 2.dp),
		horizontalAlignment = Alignment.CenterHorizontally,
	) {
		Text("Comments", style = MaterialTheme.typography.titleMedium)
		media?.let {
			// Artist and title on one line, in the order History already says
			// them, and clipped rather than wrapped: a long title may not push
			// the conversation down the screen.
			val line = it.artist?.takeIf { a -> a.isNotBlank() }
				?.let { a -> "$a — ${it.title}" }
				?: it.title
			Text(
				line,
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
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
	thumbnails: Boolean,
	attachments: SnapAttachmentController?,
	onReplyTo: (SnapReplyTarget) -> Unit,
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
		thumbnails = thumbnails,
		onReplyTo = onReplyTo,
		onEdit = SnapReplyTarget.of(reply)
			?.takeIf { threads.canEdit(reply.author) }
			?.let { target -> { threads.startEdit(root, target, SnapEditKind.REPLY, reply.body) } },
		onDelete = SnapReplyTarget.of(reply)
			?.takeIf { threads.canDelete(reply.author) }
			?.let { target -> { threads.requestDelete(root, target, SnapEditKind.REPLY, reply.body) } },
		attachments = attachments,
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
/**
 * An edit that left the editor and is settling (Issue 40D): the images it is
 * adding, drawn from this device until they are hosted, and what is going on.
 * A failed one says so and offers to try again or let it go — the comment
 * itself already shows what Hive holds.
 */
@Composable
private fun PendingEditRow(
	pending: SnapThreadController.PendingEdit,
	target: SnapReplyTarget,
	threads: SnapThreadController,
	attachments: SnapAttachmentController?,
) {
	val added = attachments?.items(pending.imagesKey).orEmpty()
	when (pending.phase) {
		SnapThreadController.Phase.SAVING -> {
			if (added.isNotEmpty()) {
				Row(
					Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 6.dp),
					horizontalArrangement = Arrangement.spacedBy(8.dp),
				) {
					added.forEach { item ->
						key(item.id) { AttachmentThumb(item, removable = false, onRemove = {}) }
					}
				}
			}
			val progress = attachments?.progress(pending.imagesKey)
			Text(
				when {
					progress == null -> "Saving edit…"
					progress.total == 1 -> "Uploading image…"
					else -> "Uploading image ${progress.index} of ${progress.total}…"
				},
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.padding(top = 4.dp),
			)
		}
		SnapThreadController.Phase.FAILED, SnapThreadController.Phase.UNCERTAIN -> {
			ThreadNotice(pending.message ?: "Your edit wasn't saved.")
			Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
				TextButton(onClick = { threads.retryEdit(target) }) {
					Text("Retry edit", style = MaterialTheme.typography.labelMedium)
				}
				// Only once the attempt is proven not to have landed. While its
				// outcome is unresolved there is nothing safe to discard, and a
				// disabled button in error red reads as available (A36, 40D retest).
				if (pending.phase == SnapThreadController.Phase.FAILED) {
					TextButton(onClick = { threads.discardEdit(target) }) {
						Text(
							"Discard edit",
							style = MaterialTheme.typography.labelMedium,
							color = MaterialTheme.colorScheme.error,
						)
					}
				}
			}
		}
	}
}

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
	/** See [SnapThreadSheet]'s `thumbnails`. */
	thumbnails: Boolean,
	/** Aims the sheet's one composer at this comment. */
	onReplyTo: (SnapReplyTarget) -> Unit,
	/** Present only on the viewer's own comments: puts this one in the bar. */
	onEdit: (() -> Unit)? = null,
	/** Present only on the viewer's own comments: asks Hive, then confirms. */
	onDelete: (() -> Unit)? = null,
	/** Where an edit's new images wait, for drawing them while the edit settles. */
	attachments: SnapAttachmentController? = null,
) {
	val key = target?.let { threads.replyKey(it) }
	// An edit of this comment still settling, or one that failed (Issue 40D).
	val pendingEdit = target?.let { threads.pendingEdit(it) }
	val settling = target?.let(threads::isEditSettling) == true
	val status = key?.let { threads.status(it) } ?: SnapPostStatus.Idle

	Row(Modifier.fillMaxWidth().padding(start = (depth * 14).dp, top = 6.dp)) {
		HiveAvatar(account = author, size = 30.dp)
		Spacer(Modifier.width(10.dp))
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
			// The one place both a root Snap and a reply draw their media, keyed
			// on the comment's own body — so an edit redraws it and a deleted
			// comment takes its previews with it. A link drawn as a preview is
			// not printed above it as well; [body] itself is never changed.
			val shown = remember(body) { SnapMediaText.display(body) }
			shown.text.takeIf { it.isNotEmpty() }?.let {
				// Complete, however long. See [SnapThreadSheet]. Links in it
				// are tappable and open outside the app.
				SnapLinkedText(it, style = MaterialTheme.typography.bodyMedium)
			}
			SnapMediaPreviews(shown.media, thumbnails)
			if (target != null && pendingEdit != null) {
				PendingEditRow(pendingEdit, target, threads, attachments)
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

				// The composer is no longer here. Every comment's Reply aims the
				// one composer at the bottom of the sheet — see
				// [ThreadComposerBar] — so the conversation is never pushed
				// around by a text box opening inside it.
				run {
					// Whatever the last Like attempt had to say. Ambiguity reads
					// differently from a refusal on purpose: one offers a re-read
					// and the other does not.
					likes.notice(target)?.let { ThreadNotice(it) }
					val deleteNotice = threads.deleteNotice(target)
					when (deleteNotice) {
						is SnapPostStatus.Failed -> ThreadNotice(deleteNotice.message)
						is SnapPostStatus.Uncertain -> ThreadNotice(deleteNotice.message)
						else -> Unit
					}
					Row(
						horizontalArrangement = Arrangement.spacedBy(4.dp),
						verticalAlignment = Alignment.CenterVertically,
					) {
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
							// Plain word under the text, as a comment thread
							// writes it — the icon belonged to an action row
							// that no longer exists.
							Text(
								"Reply",
								style = MaterialTheme.typography.labelMedium,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
								modifier = Modifier
									.clip(RoundedCornerShape(6.dp))
									.clickable(onClickLabel = "Reply to @$author") {
										onReplyTo(target)
									}
									.padding(vertical = 4.dp, horizontal = 2.dp),
							)
							// Only the author's own, and never on a comment whose
							// outcome — or whose edit — is still being settled.
							onEdit?.takeIf { !settling }?.let { edit ->
								Spacer(Modifier.width(10.dp))
								Text(
									"Edit",
									style = MaterialTheme.typography.labelMedium,
									color = MaterialTheme.colorScheme.onSurfaceVariant,
									modifier = Modifier
										.clip(RoundedCornerShape(6.dp))
										.clickable(onClickLabel = "Edit", onClick = edit)
										.padding(vertical = 4.dp, horizontal = 2.dp),
								)
							}
							onDelete?.takeIf { !settling }?.let { delete ->
								Spacer(Modifier.width(10.dp))
								// An unproven deletion offers a read, never a
								// second delete — the controller settles it.
								val label = if (deleteNotice is SnapPostStatus.Uncertain) {
									"Check deletion"
								} else {
									"Delete"
								}
								Text(
									label,
									style = MaterialTheme.typography.labelMedium,
									color = MaterialTheme.colorScheme.onSurfaceVariant,
									modifier = Modifier
										.clip(RoundedCornerShape(6.dp))
										.clickable(onClickLabel = label, onClick = delete)
										.padding(vertical = 4.dp, horizontal = 2.dp),
								)
							}
						}
					}
				}
			}
		}
		// The Like rail, on the right edge and aligned with the comment it
		// belongs to: heart above, count under it. Absent entirely on this
		// user's own comments, where `SocialLikeCount` shows the number alone —
		// a filled heart there would claim they voted for themselves.
		Column(
			horizontalAlignment = Alignment.CenterHorizontally,
			modifier = Modifier.padding(start = 6.dp, top = 2.dp),
		) {
			if (likes.showsHeart(author)) {
				// Null only for a comment whose own identity did not survive
				// validation: no target, no Like control, and nothing drawn.
				target?.let {
					LikeHeart(
						heart = likes.heart(target, viewerVote),
						count = likes.likeCount(target, viewerVote, likeCount),
						pending = likes.isPending(target),
						onLike = { likes.like(target) },
						onRecheck = { likes.recheck(target) },
					)
				}
			} else {
				SocialLikeCount(likeCount)
			}
		}
	}
	Box(Modifier.height(2.dp))
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
	Icon(
		if (filled) WaxIcons.HeartFilled else WaxIcons.Heart,
		contentDescription = if (filled) "Liked" else "Like",
		tint = if (filled) {
			MaterialTheme.colorScheme.primary
		} else {
			MaterialTheme.colorScheme.onSurfaceVariant
		},
		modifier = Modifier
			.size(19.dp)
			.then(
				// Only an outline heart is a live control. Filled has nothing
				// left to do, and inert never had anything to do.
				if (heart == SnapHeart.OUTLINE) {
					Modifier.clickable(onClickLabel = "Like", onClick = onLike)
				} else {
					Modifier
				},
			),
	)
	// Nothing at zero: "0" beside every new comment is noise, and an absence
	// already reads as none.
	if (count > 0) {
		Text(
			"$count",
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}
	if (pending) {
		// Named rather than a bare "Check again": the comment beside it may
		// have a re-check of its own.
		TextButton(onClick = onRecheck, contentPadding = PaddingValues(2.dp)) {
			Text("Check", style = MaterialTheme.typography.labelSmall)
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
	Icon(
		WaxIcons.Heart,
		contentDescription = "Likes",
		tint = MaterialTheme.colorScheme.onSurfaceVariant,
		modifier = Modifier.size(19.dp),
	)
	Text(
		"$count",
		style = MaterialTheme.typography.labelSmall,
		color = MaterialTheme.colorScheme.onSurfaceVariant,
	)
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

/**
 * The one confirmation a deletion gets, naming exactly what goes.
 *
 * Delete is live only once the chain has just said yes; the deleter asks
 * again before it signs. Cancel sends nothing. While the deletion runs the
 * dialog stays, so the result is never inferred from the dialog going away.
 */
@Composable
private fun DeleteDialog(request: SnapThreadController.Deleting, threads: SnapThreadController) {
	val what = if (request.kind == SnapEditKind.ROOT) "Snap" else "reply"
	val running = request.phase == SnapThreadController.DeletePhase.RUNNING
	AlertDialog(
		onDismissRequest = { if (!running) threads.cancelDelete() },
		title = { Text("Delete this $what from Hive?") },
		text = {
			Column {
				Text(
					request.body,
					style = MaterialTheme.typography.bodyMedium,
					maxLines = 4,
					overflow = TextOverflow.Ellipsis,
				)
				Spacer(Modifier.height(6.dp))
				Text(
					"@${request.target.author}/${request.target.permlink}",
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				Spacer(Modifier.height(8.dp))
				Text(
					when (request.phase) {
						SnapThreadController.DeletePhase.CHECKING -> "Checking Hive…"
						SnapThreadController.DeletePhase.CONFIRM ->
							"This can't be undone. The History entry and its scrobble stay."
						SnapThreadController.DeletePhase.RUNNING -> "Deleting and confirming on Hive…"
					},
					style = MaterialTheme.typography.bodySmall,
				)
			}
		},
		confirmButton = {
			TextButton(
				onClick = threads::confirmDelete,
				enabled = request.phase == SnapThreadController.DeletePhase.CONFIRM,
			) {
				// Red only while it can actually be pressed; the disabled state
				// must not read as live while checking or deleting.
				val live = request.phase == SnapThreadController.DeletePhase.CONFIRM
				Text(
					"Delete",
					color = if (live) {
						MaterialTheme.colorScheme.error
					} else {
						MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
					},
				)
			}
		},
		dismissButton = {
			TextButton(onClick = threads::cancelDelete, enabled = !running) { Text("Cancel") }
		},
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
