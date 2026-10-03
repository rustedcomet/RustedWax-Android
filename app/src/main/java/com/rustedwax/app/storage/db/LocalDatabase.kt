package com.rustedwax.app.storage.db

import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import androidx.annotation.WorkerThread
import java.io.File

/**
 * RustedWax's structured local database (Issue #41): one SQLite file, opened
 * through the platform's own [SQLiteOpenHelper]. No Room and no extra runtime
 * dependency.
 *
 * This class owns opening, versioning and corruption handling, and nothing
 * else. It knows no record types and makes no decisions about them: adapters
 * built on [write] and [read] persist state that playback, finalization and
 * the Snap write paths have already decided. Nothing here may reach a Hive
 * write, the broadcast queue or a publisher, and the architecture tests hold
 * it to that.
 *
 * ## Opening
 *
 *  - **Write-ahead logging** on, so a reader never waits on the single writer.
 *  - **No foreign keys.** No table references another, so the pragma would
 *    only be one more thing to keep consistent.
 *  - **Upgrades are explicit**, step by step from [LocalDatabaseSchema.upgradeSteps].
 *    An unknown step fails the open inside the helper's transaction, which
 *    leaves the file and its version exactly as they were. A **downgrade**
 *    fails the open too. There is no destructive fallback anywhere.
 *
 * ## Corruption
 *
 * The platform's default handler deletes a corrupt database. This one moves
 * the whole file set into a private quarantine directory instead — see
 * [DatabaseQuarantine] — and only then lets the platform carry on against a
 * new, empty file. If the move cannot be completed safely, the open **fails**
 * rather than continuing over the corrupt files.
 *
 * A fresh database after a quarantine is empty, not recovered. The bytes are
 * kept, [quarantined] lists them, and the legacy SharedPreferences stores are
 * never touched by this class, so later slices can re-import from those.
 *
 * ## Threads
 *
 * The first [write] or [read] opens the file and may create or upgrade it, and
 * every call is disk I/O. Both are [WorkerThread] for that reason. One instance
 * per process ([get]), because SQLite's thread safety rests on every caller
 * sharing one connection pool.
 */
internal class LocalDatabase internal constructor(
	context: Context,
	name: String,
	nowMillis: () -> Long = System::currentTimeMillis,
) : SQLiteOpenHelper(
	context.applicationContext,
	name,
	null,
	LocalDatabaseSchema.VERSION,
	QuarantiningErrorHandler(nowMillis),
) {

	private val file: File = context.applicationContext.getDatabasePath(name)

	init {
		setWriteAheadLoggingEnabled(true)
	}

	override fun onCreate(db: SQLiteDatabase) {
		// The helper has already opened a transaction around this call, so the
		// schema is created whole or not at all.
		LocalDatabaseSchema.CREATE_V1.forEach(db::execSQL)
	}

	override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
		LocalDatabaseSchema.upgradeSteps(oldVersion, newVersion).forEach(db::execSQL)
	}

	override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
		// Said explicitly rather than inherited: an older build must never
		// reinterpret, or recreate, a database a newer one wrote.
		throw SQLiteException(
			"local database is version $oldVersion; this build only understands $newVersion",
		)
	}

	/**
	 * Run [block] in one immediate transaction: every write in it lands, or none
	 * does. The only way adapters should write.
	 */
	@WorkerThread
	fun <T> write(block: (SQLiteDatabase) -> T): T {
		val db = open { writableDatabase }
		db.beginTransaction()
		try {
			val result = block(db)
			db.setTransactionSuccessful()
			return result
		} finally {
			db.endTransaction()
		}
	}

	/** Run a read against the database. */
	@WorkerThread
	fun <T> read(block: (SQLiteDatabase) -> T): T = block(open { readableDatabase })

	/** Every quarantined copy of this database still on disk, oldest first. */
	fun quarantined(): List<File> = DatabaseQuarantine.existing(file)

	/**
	 * Refuse to open over a split file set.
	 *
	 * A `-wal` or `-shm` with no main file beside it is only left behind when a
	 * quarantine could not be completed (or by something outside this app).
	 * Opening would create a new main file next to a stale log that SQLite may
	 * then replay into it, so the open stops instead and the files stay put.
	 */
	private fun open(opener: () -> SQLiteDatabase): SQLiteDatabase {
		if (!file.exists()) {
			val orphans = DatabaseQuarantine.COMPANION_SUFFIXES
				.map { File(file.path + it) }
				.filter { it.exists() }
			if (orphans.isNotEmpty()) {
				throw SQLiteException(
					"local database companions ${orphans.map { it.name }} exist without " +
						"${file.name}; refusing to create a new database beside them",
				)
			}
		}
		return opener()
	}

	companion object {
		@Volatile
		private var instance: LocalDatabase? = null

		/** The process's one instance, opened lazily on first use. */
		fun get(context: Context): LocalDatabase =
			instance ?: synchronized(this) {
				instance ?: LocalDatabase(context, LocalDatabaseSchema.NAME).also { instance = it }
			}
	}
}

/**
 * The corruption handler [LocalDatabase] installs in place of the platform's
 * deleting one.
 *
 * Called by the platform when SQLite reports corruption, either while opening
 * (after which the platform retries the open once) or during a statement on an
 * open database. Sequence:
 *
 *  1. close [SQLiteDatabase] if it is open, so no connection holds the files
 *     while they move;
 *  2. move the set with [DatabaseQuarantine];
 *  3. on [DatabaseQuarantine.Outcome.Quarantined] (or nothing to move), return,
 *     and the platform reopens a new empty file;
 *  4. on anything else, throw. On the open path that ends the open — the
 *     platform's retry never runs over the corrupt files — and on the
 *     statement path it is what the statement's caller receives.
 *
 * Nothing in this sequence deletes a file.
 */
internal class QuarantiningErrorHandler(
	private val nowMillis: () -> Long,
) : DatabaseErrorHandler {

	override fun onCorruption(dbObj: SQLiteDatabase) {
		val path = dbObj.path
		runCatching { if (dbObj.isOpen) dbObj.close() }
		if (path.isNullOrEmpty() || path == SQLiteDatabaseInMemory) return

		when (val outcome = DatabaseQuarantine.quarantine(File(path), nowMillis())) {
			DatabaseQuarantine.Outcome.NothingToMove -> Unit
			is DatabaseQuarantine.Outcome.Quarantined ->
				Log.w(TAG, "corrupt local database quarantined to ${outcome.directory.name}: ${outcome.moved}")
			is DatabaseQuarantine.Outcome.Refused -> {
				Log.e(TAG, "corrupt local database left in place: ${outcome.reason}")
				throw SQLiteException("corrupt local database could not be quarantined: ${outcome.reason}")
			}
			is DatabaseQuarantine.Outcome.Stranded -> {
				Log.e(TAG, "corrupt local database split during quarantine: ${outcome.reason}")
				throw SQLiteException("corrupt local database quarantine incomplete: ${outcome.reason}")
			}
		}
	}

	private companion object {
		const val TAG = "RustedWaxDb"
		const val SQLiteDatabaseInMemory = ":memory:"
	}
}
