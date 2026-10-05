package com.rustedwax.app.storage.db

/**
 * The structured local database's schema, as plain SQL (Issue #41).
 *
 * Pure on purpose: no Android, so the statements, the version and the owner
 * rule are reviewable and testable without a device. [LocalDatabase] is the
 * only thing that executes them.
 *
 * The database **persists state other code has already decided**. Nothing in
 * this file chooses whether a listen counts, whether a scrobble is sent, or
 * whether a Snap may be published, and nothing read back from these tables is
 * meant to be consulted for any of those decisions.
 *
 * Every table except [LEGACY_IMPORT] is account-scoped through an `owner`
 * column — see [LocalOwner]. Ownership is part of the primary key or the
 * leading column of an index wherever rows are looked up, so no query has a
 * reason to read across accounts.
 *
 * Version 1 is created whole. A later version adds explicit steps in
 * [upgradeSteps]; there is deliberately no "drop everything and recreate"
 * fallback anywhere in this layer. A new install runs [CREATE_V1] and then
 * every step, so a fresh database and an upgraded one are the same schema.
 */
internal object LocalDatabaseSchema {

	/** The file under the app's private databases directory. */
	const val NAME = "rustedwax.db"

	const val VERSION = 3

	const val HISTORY = "history"
	const val NOT_LOGGED = "not_logged"
	const val SNAP_DRAFT = "snap_draft"
	const val POSTED_SNAP_CACHE = "posted_snap_cache"
	const val SNAP_REFRESH = "snap_refresh"
	const val THREAD_PREVIEW = "thread_preview"
	const val SEEN_REPLY = "seen_reply"
	const val REPLY_NOTICE = "reply_notice"
	const val THREAD_SEED = "thread_seed"
	const val SYNC_CHECKPOINT = "sync_checkpoint"
	const val LEGACY_IMPORT = "legacy_import"
	const val MY_SNAP = "my_snap"
	const val MY_SNAP_TOMBSTONE = "my_snap_tombstone"

	/**
	 * Owner column for rows that can belong to the signed-out device.
	 *
	 * Lowercase, trimmed and non-empty. `'-'` passes, which is the point: it is
	 * the signed-out owner, and no valid Hive name can be that string.
	 */
	private const val OWNER_ANY =
		"owner TEXT NOT NULL CHECK (length(owner) > 0 AND owner = lower(trim(owner)))"

	/**
	 * Owner column for rows that only a Hive account can produce — a signed
	 * scrobble, a refresh of that account's Snaps, a reply addressed to it.
	 * The signed-out owner is refused by the schema itself rather than by every
	 * future writer remembering to.
	 */
	private const val OWNER_ACCOUNT =
		"owner TEXT NOT NULL CHECK (length(owner) > 0 AND owner = lower(trim(owner)) " +
			"AND owner <> '${LocalOwner.SIGNED_OUT}')"

