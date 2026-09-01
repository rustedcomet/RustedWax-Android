package com.rustedwax.app.detect

import com.rustedwax.core.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeShortParserTest {

	@Test
	fun `measured organic Shorts require player title handle and seekbar`() {
		val cases = listOf(
			Case("Dura - Becky G 🔥", "@fansclubbeckyg394", 7, 20),
			Case("Spider - Man ...", "@Etzy-Am", 31, 92),
			Case("🎭 He Put on a Magic Mask! | The Mask (1994)😂#shorts #movierecap", "@SonaDarus", 61, 158),
			Case("Which is your favorite team? ⚡ Team Iron Man or 🛡️ Team Captain America? #Marvel", "@Status_svijet", 12, 41),
			Case("Hackers' Skills...", "@Beredist", 48, 139),
		)
		cases.forEach { expected ->
			assertEquals(
				NativeShortParser.Result.Organic(
					expected.title,
					expected.handle.lowercase(),
					expected.current,
					expected.total,
				),
				NativeShortParser.parse(player(expected)),
			)
		}
	}

	@Test
	fun `Samsung measured sibling overlay and time bar shape parses`() {
		val title = "🎭 He Put on a Magic Mask! | The Mask (1994)😂#shorts #movierecap"
		val overlay = node(
			id = "reel_player_overlay_container",
			children = listOf(
				node(description = "Go to channel @SonaDarus", clickable = true, className = "android.widget.Button"),
				node(description = "@SonaDarus"),
				node(description = "Subscribe to @SonaDarus.", clickable = true, className = "android.widget.Button"),
				node(description = title, clickable = true),
				// Measured on the live A12 write gate: YouTube duplicated one
				// title hashtag as a standalone semantic navigation chip.
				node(description = "#movierecap", clickable = true),
				node(description = "Original Sound (Contains music from: Retro · Wayne Jones)", clickable = true, className = "android.widget.Button"),
				node(description = "like this video along with 595 thousand other people", clickable = true, className = "android.widget.RadioButton"),
				node(description = "View 2,417 comments", clickable = true, className = "android.widget.Button"),
				node(description = "Share this video", clickable = true, className = "android.widget.Button"),
				node(description = "Play video", clickable = true, className = "android.widget.ImageView"),
				node(description = "Next Video", clickable = true, className = "android.widget.ImageView"),
			),
		)
		val structural = node(
			id = "reel_watch_fragment_root",
			children = listOf(overlay, node(id = "reel_watch_player")),
		)
		val timeBar = node(
			id = "reel_time_bar",
			children = listOf(node(description = "0 minutes 20 seconds of 2 minutes 38 seconds")),
		)
		assertEquals(
			NativeShortParser.Result.Organic(title, "@sonadarus", 20, 158),
			NativeShortParser.parse(NativeShortTree(node(pkg = YT, children = listOf(structural, timeBar)))),
		)
	}

	@Test
	fun `supported measured time phrases parse and unsupported shapes refuse`() {
		assertEquals(7L to 20L, NativeShortParser.parseTime("0 minutes 7 seconds of 0 minutes 20 seconds"))
		assertEquals(61L to 158L, NativeShortParser.parseTime("1 minute 1 second of 2 minutes 38 seconds"))
		assertEquals(7L to 20L, NativeShortParser.parseTime("0 minutos 7 segundos de 0 minutos 20 segundos"))
		assertEquals(67L to 140L, NativeShortParser.parseTime("1 minuto e 7 segundos de 2 minutos e 20 segundos"))
		assertEquals(null, NativeShortParser.parseTime("7 of 20 seconds"))
		assertEquals(null, NativeShortParser.parseTime("0 Minuten 7 Sekunden von 0 Minuten 20 Sekunden"))
		assertEquals(null, NativeShortParser.parseTime("0 minutes 20 seconds of 0 minutes 0 seconds"))
		assertEquals(null, NativeShortParser.parseTime("0 minutes 21 seconds of 0 minutes 20 seconds"))
	}

	@Test
	fun `home cards ordinary pages and comments are not playback proof`() {
		val card = node(
			id = "rich_item_content",
			children = listOf(node(text = "Dura - Becky G 🔥"), node(text = "@fansclubbeckyg394")),
		)
		assertInvalid(NativeShortTree(node(pkg = YT, children = listOf(card))))
		assertInvalid(
			NativeShortTree(
				node(
					pkg = YT,
					id = "watch_player",
					children = listOf(node(text = "x title"), node(text = "@owner")),
				),
			),
		)
		assertInvalid(
			player(Case("x title", "@owner", 1, 20), extraRoot = listOf(node(id = "comments_panel"))),
		)
	}

	@Test
	fun `controls and sound labels cannot become the title`() {
		// The control is excluded, leaving no title at all — which is a Short
		// with an unread title, not a refusal.
		assertNoTitle(
			player(Case("Use this sound", "@owner", 1, 20), titleId = "sound_button"),
		)
	}

	@Test
	fun `missing and conflicting structural evidence refuses`() {
		// No handle *and* nothing to measure. With a readable bar these are
		// continuations of an already-acquired Short instead (v0.9.16).
		assertInvalid(player(Case("Title", "@owner", 1, 20), includeHandle = false, seekbarLabel = ""))
		assertInvalid(player(Case("Title", "@owner", 1, 20), includeSeekbar = false))
		assertInvalid(
			player(Case("Title", "@owner", 1, 20), secondHandle = "@different", seekbarLabel = ""),
		)
		assertNoTitle(player(Case("Title", "@owner", 1, 20), secondTitle = "Other title"))
		assertInvalid(player(Case("Title", "@owner", 1, 20), secondSeekbar = 2L to 30L))
	}

	@Test
	fun `an absent handle and an ambiguous one are told apart`() {
		val absent = NativeShortParser.parse(
			player(Case("Title", "@owner", 1, 20), includeHandle = false, seekbarLabel = ""),
		) as NativeShortParser.Result.Invalid
		assertTrue(absent.reason, "found none" in absent.reason)
		assertTrue(absent.reason, "no visible label in the player carried an @" in absent.reason)

		val ambiguous = NativeShortParser.parse(
			player(Case("Title", "@owner", 1, 20), secondHandle = "@different", seekbarLabel = ""),
		) as NativeShortParser.Result.Invalid
		assertTrue(ambiguous.reason, "found 2" in ambiguous.reason)
		assertTrue(ambiguous.reason, "@owner" in ambiguous.reason)
		assertTrue(ambiguous.reason, "@different" in ambiguous.reason)
	}

	@Test
	fun `a handle the parser would not accept is quoted rather than lost`() {
		// A footer that *does* carry an @ but not one this parser recognises —
		// a locale we have no "Go to channel" phrase for, a handle outside the
		// accepted character set — is the case worth seeing in the log, because
		// it is the one a parser change could fix.
		val refusal = NativeShortParser.parse(
			player(
				Case("Title", "@owner", 1, 20),
				includeHandle = false,
				seekbarLabel = "",
				extraPlayer = listOf(node(text = "Kanalinaan @Ünïcödé-Öwnér")),
			),
		) as NativeShortParser.Result.Invalid
		assertTrue(refusal.reason, "found none" in refusal.reason)
		assertTrue(refusal.reason, "Kanalinaan @Ünïcödé-Öwnér" in refusal.reason)
	}

	@Test
	fun `the handle refusal detail does not defeat the diagnostic throttle`() {

		val first = NativeShortParser.parse(
			player(Case("A", "@a", 1, 20), includeHandle = false, seekbarLabel = ""),
		) as NativeShortParser.Result.Invalid
		val second = NativeShortParser.parse(
			player(
				Case("B", "@b", 1, 20),
				includeHandle = false,
				seekbarLabel = "",
				extraPlayer = listOf(node(text = "Go to canal @somethingelse")),
			),
		) as NativeShortParser.Result.Invalid
		assertEquals(
			NativeShortDiagnosticKey.of(first.reason),
			NativeShortDiagnosticKey.of(second.reason),
		)
		assertTrue(first.reason != second.reason)
	}

	@Test
	fun `the speed chip is read out of the stripped 2x overlay`() {
		val refusal = NativeShortParser.parse(
			player(
				Case("Title", "@owner", 1, 20),
				includeHandle = false,
				// The container survives the hold; only its readable time goes,
				// which is the measured picture-in-picture signature.
				seekbarLabel = "",
				extraPlayer = listOf(
					node(text = "2x"),
					node(text = "Pull down to lock 2x speed"),
				),
			),
		) as NativeShortParser.Result.Invalid
		assertEquals(2.0, refusal.playbackRate!!, 0.001)
		assertTrue(refusal.reason, "2x" in refusal.reason)
		assertTrue("nothing is measurable here", refusal.progressSurfaceLost)
	}

	@Test
	fun `a refusal that stops at the seekbar still reports the rate`() {
		// A second time-bar container refuses before the handle is ever looked at.
		val refusal = NativeShortParser.parse(
			player(
				Case("Title", "@owner", 1, 20),
				secondSeekbar = 9L to 40L,
				extraPlayer = listOf(node(text = "2x")),
			),
		) as NativeShortParser.Result.Invalid
		assertTrue(refusal.reason, "seekbar container" in refusal.reason)
		assertEquals(2.0, refusal.playbackRate!!, 0.001)
	}

	@Test
	fun `a sentence mentioning a speed is not a speed chip`() {
		val refusal = NativeShortParser.parse(
			player(
				Case("Title", "@owner", 1, 20),
				includeHandle = false,
				seekbarLabel = "",
				extraPlayer = listOf(node(text = "Pull down to lock 2x speed")),
			),
		) as NativeShortParser.Result.Invalid
		assertNull(refusal.playbackRate)
	}

	@Test
	fun `an implausible chip is ignored rather than believed`() {
		listOf("9x", "0.5x", "2xx").forEach { label ->
			val refusal = NativeShortParser.parse(
				player(
					Case("Title", "@owner", 1, 20),
					includeHandle = false,
					seekbarLabel = "",
					extraPlayer = listOf(node(text = label)),
				),
			) as NativeShortParser.Result.Invalid
			assertNull(label, refusal.playbackRate)
		}
	}

	@Test
	fun `hidden evidence and scan budgets fail closed`() {
		assertInvalid(player(Case("Title", "@owner", 1, 20), handleVisible = false, seekbarLabel = ""))
		assertInvalid(player(Case("Title", "@owner", 1, 20)).copy(exceededCaptureBudget = true))
		var deep = node(text = "leaf")
		repeat(26) { deep = node(children = listOf(deep)) }
		assertInvalid(NativeShortTree(node(pkg = YT, children = listOf(deep))))
	}

	@Test
	fun `literal ad label binds only inside the proven player`() {
		val inside = NativeShortParser.parse(
			player(Case("Advert title", "@advertiser", 3, 18), extraPlayer = listOf(node(text = "Sponsored"))),
		)
		assertTrue(inside is NativeShortParser.Result.Ad)
		assertEquals("Sponsored", (inside as NativeShortParser.Result.Ad).signal)

		val organicTree = player(Case("Organic title", "@creator", 3, 18))
		val outside = NativeShortParser.parse(
			organicTree.copy(
				root = organicTree.root.copy(
					children = organicTree.root.children + node(text = "Sponsored"),
				),
			),
		)
		assertTrue(outside is NativeShortParser.Result.Organic)
	}

	@Test
	fun `measured auto-dub badge does not compete with the title`() {

		val title = "Shakira Fue a Ver el Partido de Messi y Todos Pensaron lo Mismo 😱 #artista"
		val overlay = node(
			id = "reel_player_overlay_container",
			children = listOf(
				node(description = "Auto-dubbed"),
				node(description = "Go to channel @enefectoescine17", clickable = true),
				node(description = "@enefectoescine17"),
				node(description = "Subscribe to @enefectoescine17.", clickable = true),
				node(description = title, clickable = true),
				node(description = "like this video along with 99 thousand other people", clickable = true),
				node(description = "View 1,109 comments", clickable = true),
				node(description = "Share this video", clickable = true),
				node(description = "Remix this Short along with 11 other remixes", clickable = true),
				node(description = "See more videos using this sound", clickable = true),
			),
		)
		val structural = node(
			id = "reel_watch_fragment_root",
			children = listOf(overlay, node(id = "reel_watch_player")),
		)
		// Measured shape: reel_time_bar is a sibling of the Shorts root under
		// android:id/content, carrying exactly one SeekBar child.
		val timeBar = node(
			id = "reel_time_bar",
			children = listOf(
				node(
					description = "0 minutes 8 seconds of 1 minute 7 seconds",
					className = "android.widget.SeekBar",
				),
			),
		)
		assertEquals(
			NativeShortParser.Result.Organic(title, "@enefectoescine17", 8, 67),
			NativeShortParser.parse(NativeShortTree(node(pkg = YT, children = listOf(structural, timeBar)))),
		)
	}

	@Test
	fun `excluding the badge does not admit a genuinely conflicting title`() {
		// Two real candidates must never resolve to one of them. The Short is
		// still tracked and measured — identity comes from watch history — but
		// the on-screen title is dropped rather than guessed.
		assertNoTitle(
			player(
				Case("Title", "@owner", 1, 20),
				secondTitle = "Other title",
				extraPlayer = listOf(node(description = "Auto-dubbed")),
			),
		)
	}

	@Test
	fun `a named player with no readable time is proven, not refused`() {
		val result = NativeShortParser.parse(
			player(Case("Title", "@owner", 1, 20), seekbarLabel = "8 of 67 seconds"),
		)
		assertTrue("$result", result is NativeShortParser.Result.OrganicUnmeasured)
		val unmeasured = result as NativeShortParser.Result.OrganicUnmeasured
		assertEquals("Title", unmeasured.title)
		assertEquals("@owner", unmeasured.ownerHandle)
	}

	@Test
	fun `a missing seekbar container is still a structural refusal`() {

		val missingContainer = NativeShortParser.parse(
			player(Case("Title", "@owner", 1, 20), includeSeekbar = false),
		)
		assertTrue(missingContainer is NativeShortParser.Result.Invalid)
		assertFalse((missingContainer as NativeShortParser.Result.Invalid).progressSurfaceLost)
		assertTrue(missingContainer.reason.contains("container"))
	}

	@Test
	fun `no handle and no readable time is the picture-in-picture signature`() {

		val pip = NativeShortParser.parse(
			player(
				Case("Title", "@owner", 1, 20),
				seekbarLabel = "8 of 67 seconds",
				includeHandle = false,
			),
		)
		assertTrue((pip as NativeShortParser.Result.Invalid).progressSurfaceLost)

		// A readable time with no handle is not a refusal at all any more: the
		// surface is plainly still there, so it is a measurement for whatever
		// Short is already being tracked (v0.9.16, the 2× hold).
		val noHandle = NativeShortParser.parse(
			player(Case("Title", "@owner", 1, 20), includeHandle = false),
		)
		assertEquals(
			NativeShortParser.Result.OrganicUnnamed(1, 20),
			noHandle,
		)

		// Two readable times is an ambiguous read of a surface that is still
		// there — refuse, and never treat it as unmeasured.
		val ambiguous = NativeShortParser.parse(
			player(Case("Title", "@owner", 1, 20), secondSeekbar = 2L to 30L),
		)
		assertTrue(ambiguous is NativeShortParser.Result.Invalid)
		assertFalse((ambiguous as NativeShortParser.Result.Invalid).progressSurfaceLost)
	}

	@Test
	fun `ordinary refusals never claim the progress surface was lost`() {

		listOf(
			// No time-bar container at all, two of them, a blown budget and the
			// wrong package: every one of these means "not a Shorts player",
			// never "playing but unmeasurable".
			player(Case("Title", "@owner", 1, 20), includeSeekbar = false),
			player(Case("Title", "@owner", 1, 20), secondSeekbar = 2L to 30L),
			player(Case("Title", "@owner", 1, 20)).copy(exceededCaptureBudget = true),
			NativeShortTree(node(pkg = "com.other.app")),
		).forEach { tree ->
			val result = NativeShortParser.parse(tree)
			assertFalse(
				"$result should not claim a lost progress surface",
				(result as NativeShortParser.Result.Invalid).progressSurfaceLost,
			)
		}
	}

	@Test
	fun `the measured id-less footer resolves despite its like and comment counters`() {

		val footer = listOf(
			node(description = "Go to channel @MontRecaps"),
			node(text = "@MontRecaps", description = "@MontRecaps"),
			node(text = "@MontRecaps", className = "android.widget.Button"),
			node(description = "Subscribe to @MontRecaps.", className = "android.widget.Button"),
			node(text = "Subscribe", description = "Subscribe"),
			node(text = "He Chose Hell for Love ❤️", description = "He Chose Hell for Love ❤️"),
			node(
				description = "like this video along with 2 thousand other people",
				className = "android.widget.RadioButton",
			),
			node(text = "2K", description = "2K"),
			node(description = "View 14 comments", className = "android.widget.Button"),
			node(text = "14", description = "14"),
			node(description = "Share this video", className = "android.widget.Button"),
			node(text = "Share", description = "Share"),
			node(description = "Remix", className = "android.widget.Button"),
			node(text = "Remix", description = "Remix"),
			node(
				description = "See more videos using this sound",
				className = "android.widget.Button",
			),
		)
		val structural = node(
			id = "reel_watch_fragment_root",
			children = listOf(node(id = "reel_watch_player", children = footer)),
		)
		val timeBar = node(
			id = "reel_time_bar",
			children = listOf(
				node(className = "android.widget.SeekBar", description = "1 minute 21 seconds of 2 minutes 59 seconds"),
			),
		)
		assertEquals(
			NativeShortParser.Result.Organic("He Chose Hell for Love ❤️", "@montrecaps", 81, 179),
			NativeShortParser.parse(
				NativeShortTree(node(pkg = YT, children = listOf(structural, timeBar))),
			),
		)
	}

	@Test
	fun `counters are excluded by shape but real titles are not`() {
		// Only a literal that is *entirely* a count is dropped. A title that
		// merely contains or starts with a number keeps its text.
		listOf("14", "2K", "1.2M", "999", "2,5 mil", "12 500", "3B").forEach { counter ->
			assertEquals(
				"$counter should not be a title",
				NativeShortParser.Result.Organic("Real title", "@owner", 1, 20),
				NativeShortParser.parse(
					player(
						Case("Real title", "@owner", 1, 20),
						titleId = null,
						extraPlayer = listOf(node(text = counter, description = counter)),
					),
				),
			)
		}
		// A genuinely conflicting prose title is still never guessed at.
		assertNoTitle(
			player(
				Case("Real title", "@owner", 1, 20),
				titleId = null,
				extraPlayer = listOf(node(text = "2000 reasons to leave", description = "2000 reasons to leave")),
			),
		)
	}

	@Test
	fun `an uncounted View comments control is not a title`() {

		listOf("View comments", "View 14 comments", "View 1 comment", "View 2,417 comments")
			.forEach { control ->
				assertEquals(
					"$control should not be a title",
					NativeShortParser.Result.Organic("Real title", "@owner", 1, 20),
					NativeShortParser.parse(
						player(
							Case("Real title", "@owner", 1, 20),
							titleId = null,
							extraPlayer = listOf(node(description = control)),
						),
					),
				)
			}
	}

	@Test
	fun `position resolves an id-less footer the blocklist cannot`() {
		// The real shape measured on the device: no resource ids anywhere in the
		// footer, and an unknown control the blocklist has never seen. Before
		// geometry this refused; the title runs along the bottom-left while every
		// control that keeps being mistaken for it is pinned to the right column.
		val footer = listOf(
			bounded(30, 1360, 640, 1390, text = "He Chose Hell for Love ❤️"),
			bounded(630, 880, 700, 950, text = "2K"),
			bounded(630, 980, 700, 1050, text = "14"),
			bounded(628, 1100, 700, 1170, description = "Some brand new control"),
			bounded(30, 1300, 300, 1340, description = "Go to channel @MontRecaps"),
		)
		val structural = node(
			id = "reel_watch_fragment_root",
			children = listOf(node(id = "reel_watch_player", children = footer)),
		)
		val timeBar = node(
			id = "reel_time_bar",
			children = listOf(node(description = "1 minute 21 seconds of 2 minutes 59 seconds")),
		)
		assertEquals(
			NativeShortParser.Result.Organic("He Chose Hell for Love ❤️", "@montrecaps", 81, 179),
			NativeShortParser.parse(
				NativeShortTree(node(pkg = YT, children = listOf(structural, timeBar))),
			),
		)
	}

	@Test
	fun `two left-aligned candidates still refuse rather than guess`() {
		// Geometry narrows; it never picks. A wrong title is a permanent wrong
		// scrobble, so genuine ambiguity must still fail closed.
		val footer = listOf(
			bounded(30, 1360, 640, 1390, text = "He Chose Hell for Love ❤️"),
			bounded(30, 1240, 620, 1280, text = "A completely different sentence"),
			bounded(630, 880, 700, 950, text = "2K"),
			bounded(30, 1300, 300, 1340, description = "Go to channel @MontRecaps"),
		)
		val structural = node(
			id = "reel_watch_fragment_root",
			children = listOf(node(id = "reel_watch_player", children = footer)),
		)
		val timeBar = node(
			id = "reel_time_bar",
			children = listOf(node(description = "0 minutes 8 seconds of 0 minutes 48 seconds")),
		)
		assertNoTitle(NativeShortTree(node(pkg = YT, children = listOf(structural, timeBar))))
	}

	@Test
	fun `the sound attribution pill is not a second title candidate`() {

		assertEquals(
			NativeShortParser.Result.Organic(resilientGlamourTitle, "@resilientglamour", 5, 49),
			NativeShortParser.parse(resilientGlamourShort(5)),
		)
	}

	/** The exact measured content-description, zero-width separators and all. */
	private val resilientGlamourTitle =
		"Karen Cruz Mexico\u2019s Flag Football Queen \uD83C\uDDF2\uD83C\uDDFD\uD83C\uDFC8 " +
			"#flagfootball\u200b #mexico\u200b #shorts\u200b #youtubeshorts\u200b #football\u200b"

	private fun resilientGlamourShort(seconds: Int) = measuredShort(
		listOf(
			// avatar + handle + Subscribe
			group(
				0, 1750, 900, 1862,
				group(
					45, 1761, 518, 1851,
					button(45, 1761, 135, 1851).copy(
						children = listOf(
							bounded(45, 1761, 135, 1851, description = "Go to channel @ResilientGlamour")
								.copy(className = "android.widget.ImageView"),
						),
					),
					group(
						158, 1761, 518, 1851,
						bounded(158, 1761, 495, 1851, text = "@ResilientGlamour", description = "@ResilientGlamour")
							.copy(
								children = listOf(
									bounded(158, 1761, 495, 1851, text = "@ResilientGlamour")
										.copy(className = "android.widget.Button"),
								),
							),
					),
				),
				button(518, 1761, 739, 1851, description = "Subscribe to @ResilientGlamour.").copy(
					children = listOf(bounded(552, 1786, 705, 1826, text = "Subscribe", description = "Subscribe)")),
				),
			),
			// the title, whose ancestors carry no label of their own
			group(
				0, 1862, 900, 1934,
				group(
					0, 1862, 900, 1934,
					group(
						45, 1873, 900, 1923,
						bounded(
							45, 1873, 900, 1923,
							text = resilientGlamourTitle,
							description = resilientGlamourTitle,
						),
					),
				),
			),
			// the sound chip: an icon and the same label twice
			group(
				0, 1934, 900, 2036,
				button(45, 1951, 900, 2019, description = soundChipLabel).copy(
					children = listOf(
						bounded(71, 1968, 105, 2002).copy(className = "android.widget.ImageView"),
						bounded(116, 1962, 874, 2007, text = soundChipLabel, description = soundChipLabel),
					),
				),
			),
			group(
				900, 1213, 1080, 2070,
				action(900, 1213, 1382, "like this video along with 23 thousand other people", "23K"),
				action(900, 1382, 1551, "View 1,121 comments", "1,121"),
				action(900, 1551, 1720, "Share this video", "Share"),
				action(900, 1720, 1889, "Remix", "Remix"),
				action(900, 1889, 2070, "See more videos using this sound", null),
			),
		),
		"0 minutes $seconds seconds of 0 minutes 49 seconds",
	)

	private val soundChipLabel =
		"She Went From Figure Skating to Softball and Dominated \uD83E\uDD2F " +
			"#shorts #youtubeshorts #softball"

	private fun group(
		left: Int,
		top: Int,
		right: Int,
		bottom: Int,
		vararg children: NativeShortNode,
	) = bounded(left, top, right, bottom).copy(children = children.toList())

	private fun button(
		left: Int,
		top: Int,
		right: Int,
		bottom: Int,
		description: String? = null,
	) = bounded(left, top, right, bottom, description = description)
		.copy(className = "android.widget.Button", clickable = true)

	/** One right-column control: an icon, and its counter or caption underneath. */
	private fun action(
		left: Int,
		top: Int,
		bottom: Int,
		description: String,
		caption: String?,
	) = button(left, top, 1080, bottom, description = description).copy(
		children = listOfNotNull(
			bounded(956, top + 22, 1024, top + 90).copy(
				children = listOf(
					bounded(956, top + 22, 1024, top + 90).copy(className = "android.widget.ImageView"),
				),
			),
			caption?.let { bounded(left, bottom - 88, 1080, bottom - 42, text = it, description = it) },
		),
	)

	@Test
	fun `an AI disclosure chip's inner label is not a title candidate`() {

		val title = "The Most Stunning Colombia Fan? \uD83D\uDE0D\uD83C\uDDE8\uD83C\uDDF4 #shorts\u200b"
		val sound = "Original Sound (Contains music from: Gata Only \u00b7 FloyyMenor & Cris Mj)"
		val footer = listOf(
			group(
				0, 1750, 900, 1862,
				group(
					45, 1761, 518, 1851,
					button(45, 1761, 135, 1851).copy(
						children = listOf(
							bounded(45, 1761, 135, 1851, description = "Go to channel @ZoeCole-x6z")
								.copy(className = "android.widget.ImageView"),
						),
					),
					bounded(158, 1761, 413, 1851, text = "@ZoeCole-x6z", description = "@ZoeCole-x6z"),
				),
				button(436, 1761, 657, 1851, description = "Subscribe to @ZoeCole-x6z.").copy(
					children = listOf(bounded(470, 1786, 623, 1826, text = "Subscribe", description = "Subscribe")),
				),
			),
			group(
				0, 1862, 900, 1934,
				bounded(45, 1873, 900, 1923, text = title, description = title),
			),
			group(
				0, 1934, 900, 2036,
				button(45, 1951, 175, 2019, description = "AI: Content was made with AI").copy(
					children = listOf(
						bounded(116, 1962, 149, 2007, text = "AI", description = "AI"),
						bounded(269, 1962, 577, 2007, text = sound, description = sound),
					),
				),
			),
			group(
				900, 1213, 1080, 2070,
				action(900, 1213, 1382, "like this video along with 44 thousand other people", "44K"),
				action(900, 1382, 1551, "View 2,019 comments", "2,019"),
				action(900, 1551, 1720, "Share this video", "Share"),
				action(900, 1720, 1889, "Remix this Short along with 1.4 million other remixes", "1.4M"),
				action(900, 1889, 2070, "See more videos using this sound", null),
			),
		)
		assertEquals(
			NativeShortParser.Result.Organic(title, "@zoecole-x6z", 0, 46),
			NativeShortParser.parse(measuredShort(footer, "0 minutes 0 seconds of 0 minutes 46 seconds")),
		)
	}

	@Test
	fun `an artist channel row and a Shop chip are not title candidates`() {

		val handle = bounded(45, 1863, 135, 1953, description = "Go to channel @ITSBIZKIT")
		val title = "SWIZZ BEATZ SURPRISED ALICIA KEYS WITH THE RARE VIRGIL ABLOH MAYBACH \uD83D\uDE33\uD83D\uDD25"
		val footer = listOf(
			bounded(
				45, 1751, 292, 1841,
				description = "Shop, , ",
				children = listOf(
					bounded(68, 1762, 136, 1830).copy(className = "android.widget.ImageView"),
				),
			),
			handle,
			bounded(158, 1863, 439, 1953, description = "@ITSBIZKIT, Official Artist Channel"),
			bounded(439, 1863, 660, 1953, description = "Subscribe to @ITSBIZKIT."),
			bounded(45, 1975, 900, 2025, description = title),
			action(900, 1213, 1382, "like this video along with 16 thousand other people", "16K"),
			action(900, 1382, 1551, "View 642 comments", "642"),
			action(900, 1551, 1720, "Share this video", "Share"),
			action(900, 1720, 1889, "Remix", "Remix"),
			action(900, 1889, 2070, "See more videos using this sound", null),
		)
		assertEquals(
			NativeShortParser.Result.Organic(title, "@itsbizkit", 8, 92),
			NativeShortParser.parse(measuredShort(footer, "0 minutes 8 seconds of 1 minute 32 seconds")),
		)
	}

	@Test
	fun `a chip that is not a Button cannot smuggle its label through a child`() {
		// Constructed, not measured. YouTube reports a chip's label twice — once
		// on the chip and once on a bare node inside it — and the shipped
		// captures of the sound chip and the AI chip both happen to make the
		// outer node a `Button`, which is refused on its class alone. Nothing
		// guarantees the next one will be. A label is therefore attributed to the
		// outermost node that reports it, so the chip is judged as a chip whether
		// or not Android called it a Button.
		val title = "A perfectly ordinary title"
		val chip = "Some chip nobody has seen yet"
		val footer = listOf(
			bounded(45, 1863, 135, 1953, description = "Go to channel @owner"),
			bounded(45, 1975, 900, 2025, text = title, description = title),
			group(
				45, 2051, 900, 2119,
				bounded(45, 2051, 900, 2119, description = chip).copy(
					children = listOf(
						bounded(71, 2068, 105, 2102).copy(className = "android.widget.ImageView"),
						bounded(116, 2062, 874, 2107, text = chip, description = chip),
					),
				),
			),
		)
		assertEquals(
			NativeShortParser.Result.Organic(title, "@owner", 8, 92),
			NativeShortParser.parse(measuredShort(footer, "0 minutes 8 seconds of 1 minute 32 seconds")),
		)
	}

	@Test
	fun `narrowing breaks a tie and never removes the only left-aligned candidate`() {
		// The whole point of these rules is to decide between two candidates in
		// the title's own region. A footer where only one candidate is there at
		// all is not ambiguous, so it must answer exactly as it did before the
		// rules existed — even if that candidate is the shape a rule refuses.
		val title = "A title that shares the channel's row"
		val footer = listOf(
			bounded(45, 1863, 135, 1953, description = "Go to channel @owner"),
			// Level with the avatar, so the channel-row rule refuses it, and the
			// only other candidate is out in the right-hand action column.
			bounded(45, 1863, 900, 1953, text = title, description = title),
			bounded(900, 1213, 1080, 1382, description = "Some brand new control"),
		)
		assertEquals(
			NativeShortParser.Result.Organic(title, "@owner", 8, 92),
			NativeShortParser.parse(measuredShort(footer, "0 minutes 8 seconds of 1 minute 32 seconds")),
		)
	}

	@Test
	fun `an icon-bearing row is excluded only when something else survives`() {
		// The narrowing removes candidates; it never invents one. A footer whose
		// single title candidate happens to carry an icon still reports that
		// title rather than refusing, because there is nothing to be ambiguous
		// with.
		val footer = listOf(
			bounded(45, 1863, 135, 1953, description = "Go to channel @onlyone"),
			bounded(
				45, 1975, 900, 2025,
				description = "The only prose in this footer",
				children = listOf(
					bounded(71, 1990, 105, 2010).copy(className = "android.widget.ImageView"),
				),
			),
		)
		assertEquals(
			NativeShortParser.Result.Organic("The only prose in this footer", "@onlyone", 8, 92),
			NativeShortParser.parse(measuredShort(footer, "0 minutes 8 seconds of 1 minute 32 seconds")),
		)
	}

	private fun measuredShort(footer: List<NativeShortNode>, seekbar: String): NativeShortTree {
		val structural = node(
			id = "reel_watch_fragment_root",
			children = listOf(node(id = "reel_watch_player", children = footer)),
		)
		val timeBar = node(id = "reel_time_bar", children = listOf(node(description = seekbar)))
		return NativeShortTree(node(pkg = YT, children = listOf(structural, timeBar)))
	}

	private fun bounded(
		left: Int,
		top: Int,
		right: Int,
		bottom: Int,
		text: String? = null,
		description: String? = null,
		children: List<NativeShortNode> = emptyList(),
	) = NativeShortNode(
		text = text,
		contentDescription = description,
		left = left,
		top = top,
		right = right,
		bottom = bottom,
		children = children,
	)

	@Test
	fun `a bare upload date is not a title`() {

		listOf(
			"August 5, 2026", "5 August 2026", "2026-08-05", "5/8/2026",
			"Aug 5, 2026", "5 de agosto de 2026",
		).forEach { date ->
			assertEquals(
				"$date should not be a title",
				NativeShortParser.Result.Organic("Real title", "@owner", 1, 20),
				NativeShortParser.parse(
					player(
						Case("Real title", "@owner", 1, 20),
						titleId = null,
						extraPlayer = listOf(node(text = date, description = date)),
					),
				),
			)
		}
		// A title that merely mentions a date keeps its text.
		assertEquals(
			NativeShortParser.Result.Organic("August 5, 2026 was the day it all changed", "@owner", 1, 20),
			NativeShortParser.parse(
				player(Case("August 5, 2026 was the day it all changed", "@owner", 1, 20)),
			),
		)
	}

	// ── retained in-app miniplayer ─────────────────────────────────────────

	private fun miniplayer(
		title: String = "Some Retained Video",
		current: Long = 14,
		total: Long = 269,
	): NativeShortNode = node(
		id = "modern_miniplayer",
		description = "Minimized player",
		children = listOf(
			node(id = "player_video_title_view", text = title),
			node(text = "Go to channel @SomeOtherChannel"),
			node(
				id = "time_bar_current_time",
				description = "$current minutes 0 seconds of $total minutes 0 seconds",
			),
		),
	)

	/**
	 * The foreground Short is what latches; the retained player beside it is not
	 * a second candidate.
	 */
	@Test
	fun `a retained miniplayer never supplies the foreground Short's identity or progress`() {
		val short = Case("Dura - Becky G 🔥", "@fansclubbeckyg394", 7, 20)

		val result = NativeShortParser.parse(
			player(short, extraRoot = listOf(miniplayer(title = "Some Retained Video", current = 14, total = 269))),
		)

		// Measured behaviour, and the right one: a second title/handle candidate
		// makes naming ambiguous, so the parser declines to name rather than
		// choosing. What it must never do is adopt the *other* player's values.
		assertEquals(
			"the retained player's own progress was measured as the Short's",
			NativeShortParser.Result.OrganicUnnamed(7, 20),
			result,
		)
		assertFalse(
			"the retained player's title or handle reached the result: $result",
			result.toString().contains("Some Retained Video") ||
				result.toString().contains("SomeOtherChannel"),
		)
	}

	/**
	 * The mini-player's own title, handle and seekbar are never read as the
	 * Short's — the failure that would matter, because all three are shaped
	 * exactly like the fields the parser wants.
	 */
	@Test
	fun `a miniplayer inside the player subtree cannot supply title handle or progress`() {
		val short = Case("Dura - Becky G 🔥", "@fansclubbeckyg394", 7, 20)
		val result = NativeShortParser.parse(
			player(short, extraPlayer = listOf(miniplayer(title = "Retained Decoy", current = 3, total = 400))),
		)

		// Nested inside the player subtree is the harder case: every field is
		// exactly the shape the parser is looking for, in the place it looks.
		assertEquals(
			"a retained mini-player's progress was measured as the Short's",
			NativeShortParser.Result.OrganicUnnamed(7, 20),
			result,
		)
		assertFalse(
			"a retained mini-player's title reached the result: $result",
			result.toString().contains("Retained Decoy"),
		)
	}

	/**
	 * The negative control: with the Short gone and only the retained player
	 * left, nothing may be proven.
	 *
	 * This is the one that would catch a parser bypass. If a mini-player could
	 * satisfy the structural proof on its own it would become a second listen —
	 * a video the user is not watching, measured as though they were.
	 */
	@Test
	fun `a retained miniplayer alone proves no Short`() {
		val onlyMiniplayer = NativeShortTree(
			node(pkg = YT, children = listOf(miniplayer())),
		)

		assertInvalid(onlyMiniplayer)
	}

	/**
	 * Losing the foreground root does not promote the mini-player.
	 *
	 * The Shorts root and player are required together; a tree that keeps the
	 * mini-player but loses `reel_watch_fragment_root` is a Short that ended, not
	 * a Short that moved.
	 */
	@Test
	fun `losing the Shorts root does not promote a retained miniplayer`() {
		val orphanedPlayer = NativeShortTree(
			node(
				pkg = YT,
				children = listOf(
					node(id = "reel_watch_player", children = listOf(miniplayer())),
					miniplayer(),
				),
			),
		)

		assertInvalid(orphanedPlayer)
	}

	/**
	 * Swapped identities: the mini-player wearing the Shorts resource ids, and
	 * the Short wearing the mini-player's.
	 *
	 * A parser that matched on shape rather than on the exact ids would pass every
	 * test above and fail this one.
	 */
	@Test
	fun `a miniplayer wearing Shorts ids is judged on the ids, not the shape`() {
		val disguised = NativeShortTree(
			node(
				pkg = YT,
				children = listOf(
					node(
						id = "modern_miniplayer",
						children = listOf(
							node(id = "reel_title", text = "Dura - Becky G 🔥"),
							node(text = "Go to channel @fansclubbeckyg394"),
							seekbar(7, 20),
						),
					),
				),
			),
		)

		assertInvalid(disguised)
	}

	// ── surface interpretation, now owned by the Shorts adapter ────────────

	private fun readSurface(
		tree: NativeShortTree,
		pipPlaying: Boolean = false,
		inferenceEnabled: Boolean = false,
	) = NativeShortsAdapter().readSurface(
		ShortsSurfaceCapture(tree, pipPlaying = pipPlaying, inferenceEnabled = inferenceEnabled),
	)

	@Test
	fun `the Shorts adapter owns identity stabilization for production captures`() {
		val adapter = NativeShortsAdapter()
		val firstTree = player(Case("Stable", "@owner", 0, 20))
		val secondTree = player(Case("Stable", "@owner", 1, 20))

		val waiting = adapter.readStableSurface(
			ShortsSurfaceCapture(firstTree, pipPlaying = false, inferenceEnabled = false),
			observedAtMillis = 0,
		)
		val accepted = adapter.readStableSurface(
			ShortsSurfaceCapture(secondTree, pipPlaying = false, inferenceEnabled = false),
			observedAtMillis = NativeShortStabilizer.STABILITY_MS,
		)

		assertTrue(waiting is ShortsSurfaceReading.Absent)
		assertTrue(
			"the adapter did not accept a stable identity: $accepted",
			accepted is ShortsSurfaceReading.Proven,
		)
	}

	@Test
	fun `the measured dSYyRBKh4kA footer reaches the tracker with its title`() {

		val adapter = NativeShortsAdapter()
		val tracker = ForegroundShortTracker()
		fun capture(seconds: Int) = ShortsSurfaceCapture(
			resilientGlamourShort(seconds),
			pipPlaying = false,
			inferenceEnabled = false,
		)

		adapter.readStableSurface(capture(4), observedAtMillis = 0)
		val proven = adapter.readStableSurface(
			capture(5),
			observedAtMillis = NativeShortStabilizer.STABILITY_MS,
		)

		val organic = (proven as ShortsSurfaceReading.Proven).result
			as NativeShortParser.Result.Organic
		tracker.observe(
			ForegroundShortTracker.OrganicObservation(
				title = organic.title,
				ownerHandle = organic.ownerHandle,
				currentSeconds = organic.currentSeconds,
				totalSeconds = organic.totalSeconds,
				observedAtMillis = NativeShortStabilizer.STABILITY_MS,
				sourceEpoch = 1,
			),
		)

		assertEquals(resilientGlamourTitle, tracker.snapshot()?.title)
		assertEquals("@resilientglamour", tracker.snapshot()?.ownerHandle)
	}

	@Test
	fun `unavailable-surface facts promote only a fresh identity with the complete PiP pair`() {
		fun decision(audio: Boolean, window: Boolean): ShortsUnavailableDecision {
			val adapter = NativeShortsAdapter()
			adapter.readStableSurface(
				ShortsSurfaceCapture(
					player(Case("Immediate PiP", "@owner", 1, 33)),
					pipPlaying = false,
					inferenceEnabled = true,
				),
				observedAtMillis = 1_000,
			)
			return adapter.readUnavailableSurface(
				ShortsSurfaceUnavailableFacts(
					kind = ShortsSurfaceUnavailableKind.SURFACE_GONE,
					reason = "root unavailable",
					observedAtMillis = 1_500,
					displayOff = false,
					pipEvidence = ShortsPipEvidence(
						inferenceEnabled = true,
						mediaAudioStarted = audio,
						visiblePinnedWindow = window,
					),
					foregroundSurfaceOwned = false,
				),
			)
		}

		val promoted = decision(audio = true, window = true)
		assertEquals(2, promoted.readings.size)
		assertTrue(promoted.readings.first() is ShortsSurfaceReading.Proven)
		assertEquals(
			listOf(true),
			promoted.readings.filterIsInstance<ShortsSurfaceReading.Absent>()
				.map { it.progressSurfaceLost && it.inferredPlaying },
		)
		assertTrue(promoted.preserveProofFreshness)

		listOf(
			decision(audio = false, window = true),
			decision(audio = true, window = false),
		).forEach { refused ->
			assertEquals(1, refused.readings.size)
			val absent = refused.readings.single() as ShortsSurfaceReading.Absent
			assertFalse(absent.progressSurfaceLost)
			assertFalse(absent.inferredPlaying)
			assertFalse(refused.preserveProofFreshness)
		}
	}

	@Test
	fun `non-PiP disappearance resets stabilization and credits nothing`() {
		val adapter = NativeShortsAdapter()
		val first = player(Case("Reset me", "@owner", 0, 33))
		val second = player(Case("Reset me", "@owner", 1, 33))
		adapter.readStableSurface(
			ShortsSurfaceCapture(first, pipPlaying = false, inferenceEnabled = false),
			observedAtMillis = 1_000,
		)
		val unavailable = adapter.readUnavailableSurface(
			ShortsSurfaceUnavailableFacts(
				kind = ShortsSurfaceUnavailableKind.SURFACE_GONE,
				reason = "not PiP",
				observedAtMillis = 1_200,
				displayOff = false,
				pipEvidence = ShortsPipEvidence(false, false, false),
				foregroundSurfaceOwned = false,
			),
		)
		val absent = unavailable.readings.single() as ShortsSurfaceReading.Absent
		assertFalse(absent.progressSurfaceLost)
		assertFalse(absent.inferredPlaying)

		val afterReset = adapter.readStableSurface(
			ShortsSurfaceCapture(second, pipPlaying = false, inferenceEnabled = false),
			observedAtMillis = 1_000 + NativeShortStabilizer.STABILITY_MS,
		)
		assertTrue("the pre-disappearance frame survived the reset", afterReset is ShortsSurfaceReading.Absent)
	}

	@Test
	fun `surface-less PiP preserves an already owned Short without ordinary-path credit`() {
		val adapter = NativeShortsAdapter()
		val decision = adapter.readUnavailableSurface(
			ShortsSurfaceUnavailableFacts(
				kind = ShortsSurfaceUnavailableKind.SURFACE_GONE,
				reason = "owned PiP",
				observedAtMillis = 2_000,
				displayOff = true,
				pipEvidence = ShortsPipEvidence(true, true, true),
				foregroundSurfaceOwned = true,
			),
		)
		val missing = decision.readings.single() as ShortsSurfaceReading.Absent
		assertTrue(decision.preserveProofFreshness)
		assertTrue(missing.displayOff)

		val neutral = adapter.read(
			NativeShortsObserver.Event.Missing(
				reason = missing.reason,
				observedAtMillis = 2_000,
				progressSurfaceLost = missing.progressSurfaceLost,
				inferredPlaying = missing.inferredPlaying,
				displayOff = missing.displayOff,
			),
			foregroundSurfaceOwned = true,
			hostNowMillis = 2_000,
		)
		assertEquals(1, neutral.size)
		assertTrue(neutral.single() is PlaybackInput.ForegroundSurfaceUnavailable)
	}

	@Test
	fun `a 2x hold that hides the footer still acquires and credits the Short`() {
		val adapter = NativeShortsAdapter()
		val epoch = NativeSourceSwitches.epochFor(YouTubeProbe.YOUTUBE_PACKAGE)!!
		val tracker = ForegroundShortTracker()

		fun poll(tree: NativeShortTree, atMillis: Long): ForegroundShortTracker.Update {
			val reading = adapter.readStableSurface(
				ShortsSurfaceCapture(tree, pipPlaying = true, inferenceEnabled = true),
				observedAtMillis = atMillis,
			)
			val inputs = when (reading) {
				is ShortsSurfaceReading.Proven -> adapter.read(
					NativeShortsObserver.Event.Parsed(
						result = reading.result,
						observedAtMillis = atMillis,
						inferredPlaying = reading.inferredPlaying,
					),
					foregroundSurfaceOwned = true,
					hostNowMillis = atMillis,
				)
				is ShortsSurfaceReading.Absent -> adapter.read(
					NativeShortsObserver.Event.Missing(
						reason = reading.reason,
						observedAtMillis = atMillis,
						progressSurfaceLost = reading.progressSurfaceLost,
						inferredPlaying = reading.inferredPlaying,
						playbackRate = reading.playbackRate,
					),
					foregroundSurfaceOwned = true,
					hostNowMillis = atMillis,
				)
			}
			return inputs.filterIsInstance<PlaybackInput.ForegroundSurface>()
				.map(tracker::reduce)
				.last()
		}

		// One footer frame, then the finger goes down and stays down.
		poll(player(Case("Held at 2x", "@owner", 0, 20)), 0)
		var finalized = emptyList<SessionSnapshot>()
		var position = 0L
		var at = 400L
		while (at <= 10_400) {
			// Two content seconds per wall-clock second, capped at the length.
			position = minOf(20, (at * 2) / 1_000)
			finalized = finalized + poll(held2x(position), at).finalized
			at += 1_000
		}

		assertTrue(
			"a Short held at 2x never acquired an identity and produced no listen",
			tracker.hasActive,
		)
		val listen = finalized.singleOrNull()
		assertNotNull("the 20s Short held at 2x did not finalize exactly once: $finalized", listen)
		assertEquals("Held at 2x", listen!!.title)
		assertEquals("@owner", listen.artist)
		assertEquals(20_000L, listen.durationMs)
		// Ten wall-clock seconds of a 20-second Short at 2x is the whole Short.
		assertTrue(
			"2x progress was credited as ordinary 1x wall clock: ${listen.playedMs}ms",
			listen.playedMs >= 20_000,
		)
		assertEquals(epoch, listen.sourceEpoch)
	}

	/** The tree YouTube publishes while the 2x press-and-hold is active. */
	private fun held2x(position: Long) = NativeShortTree(
		node(
			pkg = YT,
			children = listOf(
				node(
					id = "reel_watch_fragment_root",
					children = listOf(
						node(
							id = "reel_watch_player",
							children = listOf(
								node(text = "2x"),
								node(text = "Pull down to lock 2x speed"),
								seekbar(position, 20),
							),
						),
					),
				),
			),
		),
	)

	@Test
	fun `the production adapter preserves a complete first frame for immediate PiP`() {
		val adapter = NativeShortsAdapter()
		val firstTree = player(Case("Immediate PiP", "@owner", 1, 33))
		val waiting = adapter.readStableSurface(
			ShortsSurfaceCapture(firstTree, pipPlaying = false, inferenceEnabled = false),
			observedAtMillis = 1_000,
		)

		assertTrue("the first frame bypassed stabilization: $waiting", waiting is ShortsSurfaceReading.Absent)
		// The first PiP capture may still have a YouTube root. Its player/time-bar
		// containers survive, but the footer and readable time are gone. This exact
		// path must promote the pending frame before an Absent reading resets it.
		val pipTree = player(
			Case("Immediate PiP", "@owner", 1, 33),
			seekbarLabel = "8 of 67 seconds",
			includeHandle = false,
		)
		val handedOff = adapter.readStableSurface(
			ShortsSurfaceCapture(pipTree, pipPlaying = true, inferenceEnabled = true),
			observedAtMillis = 1_900,
		)
		assertTrue("the immediate PiP frame discarded its pending identity: $handedOff", handedOff is ShortsSurfaceReading.Proven)
		val promoted = (handedOff as ShortsSurfaceReading.Proven).result
		assertEquals(
			NativeShortParser.Result.Organic(
				title = "Immediate PiP",
				ownerHandle = "@owner",
				currentSeconds = 1,
				totalSeconds = 33,
			),
			promoted,
		)
	}

	@Test
	fun `a speed chip permits inference on the setting, not on the audio pair`() {
		val held = NativeShortTree(node(pkg = YT, children = listOf(node(id = "reel_watch_player"))))
		val reading = readSurface(held, pipPlaying = false, inferenceEnabled = true)

		assertTrue("expected no provable Short: $reading", reading is ShortsSurfaceReading.Absent)
		val absent = reading as ShortsSurfaceReading.Absent
		// This fixture has no chip, so the pair governs and it said no.
		assertFalse(
			"inference was permitted with neither a chip nor the audio pair",
			absent.inferredPlaying,
		)
	}

	/**
	 * A proven measurable Short carries the pair so the tracker can distinguish a
	 * genuinely advancing seekbar from the long cached plateaus seen in the field.
	 */
	@Test
	fun `a measurable Short carries paired playback evidence to the tracker`() {
		val short = Case("Dura - Becky G 🔥", "@fansclubbeckyg394", 7, 20)
		val reading = readSurface(player(short), pipPlaying = true, inferenceEnabled = true)

		assertTrue("a measurable Short was not proven: $reading",
			reading is ShortsSurfaceReading.Proven)
		assertTrue(
			"paired playback evidence was discarded before the tracker could detect a plateau",
			(reading as ShortsSurfaceReading.Proven).inferredPlaying,
		)
	}

	@Test
	fun `a readable footerless 2x surface carries the rate and uses the inference setting`() {
		val held = player(
			Case("Best Girl Guitarist 🎸 Bae", "@lfgbae", 2, 55),
			includeHandle = false,
			extraPlayer = listOf(node(text = "2x")),
		)

		val enabled = readSurface(held, pipPlaying = false, inferenceEnabled = true)
		assertTrue("the measurable held Short was not proven: $enabled",
			enabled is ShortsSurfaceReading.Proven)
		val proven = enabled as ShortsSurfaceReading.Proven
		val parsed = proven.result as NativeShortParser.Result.OrganicUnnamed
		assertEquals(2.0, parsed.playbackRate!!, 0.001)
		assertTrue(
			"the visible speed chip did not authorize opt-in stalled-progress inference",
			proven.inferredPlaying,
		)

		val disabled = readSurface(held, pipPlaying = true, inferenceEnabled = false)
		assertTrue(disabled is ShortsSurfaceReading.Proven)
		assertFalse(
			"the speed chip bypassed the owner's inference setting",
			(disabled as ShortsSurfaceReading.Proven).inferredPlaying,
		)
	}

	/**
	 * An unmeasured Short — proven, named, but with no readable seekbar — may
	 * accrue only when the paired evidence answers.
	 */
	@Test
	fun `an unmeasured Short accrues only when the audio pair answers`() {
		// A proof without a reading: player, container and handle all proven, but
		// the bar publishes no parseable time. Same fixture as
		// `a named player with no readable time is proven, not refused`.
		val unmeasured = player(
			Case("Title", "@owner", 1, 20),
			seekbarLabel = "8 of 67 seconds",
		)

		val withoutPair = readSurface(unmeasured, pipPlaying = false)
		val withPair = readSurface(unmeasured, pipPlaying = true)

		assertTrue(withoutPair is ShortsSurfaceReading.Proven)
		assertTrue(withPair is ShortsSurfaceReading.Proven)
		assertFalse(
			"an unmeasured Short accrued with no paired evidence",
			(withoutPair as ShortsSurfaceReading.Proven).inferredPlaying,
		)
		assertTrue(
			"an unmeasured Short did not accrue when the pair answered",
			(withPair as ShortsSurfaceReading.Proven).inferredPlaying,
		)
	}

	/** A retained mini-player alone is Absent, and Absent may still not accrue. */
	@Test
	fun `a retained miniplayer alone permits no inference`() {
		val reading = readSurface(
			NativeShortTree(node(pkg = YT, children = listOf(miniplayer()))),
			pipPlaying = true,
			inferenceEnabled = true,
		)

		assertTrue(reading is ShortsSurfaceReading.Absent)
		assertFalse(
			"a retained mini-player was credited as an unmeasurable Short",
			(reading as ShortsSurfaceReading.Absent).inferredPlaying,
		)
	}

	private data class Case(val title: String, val handle: String, val current: Long, val total: Long)

	private fun player(
		case: Case,
		titleId: String? = "reel_title",
		includeHandle: Boolean = true,
		includeSeekbar: Boolean = true,
		handleVisible: Boolean = true,
		secondHandle: String? = null,
		secondTitle: String? = null,
		secondSeekbar: Pair<Long, Long>? = null,
		seekbarLabel: String? = null,
		extraPlayer: List<NativeShortNode> = emptyList(),
		extraRoot: List<NativeShortNode> = emptyList(),
	): NativeShortTree {
		val playerChildren = mutableListOf(
			node(id = titleId, text = case.title),
			node(id = "sound_button", text = "Use this sound", clickable = true),
		)
		if (includeHandle) playerChildren += node(text = "Go to channel ${case.handle}", visible = handleVisible)
		secondHandle?.let { playerChildren += node(text = it) }
		secondTitle?.let { playerChildren += node(id = "reel_title", text = it) }
		if (includeSeekbar) playerChildren += seekbar(case.current, case.total, seekbarLabel)
		secondSeekbar?.let { playerChildren += seekbar(it.first, it.second) }
		playerChildren += extraPlayer
		val structural = node(
			id = "reel_watch_fragment_root",
			children = listOf(node(id = "reel_watch_player", children = playerChildren)) + extraRoot,
		)
		return NativeShortTree(node(pkg = YT, children = listOf(structural)))
	}

	private fun seekbar(current: Long, total: Long, label: String? = null): NativeShortNode = node(
		id = "reel_time_bar",
		description = label
			?: ("${current / 60} minutes ${current % 60} seconds of " +
				"${total / 60} minutes ${total % 60} seconds"),
	)

	private fun node(
		pkg: String? = null,
		id: String? = null,
		text: String? = null,
		description: String? = null,
		visible: Boolean = true,
		clickable: Boolean = false,
		className: String? = null,
		children: List<NativeShortNode> = emptyList(),
	) = NativeShortNode(
		packageName = pkg,
		resourceId = id?.let { "com.google.android.youtube:id/$it" },
		text = text,
		contentDescription = description,
		visible = visible,
		clickable = clickable,
		className = className,
		children = children,
	)

	/**
	 * A proven, measurable Short whose on-screen title could not be read.
	 *
	 * The listen survives — identity is resolved from watch history on owner
	 * handle + duration — but the title is never guessed from two candidates.
	 */
	private fun assertNoTitle(tree: NativeShortTree) {
		val result = NativeShortParser.parse(tree)
		assertTrue("expected Organic, got $result", result is NativeShortParser.Result.Organic)
		assertNull((result as NativeShortParser.Result.Organic).title)
	}

	private fun assertInvalid(tree: NativeShortTree) {
		assertTrue(NativeShortParser.parse(tree) is NativeShortParser.Result.Invalid)
	}

	private companion object {
		const val YT = YouTubeProbe.YOUTUBE_PACKAGE
	}
}
