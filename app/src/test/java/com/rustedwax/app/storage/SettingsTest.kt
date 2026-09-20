package com.rustedwax.app.storage

import com.rustedwax.app.scrobble.SettingsPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTest {

	private class MemoryStore(seed: Map<String, Any> = emptyMap()) : SettingsStore {
		private val values = seed.toMutableMap()

		override fun getBoolean(key: String, default: Boolean): Boolean =
			values[key] as? Boolean ?: default

		override fun putBoolean(key: String, value: Boolean) {
			values[key] = value
		}

		override fun getInt(key: String, default: Int): Int = values[key] as? Int ?: default

		override fun putInt(key: String, value: Int) {
			values[key] = value
		}

		override fun getString(key: String, default: String): String =
			values[key] as? String ?: default

		override fun putString(key: String, value: String) {
			values[key] = value
		}

		override fun contains(key: String): Boolean = key in values

		override fun isEmpty(): Boolean = values.isEmpty()

		override fun remove(key: String) {
			values -= key
		}
	}

	/** An install that already exists: something, anything, has been stored. */
	private fun upgrading(vararg stored: Pair<String, Any>) =
		MemoryStore(mapOf("monitoringEnabled" to true) + stored)

	@Test
	fun `automatic write generation advances only on real toggle transitions`() {
		val policy = SettingsPolicy(Settings(upgrading("autoScrobble" to true)))
		val initial = policy.automaticWriteAuthorization

		policy.autoScrobble = true
		assertEquals(initial, policy.automaticWriteAuthorization)

		policy.autoScrobble = false
		assertFalse(policy.automaticWriteAuthorization.enabledAtStart)
		assertEquals(initial.generation + 1, policy.automaticWriteAuthorization.generation)

		policy.autoScrobble = true
		assertTrue(policy.automaticWriteAuthorization.enabledAtStart)
		assertEquals(initial.generation + 2, policy.automaticWriteAuthorization.generation)
	}

	@Test
	fun `transport commit accepts only the current uninterrupted ON generation`() {
		val policy = SettingsPolicy(Settings(upgrading("autoScrobble" to true)))
		val firstOn = policy.automaticWriteAuthorization
		assertTrue(policy.tryCommitAutomaticWrite(firstOn))

		policy.autoScrobble = false
		assertFalse(policy.tryCommitAutomaticWrite(firstOn))
		assertFalse(policy.tryCommitAutomaticWrite(policy.automaticWriteAuthorization))

		policy.autoScrobble = true
		assertFalse(policy.tryCommitAutomaticWrite(firstOn))
		assertTrue(policy.tryCommitAutomaticWrite(policy.automaticWriteAuthorization))
	}

	@Test
	fun `a fresh install starts with the v0-11-0 defaults`() {
		val settings = Settings(MemoryStore())

		assertTrue("YouTube scrobbling", settings.youTubeScrobbling)
		assertFalse("event log", settings.eventLogging)
		assertTrue("disable Shorts", settings.disableShorts)
		// Unchanged, and the reason the YouTube default is safe: a fresh install
		// watches, but broadcasts nothing until the user opts in with a key.
		assertFalse("auto-scrobble", settings.autoScrobble)
		assertFalse("developer mode", settings.developerMode)
	}

	/**
	 * Developer mode is unlocked by seven taps on the version, so it has to
	 * survive being backgrounded — someone who unlocked it and went to look at a
	 * Short would otherwise find it locked again on their way back.
	 */
	@Test
	fun `developer mode is off until it is unlocked, and then it stays unlocked`() {
		val store = MemoryStore()
		assertFalse(Settings(store).developerMode)

		Settings(store).developerMode = true
		assertTrue(Settings(store).developerMode)

		Settings(store).developerMode = false
		assertFalse(Settings(store).developerMode)
	}

	@Test
	fun `the mechanisms folded into the master switch follow it`() {
		val store = MemoryStore()
		val settings = Settings(store)
		assertTrue(settings.enrichment)
		assertTrue(settings.pipInference)

		settings.youTubeScrobbling = false
		assertFalse("lookups", settings.enrichment)
		assertFalse("picture-in-picture", settings.pipInference)

		// And it survives a reload rather than only holding in memory.
		assertFalse(Settings(store).enrichment)
	}

	@Test
	fun `upgrading keeps the old defaults for anyone who never chose`() {
		val store = upgrading()
		val settings = Settings(store)

		assertTrue("event log was on before v0.11.0", settings.eventLogging)
		assertFalse("Shorts scrobbled before v0.11.0", settings.disableShorts)
	}

	@Test
	fun `upgrading never overwrites a choice the user did make`() {
		val settings = Settings(
			upgrading(
				"eventLogging" to false,
				"disableShorts" to true,
			),
		)

		assertFalse(settings.eventLogging)
		assertTrue(settings.disableShorts)
	}

	// region v2 — the short-clip floor stops being a setting
	//
	// `Short clips` only ever decided whether a *verified public* Short could
	// use the 10-second floor. Whether Shorts count at all is `Disable Shorts`,
	// which is the question people actually hold an opinion about — and the two
	// sitting next to each other is how somebody ends up certain they turned
	// Shorts off while Shorts keep landing on a ledger that cannot be edited.

	@Test
	fun `a fresh install stores no short-clip policy at all`() {
		val store = MemoryStore()
		Settings(store)

		assertFalse("a removed setting was written on a fresh install", store.contains("shortClipScrobbling"))
	}

	@Test
	fun `the stored short-clip policy is deleted on upgrade`() {
		val store = upgrading("shortClipScrobbling" to true)
		Settings(store)

		assertFalse(store.contains("shortClipScrobbling"))
	}

	/**
	 * The one Shorts answer that *is* a policy has to come through untouched:
	 * an install that deliberately let Shorts scrobble must not have them
	 * switched off by a migration that was only removing the other switch.
	 */
	@Test
	fun `removing the short-clip policy does not disturb Disable Shorts`() {
		assertFalse(
			Settings(upgrading("disableShorts" to false, "shortClipScrobbling" to true))
				.disableShorts,
		)
		assertTrue(
			Settings(upgrading("disableShorts" to true, "shortClipScrobbling" to false))
				.disableShorts,
		)
	}

	@Test
	fun `an install that never reached v1 still gets both migrations`() {
		val store = upgrading()
		val settings = Settings(store)

		assertTrue("the v1 default was not pinned", settings.eventLogging)
		assertFalse("the v1 default was not pinned", settings.disableShorts)
		assertFalse("the v2 removal did not run", store.contains("shortClipScrobbling"))
		assertEquals(2, store.getInt("settingsSchemaVersion", 0))
	}

	@Test
	fun `an install already at v1 is moved to v2 without re-pinning v1 defaults`() {
		val store = upgrading("settingsSchemaVersion" to 1, "shortClipScrobbling" to true)
		val settings = Settings(store)

		assertFalse("the removal did not run", store.contains("shortClipScrobbling"))
		assertEquals(2, store.getInt("settingsSchemaVersion", 0))
		// v1 already answered these; v2 must not answer them again.
		assertFalse("a v1 default was re-pinned over a v1 install", store.contains("eventLogging"))
	}

	@Test
	fun `the migration version is two`() {
		assertEquals(2, SettingsMigration.CURRENT_VERSION)
	}

	// endregion

	/**
	 * Browser YouTube was never a setting, so every existing install was
	 * scrobbling YouTube from at least one surface and the unified switch
	 * migrates on. See [SettingsMigration] for why the alternative — off for
	 * anyone whose *native* switches were off — is the worse failure.
	 */
	@Test
	fun `the unified YouTube switch migrates on even with both native sources off`() {
		val settings = Settings(
			upgrading("nativeYouTube" to false, "nativeYouTubeMusic" to false),
		)

		assertTrue(settings.youTubeScrobbling)
	}

	@Test
	fun `the migration runs once and does not re-decide afterwards`() {
		val store = upgrading()
		Settings(store).youTubeScrobbling = false
		Settings(store).eventLogging = false

		val reloaded = Settings(store)
		assertFalse("a later off must survive the next construction", reloaded.youTubeScrobbling)
		assertFalse(reloaded.eventLogging)
	}

	@Test
	fun `the diagnostic logging deadline is deleted rather than left behind`() {
		val store = upgrading("diagnosticLoggingUntil" to "99999999999")
		Settings(store)

		assertFalse(store.contains("diagnosticLoggingUntil"))
	}

	@Test
	fun `the superseded lookup and PiP keys are deleted`() {
		val store = upgrading("enrichment" to false, "pipInference" to false)
		val settings = Settings(store)

		assertFalse(store.contains("enrichment"))
		assertFalse(store.contains("pipInference"))
		// And the stored `false` can no longer contradict the switch in charge.
		assertTrue(settings.enrichment)
	}

	@Test
	fun `the pre-app-list native opt-ins are still readable for the app list`() {
		val settings = Settings(upgrading("nativeYouTube" to true))

		assertTrue(settings.nativeYouTube)
		assertFalse(settings.nativeYouTubeMusic)
	}

	@Test
	fun `the schema version is stamped so the migration cannot run twice`() {
		val store = MemoryStore()
		Settings(store)

		assertEquals(SettingsMigration.CURRENT_VERSION, store.getInt("settingsSchemaVersion", 0))
	}

	// ── default Like strength ──────────────────────────────────────────

	/**
	 * The gentlest setting the slider offers, not a middle value. Spending as
	 * little of somebody's voting power as possible until they ask otherwise is
	 * the right default for an irreversible thing that costs them something.
	 */
	@Test
	fun `a fresh install Likes at ten percent`() {
		assertEquals(10, Settings(MemoryStore()).likePercent)
	}

	/** An upgrading install has never chosen one either, so it gets the same. */
	@Test
	fun `an upgrading install also starts at ten percent`() {
		assertEquals(10, Settings(upgrading()).likePercent)
	}

	@Test
	fun `a chosen Like strength is stored and read back exactly`() {
		val settings = Settings(upgrading())
		listOf(10, 11, 37, 63, 99, 100).forEach {
			settings.likePercent = it
			assertEquals("a $it% Like must round-trip", it, settings.likePercent)
		}
	}

	/**
	 * Clamped on the way in **and** on the way out. The stored value outlives
	 * the slider that wrote it, so a hand-edited preference file or a value from
	 * an older build must not become a 0% vote — which Hive reads as *removing*
	 * a vote — or one stronger than anybody chose.
	 */
	@Test
	fun `a Like strength below the floor is clamped on write`() {
		val settings = Settings(upgrading())
		listOf(9, 0, -1, -1000).forEach {
			settings.likePercent = it
			assertEquals("$it must clamp up to the floor", 10, settings.likePercent)
		}
	}

	@Test
	fun `a Like strength above full is clamped on write`() {
		val settings = Settings(upgrading())
		listOf(101, 1000, Int.MAX_VALUE).forEach {
			settings.likePercent = it
			assertEquals("$it must clamp down to full", 100, settings.likePercent)
		}
	}

	@Test
	fun `a stored value outside the range is clamped on read`() {
		assertEquals(10, Settings(upgrading("likeStrengthPercent" to 0)).likePercent)
		assertEquals(10, Settings(upgrading("likeStrengthPercent" to -5)).likePercent)
		assertEquals(100, Settings(upgrading("likeStrengthPercent" to 5000)).likePercent)
	}

	/** A new key, so the migration has nothing to preserve and must not invent one. */
	@Test
	fun `the Like strength is not written until somebody chooses one`() {
		val store = MemoryStore()
		Settings(store).likePercent
		assertFalse(
			"reading the default must not store it",
			store.contains("likeStrengthPercent"),
		)
	}

}
