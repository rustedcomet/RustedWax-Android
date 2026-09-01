package com.rustedwax.app.enrich

import com.rustedwax.youtube.identity.VideoResolution

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Small process-memory index of identities verified during one monitoring run.
 *
 * Entries are candidates, never payload authority. A caller must fetch the
 * candidate's watch page and corroborate its canonical title, channel and
 * duration again before returning a [VideoResolution].
 */
object VerifiedIdentityCandidateCache {

	private data class Entry(
		val packageName: String,
		val videoId: String,
		val titleKey: String,
		val channelKey: String,
		val ownerHandleKey: String?,
		val durationSeconds: Long,
		val verifiedAtMillis: Long,
	)

	private val entries = ConcurrentHashMap<String, Entry>()

	fun remember(
		packageName: String,
		videoId: String,
		title: String?,
		channel: String?,
		durationMs: Long?,
		ownerHandle: String? = null,
		now: Long = System.currentTimeMillis(),
	) {
		val titleKey = title?.let(SearchResultsParser::titleKey)?.takeIf(String::isNotBlank)
			?: return
		val ownerHandleKey = ownerHandle?.let(OwnerHandle::normalize)
		if (ownerHandle != null && ownerHandleKey == null) return
		val channelKey = if (ownerHandleKey != null) {
			ownerHandleKey
		} else {
			SearchResultsParser.channelKey(channel) ?: return
		}
		val durationSeconds = durationMs?.takeIf { it > 0 }?.div(1000) ?: return
		if (!VIDEO_ID.matches(videoId)) return
		prune(now)
		entries["$packageName|$videoId"] = Entry(
			packageName = packageName,
			videoId = videoId,
			titleKey = titleKey,
			channelKey = channelKey,
			ownerHandleKey = ownerHandleKey,
			durationSeconds = durationSeconds,
			verifiedAtMillis = now,
		)
		trimToSize()
	}

	fun candidates(
		packageName: String,
		title: String?,
		channel: String?,
		durationMs: Long?,
		ownerHandle: String? = null,
		now: Long = System.currentTimeMillis(),
	): List<String> {
		val titleKey = title?.let(SearchResultsParser::titleKey)?.takeIf(String::isNotBlank)
			?: return emptyList()
		val ownerHandleKey = ownerHandle?.let(OwnerHandle::normalize)
		if (ownerHandle != null && ownerHandleKey == null) return emptyList()
		val channelKey = if (ownerHandleKey != null) {
			ownerHandleKey
		} else {
			SearchResultsParser.channelKey(channel) ?: return emptyList()
		}
		val durationSeconds = durationMs?.takeIf { it > 0 }?.div(1000) ?: return emptyList()
		prune(now)
		return entries.values
			.asSequence()
			.filter {
				it.packageName == packageName &&
					it.titleKey == titleKey &&
					it.channelKey == channelKey &&
					(ownerHandleKey == null || it.ownerHandleKey == ownerHandleKey) &&
					abs(it.durationSeconds - durationSeconds) <= DURATION_TOLERANCE_SEC
			}
			.sortedByDescending { it.verifiedAtMillis }
			.map { it.videoId }
			.distinct()
			.toList()
	}

	fun clear(packageName: String) {
		entries.entries.removeIf { it.value.packageName == packageName }
	}

	fun clearAll() = entries.clear()

	internal fun size(): Int = entries.size

	private fun prune(now: Long) {
		entries.entries.removeIf { now - it.value.verifiedAtMillis > MAX_AGE_MS }
	}

	private fun trimToSize() {
		val overflow = entries.size - MAX_ENTRIES
		if (overflow <= 0) return
		entries.values.sortedBy { it.verifiedAtMillis }.take(overflow).forEach {
			entries.remove("${it.packageName}|${it.videoId}", it)
		}
	}

	internal const val MAX_ENTRIES = 32
	internal const val MAX_AGE_MS = 6 * 60 * 60 * 1000L
	private const val DURATION_TOLERANCE_SEC = 5L
	private val VIDEO_ID = Regex("""^[A-Za-z0-9_-]{11}$""")
}
