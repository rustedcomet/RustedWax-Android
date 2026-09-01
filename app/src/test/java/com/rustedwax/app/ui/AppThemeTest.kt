package com.rustedwax.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The stored appearance, resolved once for every window the app owns.
 *
 * The sign-in screen used to compose a bare `MaterialTheme {}`, so a device set
 * to the RustedWax dark theme opened Google's sign-in behind default Material
 * purple-on-white chrome. The resolver is shared so there is one answer rather
 * than one per activity.
 */
class AppThemeTest {

	@Test
	fun `each stored choice resolves to itself`() {
		assertEquals(ThemeChoice.SYSTEM, AppTheme.choice("SYSTEM"))
		assertEquals(ThemeChoice.LIGHT, AppTheme.choice("LIGHT"))
		assertEquals(ThemeChoice.DARK, AppTheme.choice("DARK"))
	}

	@Test
	fun `every enum value survives a round trip through storage`() {
		ThemeChoice.entries.forEach { assertEquals(it, AppTheme.choice(it.name)) }
	}

	@Test
	fun `an unreadable stored value follows the system rather than refusing`() {
		assertEquals(ThemeChoice.SYSTEM, AppTheme.choice(null))
		assertEquals(ThemeChoice.SYSTEM, AppTheme.choice(""))
		assertEquals(ThemeChoice.SYSTEM, AppTheme.choice("dark"))
		assertEquals(ThemeChoice.SYSTEM, AppTheme.choice("MIDNIGHT"))
	}
}
