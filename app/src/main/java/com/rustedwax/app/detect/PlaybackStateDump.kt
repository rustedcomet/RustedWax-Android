package com.rustedwax.app.detect

import android.os.Bundle
import android.os.Build
import android.media.session.PlaybackState

/** Exact, non-interpretive native PlaybackState instrumentation for device review. */
object PlaybackStateDump {
	fun dump(state: PlaybackState?): List<String> {
		if (state == null) return listOf("<null state>")
		val lines = mutableListOf(
			"state = ${state.state}",
			"position = ${state.position}",
			"bufferedPosition = ${state.bufferedPosition}",
			"playbackSpeed = ${state.playbackSpeed}",
			"actions = ${state.actions} (0x${state.actions.toString(16)})",
			"errorMessage = ${quoted(state.errorMessage?.toString())}",
			"lastPositionUpdateTime = ${state.lastPositionUpdateTime}",
			"activeQueueItemId = ${state.activeQueueItemId}",
		)
		lines += if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			"isActive = ${state.isActive}"
		} else {
			"isActive = <unavailable before API 31>"
		}
		if (state.customActions.isEmpty()) {
			lines += "customActions = []"
		} else {
			state.customActions.forEachIndexed { index, action ->
				lines += "customAction[$index].action = ${quoted(action.action)}"
				lines += "customAction[$index].name = ${quoted(action.name?.toString())}"
				lines += "customAction[$index].icon = ${action.icon}"
				lines += bundleLines("customAction[$index].extras", action.extras)
			}
		}
		lines += bundleLines("extras", state.extras)
		return lines
	}

	@Suppress("DEPRECATION")
	private fun bundleLines(prefix: String, bundle: Bundle?): List<String> {
		if (bundle == null) return listOf("$prefix = <null>")
		if (bundle.isEmpty) return listOf("$prefix = {}")
		return bundle.keySet().sorted().map { key ->
			val value = runCatching { bundle.get(key) }.getOrNull()
			"$prefix.$key = ${render(value)}"
		}
	}

	private fun render(value: Any?): String = when (value) {
		null -> "<null>"
		is CharSequence -> quoted(value.toString())
		is ByteArray -> value.joinToString(prefix = "[", postfix = "]")
		is IntArray -> value.joinToString(prefix = "[", postfix = "]")
		is LongArray -> value.joinToString(prefix = "[", postfix = "]")
		is BooleanArray -> value.joinToString(prefix = "[", postfix = "]")
		is FloatArray -> value.joinToString(prefix = "[", postfix = "]")
		is DoubleArray -> value.joinToString(prefix = "[", postfix = "]")
		else -> value.toString()
	}

	private fun quoted(value: String?): String = value?.let { "\"$it\"" } ?: "<null>"
}
