package com.rustedwax.app.ui

/**
 * The settings screen's rows, in the order they are drawn.
 *
 * The order is a product decision rather than an accident of which card was
 * added last, so it is stated once, here, and asserted in `SettingsOutlineTest`.
 * `ScrobbleControls` walks this list; it does not keep a second copy.
 */
enum class SettingsRow {
	/**
	 * First, because it is the switch people open Settings for.
	 *
	 * It used to be fourth, under two rows about *evidence* and a card that
	 * restated what was being watched — so the question "is this thing actually
	 * scrobbling?" was answered below the fold on a 720×1600 phone.
	 */
	AUTOMATIC_SCROBBLING,
	YOUTUBE_SCROBBLING,
	BROWSER_EVIDENCE,

	/** The two account rows, together, because they are read together. */
	WATCH_HISTORY,
	HIVE_ACCOUNT,

	/**
	 * Directly under the Hive account, because it is a preference *about* that
	 * account's voting power and is meaningless without a key saved.
	 */
	SNAPS_AND_LIKES,

	PICTURE_IN_PICTURE,
	QUEUE,
	APPEARANCE,

	/**
	 * The hidden tier, and everything in it.
	 *
	 * This replaced `ADVANCED` outright. Advanced was a disclosure triangle over
	 * four privacy switches, a Shorts rule and the event log — a second settings
	 * screen anybody could open, holding questions almost nobody has. What is
	 * left of it that is still offered lives here, behind seven taps on the
	 * version, together with the foreground-Shorts grant and the connection
	 * check.
	 */
	DEVELOPER_MODE,
	FOREGROUND_SHORTS,

	/** Always last, and the only way into [DEVELOPER_MODE]. */
	ABOUT,
}

object SettingsOutline {

	/**
	 * The rows to draw right now.
	 *
	 * Four are conditional. Usage access is a grant that cannot be asked for in
	 * a dialog, so it appears only while it is missing; the queue row appears
	 * only while something is waiting to send; and the two developer rows appear
	 * only once the tier has been unlocked.
	 *
	 * The card that used to head this list — "Watching YouTube in Brave and
	 * Chrome, the YouTube app, and YouTube Music" — is gone. It restated the
	 * switch two rows below it, and the monitoring status it also carried was
	 * already in the strip that is visible from every destination, which is
	 * where a stop control belongs. See [MonitoringStatus].
	 */
	fun rows(
		usageAccessGranted: Boolean,
		queuedCount: Int,
		developerMode: Boolean,
	): List<SettingsRow> = SettingsRow.entries.filter { row ->
		when (row) {
			SettingsRow.PICTURE_IN_PICTURE -> !usageAccessGranted
			SettingsRow.QUEUE -> queuedCount > 0
			SettingsRow.DEVELOPER_MODE, SettingsRow.FOREGROUND_SHORTS -> developerMode
			else -> true
		}
	}

	/** What the row is called on screen. */
	fun title(row: SettingsRow): String = when (row) {
		SettingsRow.AUTOMATIC_SCROBBLING -> "Automatic scrobbling"
		SettingsRow.YOUTUBE_SCROBBLING -> "YouTube scrobbling"
		SettingsRow.FOREGROUND_SHORTS -> "Foreground Shorts evidence"
		SettingsRow.BROWSER_EVIDENCE -> "Browser evidence access"
		SettingsRow.WATCH_HISTORY -> "YouTube watch history"
		SettingsRow.HIVE_ACCOUNT -> "Hive account"
		SettingsRow.SNAPS_AND_LIKES -> "Snaps & Likes"
		SettingsRow.PICTURE_IN_PICTURE -> "Picture-in-picture time"
		SettingsRow.DEVELOPER_MODE -> "Developer mode"
		SettingsRow.APPEARANCE -> "Appearance"
		SettingsRow.QUEUE -> "Waiting to send"
		SettingsRow.ABOUT -> "About"
	}

	/**
	 * What the YouTube switch says it does.
	 *
	 * Names the two apps it is *for* and the two things it does that someone
	 * would want disclosed — a metadata lookup, and counting time spent in a
	 * floating window. It deliberately does not enumerate every surface the
	 * switch happens to gate; a settings row is a description of the feature,
	 * not an inventory of its implementation.
	 */
	fun youTubeScrobblingBody(on: Boolean): String = if (on) {
		"On — YouTube app and YouTube Music. RustedWax may look up YouTube " +
			"metadata when needed to verify what played. Picture-in-picture " +
			"time is counted where Usage Access allows it."
	} else {
		"Off — nothing YouTube is watched. Anything part-played is dropped " +
			"rather than finished."
	}
}

/**
 * The one line that has to be visible from every destination.
 *
 * ## Why there is no headline while it runs
 *
 * It used to read **Monitoring**, with `Scrobbling at 60% played` underneath.
 * The second line is the one that says something — it states the rule the app
 * runs on — and the word above it only described the app watching, which is not
 * what a person came to find out and reads as surveillance for no benefit. So
 * while the app is running there is no headline at all.
 *
 * `Stopped` keeps one, because that *is* the thing to notice: nothing is being
 * read, and until it is started again nothing will be.
 */
object MonitoringStatus {

	/** Null while running — see the class note. */
	fun headline(monitoring: Boolean): String? = if (monitoring) null else "Stopped"

	fun detail(
		monitoring: Boolean,
		hasKey: Boolean,
		autoScrobble: Boolean,
		thresholdPercent: Int,
		queuedCount: Int,
	): String = buildString {
		append(
			when {
				!monitoring -> "Nothing is being read"
				!hasKey -> "Add a Hive key to scrobble"
				autoScrobble -> "Scrobbling at $thresholdPercent% played"
				else -> "Automatic scrobbling is off"
			},
		)
		if (queuedCount > 0) append(" · $queuedCount waiting to send")
	}
}
