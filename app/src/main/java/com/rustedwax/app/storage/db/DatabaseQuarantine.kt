package com.rustedwax.app.storage.db

import java.io.File

/**
 * Moving a corrupt database out of the way **without deleting a byte of it**.
 *
 * Android's default corruption handler deletes the database file and its
 * companions, then lets the open continue against an empty file. For this app
 * that would silently erase the user's History and Snap state, so
 * [LocalDatabase] replaces that handler with this.
 *
 * The whole set — the main file and any `-wal`, `-shm` and `-journal` beside
 * it — is moved into one directory named `<db>.corrupt-<millis>`, keeping the
 * original file names. Keeping the names is what keeps the copy useful: SQLite
 * pairs a write-ahead log with its database by name, so the quarantined pair
 * can still be opened together for recovery.
 *
 * Order matters, and is the reason this is more than a loop of renames:
 *
 *  1. **The main file moves first.** If it cannot be moved, nothing has been
 *     touched and the outcome is [Outcome.Refused]: the caller must not let a
 *     fresh database be created over it.
 *  2. **Then each companion.** A companion left behind beside a new, empty
 *     main file is dangerous — a stale write-ahead log can be replayed into
 *     the new database. So if any companion cannot be moved, everything already
 *     moved is put back and the outcome is again [Outcome.Refused]. If even the
 *     put-back fails, the outcome is [Outcome.Stranded], naming where every
 *     file now is; still nothing has been deleted.
 *
 * Pure `java.io`, so the sequence is testable on the JVM against a temporary
 * directory. It never deletes, truncates or writes inside any of the files.
 */
internal object DatabaseQuarantine {

	/** Companion suffixes SQLite may leave beside a database file. */
	val COMPANION_SUFFIXES = listOf("-wal", "-shm", "-journal")

	/** The prefix every quarantine directory for [database] starts with. */
	fun prefixFor(database: File): String = "${database.name}.corrupt-"

	sealed interface Outcome {
		/** No database file existed; there was nothing to preserve. */
		data object NothingToMove : Outcome

		/** Every file of the set now lives in [directory], names unchanged. */
		data class Quarantined(val directory: File, val moved: List<String>) : Outcome

		/**
		 * Nothing was moved — or everything moved was put back. The corrupt
		 * files are exactly where they were; the database must not be reopened
		 * as if it were fresh.
		 */
		data class Refused(val reason: String) : Outcome

		/**
		 * Part of the set moved and could not be put back. Nothing is lost —
		 * [inQuarantine] lists what is in [directory], the rest is in place —
		 * but the set is split and must not be reopened as fresh.
		 */
		data class Stranded(val directory: File, val inQuarantine: List<String>, val reason: String) : Outcome
	}

	/**
	 * Move [database] and its companions into a new quarantine directory.
	 *
	 * [move] is [File.renameTo] in production; tests replace it to reach the
	 * failure branches a real filesystem will not produce on demand.
	 */
	fun quarantine(
		database: File,
		nowMillis: Long,
		move: (from: File, to: File) -> Boolean = { from, to -> from.renameTo(to) },
	): Outcome {
		if (!database.exists()) return Outcome.NothingToMove
		val parent = database.absoluteFile.parentFile
			?: return Outcome.Refused("database has no parent directory")

		val directory = uniqueDirectory(parent, prefixFor(database), nowMillis)
			?: return Outcome.Refused("no free quarantine directory name")
		if (!directory.mkdir()) {
			return Outcome.Refused("could not create ${directory.name}")
		}

		val moved = mutableListOf<String>()
		if (!move(database, File(directory, database.name))) {
			directory.delete() // Empty: only ever the directory just made.
			return Outcome.Refused("could not move ${database.name}")
		}
		moved += database.name

		for (suffix in COMPANION_SUFFIXES) {
			val companion = File(parent, database.name + suffix)
			if (!companion.exists()) continue
			if (move(companion, File(directory, companion.name))) {
				moved += companion.name
				continue
			}
			// Put the set back together where it was.
			val notRestored = moved.filterNot { name ->
				move(File(directory, name), File(parent, name))
			}
			if (notRestored.isEmpty()) {
				directory.delete() // Empty again: every file went back.
				return Outcome.Refused("could not move ${companion.name}; set restored in place")
			}
			return Outcome.Stranded(
				directory = directory,
				inQuarantine = notRestored,
				reason = "could not move ${companion.name} and could not restore $notRestored",
			)
		}
		return Outcome.Quarantined(directory, moved)
	}

	/** Every quarantine directory left beside [database], oldest name first. */
	fun existing(database: File): List<File> {
		val parent = database.absoluteFile.parentFile ?: return emptyList()
		val prefix = prefixFor(database)
		return parent.listFiles { f -> f.isDirectory && f.name.startsWith(prefix) }
			.orEmpty()
			.sortedBy { it.name }
	}

	private fun uniqueDirectory(parent: File, prefix: String, nowMillis: Long): File? {
		val base = File(parent, "$prefix$nowMillis")
		if (!base.exists()) return base
		return (1..99).asSequence()
			.map { File(parent, "$prefix$nowMillis-$it") }
			.firstOrNull { !it.exists() }
	}
}
