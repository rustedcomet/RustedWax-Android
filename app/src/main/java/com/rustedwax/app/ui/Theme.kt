package com.rustedwax.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The four colours the design calls out by name, plus the two shades of pink
 * the interaction needs.
 *
 * Everything else in both schemes is derived from these — a card, a divider or
 * a disabled label is a neutral pulled toward the same warm hue, never a new
 * colour invented at the call site.
 */
object Wax {
	/** Hot pink / magenta: the accent, and the only strong colour in the app. */
	val HotPink = Color(0xFFEC3F75)

	/** The same pink with the lights off — legible on near-black. */
	val HotPinkLight = Color(0xFFFF5C8F)

	/**
	 * Where a pressed button lands.
	 *
	 * A white surface animating to this is the app's press feedback: the ripple
	 * says "the tap registered", this says "on this control".
	 */
	val PinkWash = Color(0xFFFDE3EC)

	/** [PinkWash]'s counterpart on a dark surface. */
	val PinkWashDark = Color(0xFF3E1226)

	/** Ivory cream: the logo's drip, and the body text in dark mode. */
	val IvoryCream = Color(0xFFF5E3CB)

	/** Success green: confirmed in a block, and nothing else. */
	val SuccessGreen = Color(0xFF26A25A)

	/** Success green on near-black. */
	val SuccessGreenLight = Color(0xFF4CC17E)

	/** Muted gray: secondary text, and a value the app does not have. */
	val MutedGray = Color(0xFFA5A3A4)

	/** Queued rather than sent — warmer than the pink, quieter than the green. */
	val Amber = Color(0xFFD98324)
	val AmberLight = Color(0xFFE9A24B)
}

private val LightScheme = lightColorScheme(
	primary = Wax.HotPink,
	onPrimary = Color.White,
	primaryContainer = Wax.PinkWash,
	onPrimaryContainer = Color(0xFF7B0B33),
	inversePrimary = Wax.HotPinkLight,

	secondary = Color(0xFF9C7A50),
	onSecondary = Color.White,
	secondaryContainer = Wax.IvoryCream,
	onSecondaryContainer = Color(0xFF473320),

	tertiary = Wax.SuccessGreen,
	onTertiary = Color.White,
	tertiaryContainer = Color(0xFFD9F2E2),
	onTertiaryContainer = Color(0xFF0B4526),

	background = Color(0xFFFAF8F6),
	onBackground = Color(0xFF1B181C),
	surface = Color(0xFFFEFDFB),
	onSurface = Color(0xFF1B181C),
	surfaceVariant = Color(0xFFF3EFEC),
	onSurfaceVariant = Color(0xFF6E6A6C),
	surfaceTint = Wax.HotPink,

	surfaceContainerLowest = Color(0xFFFFFFFF),
	surfaceContainerLow = Color(0xFFFEFDFB),
	surfaceContainer = Color(0xFFF8F5F2),
	surfaceContainerHigh = Color(0xFFF2EEEB),
	surfaceContainerHighest = Color(0xFFEDE8E4),
	surfaceBright = Color(0xFFFFFFFF),
	surfaceDim = Color(0xFFE9E4E0),

	outline = Color(0xFFD8D2CE),
	outlineVariant = Color(0xFFEAE4E0),
	scrim = Color(0xFF000000),

	error = Color(0xFFD92D53),
	onError = Color.White,
	errorContainer = Color(0xFFFDE3E9),
	onErrorContainer = Color(0xFF6E0B22),
)

private val DarkScheme = darkColorScheme(
	primary = Wax.HotPinkLight,
	onPrimary = Color(0xFF33001A),
	primaryContainer = Wax.PinkWashDark,
	onPrimaryContainer = Color(0xFFFFD9E3),
	inversePrimary = Wax.HotPink,

	secondary = Color(0xFFE0C9A6),
	onSecondary = Color(0xFF3A2A14),
	secondaryContainer = Color(0xFF4A3620),
	onSecondaryContainer = Wax.IvoryCream,

	tertiary = Wax.SuccessGreenLight,
	onTertiary = Color(0xFF00381C),
	tertiaryContainer = Color(0xFF11402A),
	onTertiaryContainer = Color(0xFFB9EFCE),

	background = Color(0xFF060508),
	onBackground = Color(0xFFEFEAE3),
	surface = Color(0xFF0D0C10),
	onSurface = Color(0xFFEFEAE3),
	surfaceVariant = Color(0xFF1A181E),
	onSurfaceVariant = Wax.MutedGray,
	surfaceTint = Wax.HotPinkLight,

	surfaceContainerLowest = Color(0xFF040306),
	surfaceContainerLow = Color(0xFF0D0C10),
	surfaceContainer = Color(0xFF131218),
	surfaceContainerHigh = Color(0xFF1B191F),
	surfaceContainerHighest = Color(0xFF232028),
	surfaceBright = Color(0xFF2A2730),
	surfaceDim = Color(0xFF060508),

	outline = Color(0xFF3B3742),
	outlineVariant = Color(0xFF262330),
	scrim = Color(0xFF000000),

	error = Color(0xFFFF6B85),
	onError = Color(0xFF3A0410),
	errorContainer = Color(0xFF4E1020),
	onErrorContainer = Color(0xFFFFD9DF),
)