	/**
	 * Version 1, in creation order. Tables first, then their indexes.
	 *
	 * `history.row_id` is the row's database identity and `event_id` is a
	 * separate column. Rows from current main may carry the same value in
	 * both; the schema never assumes they match, because Stage 7B makes
	 * `event_id` a listen identity that several rows share.
	 */
	val CREATE_V1: List<String> = listOf(
		"""
		CREATE TABLE $HISTORY (
			row_id TEXT NOT NULL PRIMARY KEY,
			event_id TEXT NOT NULL,
			$OWNER_ACCOUNT,
			title TEXT NOT NULL,
			artist TEXT,
			percent INTEGER NOT NULL,
			at INTEGER NOT NULL,
			status TEXT NOT NULL,
			tx_id TEXT,
			queued INTEGER NOT NULL CHECK (queued IN (0, 1)),
			video_id TEXT NOT NULL,
			queue_op_id TEXT,
			service TEXT
		)
		""",
		"CREATE INDEX history_owner_at ON $HISTORY (owner, at DESC)",
		// Partial, so the many rows that never came from the queue do not
		// collide on NULL — and one queue operation can only ever be one row per
		// account, which is what lets an outcome update its row in place.
		"CREATE UNIQUE INDEX history_owner_queue_op ON $HISTORY (owner, queue_op_id) " +
			"WHERE queue_op_id IS NOT NULL",
		"""
		CREATE TABLE $NOT_LOGGED (
			row_id TEXT NOT NULL PRIMARY KEY,
			$OWNER_ANY,
			title TEXT NOT NULL,
			artist TEXT,
			reason TEXT NOT NULL,
			at INTEGER NOT NULL,
			played INTEGER NOT NULL,
			duration INTEGER,
			video_id TEXT
		)
		""",
		"CREATE INDEX not_logged_owner_at ON $NOT_LOGGED (owner, at DESC)",
		"""
		CREATE TABLE $SNAP_DRAFT (
			$OWNER_ANY,
			event_id TEXT NOT NULL,
			text TEXT NOT NULL,
			updated_at INTEGER NOT NULL,
			PRIMARY KEY (owner, event_id)
		)
		""",
		"""
		CREATE TABLE $POSTED_SNAP_CACHE (
			$OWNER_ACCOUNT,
			content_id TEXT NOT NULL,
			chain_body TEXT NOT NULL,
			record_body TEXT NOT NULL,
			at INTEGER NOT NULL,
			PRIMARY KEY (owner, content_id)
		)
		""",
		// Oldest-first eviction within one account.
		"CREATE INDEX posted_snap_cache_owner_at ON $POSTED_SNAP_CACHE (owner, at)",
		"""
		CREATE TABLE $SNAP_REFRESH (
			$OWNER_ACCOUNT PRIMARY KEY,
			last_refreshed_ms INTEGER NOT NULL
		)
		""",
		"""
		CREATE TABLE $THREAD_PREVIEW (
			$OWNER_ANY,
			root_id TEXT NOT NULL,
			total INTEGER NOT NULL CHECK (total >= 0),
			items_json TEXT NOT NULL,
			at INTEGER NOT NULL,
			PRIMARY KEY (owner, root_id)
		)
		""",
		"CREATE INDEX thread_preview_owner_at ON $THREAD_PREVIEW (owner, at)",
		// The "already seen" fact on its own, never deleted. Kept apart from
		// [REPLY_NOTICE] so that pruning display data can never make an old
		// reply look new.
		"""
		CREATE TABLE $SEEN_REPLY (
			$OWNER_ACCOUNT,
			content_id TEXT NOT NULL,
			seeded INTEGER NOT NULL CHECK (seeded IN (0, 1)),
			first_seen_at INTEGER NOT NULL,
			PRIMARY KEY (owner, content_id)
		)
		""",
		"""
		CREATE TABLE $REPLY_NOTICE (
			$OWNER_ACCOUNT,
			content_id TEXT NOT NULL,
			root_id TEXT NOT NULL,
			text TEXT NOT NULL,
			created INTEGER,
			read INTEGER NOT NULL CHECK (read IN (0, 1)),
			at INTEGER NOT NULL,
			PRIMARY KEY (owner, content_id)
		)
		""",
		// Unread listing and the answered-oldest-first pruning order.
		"CREATE INDEX reply_notice_owner_read_at ON $REPLY_NOTICE (owner, read, at)",
		"""
		CREATE TABLE $THREAD_SEED (
			$OWNER_ACCOUNT,
			root_id TEXT NOT NULL,
			seeded_at INTEGER NOT NULL,
			PRIMARY KEY (owner, root_id)
		)
		""",
		"""
		CREATE TABLE $SYNC_CHECKPOINT (
			$OWNER_ANY,
			scope TEXT NOT NULL,
			value TEXT NOT NULL,
			updated_at INTEGER NOT NULL,
			PRIMARY KEY (owner, scope)
		)
		""",
		// Bookkeeping for importing the SharedPreferences stores, one row per
		// legacy source. Not account data, so no owner. `parser_version` lets a
		// source that was UNREADABLE under one parser be retried by a newer one
		// without re-reading an unchanged blob on every launch. `generation` is
		// the newest legacy write generation this database has absorbed: a blob
		// with a higher one was written by a run that could not open the
		// database, and is newer than what it holds.
		"""
		CREATE TABLE $LEGACY_IMPORT (
			source TEXT NOT NULL PRIMARY KEY,
			state TEXT NOT NULL CHECK (state IN (${LegacyImportState.sqlList()})),
			parser_version INTEGER NOT NULL,
			fingerprint TEXT,
			generation INTEGER NOT NULL CHECK (generation >= 0),
			seen INTEGER NOT NULL CHECK (seen >= 0),
			imported INTEGER NOT NULL CHECK (imported >= 0),
			rejected INTEGER NOT NULL CHECK (rejected >= 0),
			updated_at INTEGER NOT NULL
		)
		""",
	).map { it.trimIndent() }

