package com.rustedwax.app.detect

import com.rustedwax.core.*
import com.rustedwax.app.scrobble.ScrobbleRules
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TrackProgressCarryTest {

	private val pkg = "com.android.chrome"
	private val track = "LUNA|Feid|196000"

	@Before fun setUp() = TrackProgressCarry.clear()
	@After fun tearDown() = TrackProgressCarry.clear()

	private fun progress(
		playedMs: Long,
		at: Long = 1_000_000L,
		lastPositionMs: Long? = null,
		loopDetected: Boolean = false,
		identity: YouTubeProbe.Identity? = null,
		explicitAdSignal: String? = null,
		accessibilityCoverage: MediaSessionAccessibilityEvidence.Coverage? = null,
		trackInstanceToken: Long? = null,
		promptFinalization: Boolean = false,
	) = TrackProgressCarry.Progress(
		playedMs = playedMs,
		trackStartedAtEpochSec = 1_785_000_000L,
		fastestSpeedSeen = 1.0,
		atMillis = at,
		lastPositionMs = lastPositionMs,
		loopDetected = loopDetected,
		identity = identity,
		explicitAdSignal = explicitAdSignal,
		accessibilityCoverage = accessibilityCoverage,
		trackInstanceToken = trackInstanceToken,
		promptFinalization = promptFinalization,
	)

	/**
	 * The field case, to the second: 47 + 24 + 85 = 156 s of 196 = 80%, where
	 * each fragment alone was 24%, 12% and 43%.
	 */
	@Test
	fun `three fragments of one video add up to a full listen`() {
		var carriedIn = 0L

		// Fragment 1 — session torn down after 47s.
		TrackProgressCarry.remember(pkg, track, progress(carriedIn + 47_000, at = 1_000_000))

		// Fragment 2 — new session claims it, plays 24s more.
		carriedIn = TrackProgressCarry.claim(pkg, track, now = 1_021_000)!!.playedMs
		assertEquals(47_000L, carriedIn)
		TrackProgressCarry.remember(pkg, track, progress(carriedIn + 24_000, at = 1_045_000))

		// Fragment 3 — claims again, plays 85s more.
		carriedIn = TrackProgressCarry.claim(pkg, track, now = 1_063_000)!!.playedMs
		assertEquals(71_000L, carriedIn)

		val total = carriedIn + 85_000
		assertEquals(156_000L, total)
		// The number that matters: this now clears the 60% threshold.
		assertEquals(0.79, total.toDouble() / 196_000, 0.01)
	}

	/**
	 * What the v0.11.0 opt-out sweep asks before it drops anything.
	 *
	 * `SessionProbe.refreshTargets` clears every package that stopped being a
	 * source, and browsers reach that path for the first time now that the
	 * YouTube master switch can un-accept them. It asks this so it can say a
	 * line when there was play time to lose and stay silent otherwise — a run of
	 * identical lines on every config change is what buried the last two
	 * diagnoses in this project.
	 */
	@Test
	fun `a package reports whether it is holding play time, and only its own`() {
		assertFalse(TrackProgressCarry.hasPackage(pkg))

		TrackProgressCarry.remember(pkg, track, progress(47_000))
		assertTrue(TrackProgressCarry.hasPackage(pkg))
		assertFalse(TrackProgressCarry.hasPackage("com.google.android.youtube"))

		TrackProgressCarry.clearPackage(pkg)
		assertFalse(TrackProgressCarry.hasPackage(pkg))
	}

	/** The listen's real start time travels with it, so the timestamp stays true. */
	@Test
	fun `the track start time is carried, not restarted`() {
		TrackProgressCarry.remember(pkg, track, progress(47_000))
		assertEquals(1_785_000_000L, TrackProgressCarry.claim(pkg, track, now = 1_010_000)!!.trackStartedAtEpochSec)
	}

	/** A track watched at 2× before the teardown is still reported as such. */
	@Test
	fun `the observed playback rate is carried`() {
		TrackProgressCarry.remember(
			pkg,
			track,
			TrackProgressCarry.Progress(90_000, 1_785_000_000L, 2.0, 1_000_000L),
		)
		assertEquals(2.0, TrackProgressCarry.claim(pkg, track, now = 1_010_000)!!.fastestSpeedSeen, 0.0)
	}

	@Test
	fun `the last position and detected loop are carried`() {
		TrackProgressCarry.remember(
			pkg,
			track,
			progress(
				playedMs = 254_000,
				lastPositionMs = 125_021,
				loopDetected = true,
			),
		)
		val carried = TrackProgressCarry.claim(pkg, track, now = 1_010_000)!!
		assertEquals(125_021L, carried.lastPositionMs)
		assertTrue(carried.loopDetected)
	}

	@Test
	fun `confirmed identity and explicit ad evidence are carried together`() {
		val identity = YouTubeProbe.Identity.Confirmed(
			videoId = "abcdefghijk",
			url = "https://www.youtube.com/watch?v=abcdefghijk",
			isMusic = false,
			isShort = true,
			source = "test",
		)
		TrackProgressCarry.remember(
			pkg,
			track,
			progress(
				playedMs = 47_000,
				identity = identity,
				explicitAdSignal = "Sponsored",
				trackInstanceToken = 73,
			),
		)
		val carried = TrackProgressCarry.claim(pkg, track, now = 1_010_000)!!
		assertEquals(identity, carried.identity)
		assertEquals("Sponsored", carried.explicitAdSignal)
		assertEquals(73L, carried.trackInstanceToken)
	}

	@Test
	fun `watch url organic A ad B organic A keeps evidence and progress separated`() {
		val organic = TrackIdentity("Organic A", "Artist", null, 180_000)
		val advert = TrackIdentity("Advert B", "Advertiser", null, 34_000)
		val organicCoverage = MediaSessionAccessibilityEvidence.Coverage(
			instance = MediaSessionAdEvidence.TrackInstance(pkg, 101, organic),
			atMillis = 1_000_000,
			urlGeneration = 9,
			videoId = "abcdefghijk",
		)
		val advertCoverage = MediaSessionAccessibilityEvidence.Coverage(
			instance = MediaSessionAdEvidence.TrackInstance(pkg, 202, advert),
			atMillis = 1_000_001,
			urlGeneration = 9,
			videoId = null,
		)

		TrackProgressCarry.remember(
			pkg,
			organic,
			progress(
				playedMs = 40_000,
				accessibilityCoverage = organicCoverage,
				trackInstanceToken = 101,
			),
		)
		TrackProgressCarry.remember(
			pkg,
			advert,
			progress(
				playedMs = 34_000,
				explicitAdSignal = "Sponsored",
				accessibilityCoverage = advertCoverage,
				trackInstanceToken = 202,
			),
		)

		val resumedAdvert = TrackProgressCarry.claim(pkg, advert, now = 1_010_000)!!
		assertEquals("Sponsored", resumedAdvert.explicitAdSignal)
		assertEquals(202L, resumedAdvert.trackInstanceToken)
		assertEquals(advertCoverage, resumedAdvert.accessibilityCoverage)
		assertTrue(
			!ScrobbleRules.decide(
				playedMs = resumedAdvert.playedMs,
				durationMs = advert.durationMs,
				explicitAdSignal = resumedAdvert.explicitAdSignal,
			).shouldScrobble,
		)

		val resumedOrganic = TrackProgressCarry.claim(pkg, organic, now = 1_010_000)!!
		assertEquals(40_000L, resumedOrganic.playedMs)
		assertNull(resumedOrganic.explicitAdSignal)
		assertEquals(101L, resumedOrganic.trackInstanceToken)
		assertEquals(organicCoverage, resumedOrganic.accessibilityCoverage)
		assertTrue(
			ScrobbleRules.decide(
				playedMs = resumedOrganic.playedMs + 80_000,
				durationMs = organic.durationMs,
				explicitAdSignal = resumedOrganic.explicitAdSignal,
			).shouldScrobble,
		)
	}

	// region what must not be carried

	@Test
	fun `exact-id-less native YouTube sessions never store or claim continuation progress`() {
		val native = YouTubeProbe.YOUTUBE_PACKAGE
		val bellakeo = TrackIdentity("BELLAKEO", "Peso Pluma & Anitta", null, 196_000)
		assertNull(TrackProgressCarry.remember(native, bellakeo, progress(47_000)))
		assertEquals(0, TrackProgressCarry.size())
		assertNull(TrackProgressCarry.claim(native, bellakeo, now = 1_010_000))
	}

	@Test
	fun `repeated exact-id-less native labels cannot inherit each others progress`() {
		val native = YouTubeProbe.YOUTUBE_PACKAGE
		val first = TrackIdentity("BELLAKEO", "Peso Pluma & Anitta", null, 196_000)
		val later = TrackIdentity("BELLAKEO", "Peso Pluma & Anitta", null, 196_000)
		assertNull(TrackProgressCarry.remember(native, first, progress(156_000)))
		assertNull(TrackProgressCarry.claim(native, later, now = 1_010_000))
	}

	@Test
	fun `native continuation is allowed only with exact immutable item authority`() {
		val native = YouTubeProbe.YOUTUBE_PACKAGE
		val exact = TrackIdentity(
			title = "Exact native item",
			artist = "Artist",
			album = null,
			durationMs = 180_000,
			sourceItemId = "abcdefghijk",
		)
		assertNotNull(TrackProgressCarry.remember(native, exact, progress(47_000)))
		assertEquals(47_000, TrackProgressCarry.claim(native, exact, now = 1_010_000)!!.playedMs)
	}

	@Test
	fun `native replacement must independently establish the same immutable id`() {
		val native = YouTubeProbe.YOUTUBE_PACKAGE
		val old = TrackIdentity(
			"La Rompe Corazones", "Daddy Yankee", null, 205_000, "abcdefghijk",
		)
		assertNotNull(TrackProgressCarry.remember(native, old, progress(106_000)))

		val unresolved = old.copy(sourceItemId = null)
		assertNull(TrackProgressCarry.claim(native, unresolved, now = 1_010_000))
		val different = old.copy(sourceItemId = "lmnopqrstuv")
		assertNull(TrackProgressCarry.claim(native, different, now = 1_010_000))
		assertEquals(
			106_000,
			TrackProgressCarry.claim(native, old.copy(), now = 1_010_000)!!.playedMs,
		)
		assertNull(TrackProgressCarry.claim(native, old.copy(), now = 1_010_000))
	}

	// region resuming after a long interruption

	private fun canYouFeelMyHeart() = TrackIdentity(
		title = """Bring Me The Horizon - "Can You Feel My Heart"""",
		artist = "Bring Me The Horizon",
		album = "Bring Me The Horizon",
		durationMs = 234_000,
		sourceItemId = "6AVRCQBc59w",
	)

	@Test
	fun `a listen resumed minutes later still adds up to one full listen`() {
		val native = YouTubeProbe.YOUTUBE_PACKAGE
		val track = canYouFeelMyHeart()
		assertNotNull(
			TrackProgressCarry.remember(
				native, track, progress(105_000, at = 1_000_000, lastPositionMs = 105_000),
			),
		)
		// Four minutes and three seconds away — far past the metadata window.
		val backAgain = 1_000_000L + 243_000L
		val claimed = TrackProgressCarry.claim(
			native, track, now = backAgain, resumePositionMs = 107_968,
		)
		assertEquals(105_000, claimed!!.playedMs)
		assertEquals(0, TrackProgressCarry.size())
	}

	/** The bound this replaces still has to hold: a replay is not a resume. */
	@Test
	fun `a later separate viewing of the same video inherits nothing`() {
		val native = YouTubeProbe.YOUTUBE_PACKAGE
		val track = canYouFeelMyHeart()
		TrackProgressCarry.remember(
			native, track, progress(105_000, at = 1_000_000, lastPositionMs = 105_000),
		)
		val later = 1_000_000L + 243_000L
		// Started from the beginning: a separate listen, and the fragment it did
		// not claim is still owed its own finalization.
		assertNull(TrackProgressCarry.claim(native, track, now = later, resumePositionMs = 0))
		assertNull(TrackProgressCarry.claim(native, track, now = later, resumePositionMs = 40_000))
		assertNull(TrackProgressCarry.claim(native, track, now = later, resumePositionMs = null))
		assertEquals(1, TrackProgressCarry.size())
	}

	/**
	 * A replay from zero must never land inside the window around the carried
	 * position, which is exactly what the floor guarantees.
	 */
	@Test
	fun `position may only extend the window from far enough into the item`() {
		assertTrue(
			TrackProgressCarry.RESUME_MIN_POSITION_MS > TrackProgressCarry.RESUME_WINDOW_MS,
		)
		val native = YouTubeProbe.YOUTUBE_PACKAGE
		val track = canYouFeelMyHeart()
		// Stopped 8s in: too early for position to distinguish a resume from a
		// replay, so only the ordinary window applies. Inside it, unchanged.
		TrackProgressCarry.remember(
			native, track, progress(8_000, at = 1_000_000, lastPositionMs = 8_000),
		)
		assertEquals(
			8_000,
			TrackProgressCarry.claim(
				native, track, now = 1_010_000, resumePositionMs = 8_100,
			)!!.playedMs,
		)
		// Outside it, position cannot rescue what position cannot distinguish.
		TrackProgressCarry.remember(
			native, track, progress(8_000, at = 1_000_000, lastPositionMs = 8_000),
		)
		val later = 1_000_000L + 243_000L
		assertNull(TrackProgressCarry.claim(native, track, now = later, resumePositionMs = 8_100))
	}

	/** Nothing is claimable forever; the long window is a bound, not a licence. */
	@Test
	fun `even a position-corroborated continuation expires eventually`() {
		val native = YouTubeProbe.YOUTUBE_PACKAGE
		val track = canYouFeelMyHeart()
		val token = TrackProgressCarry.remember(
			native, track, progress(105_000, at = 1_000_000, lastPositionMs = 105_000),
		)!!
		val tooLate = 1_000_000L + TrackProgressCarry.RESUMED_TTL_MS + 1
		assertNull(
			TrackProgressCarry.claim(native, track, now = tooLate, resumePositionMs = 105_000),
		)
		// And its owner is handed the fragment to finalize rather than losing it.
		TrackProgressCarry.remember(
			native, track, progress(105_000, at = 1_000_000, lastPositionMs = 105_000),
		)
		assertNotNull(TrackProgressCarry.expire(native, track.semanticKey, token + 1, tooLate))
	}

	/**
	 * A second viewing that ends while the first is still waiting displaces it.
	 * Over a fifteen-minute window that is an ordinary thing to do — play a song,
	 * play it again — and the displaced fragment is a listen of its own, so its
	 * owner must still be handed it. Losing it silently is the failure this whole
	 * object exists to prevent.
	 */
	@Test
	fun `a displaced continuation is still finalized by its owner`() {
		val native = YouTubeProbe.YOUTUBE_PACKAGE
		val track = canYouFeelMyHeart()
		val first = TrackProgressCarry.remember(
			native, track, progress(105_000, at = 1_000_000, lastPositionMs = 105_000),
		)!!
		val second = TrackProgressCarry.remember(
			native, track, progress(60_000, at = 1_200_000, lastPositionMs = 60_000),
		)!!
		assertEquals(105_000, TrackProgressCarry.expire(native, track.semanticKey, first)!!.playedMs)
		// Collected once only, and the newer continuation is untouched by it.
		assertNull(TrackProgressCarry.expire(native, track.semanticKey, first))
		assertEquals(
			60_000,
			TrackProgressCarry.claim(
				native, track, now = 1_210_000, resumePositionMs = 60_000,
			)!!.playedMs,
		)
		assertEquals(0, TrackProgressCarry.size())
		assertNotNull(second)
	}

	/**
	 * Patience is for a listen that might come back. Once a different track is
	 * demonstrably playing, waiting the rest of the fifteen minutes only leaves a
	 * finished listen unwritten for longer.
	 */
	@Test
	fun `a different track starting collects the listen that will not resume`() {
		val native = YouTubeProbe.YOUTUBE_PACKAGE
		val abandoned = canYouFeelMyHeart()
		val token = TrackProgressCarry.remember(
			native, abandoned, progress(105_000, at = 1_000_000, lastPositionMs = 105_000),
		)!!
		val nextSong = TrackIdentity(
			"Bring Me The Horizon - Ludens (Official Video)",
			"Bring Me The Horizon",
			null,
			283_000,
			"B9wvTuDC-H0",
		)
		TrackProgressCarry.abandon(native, nextSong)
		assertEquals(0, TrackProgressCarry.size())
		// Collected for its owner to finalize, never silently dropped.
		assertEquals(
			105_000,
			TrackProgressCarry.expire(native, abandoned.semanticKey, token)!!.playedMs,
		)
	}

	/**
	 * An ad or transition phase carries no resolved id, and must never be read as
	 * proof that the song it interrupted is over.
	 */
	@Test
	fun `an exact-id-less newcomer cannot end another track's wait`() {
		val native = YouTubeProbe.YOUTUBE_PACKAGE
		val waiting = canYouFeelMyHeart()
		TrackProgressCarry.remember(
			native, waiting, progress(105_000, at = 1_000_000, lastPositionMs = 105_000),
		)
		TrackProgressCarry.abandon(native, TrackIdentity("Some ad", "Advertiser", null, 30_000))
		assertEquals(1, TrackProgressCarry.size())
		// The interrupted listen is still there to be resumed afterwards.
		assertEquals(
			105_000,
			TrackProgressCarry.claim(
				native, waiting, now = 1_243_000, resumePositionMs = 107_968,
			)!!.playedMs,
		)
	}

	/** The track that is actually resuming must not collect itself. */
	@Test
	fun `abandon leaves the continuation for the track that is resuming`() {
		val native = YouTubeProbe.YOUTUBE_PACKAGE
		val track = canYouFeelMyHeart()
		TrackProgressCarry.remember(
			native, track, progress(105_000, at = 1_000_000, lastPositionMs = 105_000),
		)
		TrackProgressCarry.abandon(native, track)
		assertEquals(1, TrackProgressCarry.size())
	}

	@Test
	fun `a track that ran to its end is not held waiting to resume`() {
		val native = YouTubeProbe.YOUTUBE_PACKAGE
		val finished = TrackIdentity(
			"Zara Larsson - Ain't My Fault (Official Video)",
			"ZaraLarssonMusicVEVO",
			null,
			223_000,
			"eC-F_VZ2T1c",
		)
		val atTheEnd = progress(210_000, at = 1_000_000, lastPositionMs = 223_381)
		assertFalse(TrackProgressCarry.holdsResumeWindow(finished, atTheEnd))
		val token = TrackProgressCarry.remember(native, finished, atTheEnd)!!
		// Collectable on the ordinary schedule, exactly as before the long window
		// existed, so a completed listen is never delayed by it.
		assertEquals(
			210_000,
			TrackProgressCarry.expire(
				native, finished.semanticKey, token, now = 1_000_000 + TrackProgressCarry.TTL_MS,
			)!!.playedMs,
		)
		// Interrupted mid-item, the same track still gets the long window.
		assertTrue(
			TrackProgressCarry.holdsResumeWindow(
				finished, progress(105_000, at = 1_000_000, lastPositionMs = 105_000),
			),
		)
	}

	/**
	 * Reduced physical regression. YouTube Music token 553
	 * disappeared at 199165ms of 299235ms (66.6%) after resolving exact id
	 * UxQv0SGRt8g. The generic resumable-position path parked that already-earned
	 * auto-scrobble for fifteen minutes, leaving it in neither History nor Not
	 * logged. The scoring layer may request prompt disposition without teaching
	 * this carry store what a threshold means.
	 */
	@Test
	fun `an earned auto scrobble uses the ordinary continuation deadline`() {
		val native = YouTubeProbe.YOUTUBE_MUSIC_PACKAGE
		val song = TrackIdentity(
			"Estamos Clear (feat. Bad Bunny)",
			"Miky Woodz",
			null,
			299_235,
			"UxQv0SGRt8g",
		)
		val earned = progress(
			playedMs = 199_165,
			at = 1_000_000,
			lastPositionMs = 199_817,
			promptFinalization = true,
		)

		assertFalse(TrackProgressCarry.holdsResumeWindow(song, earned))
		val token = TrackProgressCarry.remember(native, song, earned)!!
		assertEquals(
			199_165,
			TrackProgressCarry.claim(
				native,
				song,
				resumePositionMs = 200_000,
				now = 1_000_000 + 30_000,
			)!!.playedMs,
		)

		// Recreate the vanished session to exercise its terminal deadline too.
		val expiringToken = TrackProgressCarry.remember(native, song, earned)!!
		assertNull(
			TrackProgressCarry.expire(
				native,
				song,
				expiringToken,
				now = 1_000_000 + TrackProgressCarry.TTL_MS - 1,
			),
		)
		assertEquals(
			199_165,
			TrackProgressCarry.expire(
				native,
				song,
				expiringToken,
				now = 1_000_000 + TrackProgressCarry.TTL_MS,
			)!!.playedMs,
		)
	}

	/** An exact id on both sides is what makes position mean anything. */
	@Test
	fun `the long window is offered only to an exactly identified track`() {
		val native = YouTubeProbe.YOUTUBE_PACKAGE
		val exact = canYouFeelMyHeart()
		val later = 1_000_000L + 243_000L
		TrackProgressCarry.remember(
			native, exact, progress(105_000, at = 1_000_000, lastPositionMs = 105_000),
		)
		// Same track, but this session has not established the id independently.
		assertNull(
			TrackProgressCarry.claim(
				native, exact.copy(sourceItemId = null), now = later, resumePositionMs = 107_968,
			),
		)
		assertNull(
			TrackProgressCarry.claim(
				native,
				exact.copy(sourceItemId = "lmnopqrstuv"),
				now = later,
				resumePositionMs = 107_968,
			),
		)
	}

	/** A browser track with no exact id keeps the metadata window exactly as before. */
	@Test
	fun `a browser continuation is unchanged by the position rule`() {
		TrackProgressCarry.remember(pkg, track, progress(47_000, at = 1_000_000, lastPositionMs = 90_000))
		val later = 1_000_000L + 243_000L
		assertNull(TrackProgressCarry.claim(pkg, track, now = later))
	}

	@Test
	fun `a browser with a latched address-bar id resumes like a native one`() {
		val chromeTrack = TrackIdentity(
			"Zara Larsson - Ain't My Fault (Official Video)", "Zara Larsson", null, 223_000,
		)
		val latched = YouTubeProbe.Identity.Confirmed(
			videoId = "eC-F_VZ2T1c",
			url = "https://m.youtube.com/watch?v=eC-F_VZ2T1c",
			isMusic = true,
			source = "address bar (latched)",
		)
		TrackProgressCarry.remember(
			pkg,
			chromeTrack,
			progress(90_000, at = 1_000_000, lastPositionMs = 90_000, identity = latched),
		)
		val later = 1_000_000L + 243_000L
		// A replacement that cannot name the video is refused, however well its
		// position lines up: the id is what makes position mean this video.
		assertNull(
			TrackProgressCarry.claim(pkg, chromeTrack, now = later, resumePositionMs = 91_000),
		)
		assertNull(
			TrackProgressCarry.claim(
				pkg, chromeTrack, now = later,
				resumePositionMs = 91_000, resumeVideoId = "lmnopqrstuv",
			),
		)
		assertEquals(
			90_000,
			TrackProgressCarry.claim(
				pkg, chromeTrack, now = later,
				resumePositionMs = 91_000, resumeVideoId = "eC-F_VZ2T1c",
			)!!.playedMs,
		)
	}


	// region interrupted continuity (Bug 2)

	/**
	 * A screen lock is not the end of a viewing.
	 *
	 * The physical case, to the millisecond: Gangnam Style at 71 065 ms with
	 * 71 286 ms measured, the screen locked, and the same video back four minutes
	 * later at 110 591 ms. The forty seconds in between played to nobody, so the
	 * position moved without the clock — which is precisely the shape the
	 * in-place resume window cannot describe, and precisely why the whole listen
	 * used to be thrown away and restarted at zero.
	 */
	@Test
	fun `an interrupted listen resumes at the position it kept playing to`() {
		val native = YouTubeProbe.YOUTUBE_PACKAGE
		val gangnamStyle = TrackIdentity(
			title = "PSY - GANGNAM STYLE(강남스타일) M/V",
			artist = "officialpsy",
			album = null,
			durationMs = 252_000,
			sourceItemId = "9bZkp7q19f0",
		)
		val interrupted = progress(71_286, at = 1_000_000, lastPositionMs = 71_065)
		// The long window is what makes the claim reachable at all.
		assertTrue(TrackProgressCarry.holdsResumeWindow(gangnamStyle, interrupted))
		TrackProgressCarry.remember(native, gangnamStyle, interrupted)

		assertEquals(
			71_286,
			TrackProgressCarry.claim(
				native,
				gangnamStyle,
				now = 1_000_000 + 244_000,
				resumePositionMs = 110_591,
			)!!.playedMs,
		)
	}

	/**
	 * The gap is a bound, not a credit.
	 *
	 * What comes back is the play time that was measured, unchanged. The forty
	 * seconds the position advanced by while nothing was watching are not in it,
	 * and this is the assertion that says so out loud: the claim is worth exactly
	 * what the transport had earned before the interruption.
	 */
	@Test
	fun `resuming further in credits none of the interval nobody watched`() {
		val native = YouTubeProbe.YOUTUBE_PACKAGE
		val track = canYouFeelMyHeart()
		TrackProgressCarry.remember(
			native, track, progress(71_286, at = 1_000_000, lastPositionMs = 71_065),
		)
		val claimed = TrackProgressCarry.claim(
			native, track, now = 1_000_000 + 244_000, resumePositionMs = 110_591,
		)!!
		assertEquals(71_286, claimed.playedMs)
		assertEquals(71_065L, claimed.lastPositionMs)
	}

	/**
	 * How far ahead the item may come back is the wall clock, and nothing else.
	 *
	 * Eighty seconds after the carry, the most of the item that can possibly have
	 * gone by is eighty seconds. A position two minutes further in is a seek or a
	 * later viewing; either way it is not the continuation of this one, and it
	 * inherits nothing.
	 */
	@Test
	fun `a jump the clock cannot account for is not a continuation`() {
		val native = YouTubeProbe.YOUTUBE_PACKAGE
		val track = canYouFeelMyHeart()
		val at = 1_000_000L
		val soonAfter = at + TrackProgressCarry.TTL_MS + 20_000

		TrackProgressCarry.remember(
			native, track, progress(71_286, at = at, lastPositionMs = 71_065),
		)
		assertNull(
			TrackProgressCarry.claim(
				native, track, now = soonAfter, resumePositionMs = 190_000,
			),
		)
		// The same carry, the same instant, a position the elapsed time can
		// account for: claimed. Only the size of the jump differed.
		assertEquals(
			71_286,
			TrackProgressCarry.claim(
				native, track, now = soonAfter, resumePositionMs = 145_000,
			)!!.playedMs,
		)
	}

	/**
	 * Forward only. An item that comes back *earlier* than it stopped was
	 * restarted or seeked backwards, and neither continues this viewing — the
	 * ordinary window around the carried position remains the whole allowance in
	 * that direction.
	 */
	@Test
	fun `an interruption never lets a rewind inherit the listen`() {
		val native = YouTubeProbe.YOUTUBE_PACKAGE
		val track = canYouFeelMyHeart()
		TrackProgressCarry.remember(
			native, track, progress(105_000, at = 1_000_000, lastPositionMs = 105_000),
		)
		assertNull(
			TrackProgressCarry.claim(
				native, track, now = 1_000_000 + 244_000, resumePositionMs = 45_000,
			),
		)
	}

	/**
	 * A different video is a different listen, however well its position lines up
	 * with the interruption.
	 */
	@Test
	fun `another video may not inherit an interrupted listen`() {
		val native = YouTubeProbe.YOUTUBE_PACKAGE
		val track = canYouFeelMyHeart()
		TrackProgressCarry.remember(
			native, track, progress(71_286, at = 1_000_000, lastPositionMs = 71_065),
		)
		val later = 1_000_000L + 244_000L
		assertNull(
			TrackProgressCarry.claim(
				native,
				track.copy(sourceItemId = "lmnopqrstuv"),
				now = later,
				resumePositionMs = 110_591,
			),
		)
		assertNull(
			TrackProgressCarry.claim(
				native,
				track.copy(sourceItemId = null),
				now = later,
				resumePositionMs = 110_591,
			),
		)
		// Untouched: the listen is still owed to the video that earned it.
		assertEquals(
			71_286,
			TrackProgressCarry.claim(
				native, track, now = later, resumePositionMs = 110_591,
			)!!.playedMs,
		)
	}

	/**
	 * An already-earned automatic listen still takes the ordinary deadline, so
	 * the interruption allowance can never reach it. Bug 5's sibling rule, held
	 * here because this is where a wider position rule would have quietly
	 * undone it.
	 */
	@Test
	fun `the interruption allowance does not extend an earned listen`() {
		val native = YouTubeProbe.YOUTUBE_MUSIC_PACKAGE
		val track = canYouFeelMyHeart()
		val earned = progress(
			playedMs = 199_165,
			at = 1_000_000,
			lastPositionMs = 199_817,
			promptFinalization = true,
		)
		TrackProgressCarry.remember(native, track, earned)
		assertNull(
			TrackProgressCarry.claim(
				native, track, now = 1_000_000 + 244_000, resumePositionMs = 220_000,
			),
		)
	}

	@Test
	fun `carry inherits the original interruption deadline instead of restarting it`() {
		val native = YouTubeProbe.YOUTUBE_PACKAGE
		val track = canYouFeelMyHeart()
		val interruptedAt = 1_000_000L
		val deadline = interruptedAt + TrackProgressCarry.RESUMED_TTL_MS
		val teardownAt = deadline - 20_000
		val progress = progress(
			playedMs = 71_286,
			at = teardownAt,
			lastPositionMs = 71_065,
		).copy(
			interruptionStartedAtMillis = interruptedAt,
			interruptionDeadlineMillis = deadline,
		)

		assertEquals(deadline, TrackProgressCarry.continuationDeadlineMillis(track, progress))
		TrackProgressCarry.remember(native, track, progress)
		assertNull(
			TrackProgressCarry.claim(
				native,
				track,
				now = deadline,
				resumePositionMs = 110_591,
			),
		)
	}

	@Test
	fun `carry remains claimable immediately before the original interruption deadline`() {
		val native = YouTubeProbe.YOUTUBE_PACKAGE
		val track = canYouFeelMyHeart()
		val interruptedAt = 1_000_000L
		val deadline = interruptedAt + TrackProgressCarry.RESUMED_TTL_MS
		val progress = progress(
			playedMs = 71_286,
			at = deadline - 20_000,
			lastPositionMs = 71_065,
		).copy(
			interruptionStartedAtMillis = interruptedAt,
			interruptionDeadlineMillis = deadline,
		)
		TrackProgressCarry.remember(native, track, progress)

		assertEquals(
			71_286,
			TrackProgressCarry.claim(
				native,
				track,
				now = deadline - 1,
				resumePositionMs = 110_591,
			)!!.playedMs,
		)
	}

	// endregion

	// endregion

	/**
	 * Consumed on read. Two sessions inheriting the same play time would double
	 * count it straight onto a chain that can't be edited.
	 */
	@Test
	fun `progress can only be claimed once`() {
		TrackProgressCarry.remember(pkg, track, progress(47_000))
		assertNotNull(TrackProgressCarry.claim(pkg, track, now = 1_010_000))
		assertNull(TrackProgressCarry.claim(pkg, track, now = 1_010_000))
	}

	/** Bounded so it can't attach itself to a genuine separate viewing later. */
	@Test
	fun `progress expires`() {
		TrackProgressCarry.remember(pkg, track, progress(47_000, at = 1_000_000))
		val tooLate = 1_000_000 + TrackProgressCarry.TTL_MS + 1
		assertNull(TrackProgressCarry.claim(pkg, track, now = tooLate))
	}

	@Test
	fun `an unclaimed continuation finalizes only after its grace period`() {
		val token = TrackProgressCarry.remember(
			pkg,
			track,
			progress(47_000, at = 1_000_000),
		)!!
		assertNull(
			TrackProgressCarry.expire(
				pkg,
				track,
				token,
				now = 1_000_000 + TrackProgressCarry.TTL_MS - 1,
			),
		)
		assertEquals(
			47_000L,
			TrackProgressCarry.expire(
				pkg,
				track,
				token,
				now = 1_000_000 + TrackProgressCarry.TTL_MS,
			)!!.playedMs,
		)
	}

	/**
	 * Each token collects its own fragment and only its own.
	 *
	 * The old token used to get null here, which read as "an older callback
	 * cannot steal a newer continuation" — true, but it also meant the displaced
	 * fragment was dropped without ever being finalized. Over the 60-second
	 * window that was nearly unreachable; over the resumed window (a listen may
	 * now wait fifteen minutes) it is an ordinary sequence, so the old token is
	 * handed back **its own** 10s. The invariant that mattered is unchanged and
	 * still asserted below: it does not receive the newer 20s, and the newer
	 * continuation is still there for its own owner.
	 */
	@Test
	fun `an old expiry token cannot consume a newer continuation`() {
		val old = TrackProgressCarry.remember(pkg, track, progress(10_000, at = 1_000_000))!!
		val newer = TrackProgressCarry.remember(pkg, track, progress(20_000, at = 1_010_000))!!
		assertEquals(
			10_000L,
			TrackProgressCarry.expire(
				pkg,
				track,
				old,
				now = 1_010_000 + TrackProgressCarry.TTL_MS,
			)!!.playedMs,
		)
		assertNull(
			TrackProgressCarry.expire(
				pkg,
				track,
				old,
				now = 1_010_000 + TrackProgressCarry.TTL_MS,
			),
		)
		assertEquals(
			20_000L,
			TrackProgressCarry.expire(
				pkg,
				track,
				newer,
				now = 1_010_000 + TrackProgressCarry.TTL_MS,
			)!!.playedMs,
		)
	}

	@Test
	fun `cancel removes only the matching continuation`() {
		val old = TrackProgressCarry.remember(pkg, track, progress(10_000))!!
		val newer = TrackProgressCarry.remember(pkg, track, progress(20_000))!!
		TrackProgressCarry.cancel(pkg, track, old)
		assertEquals(20_000L, TrackProgressCarry.claim(pkg, track, now = 1_010_000)!!.playedMs)
		assertNull(TrackProgressCarry.claim(pkg, track, now = 1_010_000))
		assertTrue(old != newer)
	}

	@Test
	fun `a different track does not inherit the time`() {
		TrackProgressCarry.remember(pkg, track, progress(47_000))
		assertNull(TrackProgressCarry.claim(pkg, "TQG|KAROL G|197000", now = 1_010_000))
	}

	@Test
	fun `a different browser does not inherit the time`() {
		TrackProgressCarry.remember(pkg, track, progress(47_000))
		assertNull(TrackProgressCarry.claim("com.brave.browser", track, now = 1_010_000))
	}

	@Test
	fun `package teardown clears only that native packages continuation`() {
		val youtube = YouTubeProbe.YOUTUBE_PACKAGE
		val music = YouTubeProbe.YOUTUBE_MUSIC_PACKAGE
		val youtubeTrack = TrackIdentity("YT exact", "Artist", null, 180_000, "abcdefghijk")
		val musicTrack = TrackIdentity("YTM exact", "Artist", null, 180_000, "lmnopqrstuv")
		TrackProgressCarry.remember(youtube, youtubeTrack, progress(47_000))
		TrackProgressCarry.remember(music, musicTrack, progress(29_000))

		TrackProgressCarry.clearPackage(youtube)

		assertNull(TrackProgressCarry.claim(youtube, youtubeTrack, now = 1_010_000))
		assertEquals(29_000L, TrackProgressCarry.claim(music, musicTrack, now = 1_010_000)!!.playedMs)
	}

	@Test
	fun `native recreation retains only the same exact source item`() {
		val pkg = YouTubeProbe.YOUTUBE_PACKAGE
		val first = TrackIdentity("Same title", "Same channel", null, 180_000, "abcdefghijk")
		val other = first.copy(sourceItemId = "lmnopqrstuv")
		TrackProgressCarry.remember(pkg, first, progress(73_000))

		assertNull(TrackProgressCarry.claim(pkg, other, now = 1_010_000))
		assertEquals(73_000L, TrackProgressCarry.claim(pkg, first, now = 1_010_000)!!.playedMs)
	}

	@Test
	fun `browser YouTube and YouTube Music packages cannot share continuation state`() {
		TrackProgressCarry.remember(pkg, track, progress(47_000))
		assertNull(
			TrackProgressCarry.claim(YouTubeProbe.YOUTUBE_PACKAGE, track, now = 1_010_000),
		)
		assertNull(
			TrackProgressCarry.claim(YouTubeProbe.YOUTUBE_MUSIC_PACKAGE, track, now = 1_010_000),
		)
		assertEquals(47_000L, TrackProgressCarry.claim(pkg, track, now = 1_010_000)!!.playedMs)
	}

	/** A session with no metadata yet must not claim someone else's progress. */
	@Test
	fun `a blank track key neither stores nor claims`() {
		TrackProgressCarry.remember(pkg, "", progress(47_000))
		assertEquals(0, TrackProgressCarry.size())
		assertNull(TrackProgressCarry.claim(pkg, "", now = 1_010_000))
	}

	/** Stop discards the in-flight track (D2); its time must not outlive it. */
	@Test
	fun `clear drops everything`() {
		TrackProgressCarry.remember(pkg, track, progress(47_000))
		TrackProgressCarry.clear()
		assertNull(TrackProgressCarry.claim(pkg, track, now = 1_010_000))
	}

	/** Expired entries don't accumulate for videos that never come back. */
	@Test
	fun `stale entries are pruned on write`() {
		TrackProgressCarry.remember(pkg, "gone-a", progress(1_000, at = 1_000_000))
		TrackProgressCarry.remember(pkg, "gone-b", progress(1_000, at = 1_000_000))
		assertEquals(2, TrackProgressCarry.size())
		TrackProgressCarry.remember(
			pkg,
			track,
			progress(1_000, at = 1_000_000 + TrackProgressCarry.TTL_MS + 1),
		)
		assertEquals(1, TrackProgressCarry.size())
	}
	// endregion
}
