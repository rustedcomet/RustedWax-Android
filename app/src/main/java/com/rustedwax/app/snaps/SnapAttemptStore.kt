package com.rustedwax.app.snaps

import android.content.Context
import android.content.SharedPreferences
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Where unsettled Edit and Delete attempts survive the process (Issue #56, 3C).
 *
 * Raw strings by key; [SnapWriteGuards] owns the format. A write is durable only
 * when [save] says so.
 */
interface SnapAttemptStore {
	/** Every stored entry, or null when the store cannot be read at all. */
	fun load(): Map<String, String>?

	/** Store [value] under [key]; true only once it is on disk and reads back. */
	fun save(key: String, value: String): Boolean

	/** Remove [key]; true once it is gone from disk. */
	fun remove(key: String): Boolean
}

/**
 * The app's [SnapAttemptStore]: one private preferences file, written the way
 * [SharedPreferencesPendingSnapStore] writes the records that stop duplicate
 * Snaps — `commit()`, never `apply()`, and read back before it counts. A value
 * that is not a string comes back as an empty one, which cannot be read as an
 * attempt and so locks its comment rather than vanishing.
 */
internal class SharedPreferencesSnapAttemptStore(context: Context) : SnapAttemptStore {

	private val prefs: SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
	private val file = File(context.dataDir, "shared_prefs/$FILE.xml")

	override fun load(): Map<String, String>? = runCatching {
		val loaded = prefs.all
		verdict(loaded, if (file.exists()) file.readBytes() else null)
	}.getOrNull()

	override fun save(key: String, value: String): Boolean = runCatching {
		prefs.edit().putString(key, value).commit() && prefs.getString(key, null) == value
	}.getOrDefault(false)

	override fun remove(key: String): Boolean = runCatching {
		prefs.edit().remove(key).commit() && !prefs.contains(key)
	}.getOrDefault(false)

	companion object {
		const val FILE = "rustedwax_snap_write_attempts"

		/**
		 * What a load may be trusted to say, given what Android loaded and the
		 * bytes of the file it loaded from (B1).
		 *
		 * Android's loader answers a file it cannot parse with an empty map and
		 * only logs the failure, so an empty map alone cannot tell a new store
		 * from a destroyed one. By the time this runs Android has already put a
		 * valid `.bak` back in place, so the file on disk is the one it read. The
		 * map is trusted when there is no file, or when the file is a well-formed
		 * preferences `<map>` holding exactly the names Android returned.
		 * Anything else is null — unreadable — which locks every comment; nothing
		 * here writes, so the bytes stay as evidence.
		 */
		internal fun verdict(loaded: Map<String, *>, fileBytes: ByteArray?): Map<String, String>? {
			val values = loaded.mapValues { (_, value) -> value as? String ?: "" }
			if (fileBytes == null) return values
			val names = entryNames(fileBytes) ?: return null
			return values.takeIf { names.size == loaded.size && names.toSet() == loaded.keys }
		}

		/** The `name` of every entry in a well-formed preferences file, or null. */
		private fun entryNames(bytes: ByteArray): List<String>? = runCatching {
			if (String(bytes, Charsets.UTF_8).contains("<!DOCTYPE", ignoreCase = true)) return null
			val root = DocumentBuilderFactory.newInstance()
				.apply {
					isNamespaceAware = false
					isExpandEntityReferences = false
				}
				.newDocumentBuilder()
				.parse(ByteArrayInputStream(bytes))
				.documentElement
			if (root.tagName != "map") return null
			val children = root.childNodes
			(0 until children.length)
				.map { children.item(it) }
				.filter { it.nodeType == Node.ELEMENT_NODE }
				.map { (it as Element).getAttribute("name").takeIf { name -> it.hasAttribute("name") } ?: return null }
		}.getOrNull()
	}
}
