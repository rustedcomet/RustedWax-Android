package com.rustedwax.app.detect

data class NativePlaylistCapture(
	val packageName: String?,
	val watchScreenPresent: Boolean,
	val containers: List<NativeShortNode>,
	val exceededCaptureBudget: Boolean = false,
)

object NativePlaylistParser {

	sealed interface Result {
		/** A playlist context proven on screen. */
		data class Context(
			val playlistName: String,
			val ownerName: String?,
			val position: Int,
			val total: Int,
		) : Result

		/**
		 * The native watch screen was readable and carries no playlist bar.
		 *
		 * Positive evidence, but not conclusive on its own: an ad break also
		 * removes the bar. The latch grace exists for exactly that.
		 */
		data class NoPlaylist(val reason: String) : Result

		/**
		 * Nothing could be read — backgrounded, screen off, or not YouTube.
		 *
		 * Distinct from [NoPlaylist] because it is *no evidence at all*, and
		 * playback continues in this state for entire tracks. It must never
		 * drop a latch or the common screen-off case would lose the playlist.
		 */
		data class Unobservable(val reason: String) : Result
	}

	/** ` • 61/120` and friends. */
	private val POSITION_TEXT = Regex("""(\d{1,5})\s*/\s*(\d{1,5})""")

	/** `61 out of 120`, the collapsed bar's content description. */
	private val POSITION_DESCRIPTION = Regex("""(\d{1,5})\s+out of\s+(\d{1,5})""", RegexOption.IGNORE_CASE)

	private const val MAX_NAME_LENGTH = 150
	private const val MAX_NODES = 400

	fun parse(capture: NativePlaylistCapture): Result {
		if (capture.exceededCaptureBudget) {
			return Result.Unobservable("playlist accessibility capture budget exceeded")
		}
		if (capture.packageName != YouTubeProbe.YOUTUBE_PACKAGE) {
			return Result.Unobservable("foreground package was not native YouTube")
		}
		// The miniplayer keeps playing the playlist with no watch screen at all.
		// Reporting that as NoPlaylist is what made the first field run drop a
		// good latch after 103 s and mis-resolve the following track.
		if (!capture.watchScreenPresent) {
			return Result.Unobservable("native YouTube is not on an expanded watch screen")
		}

		// One container per captured position/playlist_name anchor. Reading each
		// independently is what keeps a queue row's `title` from being mistaken
		// for the panel header's.
		val contexts = capture.containers.mapNotNull(::contextIn).distinct()

		return when (contexts.size) {
			0 -> Result.NoPlaylist("no native YouTube playlist bar on screen")
			1 -> contexts.single()
			// Two disagreeing bars means the screen is mid-transition. Refuse
			// rather than pick one.
			else -> Result.NoPlaylist("found ${contexts.size} conflicting playlist bars")
		}
	}

	private fun contextIn(container: NativeShortNode): Result.Context? {
		val nodes = descendants(container)
		if (nodes.size > MAX_NODES) return null

		val (position, total) = nodes.asSequence()
			.filter { it.visible && it.hasIdSuffix(POSITION_ID) }
			.mapNotNull(::parsePosition)
			.firstOrNull() ?: return null

		val name = nameIn(nodes) ?: return null
		val owner = nodes.asSequence()
			.filter { it.visible && it.hasIdSuffix(SUBTITLE_ID) }
			.mapNotNull { clean(it.text) }
			.firstOrNull { it.length <= MAX_NAME_LENGTH }

		return Result.Context(
			playlistName = name,
			ownerName = owner,
			position = position,
			total = total,
		)
	}

	/**
	 * The collapsed bar's own id wins; the expanded panel header falls back to
	 * the generic `title` that sits alongside the position.
	 */
	private fun nameIn(nodes: List<NativeShortNode>): String? {
		fun pick(idSuffix: String): String? = nodes.asSequence()
			.filter { it.visible && it.hasIdSuffix(idSuffix) }
			.mapNotNull { clean(it.text) }
			.firstOrNull { it.length <= MAX_NAME_LENGTH && !POSITION_TEXT.containsMatchIn(it) }

		return pick(PLAYLIST_NAME_ID) ?: pick(TITLE_ID)
	}

	private fun parsePosition(node: NativeShortNode): Pair<Int, Int>? {
		val fromText = clean(node.text)?.let(POSITION_TEXT::find)
		val fromDescription = clean(node.contentDescription)?.let(POSITION_DESCRIPTION::find)
		val match = fromText ?: fromDescription ?: return null
		val position = match.groupValues[1].toIntOrNull() ?: return null
		val total = match.groupValues[2].toIntOrNull() ?: return null
		if (total < 1 || position < 1 || position > total) return null
		return position to total
	}

	/** Non-breaking spaces and bullets are how YouTube renders this bar. */
	private fun clean(value: String?): String? = value
		?.replace('\u00A0', ' ')
		?.replace('•', ' ')
		?.trim()
		?.replace(Regex("""\s+"""), " ")
		?.takeIf(String::isNotEmpty)

	private fun descendants(root: NativeShortNode): List<NativeShortNode> {
		val out = mutableListOf<NativeShortNode>()
		fun visit(node: NativeShortNode) {
			if (out.size > MAX_NODES) return
			out += node
			node.children.forEach(::visit)
		}
		visit(root)
		return out
	}

	private fun NativeShortNode.hasIdSuffix(suffix: String): Boolean =
		resourceId?.substringAfterLast('/') == suffix

	const val POSITION_ID = "position"
	const val PLAYLIST_NAME_ID = "playlist_name"
	const val TITLE_ID = "title"
	const val SUBTITLE_ID = "subtitle"
}
