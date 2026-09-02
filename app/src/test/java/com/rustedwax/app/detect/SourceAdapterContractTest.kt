package com.rustedwax.app.detect

import com.rustedwax.core.*
import com.rustedwax.core.ItemIdentity
import com.rustedwax.core.MetadataFields
import com.rustedwax.core.PlaybackSourceCapabilities
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SourceAdapterContractTest {

	/** A metadata bundle without Android, exactly as the production readers see it. */
	private class Fields(private val values: Map<String, Any>) : MetadataFields {
		override fun getString(key: String): String? = values[key] as? String
		override fun getLong(key: String): Long = (values[key] as? Long) ?: 0
		override fun bitmapDimensions(key: String): Pair<Int, Int>? = null
		override fun keySet(): Set<String> = values.keys
	}

	private fun fields(vararg pairs: Pair<String, Any>) = Fields(pairs.toMap())

	private val title = "android.media.metadata.TITLE"
	private val artist = "android.media.metadata.ARTIST"
	private val album = "android.media.metadata.ALBUM"
	private val displayTitle = "android.media.metadata.DISPLAY_TITLE"
	private val displaySubtitle = "android.media.metadata.DISPLAY_SUBTITLE"
	private val duration = "android.media.metadata.DURATION"
	private val mediaId = "android.media.metadata.MEDIA_ID"

	private val browser = BrowserYouTubeAdapter("com.brave.browser", "Brave")
	private val native = NativeYouTubeAdapter(YouTubeProbe.YOUTUBE_PACKAGE, "YouTube")
	private val music = YouTubeMusicAdapter(YouTubeProbe.YOUTUBE_MUSIC_PACKAGE, "YouTube Music")

	@Before
	fun reset() {
		UrlEvidence.clearAll()
		NotificationHints.clearAll()
		AdEvidence.clearAll()
		MediaSessionAdEvidence.clearAll()
		MediaSessionAccessibilityEvidence.clearAll()
	}

	@After
	fun tearDown() = reset()

	// ── 1. observations become the neutral values the reducer consumes ─────

	/**
	 * The same bundle, read by two sources, produces two different reducer inputs
	 * — and each is the one that source's field evidence requires.
	 *
	 * This is the branch that used to be `if (isNative)` inside `trackIdentityOf`,
	 * and it is the reason the split cannot be cosmetic: `DISPLAY_TITLE` is a tab
	 * in a browser and a track in the app, so one reader for both was wrong for
	 * one of them by construction.
	 */
	@Test
	fun `one bundle yields a different neutral track identity per source`() {
		val bundle = fields(
			displayTitle to "Sleepwalking",
			displaySubtitle to "BMTHOfficialVEVO",
			duration to 219_000L,
		)

		assertEquals(
			"a browser read the tab's display keys as a track",
			TrackIdentity(title = null, artist = null, album = null, durationMs = 219_000),
			browser.trackIdentity(bundle),
		)
		assertEquals(
			"the native app failed to read its own display keys",
			TrackIdentity(
				title = "Sleepwalking",
				artist = "BMTHOfficialVEVO",
				album = null,
				durationMs = 219_000,
			),
			native.trackIdentity(bundle),
		)
	}

	@Test
	fun `YouTube Music Song Video toggle remains one measured listen`() {
		val video = music.trackIdentity(
			fields(
				title to "Mr. Vegas - Buss It Open (Official Video)",
				artist to "Mr. Vegas",
				duration to 208_979L,
			),
		)
		val songBeforeAlbum = music.trackIdentity(
			fields(
				title to "Buss It Open",
				artist to "Mr. Vegas",
				duration to 196_533L,
			),
		)
		val song = music.trackIdentity(
			fields(
				title to "Buss It Open",
				artist to "Mr. Vegas",
				album to "Buss It Open",
				duration to 196_533L,
			),
		)

		assertEquals("Mr. Vegas - Buss It Open (Official Video)", video.title)
		assertEquals(video.semanticKey, song.semanticKey)
		val reducer = PlaybackReducer(music.playbackCapabilities)
		var state = ListenState(
			trackIdentity = video.copy(sourceItemId = "F6RfCQgfKhQ"),
			instanceToken = 41,
			instanceEstablishedAtMillis = 1_700_000_000_000,
			startedAtEpochSec = 1_700_000_000,
			everPublishedMetadata = true,
		)
		state = reducer.reduce(
			state,
			PlaybackInput.TransportChanged(
				transport = TransportState.PLAYING,
				speed = 1.0,
				previousPositionMs = 0,
				newPositionMs = 0,
				rawPositionMs = 0,
				durationMs = 208_979,
				transportHasExactId = false,
				elapsedRealtimeMs = 0,
			),
		).state

		fun publish(identity: TrackIdentity, elapsedMs: Long): PlaybackReducer.Transition =
			reducer.reduce(
				state,
				PlaybackInput.MetadataPublished(
					identity = identity,
					namesTabOnly = false,
					outgoingTransportHasExactId = false,
					hasPreResolvedNativeId = state.trackIdentity.hasExactSourceItemId,
					outgoingTitle = state.trackIdentity.title,
					nowMillis = 1_700_000_000_000 + elapsedMs,
					elapsedRealtimeMs = elapsedMs,
					nextInstanceToken = 42,
				),
			)

		for ((index, observation) in listOf(
			songBeforeAlbum to 7_443L,
			song to 7_855L,
			video to 120_000L,
		).withIndex()) {
			val (identity, elapsedMs) = observation
			val transition = publish(identity, elapsedMs)
			assertFalse(
				"a Song/Video presentation callback finalized a fragment",
				transition.before.any { it is PlaybackEffect.Finalize },
			)
			assertEquals(identity.title, transition.state.trackIdentity.title)
			if (index == 0) {
				assertTrue(PlaybackEffect.ClearPreResolvedNativeIdentity in transition.before)
				assertTrue(PlaybackEffect.RequestCarryAuthority in transition.effects)
				assertFalse(transition.state.trackIdentity.hasExactSourceItemId)
			}
			state = transition.state
		}

		assertEquals(41L, state.instanceToken)
		assertEquals(120_000L, state.playedMsAt(120_000L))

		// 120 s is below 60% of the retained 208979 ms work. Ending now exposes
		// exactly one terminal snapshot to the engine, so it can record one
		// below-threshold outcome rather than one outcome per presentation.
		val ended = reducer.reduce(
			state,
			PlaybackInput.FinalizeRequested("stopped", elapsedRealtimeMs = 120_000L),
		)
		assertEquals(
			1,
			(ended.before + ended.effects).count { it is PlaybackEffect.FreezeAndReport },
		)
		val duplicate = reducer.reduce(
			ended.state,
			PlaybackInput.FinalizeRequested("duplicate callback", elapsedRealtimeMs = 120_001L),
		)
		assertFalse(
			(duplicate.before + duplicate.effects).any { it is PlaybackEffect.FreezeAndReport },
		)
	}

	/** The tab-title rule is a browser rule, and stops at the browser. */
	@Test
	fun `only the browser treats a document title as naming its container`() {
		val tab = fields(title to "Bring Me The Horizon - Sleepwalking - YouTube")

		assertTrue(browser.namesContainerOnly(tab))
		assertFalse("the native app has no tab to name", native.namesContainerOnly(tab))
		assertEquals(
			"a native title was stripped by a browser rule",
			"Bring Me The Horizon - Sleepwalking - YouTube",
			native.presentedTitle(tab),
		)
	}

	/**
	 * The exact-id route: only a native session can name its own item, and only
	 * from the id/URI keys.
	 */
	@Test
	fun `a native media id becomes the neutral exact source item id`() {
		val bundle = fields(title to "Sleepwalking", mediaId to "dQw4w9WgXcQ")

		assertEquals("dQw4w9WgXcQ", native.trackIdentity(bundle).sourceItemId)
		assertTrue(native.trackIdentity(bundle).hasExactSourceItemId)
		assertNull(
			"a browser manufactured an exact item id it cannot have",
			browser.trackIdentity(bundle).sourceItemId,
		)
	}

	/**
	 * The browser's address bar reaches the reducer's resolver evidence, and the
	 * native app's playlist bar reaches a different field — never the same one.
	 */
	@Test
	fun `each source contributes its own evidence to the resolver context`() {
		UrlEvidence.put(
			"com.brave.browser",
			UrlEvidence.Evidence(
				raw = "https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PL123",
				host = "www.youtube.com",
				videoId = "dQw4w9WgXcQ",
				playlistId = "PL123",
			),
		)

		val browserReading = browser.readIdentity(request(fields()))
		assertEquals("dQw4w9WgXcQ", browserReading.resolverContext.observedVideoId)
		assertEquals("PL123", browserReading.resolverContext.playlistId)

		val nativeReading = native.readIdentity(request(fields(title to "Sleepwalking")))
		assertNull(
			"the native app read a browser's address bar",
			nativeReading.resolverContext.observedVideoId,
		)
		assertNull(
			"a native observation reached the proven-URL playlist field",
			nativeReading.resolverContext.playlistId,
		)
	}

	private fun request(
		fields: MetadataFields?,
		rejected: Set<String> = emptySet(),
		context: ResolverContext = ResolverContext(),
		soleSession: Boolean = true,
	) = SourceIdentityRequest(
		fields = fields,
		rejectedItemIds = rejected,
		resolverContext = context,
		soleSession = soleSession,
	)

	// ── 5. negative control: swap the observation, lose the result ─────────

	/**
	 * The same address-bar observation, offered to the native adapter, must not
	 * produce the browser's answer.
	 *
	 * Without this the tests above could all pass while `SessionProbe` quietly
	 * kept its own `isNative` branch and never called an adapter at all: both
	 * sides would agree because both sides would be the old code.
	 */
	@Test
	fun `swapping the source refuses the other source's evidence`() {
		UrlEvidence.put(
			"com.brave.browser",
			UrlEvidence.Evidence(
				raw = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
				host = "www.youtube.com",
				videoId = "dQw4w9WgXcQ",
			),
		)
		// The identical evidence, filed under the native package.
		UrlEvidence.put(
			YouTubeProbe.YOUTUBE_PACKAGE,
			UrlEvidence.Evidence(
				raw = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
				host = "www.youtube.com",
				videoId = "dQw4w9WgXcQ",
			),
		)

		val fromBrowser = browser.readIdentity(request(fields())).identity
		assertEquals(
			"the browser did not confirm the item its address bar named",
			"dQw4w9WgXcQ",
			fromBrowser.sourceItemId,
		)

		val fromNative = native.readIdentity(request(fields(title to "Sleepwalking"))).identity
		assertNull(
			"the native adapter confirmed an item from a browser address bar",
			fromNative.sourceItemId,
		)
		assertTrue(
			"the native adapter should still prove the source by package alone",
			fromNative.isSourceProven,
		)
	}

	/** A rejected id is not evidence, whichever store it is still sitting in. */
	@Test
	fun `a disproved item id is refused as address-bar evidence`() {
		UrlEvidence.put(
			"com.brave.browser",
			UrlEvidence.Evidence(
				raw = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
				host = "www.youtube.com",
				videoId = "dQw4w9WgXcQ",
			),
		)

		val reading = browser.readIdentity(
			request(fields(), rejected = setOf("dQw4w9WgXcQ")),
		)

		assertNull(
			"a disproved id was re-admitted as evidence",
			reading.resolverContext.observedVideoId,
		)
		assertNull(reading.identity.sourceItemId)
	}

	// ── browser same-package selection is the browser's policy ────────────

	private fun candidate(
		key: Long,
		named: Boolean = false,
		finalized: Boolean = false,
		playing: Boolean = true,
	) = SourceObservationCandidate(key, named, finalized, playing)

	/**
	 * A named observation binds only to the listen playing that item.
	 *
	 * Not the sole listen and not the playing one — that one. The defect this
	 * replaces tried elimination first, so a scan naming video B became video A's
	 * coverage and A's ad evidence the moment B's session had not yet appeared.
	 */
	@Test
	fun `a named host observation binds only to the listen playing that item`() {
		val bound = browser.selectForHostObservation(
			SourceHostObservationRequest(
				namedItemId = "dQw4w9WgXcQ",
				candidates = listOf(candidate(1), candidate(2, named = true)),
			),
		)

		assertEquals(SourceBinding.Bound(2, namedThisInstance = true), bound)
	}

	/** Two live same-package tabs and no name is ambiguous, so nothing binds. */
	@Test
	fun `two live tabs refuse an unnamed observation rather than choosing`() {
		val refused = browser.selectForHostObservation(
			SourceHostObservationRequest(
				namedItemId = null,
				candidates = listOf(candidate(1), candidate(2, playing = false)),
			),
		)

		assertTrue("an unnamed observation was bound under ambiguity: $refused",
			refused is SourceBinding.Refused)
		assertTrue(
			"the refusal did not describe the ambiguity: $refused",
			(refused as SourceBinding.Refused).reason.contains("2 active MediaSession tracks"),
		)
	}

	/** One live listen and no name binds by elimination — nothing else it could be. */
	@Test
	fun `a sole live listen binds an unnamed observation by elimination`() {
		val bound = browser.selectForHostObservation(
			SourceHostObservationRequest(
				namedItemId = null,
				candidates = listOf(candidate(7), candidate(8, finalized = true)),
			),
		)

		assertEquals(SourceBinding.Bound(7, namedThisInstance = false), bound)
	}

	/** Two listens claiming one id is a stale latch, not a reason to pick one. */
	@Test
	fun `two listens claiming the same item refuse rather than coin-toss`() {
		val refused = browser.selectForHostObservation(
			SourceHostObservationRequest(
				namedItemId = "dQw4w9WgXcQ",
				candidates = listOf(candidate(1, named = true), candidate(2, named = true)),
			),
		)

		assertTrue("a coin toss was taken: $refused", refused is SourceBinding.Refused)
	}

	/**
	 * A browser screen observation can never describe a first-party app.
	 *
	 * Refused by the source itself rather than filtered out by the host, so no
	 * downstream code has to ask what kind of source it is holding — which is the
	 * boundary this phase exists to establish.
	 */
	@Test
	fun `native sources refuse a host screen observation outright`() {
		val perfectlyBindableCandidates = SourceHostObservationRequest(
			namedItemId = "dQw4w9WgXcQ",
			candidates = listOf(candidate(1, named = true)),
		)

		listOf(native, music).forEach { adapter ->
			val verdict = adapter.selectForHostObservation(perfectlyBindableCandidates)
			assertTrue(
				"${adapter.packageName} accepted browser host evidence: $verdict",
				verdict is SourceBinding.Refused,
			)
		}
	}

	// ── 2. an adapter cannot broadcast, queue, dedup, settle or finalize ───

	/**
	 * A complete observation drive changes no source switch and produces no
	 * finalized listen.
	 *
	 * Settings are the checkable half of "cannot mutate settings": the live
	 * `NativeSourceSwitches` config is the state every opt-out, epoch and
	 * acceptance decision reads. Finalization is checkable structurally — the
	 * whole contract returns [SourceIdentityReading], [SourceAdVerdict],
	 * [SourceScreenScanVerdict] and [SourceEffect], and none of those vocabularies
	 * can express a payload, a queue entry, a dedup claim or a `SessionSnapshot`.
	 */
	@Test
	fun `driving every adapter method leaves the source configuration untouched`() {
		val before = NativeSourceSwitches.config.value

		listOf(browser, native, music).forEach { adapter ->
			val bundle = fields(title to "Sleepwalking", duration to 219_000L)
			adapter.trackIdentity(bundle)
			adapter.presentedTitle(bundle)
			adapter.presentedArtist(bundle)
			adapter.namesContainerOnly(bundle)
			adapter.readIdentity(request(bundle))
			adapter.onIdentitySelected(
				SourceIdentitySelected(
					identity = YouTubeProbe.Identity.Unconfirmed("nothing proved it"),
					justConfirmed = true,
				),
			)
			val instance = MediaSessionAdEvidence.TrackInstance(
				packageName = adapter.packageName,
				token = 7,
				signature = TrackIdentity(
					title = "Sleepwalking", artist = null, album = null, durationMs = 219_000,
				),
			)
			adapter.bindTrackInstance(instance, establishedAtMillis = 1_000)
			adapter.coverageFor(
				SourceCoverageRequest(instance, null, null, nowMillis = 2_000),
			)
			adapter.restoreCarriedEvidence(
				SourceCarryRestoreRequest(instance, 1_000, null, null, atMillis = 2_000),
			)
			adapter.mayPreResolveExactItemId(SourceExactIdRequest(bundle, 219_000))
			adapter.notes(SourceMoment.ANNOUNCED)
			adapter.notes(SourceMoment.FINALIZED)
			adapter.unbindTrackInstance(instance)
			adapter.releaseTrackInstance(instance)
		}

		assertEquals(
			"an adapter mutated the live source configuration",
			before,
			NativeSourceSwitches.config.value,
		)
	}

	@Test
	fun `the Shorts adapter translates observations and can express no finalized listen`() {
		val shorts = NativeShortsAdapter()
		val before = NativeSourceSwitches.config.value

		val inputs: List<PlaybackInput> = shorts.read(
			NativeShortsObserver.Event.Missing(
				reason = "progress surface lost",
				observedAtMillis = 5_000,
				progressSurfaceLost = true,
				inferredPlaying = true,
			),
			foregroundSurfaceOwned = false,
			hostNowMillis = 5_000,
		)

		assertEquals(
			"the adapter did not emit the source-neutral reducer vocabulary",
			listOf(
				PlaybackInput.ForegroundSurfaceUnavailable(
					reason = "progress surface lost",
					nowMillis = 5_000,
					progressSurfaceLost = true,
					playing = true,
					playbackRate = null,
					discard = false,
				),
				PlaybackInput.PictureInPictureObserved(
					nowMillis = 5_000,
					playing = true,
					durationMs = null,
				),
			),
			inputs,
		)
		assertEquals(
			"the Shorts adapter mutated the live source configuration",
			before,
			NativeSourceSwitches.config.value,
		)
	}

	@Test
	fun `the Shorts adapter preserves a visible 2x rate on a proven footerless surface`() {
		val shorts = NativeShortsAdapter()
		val epoch = NativeSourceSwitches.epochFor(YouTubeProbe.YOUTUBE_PACKAGE)!!

		assertEquals(
			listOf(
				PlaybackInput.ForegroundSurfaceObserved(
					identity = null,
					positionMs = 2_000,
					durationMs = 55_000,
					nowMillis = 10_000,
					sourceEpoch = epoch,
					playing = true,
					playbackRate = 2.0,
				),
			),
			shorts.read(
				NativeShortsObserver.Event.Parsed(
					result = NativeShortParser.Result.OrganicUnnamed(
						currentSeconds = 2,
						totalSeconds = 55,
						playbackRate = 2.0,
					),
					observedAtMillis = 10_000,
					inferredPlaying = true,
				),
				foregroundSurfaceOwned = true,
				hostNowMillis = 10_000,
			),
		)
	}

	/**
	 * Inference is offered to the ordinary listen only when the foreground route
	 * holds nothing — the no-double-credit rule, at the source that decides it.
	 */
	@Test
	fun `a latched foreground Short is never also credited as inferred time`() {
		val shorts = NativeShortsAdapter()
		val progressLost = NativeShortsObserver.Event.Missing(
			reason = "progress surface lost",
			observedAtMillis = 5_000,
			progressSurfaceLost = true,
			inferredPlaying = true,
		)

		assertEquals(
			"a latched Short also offered inference to the MediaSession route",
			listOf(
				PlaybackInput.ForegroundSurfaceUnavailable(
					reason = "progress surface lost",
					nowMillis = 5_000,
					progressSurfaceLost = true,
					playing = true,
					playbackRate = null,
					discard = false,
				),
			),
			shorts.read(
				progressLost,
				foregroundSurfaceOwned = true,
				hostNowMillis = 5_000,
			),
		)
	}

	// ── 3. two adapters coexist without shared mutable selection state ─────

	/**
	 * Two adapters for the same package, and one for another, each answer from
	 * their own immutable declarations.
	 *
	 * Selection state living in a singleton is the failure this rules out: the
	 * first adapter constructed would decide what the second one is.
	 */
	@Test
	fun `two adapters for the same package do not share selection state`() {
		val first = BrowserYouTubeAdapter("com.brave.browser", "Brave")
		val second = BrowserYouTubeAdapter("com.android.chrome", "Chrome")

		assertEquals("com.brave.browser", first.packageName)
		assertEquals("com.android.chrome", second.packageName)
		assertNotEquals(
			"two adapters shared one instance",
			first as SourceAdapter,
			second as SourceAdapter,
		)
		// The native adapter constructed between them changes neither.
		val interleaved = NativeYouTubeAdapter(YouTubeProbe.YOUTUBE_PACKAGE, "YouTube")
		assertTrue(first.evidenceCapabilities.publishesHostScreenEvidence)
		assertFalse(interleaved.evidenceCapabilities.publishesHostScreenEvidence)
		assertTrue(second.evidenceCapabilities.publishesHostScreenEvidence)
	}

	/**
	 * The ignored-package diagnostic must say what the frozen implementation said.
	 *
	 * The legacy text was chosen by `YouTubeProbe.isNativePackage`: only a
	 * native package could be ignored for "the toggle is off", because only a
	 * native package has a toggle of its own. A browser rejected by the YouTube
	 * master switch read "not a supported source package".
	 *
	 * The adapter registry answered this by asking whether `forPackage` returned
	 * anything — which is true for Brave and Chrome — so every rejected browser
	 * session started claiming a native toggle it does not have. Playback
	 * measurement is unaffected, which is exactly why it needs a test: it is a
	 * silent `EventLog` divergence from the frozen behaviour, and the parity gate
	 * does not read that line.
	 */
	@Test
	fun `an ignored package is described the way the frozen implementation described it`() {
		val frozen = mapOf(
			YouTubeProbe.YOUTUBE_PACKAGE to "native source toggle is off",
			YouTubeProbe.YOUTUBE_MUSIC_PACKAGE to "native source toggle is off",
			"com.brave.browser" to "not a supported source package",
			"com.brave.browser_beta" to "not a supported source package",
			"com.brave.browser_nightly" to "not a supported source package",
			"com.android.chrome" to "not a supported source package",
			"com.chrome.beta" to "not a supported source package",
			"com.chrome.dev" to "not a supported source package",
			// The instrumented suite's own package: watchable, but not a source
			// this registry speaks for.
			"com.rustedwax.app.test" to "not a supported source package",
			"com.spotify.music" to "not a supported source package",
		)

		val actual = frozen.keys.associateWith { SourceRegistry.ignoredReason(it) }

		assertEquals(
			"the ignored-package diagnostic diverged from the frozen implementation",
			frozen,
			actual,
		)
	}

	/** The registry is the only place a package decides which adapter speaks. */
	@Test
	fun `the registry selects one adapter per package and refuses the rest`() {
		assertTrue(
			SourceRegistry.forPackage("com.brave.browser", "Brave") is BrowserYouTubeAdapter,
		)
		assertTrue(
			SourceRegistry.forPackage("com.android.chrome", "Chrome") is BrowserYouTubeAdapter,
		)
		assertTrue(
			SourceRegistry.forPackage(YouTubeProbe.YOUTUBE_PACKAGE, "YouTube")
				is NativeYouTubeAdapter,
		)
		assertTrue(
			SourceRegistry.forPackage(YouTubeProbe.YOUTUBE_MUSIC_PACKAGE, "Music")
				is YouTubeMusicAdapter,
		)
		assertNull(SourceRegistry.forPackage("com.spotify.music", "Spotify"))
	}

	@Test
	fun `only the YouTube Music adapter declares trusted artist metadata`() {
		assertTrue(music.profile.trustsMetadataArtist)
		assertFalse("ordinary native YouTube trusted a channel as an artist", native.profile.trustsMetadataArtist)
		assertFalse("a browser trusted a channel as an artist", browser.profile.trustsMetadataArtist)
	}

	@Test
	fun `YouTube Music can prove a genuine short presentation without changing native YouTube`() {
		val shortTrack = SourceExactIdRequest(
			fields = fields(title to "Skit", artist to "Someone", duration to 30_000L),
			durationMs = 30_000,
		)

		assertTrue("Music needs positive attribution for genuine short works", music.mayPreResolveExactItemId(shortTrack))
		assertFalse(
			"native YouTube keeps its existing 60-second continuity policy",
			native.mayPreResolveExactItemId(shortTrack),
		)
		assertFalse(
			"missing artist is not positive Music attribution evidence",
			music.mayPreResolveExactItemId(
				shortTrack.copy(fields = fields(title to "Skit", duration to 30_000L)),
			),
		)
		assertFalse(
			"a non-positive duration cannot identify the current presentation",
			music.mayPreResolveExactItemId(shortTrack.copy(durationMs = 0)),
		)
	}

	/** YouTube Music must not read, or clear, the YouTube app's playlist bar. */
	@Test
	fun `YouTube Music neither reads nor clears the other app's playlist evidence`() {
		NativePlaylistObserver.observe(
			NativePlaylistParser.Result.Context(
				playlistName = "2Pac Greatest",
				ownerName = "Someone",
				position = 41,
				total = 180,
			),
			nowMillis = 1_000,
		)

		val musicReading = music.readIdentity(request(fields(title to "Intro")))
		assertNull(
			"YouTube Music attached the YouTube app's playlist bar to its own listen",
			musicReading.resolverContext.nativePlaylistName,
		)

		music.onPackageStateReset()
		assertEquals(
			"YouTube Music cleared evidence belonging to the YouTube app",
			"2Pac Greatest",
			NativePlaylistObserver.current()?.playlistName,
		)

		val youTubeReading = native.readIdentity(request(fields(title to "Intro")))
		assertEquals("2Pac Greatest", youTubeReading.resolverContext.nativePlaylistName)

		native.onPackageStateReset()
		assertNull(NativePlaylistObserver.current())
	}

	// ── 4. a fake non-YouTube adapter reaches the real reducer ─────────────

	/** A source that has never heard of YouTube, answering the shared contract. */
	private data class PodcastIdentity(
		override val sourceItemId: String?,
		override val canonicalLink: String?,
		override val isSourceProven: Boolean = true,
		override val source: String = "podcast host",
	) : ItemIdentity

	/**
	 * A source with a capability combination **no YouTube package produces**, so
	 * the reducer cannot be passing by matching a known profile.
	 */
	private class FakePodcastAdapter : SourceAdapter {
		override val packageName = "fm.example.podcasts"
		override val appLabel = "Example Podcasts"
		override val originName = "Example Podcasts"
		override val playbackCapabilities = PlaybackSourceCapabilities(
			republishesShorterDurations = false,
			requiresExactIdToCarryProgress = true,
			usesStoppedReplacementGrace = true,
			supportsPictureInPictureInference = false,
		)
		override val evidenceCapabilities = SourceEvidenceCapabilities(
			packageProvesSource = true,
			publishesHostScreenEvidence = false,
			publishesStructuredTransportDump = false,
			scopedBySourceEpoch = false,
			presentsForegroundShorts = false,
		)
		override val profile = SourceProfile(
			platform = "example",
			publishesExactTrackId = true,
			needsCanonicalUrl = false,
			canonicalUrlPattern = null,
			requiresAdEvidence = false,
			minDurationSeconds = 0,
			trustsMetadataArtist = true,
		)
		override val teardownPolicy = SourceTeardownPolicy.FINALIZE_IN_FLIGHT

		override fun trackIdentity(fields: MetadataFields?) = TrackIdentity(
			title = fields?.getString("episode"),
			artist = fields?.getString("show"),
			album = null,
			durationMs = fields?.getLong("length"),
			sourceItemId = fields?.getString("guid"),
		)

		override fun presentedTitle(fields: MetadataFields?) = fields?.getString("episode")
		override fun presentedArtist(fields: MetadataFields?) = fields?.getString("show")
		override fun namesContainerOnly(fields: MetadataFields?) = false

		override fun readIdentity(request: SourceIdentityRequest) = SourceIdentityReading(
			identity = PodcastIdentity(
				sourceItemId = request.fields?.getString("guid"),
				canonicalLink = "https://example.fm/e/${request.fields?.getString("guid")}",
			),
			resolverContext = request.resolverContext,
		)

		override fun hostNotificationHint(fields: MetadataFields?, soleSession: Boolean) = null
		override fun selectForHostObservation(request: SourceHostObservationRequest) =
			SourceBinding.Refused("a podcast host publishes no screen observations")
		override fun onIdentitySelected(request: SourceIdentitySelected) = emptyList<SourceEffect>()
		override fun bindTrackInstance(
			instance: MediaSessionAdEvidence.TrackInstance,
			establishedAtMillis: Long,
		) = Unit
		override fun unbindTrackInstance(instance: MediaSessionAdEvidence.TrackInstance) = Unit
		override fun releaseTrackInstance(instance: MediaSessionAdEvidence.TrackInstance) = Unit
		override fun coverageFor(request: SourceCoverageRequest) = null
		override fun restoreCarriedEvidence(request: SourceCarryRestoreRequest) =
			SourceCarryRestoreResult()
		override fun bindVisibleAdEvidence(request: SourceVisibleAdRequest) = SourceAdVerdict.NONE
		override fun bindHostAdLabel(request: SourceHostAdLabelRequest) = SourceAdVerdict.NONE
		override fun bindScreenScan(request: SourceScreenScanRequest) = SourceScreenScanVerdict()
		override fun mayPreResolveExactItemId(request: SourceExactIdRequest) = true
		override fun notes(moment: SourceMoment) = emptyList<SourceNote>()
		override fun onPackageStateReset() = Unit
	}

	@Test
	fun `a fake non-YouTube adapter drives the shipping reducer to one finalization`() {
		val adapter = FakePodcastAdapter()
		val reducer = PlaybackReducer(adapter.playbackCapabilities)
		val bundle = fields(
			"episode" to "Why bridges fall down",
			"show" to "99% Invisible",
			"length" to 1_800_000L,
			"guid" to "urn:example:ep:4417",
		)

		val identity = adapter.trackIdentity(bundle)
		assertEquals("urn:example:ep:4417", identity.sourceItemId)

		var state = ListenState(
			trackIdentity = TrackIdentity(
				title = null, artist = null, album = null, durationMs = null,
			),
			instanceToken = 1,
			instanceEstablishedAtMillis = 0,
			startedAtEpochSec = 1_786_233_600,
		)
		state = reducer.reduce(
			state,
			PlaybackInput.MetadataPublished(
				identity = identity,
				namesTabOnly = adapter.namesContainerOnly(bundle),
				outgoingTransportHasExactId = false,
				hasPreResolvedNativeId = false,
				outgoingTitle = null,
				nowMillis = 1_786_233_600_000,
				elapsedRealtimeMs = 10_000,
				nextInstanceToken = 2,
			),
		).state
		state = reducer.reduce(
			state,
			PlaybackInput.TransportChanged(
				transport = TransportState.PLAYING,
				speed = 1.0,
				previousPositionMs = null,
				newPositionMs = 0,
				rawPositionMs = 0,
				durationMs = 1_800_000,
				transportHasExactId = true,
				elapsedRealtimeMs = 10_000,
			),
		).state

		assertEquals(
			"the shared accumulator measured nothing for a non-YouTube source",
			600_000,
			state.playedMsAt(610_000),
		)

		val ended = reducer.reduce(
			state,
			PlaybackInput.FinalizeRequested("episode ended", elapsedRealtimeMs = 610_000),
		)

		assertEquals(
			"a non-YouTube listen did not reach exactly one freeze-and-report",
			1,
			(ended.before + ended.effects).count { it is PlaybackEffect.FreezeAndReport },
		)
		assertTrue("the listen was not marked finalized", ended.state.finalized)
		assertEquals(
			"the neutral identity was rewritten by the shared core",
			"urn:example:ep:4417",
			ended.state.trackIdentity.sourceItemId,
		)
	}

	/**
	 * The fake source's own identity type reaches the shared identity contract.
	 *
	 * [SourceIdentityReading] declares [ItemIdentity], not a YouTube verdict, so a
	 * source with a URN and no video id can answer it. The `as?` in the assertion
	 * is the point: there is no YouTube verdict to be had.
	 */
	@Test
	fun `a fake adapter answers the identity contract without a YouTube verdict`() {
		val reading = FakePodcastAdapter().readIdentity(
			request(fields("guid" to "urn:example:ep:4417")),
		)

		assertEquals("urn:example:ep:4417", reading.identity.sourceItemId)
		assertEquals("https://example.fm/e/urn:example:ep:4417", reading.identity.canonicalLink)
		assertNull(
			"a non-YouTube source produced a YouTube identity",
			reading.identity as? YouTubeProbe.Identity,
		)
		// A source that publishes its own id needs no YouTube resolver evidence,
		// and the contract must not have invented any on its behalf.
		assertEquals(
			"the shared contract wrote YouTube resolver evidence for a foreign source",
			ResolverContext(),
			reading.resolverContext,
		)
	}
}
