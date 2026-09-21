package com.rustedwax.app.architecture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Now card's v1.1 presentation, and the three things it must not quietly
 * give up.
 *
 * Narrow on purpose, in the same spirit as [HistoryCardPresentationWiringTest]:
 * spacing, colours and wording are free to move and nothing here mentions them.
 * What is pinned is the small set of decisions a later edit could undo without
 * anything looking wrong on screen — the list key being the logical listen, the
 * banner being History's geometry rather than a second one, and the badge being
 * the service rather than the app.
 */
class NowCardPresentationWiringTest {

	private val root: File by lazy {
		generateSequence(File(checkNotNull(System.getProperty("user.dir"))).absoluteFile) {
			it.parentFile
		}.firstOrNull { File(it, "settings.gradle.kts").isFile }
			?: error("repository root was not found")
	}

	private fun text(path: String): String = File(root, path).let {
		assertTrue("production source missing: $path", it.isFile)
		it.readText()
	}

	private fun stripComments(source: String): String = source
		.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
		.lines()
		.joinToString("\n") { it.substringBefore("//") }

	private val screen: String get() =
		stripComments(text("app/src/main/java/com/rustedwax/app/ui/MainScreen.kt"))

	private val thumbnails: String get() =
		stripComments(text("app/src/main/java/com/rustedwax/app/ui/Thumbnails.kt"))

	/**
	 * One card per logical listen, keyed by the identity the engine already
	 * uses for that question.
	 *
	 * The assertion worth having is the negative one. Package-plus-title reads
	 * as a reasonable key right up until two sessions in one source share a
	 * title — or until two are still being read and their titles are both null,
	 * at which point every one of them is the same key and the list collapses
	 * sessions that have nothing to do with each other. It also *changes* when
	 * the metadata callback lands, so a card was a different card mid-listen.
	 */
	@Test
	fun `Now is keyed by the logical listen, not by package and title`() {
		assertTrue(
			"the Now list must key on the track instance the snapshot already exposes",
			screen.contains(Regex("""items\(liveSessions, key = \{ it\.trackInstance""")),
		)
		assertFalse(
			"package-plus-title collapses distinct sessions and is not stable across " +
				"the metadata callback",
			screen.contains(Regex("""key = \{ it\.packageName \+ it\.title \}""")),
		)
	}

	/**
	 * Now's banner is History's banner: one geometry, one cache, one fallback.
	 *
	 * `width = null` is the load-bearing argument — it is what hands the height
	 * to the shared aspect instead of to a number that would suit one phone —
	 * so it is asserted rather than the aspect constant, which both surfaces
	 * already read from the same place.
	 */
	@Test
	fun `Now draws the same full-width banner History draws`() {
		assertTrue(
			"the Now card must draw the banner off the shared thumbnail pipeline",
			screen.contains(
				Regex(
					"""fun SessionCard\([\s\S]{0,2000}?VideoThumbnail\([\s\S]{0,400}?width = null""",
				),
			),
		)
		assertTrue(
			"and History's banner must still be the same call",
			screen.contains(
				Regex("""fun VideoBanner\([\s\S]{0,900}?VideoThumbnail\([\s\S]{0,400}?width = null"""),
			),
		)
	}

	/**
	 * Now gets the image and not History's gesture.
	 *
	 * `VideoBanner` carries tap-to-open-on-YouTube. Reusing it for the parity
	 * would have been the shorter edit and would have given Now a new gesture
	 * nobody asked for, in a stage whose whole premise is that it changes no
	 * behaviour.
	 */
	@Test
	fun `the Now banner adds no tap target`() {
		assertFalse(
			"Now must not acquire History's open-on-YouTube gesture",
			screen.contains(Regex("""fun SessionCard\([\s\S]{0,2000}?VideoBanner\(""")),
		)
	}

	/**
	 * The badge is chosen by the service, and only where the service is known.
	 *
	 * Now holds the package, so it passes one. History does not retain the
	 * source after finalization, so it passes nothing and takes the default —
	 * which is the previous universal behaviour, unchanged. An edit that
	 * "helpfully" gave History a badge argument would be inferring the service
	 * from something that cannot prove it.
	 */
	@Test
	fun `only Now selects the badge, and it selects by service`() {
		assertTrue(
			"Now must pass the service badge its platform maps to",
			screen.contains(
				Regex("""fun SessionCard\([\s\S]{0,2000}?badge = card\.platform\.badge"""),
			),
		)
		assertFalse(
			"History must not pass a badge it cannot prove — it keeps the YouTube " +
				"default, which is exactly what it drew before this stage",
			screen.contains(Regex("""fun VideoBanner\([\s\S]{0,900}?badge""")),
		)
	}

	/**
	 * The two marks differ by their glyph, not only by their chip.
	 *
	 * This is the assertion that would have caught the first attempt. A red
	 * circle holding the *same* play triangle is a different chip and the same
	 * mark: at the sizes this actually draws at it reads as a round play button
	 * rather than as a service, which is precisely what a service badge must not
	 * do. Pinning the glyph — not just the shape — is what stops a later
	 * simplification collapsing them back together.
	 */
	@Test
	fun `the two services differ by glyph, not only by chip shape`() {
		assertTrue(
			"YouTube Music must draw the note, which is already this app's mark for it",
			thumbnails.contains(Regex("""ServiceBadge\.YOUTUBE_MUSIC -> WaxIcons\.MusicNote""")),
		)
		assertTrue(
			"and YouTube keeps the triangle",
			thumbnails.contains(Regex("""ServiceBadge\.YOUTUBE -> WaxIcons\.PlayTriangle""")),
		)
		assertTrue(
			"the chip shapes still differ too",
			thumbnails.contains(Regex("""ServiceBadge\.YOUTUBE_MUSIC -> CircleShape""")),
		)
	}

	/**
	 * An unnamed source is drawn with no badge at all.
	 *
	 * Two halves, and both are needed. The badge has to be *optional* in the
	 * drawing code, and the unknown platform has to actually pass nothing —
	 * either one alone would leave a branded mark on a frame whose service the
	 * app never established.
	 */
	@Test
	fun `an unrecognised source is given no badge to draw`() {
		assertTrue(
			"the badge must be optional at the point it is drawn",
			thumbnails.contains(Regex("""badge: ServiceBadge\? = ServiceBadge\.YOUTUBE""")),
		)
		assertTrue(
			"and a null badge must draw no chip",
			thumbnails.contains(Regex("""if \(frame != null && badge != null\)""")),
		)
		val card = stripComments(
			text("app/src/main/java/com/rustedwax/app/ui/NowCard.kt"),
		)
		assertTrue(
			"the unknown platform must map to nothing rather than to a brand",
			card.contains(Regex("""OTHER -> null""")),
		)
	}
}