/**
 * Rounded, but not pills. The mockup's cards and chips are the same family of
 * corner — a chip is just a small card — so one scale drives both.
 */
private val WaxShapes = Shapes(
	extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
	small = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
	medium = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
	large = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
	extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
)

/**
 * Default Material typography with the two weights the design leans on: row
 * titles are noticeably heavier than their body text, which is what lets a
 * settings card be scanned without reading it.
 */
private val WaxTypography = Typography().run {
	copy(
		titleLarge = titleLarge.copy(fontWeight = FontWeight.Bold),
		titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
		titleSmall = titleSmall.copy(fontWeight = FontWeight.SemiBold),
		labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold),
	)
}

/** What the user chose in Settings, as opposed to what the system is doing. */
enum class ThemeChoice { SYSTEM, LIGHT, DARK }

/**
 * The stored appearance, read the same way by every window the app owns.
 *
 * `MainActivity` and the YouTube sign-in activity both use this resolver, so a
 * stored app choice is interpreted identically across windows.
 */
object AppTheme {

	/**
	 * An unrecognised stored value follows the system rather than refusing to
	 * draw: this is the app's appearance, not one of its rules. A downgrade or a
	 * hand-edited preference should not be able to stop a window opening.
	 */
	fun choice(stored: String?): ThemeChoice =
		runCatching { ThemeChoice.valueOf(stored.orEmpty()) }.getOrDefault(ThemeChoice.SYSTEM)
}

/**
 * True when the resolved scheme is the dark one.
 *
 * Some colours can't come from [MaterialTheme.colorScheme] — a press wash, the
 * amber of a queued row — and still have to flip. Reading the boolean is
 * cheaper and less error-prone than comparing a scheme against a background.
 */
val LocalWaxDark = staticCompositionLocalOf { false }

/**
 * A RustedWax window: the theme, and the frame around it.
 *
 * Every activity the app owns goes through here. The sign-in screen used to
 * compose a bare `MaterialTheme {}` of its own, so a device set to the RustedWax
 * dark theme opened it in default Material purple-on-white — the same app, two
 * appearances, one of them nobody chose. Google's own page inside the WebView is
 * Google's and is deliberately left alone; everything RustedWax draws around it
 * is not.
 *
 * The window and the system bars are painted here too, because they are not part
 * of the colour scheme and switching to dark otherwise leaves a white frame
 * around a black app.
 */
@Composable
fun RustedWaxWindow(
	window: android.view.Window,
	choice: ThemeChoice,
	content: @Composable () -> Unit,
) {
	RustedWaxTheme(choice) {
		val background = MaterialTheme.colorScheme.background
		val lightBars = !LocalWaxDark.current
		androidx.compose.runtime.SideEffect {
			window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(background.toArgb()))
			// Deprecated as of API 35, where edge-to-edge is enforced and these
			// are ignored; still the only way to colour the bars on the Android
			// 11–14 devices this also runs on. The window background above is
			// what covers the API 35 case.
			@Suppress("DEPRECATION")
			run {
				window.statusBarColor = background.toArgb()
				window.navigationBarColor = background.toArgb()
			}
			androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
				isAppearanceLightStatusBars = lightBars
				isAppearanceLightNavigationBars = lightBars
			}
		}
		content()
	}
}

@Composable
fun RustedWaxTheme(
	choice: ThemeChoice = ThemeChoice.SYSTEM,
	content: @Composable () -> Unit,
) {
	val dark = when (choice) {
		ThemeChoice.SYSTEM -> isSystemInDarkTheme()
		ThemeChoice.LIGHT -> false
		ThemeChoice.DARK -> true
	}
	androidx.compose.runtime.CompositionLocalProvider(LocalWaxDark provides dark) {
		MaterialTheme(
			colorScheme = if (dark) DarkScheme else LightScheme,
			shapes = WaxShapes,
			typography = WaxTypography,
			content = content,
		)
	}
}
