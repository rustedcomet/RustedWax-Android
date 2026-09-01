package com.rustedwax.app.detect

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest

/**
 * A short-lived, process-independent memory of browser transports already ended.
 *
 * Chromium can leave an ended MediaSession in PLAYING indefinitely. The in-memory
 * Watch tombstone prevents that controller from scoring twice until Android kills
 * RustedWax; this store carries only the minimum decision across that restart.
 * Implementations must not persist browsing text.
 */
interface FinalizedPlaybackTombstones {
	data class Match(val transportUpdatedAtElapsedMs: Long)

	fun record(
		packageName: String,
		semanticKey: String,
		finalizedAtEpochMs: Long,
		finalizedAtElapsedMs: Long,
		transportUpdatedAtElapsedMs: Long,
		durationMs: Long?,
	)

	fun findStaleTransport(
		packageName: String,
		semanticKey: String,
		nowEpochMs: Long,
		nowElapsedMs: Long,
		transportUpdatedAtElapsedMs: Long,
		positionMs: Long? = null,
	): Match?

	data object None : FinalizedPlaybackTombstones {
		override fun record(
			packageName: String,
			semanticKey: String,
			finalizedAtEpochMs: Long,
			finalizedAtElapsedMs: Long,
			transportUpdatedAtElapsedMs: Long,
			durationMs: Long?,
		) = Unit

		override fun findStaleTransport(
			packageName: String,
			semanticKey: String,
			nowEpochMs: Long,
			nowElapsedMs: Long,
			transportUpdatedAtElapsedMs: Long,
			positionMs: Long?,
		): Match? = null
	}
}

/** SharedPreferences implementation whose keys and values contain no browsing text. */
internal class SharedPreferencesFinalizedPlaybackTombstones(context: Context) :
	FinalizedPlaybackTombstones {

	private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

	@Synchronized
	override fun record(
		packageName: String,
		semanticKey: String,
		finalizedAtEpochMs: Long,
		finalizedAtElapsedMs: Long,
		transportUpdatedAtElapsedMs: Long,
		durationMs: Long?,
	) {
		if (semanticKey.isBlank()) return
		val survivors = liveEntries(finalizedAtEpochMs).toMutableMap()
		survivors[keyFor(packageName, semanticKey)] = listOf(
			finalizedAtEpochMs,
			finalizedAtElapsedMs,
			transportUpdatedAtElapsedMs,
			durationMs?.takeIf { it > 0 } ?: -1L,
		).joinToString("|")
		val bounded = survivors.entries
			.sortedByDescending { parse(it.value)?.finalizedAtEpochMs ?: Long.MIN_VALUE }
			.take(MAX_ENTRIES)
		prefs.edit().clear().also { editor ->
			bounded.forEach { (key, value) -> editor.putString(key, value) }
		}.apply()
	}

	@Synchronized
	override fun findStaleTransport(
		packageName: String,
		semanticKey: String,
		nowEpochMs: Long,
		nowElapsedMs: Long,
		transportUpdatedAtElapsedMs: Long,
		positionMs: Long?,
	): FinalizedPlaybackTombstones.Match? {
		if (semanticKey.isBlank()) return null
		val live = liveEntries(nowEpochMs)
		if (live.size != prefs.all.size) rewrite(live)
		val record = parse(live[keyFor(packageName, semanticKey)]) ?: return null
		// elapsedRealtime resets on boot. A record from another boot has no safe
		// ordering relationship to this transport and therefore cannot suppress it.
		if (nowElapsedMs < record.finalizedAtElapsedMs) return null
		val unchangedStamp = transportUpdatedAtElapsedMs <= record.transportUpdatedAtElapsedMs
		val ranPastRecordedEnd = record.durationMs?.let { duration ->
			positionMs != null && positionMs >= duration
		} == true
		if (!unchangedStamp && !ranPastRecordedEnd) return null
		return FinalizedPlaybackTombstones.Match(
			if (ranPastRecordedEnd) transportUpdatedAtElapsedMs
			else record.transportUpdatedAtElapsedMs,
		)
	}

	private fun liveEntries(nowEpochMs: Long): Map<String, String> = prefs.all.mapNotNull { (key, raw) ->
		val value = raw as? String ?: return@mapNotNull null
		val record = parse(value) ?: return@mapNotNull null
		val age = nowEpochMs - record.finalizedAtEpochMs
		(key to value).takeIf { age in 0..RETENTION_MS }
	}.toMap()

	private fun rewrite(entries: Map<String, String>) {
		prefs.edit().clear().also { editor ->
			entries.forEach { (key, value) -> editor.putString(key, value) }
		}.apply()
	}

	private fun keyFor(packageName: String, semanticKey: String): String {
		val bytes = "$packageName\u0000$semanticKey".toByteArray(Charsets.UTF_8)
		return MessageDigest.getInstance("SHA-256").digest(bytes)
			.joinToString("") { "%02x".format(it) }
	}

	private fun parse(value: String?): Record? {
		val fields = value?.split('|') ?: return null
		if (fields.size != 4) return null
		return Record(
			finalizedAtEpochMs = fields[0].toLongOrNull() ?: return null,
			finalizedAtElapsedMs = fields[1].toLongOrNull() ?: return null,
			transportUpdatedAtElapsedMs = fields[2].toLongOrNull() ?: return null,
			durationMs = fields[3].toLongOrNull()?.takeIf { it > 0 },
		)
	}

	private data class Record(
		val finalizedAtEpochMs: Long,
		val finalizedAtElapsedMs: Long,
		val transportUpdatedAtElapsedMs: Long,
		val durationMs: Long?,
	)

	private companion object {
		const val PREFS = "rustedwax_finished_transport"
		const val RETENTION_MS = 6 * 60 * 60 * 1000L
		const val MAX_ENTRIES = 64
	}
}