	/**
	 * Version 2 (Issue #47): the My Snaps catalog — one row per root Snap an
	 * account is known to have published, keyed by its permanent Hive identity.
	 *
	 * Presentation state only. A row says "show this Snap under My Snaps" and
	 * nothing more: no write path reads it, and it can neither authorize nor
	 * block a Hive operation. The root Snap publication records stay where they
	 * are and remain the only thing that decides a write.
	 *
	 *  - **Keyed by `(owner, author, permlink)`.** Two local references to one
	 *    Hive object are one row. The author must be the owner: My Snaps lists
	 *    an account's own Snaps and nobody else's, and the schema says so.
	 *  - **No row limit.** History keeps 50 rows and the posted-Snap cache 100;
	 *    this table keeps every row, so a Snap stays reachable after its History
	 *    row has gone. Readers page it through [MY_SNAP_INDEX] instead.
	 *  - **Media context is optional.** `video_id`, `title`, `artist` and
	 *    `service` are filled only from what is known locally and are never
	 *    guessed; `user_text` is the last known words, for display.
	 */
	val UPGRADE_V2: List<String> = listOf(
		"""
		CREATE TABLE $MY_SNAP (
			$OWNER_ACCOUNT,
			author TEXT NOT NULL CHECK (author = owner),
			permlink TEXT NOT NULL CHECK (length(permlink) > 0),
			created_at INTEGER NOT NULL,
			event_id TEXT,
			video_id TEXT,
			title TEXT,
			artist TEXT,
			service TEXT,
			user_text TEXT,
			indexed_at INTEGER NOT NULL,
			updated_at INTEGER NOT NULL,
			PRIMARY KEY (owner, author, permlink)
		)
		""",
		// Newest first within one account, with the permlink as the tiebreak a
		// keyset page continues from.
		"CREATE INDEX $MY_SNAP_INDEX ON $MY_SNAP (owner, created_at DESC, permlink DESC)",
	).map { it.trimIndent() }

	const val MY_SNAP_INDEX = "my_snap_owner_created"

	/**
	 * Version 3 (Issue #47): a durable record that Hive **proved** one root
	 * Snap deleted, so My Snaps never lists it again.
	 *
	 * Written only by the existing deletion path, after the chain has shown
	 * the exact object gone and before the Snap's confirmed publication record
	 * is retired — in the same transaction that removes its catalog row. A
	 * failed read, a missing local record or a tap is never a tombstone.
	 *
	 * Keyed exactly like [MY_SNAP]. Presentation state like the catalog: it
	 * cannot authorize, retry or block any Hive operation.
	 */
	val UPGRADE_V3: List<String> = listOf(
		"""
		CREATE TABLE $MY_SNAP_TOMBSTONE (
			$OWNER_ACCOUNT,
			author TEXT NOT NULL CHECK (author = owner),
			permlink TEXT NOT NULL CHECK (length(permlink) > 0),
			tx_id TEXT,
			deleted_at INTEGER NOT NULL,
			PRIMARY KEY (owner, author, permlink)
		)
		""",
	).map { it.trimIndent() }

	/** Every table the current version holds, in creation order. */
	val TABLES: List<String> = listOf(
		HISTORY,
		NOT_LOGGED,
		SNAP_DRAFT,
		POSTED_SNAP_CACHE,
		SNAP_REFRESH,
		THREAD_PREVIEW,
		SEEN_REPLY,
		REPLY_NOTICE,
		THREAD_SEED,
		SYNC_CHECKPOINT,
		LEGACY_IMPORT,
		MY_SNAP,
		MY_SNAP_TOMBSTONE,
	)

	/**
	 * The statements that move a database from [from] to [to], in order.
	 *
	 * Additive only: each step creates, and none drops, deletes or rewrites a
	 * row an earlier version holds. Any request outside the known versions is
	 * an error rather than an empty list: an upgrade this code does not know
	 * how to perform must stop the open, never fall through to a destructive
	 * recreate that would throw away the user's rows.
	 */
	fun upgradeSteps(from: Int, to: Int): List<String> {
		require(from in 1 until to && to <= VERSION) {
			"no upgrade path from local database version $from to $to"
		}
		return (from + 1..to).flatMap { version ->
			when (version) {
				2 -> UPGRADE_V2
				3 -> UPGRADE_V3
				else -> throw IllegalArgumentException("no upgrade step to local database version $version")
			}
		}
	}
}

/** Where one legacy SharedPreferences source has got to. */
internal enum class LegacyImportState {
	/** Rows were written; not yet compared against the source. */
	IMPORTED,

	/** Every decoded source row was found in the database unchanged. */
	VERIFIED,

	/** The source could not be parsed as a whole. It is kept, never retired. */
	UNREADABLE;

	companion object {
		fun sqlList(): String = entries.joinToString(", ") { "'${it.name}'" }
	}
}

/**
 * How an account is spelled in the `owner` column.
 *
 * The vault saves usernames lowercased and trimmed; History already compares
 * owners case-insensitively. Normalising once here means a lookup by owner is
 * a plain equality, and the schema's CHECK refuses anything that was not.
 */
internal object LocalOwner {

	/** The signed-out device. Matches the anonymous draft-key prefix already in use. */
	const val SIGNED_OUT = "-"

	/** A Hive account's owner value, or null when there is no account. */
	fun account(name: String?): String? =
		name?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it != SIGNED_OUT }

	/** An owner for a row that the signed-out device may also own. */
	fun of(name: String?): String = account(name) ?: SIGNED_OUT
}
