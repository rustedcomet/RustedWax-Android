package com.rustedwax.app.enrich

import com.rustedwax.youtube.identity.VideoResolution
import com.rustedwax.youtube.identity.PerformerCreditEvidence
import com.rustedwax.youtube.identity.VideoResolutionFailure
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shelf a Video-mode presentation actually lives on.
 *
	 * Regression: Video mode can publish work, artist metadata and duration with no
	 * exact item id. The songs shelf omits the matching video row, so the video
	 * shelf must be queried without accepting a similarly titled different work.
 *
 * The catalog does hold this work. It holds it as
 * `Fade Away — Video • Buju Banton & Kabaka Pyramid`, and the songs filter the
 * catalog route needs — the thing that makes a row name its album and its running
 * time — excludes video rows by construction. What the songs shelf returns for
 * this artist is `Faded Away`, a *different* work, which must go on being refused.
 * So the route answered honestly about the rows it was allowed to see and the
	 * otherwise a verified listen can be lost.
 *
 * These tests fix the two halves of the recovery: which video rows are worth a
 * canonical page ([VideoIdResolver.youTubeMusicVideoRowCandidates]), and what the
 * page has to say before an id is accepted ([NativeStructuredMusicMatcher.select],
 * unchanged and shared with the songs shelf). Nothing here is fuzzy: a different
 * work, a different credit, a different length or a second surviving upload all
 * still refuse.
 */
class YouTubeMusicVideoRowRecoveryTest {

	private val work = "Fade Away"
	private val artist = "Buju Banton & Kabaka Pyramid"
	private val durationSec = 219L
	private val videoId = "fAdeAwAy123"

	// ── fixtures ─────────────────────────────────────────────────────────────

	/** A row as the videos shelf renders it: artists, then views, then `m:ss`. */
	private fun videoRow(
		videoId: String,
		title: String,
		artists: List<String>,
		duration: String?,
		musicVideoType: String = "MUSIC_VIDEO_TYPE_OMV",
	): String {
		val artistRuns = artists.joinToString(""", {"text": ", "}, """) { name ->
			"""{"text": "$name", "navigationEndpoint": {"browseEndpoint": {
				"browseEndpointContextSupportedConfigs": {"browseEndpointContextMusicConfig": {
				  "pageType": "MUSIC_PAGE_TYPE_ARTIST"}}}}}"""
		}
		val trailing = duration?.let {
			""", {"text": " • "}, {"text": "1.2M views"}, {"text": " • "}, {"text": "$it"}"""
		}.orEmpty()
		return """
			{"musicResponsiveListItemRenderer": {
			  "flexColumns": [
			    {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [
			      {"text": "$title", "navigationEndpoint": {"watchEndpoint": {
			        "videoId": "$videoId", "watchEndpointMusicSupportedConfigs": {
			          "watchEndpointMusicConfig": {"musicVideoType": "$musicVideoType"}}}}}
			    ]}}},
			    {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [
			      $artistRuns$trailing
			    ]}}}
			  ],
			  "playlistItemData": {"videoId": "$videoId"}
			}}
		""".trimIndent()
	}

	private fun shelf(vararg rows: String) = """{"contents": [${rows.joinToString(",")}]}"""

	private fun rows(vararg rows: String) =
		YouTubeMusicCatalogSearchParser.candidates(shelf(*rows))

	/** One canonical watch page, as `fetchCanonicalResolution` returns it. */
	private fun page(
		videoId: String,
		title: String,
		channel: String,
		lengthSeconds: Long,
	) = VideoResolution(
		videoId = videoId,
		source = "YouTube Music video search",
		title = title,
		channel = channel,
		lengthSeconds = lengthSeconds,
	)

	/**
	 * The two questions the recovery asks of a video row, in the order it asks
	 * them — the songs shelf's own predicates, reused rather than reinvented.
	 */
	private fun plausible(
		vararg rows: String,
		title: String = work,
		artist: String = this.artist,
		album: String? = null,
		durationSec: Long = this.durationSec,
	) = VideoIdResolver.narrowYouTubeMusicCatalogCandidates(
		rows(*rows).filter { YouTubeMusicCatalogSearchParser.matches(it, title, artist) },
		album,
		durationSec,
	)

	// ── A. a representative video row resolves ───────────────────────────────

	@Test
	fun `the video row for the played presentation is worth its page`() {
		val shelf = plausible(
			videoRow(videoId, "Fade Away", listOf("Buju Banton", "Kabaka Pyramid"), "3:39"),
		)

		assertEquals(listOf(videoId), shelf.map { it.videoId })
		assertEquals(
			"the row's own credit travels with the id for the finalization corroborator",
			listOf("Buju Banton", "Kabaka Pyramid"),
			shelf.single().artists,
		)
	}

	@Test
	fun `its canonical page proves the id and pins the 219s presentation`() {
		val verified = NativeStructuredMusicMatcher.select(
			listOf(page(videoId, "Buju Banton & Kabaka Pyramid - Fade Away", "Buju Banton", 219)),
			work,
			artist,
			durationSec,
		)

		val resolution = verified.resolution
		assertEquals(videoId, resolution?.videoId)
		assertTrue(
			"without this the 219s presentation can never be attributed and the listen scores 0",
			resolution?.presentationDurationCorroborated == true,
		)
		assertTrue(resolution?.uniquelyResolved == true)
	}

	// ── B. the near-miss song title stays refused ────────────────────────────

	@Test
	fun `Faded Away is a different work and is not recovered`() {
		assertTrue(
			"the songs shelf's own answer for this artist must not become this work",
			plausible(
				videoRow("fAdEdAwAy12", "Faded Away", listOf("Kabaka Pyramid", "Buju Banton"), "3:39"),
			).isEmpty(),
		)
	}

	@Test
	fun `a Faded Away page cannot prove Fade Away either`() {
		val verified = NativeStructuredMusicMatcher.select(
			listOf(page("fAdEdAwAy12", "Kabaka Pyramid & Buju Banton - Faded Away", "Kabaka Pyramid", 219)),
			work,
			artist,
			durationSec,
		)
		assertNull(verified.resolution)
	}

	// ── C / D. credit and length still decide ────────────────────────────────

	@Test
	fun `a video row credited to someone else is refused`() {
		assertTrue(
			plausible(
				videoRow("wRoNgOnE123", "Fade Away", listOf("Sizzla"), "3:39"),
			).isEmpty(),
		)
	}

	@Test
	fun `a partial credit is not the published collaboration`() {
		assertTrue(
			"the player named two artists; one of them is not the same credit",
			plausible(
				videoRow("wRoNgTwO123", "Fade Away", listOf("Buju Banton"), "3:39"),
			).isEmpty(),
		)
	}

	@Test
	fun `a video row of a different length never reaches a page`() {
		assertTrue(
			plausible(
				videoRow("wRoNgLeN123", "Fade Away", listOf("Buju Banton", "Kabaka Pyramid"), "5:12"),
			).isEmpty(),
		)
	}

	@Test
	fun `a page whose length disagrees with the player refuses the id`() {
		val verified = NativeStructuredMusicMatcher.select(
			listOf(page(videoId, "Buju Banton & Kabaka Pyramid - Fade Away", "Buju Banton", 312)),
			work,
			artist,
			durationSec,
		)
		assertNull("a five-minute upload is not the 219s presentation", verified.resolution)
	}

	@Test
	fun `a row that publishes no length is carried to its own page`() {
		// Absence is absence, exactly as the songs shelf treats it: the page still
		// has to agree about the length before anything is accepted.
		assertEquals(
			listOf(videoId),
			plausible(
				videoRow(videoId, "Fade Away", listOf("Buju Banton", "Kabaka Pyramid"), null),
			).map { it.videoId },
		)
	}

	// ── E. ambiguity fails closed ────────────────────────────────────────────

	@Test
	fun `two different uploads that both corroborate refuse every id`() {
		val verified = NativeStructuredMusicMatcher.select(
			listOf(
				page("fIrStUpLd12", "Buju Banton & Kabaka Pyramid - Fade Away", "Buju Banton", 219),
				page("sEcOnDuPl12", "Buju Banton & Kabaka Pyramid - Fade Away", "Kabaka Pyramid", 219),
			),
			work,
			artist,
			durationSec,
		)

		assertNull(verified.resolution)
		assertEquals(VideoResolutionFailure.AMBIGUOUS, verified.failure)
		assertTrue(verified.refusalReason.orEmpty().contains("refusing every id"))
	}

	@Test
	fun `two uploads of one recording by one owner are still one recording`() {
		// The existing family rule, unchanged and asked of a set the strict test
		// already accepted — an artist channel and its VEVO mirror of one video.
		val representative = NativeStructuredMusicMatcher.sameRecordingAmong(
			listOf(
				page("mIrRoRtWo12", "Buju Banton & Kabaka Pyramid - Fade Away", "Buju Banton", 219),
				page("mIrRoRoNe12", "Buju Banton & Kabaka Pyramid - Fade Away", "Buju Banton", 219),
			),
			work,
			artist,
			durationSec,
			playerSeconds = durationSec,
		)

		assertEquals("mIrRoRoNe12", representative?.videoId)
	}

	// ── F. the songs shelf is untouched ──────────────────────────────────────

	@Test
	fun `the songs narrower decides these rows exactly as it always has`() {
		// The recovery reuses this seam rather than inventing a second one. A music
		// video names no release, and a missing album has always been absence to
		// it, so on a video row the question it actually answers is the length.
		val songish = rows(
			videoRow(videoId, "Fade Away", listOf("Buju Banton", "Kabaka Pyramid"), "3:39"),
		)

		assertEquals(
			listOf(videoId),
			VideoIdResolver.narrowYouTubeMusicCatalogCandidates(
				songish, nativeAlbum = null, durationSec = durationSec,
			).map { it.videoId },
		)
		assertEquals(
			"an album the row never claimed is not a contradiction, then or now",
			listOf(videoId),
			VideoIdResolver.narrowYouTubeMusicCatalogCandidates(
				songish, nativeAlbum = "Born For Greatness", durationSec = durationSec,
			).map { it.videoId },
		)
		assertTrue(
			"and a row whose own length disagrees is dropped, as it always was",
			VideoIdResolver.narrowYouTubeMusicCatalogCandidates(
				rows(videoRow("wRoNgLeN123", "Fade Away", listOf("Buju Banton", "Kabaka Pyramid"), "5:12")),
				nativeAlbum = null,
				durationSec = durationSec,
			).isEmpty(),
		)
	}

	@Test
	fun `a podcast episode row is not music on either shelf`() {
		assertTrue(
			plausible(
				videoRow(
					"pOdCaStEp12", "Fade Away", listOf("Buju Banton", "Kabaka Pyramid"), "3:39",
					musicVideoType = "MUSIC_VIDEO_TYPE_PODCAST_EPISODE",
				),
			).isEmpty(),
		)
	}

	// ── the recovery as a whole ──────────────────────────────────────────────

	/** The recovery, given a shelf and the pages those rows would fetch. */
	private fun recover(
		vararg rows: String,
		pages: List<VideoResolution>,
		title: String = work,
		artist: String = this.artist,
		album: String? = null,
		durationSec: Long = this.durationSec,
		durationMs: Long? = 219_498,
	) = runBlocking {
		VideoIdResolver().youTubeMusicVideoRecovery(
			rows(*rows), title, artist, album, durationSec, durationMs,
		) { videoId -> pages.firstOrNull { it.videoId == videoId } }
	}

	@Test
	fun `a representative listen resolves its id from the video shelf`() {
		val attempt = recover(
			videoRow(videoId, "Fade Away", listOf("Buju Banton", "Kabaka Pyramid"), "3:39"),
			pages = listOf(
				page(videoId, "Buju Banton & Kabaka Pyramid - Fade Away", "Buju Banton", 219),
			),
		)

		val resolution = attempt?.resolution
		assertEquals(videoId, resolution?.videoId)
		assertTrue(
			"and pins the 219s presentation, without which the listen still scores 0",
			resolution?.presentationDurationCorroborated == true,
		)
		assertTrue(resolution?.structuredNativeMusic == true)
		assertEquals(
			"the row's complete published credit remains available as metadata",
			listOf("Buju Banton", "Kabaka Pyramid"),
			resolution?.creditedArtists,
		)
	}

	@Test
	fun `the near-miss song title is not recovered from the video shelf either`() {
		assertNull(
			"`Faded Away` is a different work and the shelf change must not smuggle it in",
			recover(
				videoRow("fAdEdAwAy12", "Faded Away", listOf("Kabaka Pyramid", "Buju Banton"), "3:39"),
				pages = listOf(
					page("fAdEdAwAy12", "Kabaka Pyramid & Buju Banton - Faded Away", "Kabaka Pyramid", 219),
				),
			),
		)
	}

	@Test
	fun `a shelf with nothing plausible leaves the songs refusal standing`() {
		assertNull(
			"null, not a refusal: the caller's own message is the one the listen carries",
			recover(
				videoRow("wRoNgOnE123", "Fade Away", listOf("Sizzla"), "3:39"),
				pages = emptyList(),
			),
		)
	}

	@Test
	fun `a page that contradicts the row refuses rather than resolving`() {
		val attempt = recover(
			videoRow(videoId, "Fade Away", listOf("Buju Banton", "Kabaka Pyramid"), "3:39"),
			pages = listOf(
				page(videoId, "Buju Banton & Kabaka Pyramid - Fade Away", "Buju Banton", 312),
			),
		)

		assertNull(attempt?.resolution)
		assertEquals(VideoResolutionFailure.NO_MATCH, attempt?.failure)
	}

	@Test
	fun `two rows that are not one recording refuse every id`() {
		// An official video and a user upload of the same length: both agree with
		// the player, neither is the other, and no rule here may choose between
		// them. `select` calls it ambiguous, the family rules decline it — the
		// uploads differ in kind — and the refusal stands.
		val attempt = recover(
			videoRow("fIrStUpLd12", "Fade Away", listOf("Buju Banton", "Kabaka Pyramid"), "3:39"),
			videoRow(
				"sEcOnDuPl12", "Fade Away", listOf("Buju Banton", "Kabaka Pyramid"), "3:39",
				musicVideoType = "MUSIC_VIDEO_TYPE_UGC",
			),
			pages = listOf(
				page("fIrStUpLd12", "Buju Banton & Kabaka Pyramid - Fade Away", "Buju Banton", 219),
				page("sEcOnDuPl12", "Buju Banton & Kabaka Pyramid - Fade Away", "Kabaka Pyramid", 219),
			),
		)

		assertNull(attempt?.resolution)
		assertEquals(VideoResolutionFailure.AMBIGUOUS, attempt?.failure)
		assertTrue(attempt?.refusalReason.orEmpty().contains("refusing every id"))
	}

	@Test
	fun `one recording mirrored by one owner still resolves`() {
		val attempt = recover(
			videoRow("mIrRoRtWo12", "Fade Away", listOf("Buju Banton", "Kabaka Pyramid"), "3:39"),
			videoRow("mIrRoRoNe12", "Fade Away", listOf("Buju Banton", "Kabaka Pyramid"), "3:39"),
			pages = listOf(
				page("mIrRoRtWo12", "Buju Banton & Kabaka Pyramid - Fade Away", "Buju Banton", 219),
				page("mIrRoRoNe12", "Buju Banton & Kabaka Pyramid - Fade Away", "Buju Banton", 219),
			),
		)

		assertEquals("mIrRoRoNe12", attempt?.resolution?.videoId)
		assertTrue(attempt?.resolution?.presentationDurationCorroborated == true)
	}

	@Test
	fun `a row whose length disagrees never reaches its page`() {
		val fetched = mutableListOf<String>()
		val attempt = runBlocking {
			VideoIdResolver().youTubeMusicVideoRecovery(
				rows(videoRow("wRoNgLeN123", "Fade Away", listOf("Buju Banton", "Kabaka Pyramid"), "5:12")),
				work, artist, null, durationSec, 219_498,
			) { videoId -> fetched += videoId; null }
		}

		assertNull(attempt)
		assertEquals(emptyList<String>(), fetched)
	}

	@Test
	fun `a page that names one artist of the collaboration still proves the row`() {
		// Representative shape: `Fade Away` lives on one artist's channel and its
		// page names only that artist, while the catalog row credits both. The
		// songs shelf already answers this with its card-backed authority; the
		// video shelf now asks the same question in the same order.
		val attempt = recover(
			videoRow(videoId, "Fade Away", listOf("Buju Banton", "Kabaka Pyramid"), "3:39"),
			pages = listOf(page(videoId, "Fade Away", "Buju Banton", 219)),
		)

		val resolution = attempt?.resolution
		assertEquals(videoId, resolution?.videoId)
		assertTrue(
			"the row's own length equalled the player's, so the surface is pinned",
			resolution?.presentationDurationCorroborated == true,
		)
		assertEquals(
			listOf("Buju Banton", "Kabaka Pyramid"),
			resolution?.creditedArtists,
		)
	}

	@Test
	fun `a page naming the work differently is not identified by its owner`() {
		// The card-backed authority refuses it — that one still wants the work — and
		// nothing may stand in for that title evidence. The uploader is metadata: the
		// artist's own channel does no better than a reupload channel.
		assertNull(
			recover(
				videoRow(videoId, "Fade Away", listOf("Buju Banton", "Kabaka Pyramid"), "3:39"),
				pages = listOf(page(videoId, "Faded Away", "Kabaka Pyramid", 219)),
			)?.resolution,
		)
		assertNull(
			recover(
				videoRow(videoId, "Fade Away", listOf("Buju Banton", "Kabaka Pyramid"), "3:39"),
				pages = listOf(page(videoId, "Faded Away", "Reggae Uploads TV", 219)),
			)?.resolution,
		)
	}

	@Test
	fun `a page of the wrong length is not rescued by the card either`() {
		assertNull(
			"the row and the player agree, but the page is a different upload",
			recover(
				videoRow(videoId, "Fade Away", listOf("Buju Banton", "Kabaka Pyramid"), "3:39"),
				pages = listOf(page(videoId, "Fade Away", "Buju Banton", 312)),
			)?.resolution,
		)
	}

	@Test
	fun `a row too far from the player's length never survives to the card`() {
		// 13 s out is past the 5 s tolerance, so only one row is left standing and
		// the card answers about that one alone.
		val attempt = recover(
			videoRow("fIrStUpLd12", "Fade Away", listOf("Buju Banton", "Kabaka Pyramid"), "3:39"),
			videoRow("sEcOnDuPl12", "Fade Away", listOf("Buju Banton", "Kabaka Pyramid"), "3:26"),
			pages = listOf(
				page("fIrStUpLd12", "Fade Away", "Buju Banton", 219),
				page("sEcOnDuPl12", "Fade Away", "Buju Banton", 206),
			),
		)

		assertEquals("fIrStUpLd12", attempt?.resolution?.videoId)
	}

	@Test
	fun `duplicate rows of one recording are collapsed by the length the player named`() {
		// The device's shelf answers with two rows for this work. They are one
		// recording by every row-level test, and the player's own 3:39 picks it.
		val attempt = recover(
			videoRow("dUpLoNe1234", "Fade Away", listOf("Buju Banton", "Kabaka Pyramid"), "3:40"),
			videoRow("dUpLtWo1234", "Fade Away", listOf("Buju Banton", "Kabaka Pyramid"), "3:39"),
			pages = listOf(
				page("dUpLoNe1234", "Fade Away", "Buju Banton", 220),
				page("dUpLtWo1234", "Fade Away", "Buju Banton", 219),
			),
		)

		assertEquals("dUpLtWo1234", attempt?.resolution?.videoId)
		assertTrue(attempt?.resolution?.presentationDurationCorroborated == true)
		assertEquals(
			PerformerCreditEvidence.YOUTUBE_MUSIC_CATALOG_AND_CANONICAL_OWNER,
			attempt?.resolution?.performerCreditEvidence,
		)
	}

	@Test
	fun `the row that owns the player's second is taken when it is the only one`() {
		// The device's shelf: a top-result card that renders no running time of
		// its own beside a second upload that does. Nothing here is a guess — one
		// row carries the player's exact 3:39 and the other carries no length to
		// compare at all.
		val attempt = recover(
			videoRow("nOlEnGtH123", "Fade Away", listOf("Buju Banton", "Kabaka Pyramid"), null),
			videoRow(videoId, "Fade Away", listOf("Buju Banton", "Kabaka Pyramid"), "3:39"),
			pages = listOf(
				page("nOlEnGtH123", "Fade Away", "Buju Banton", 219),
				page(videoId, "Fade Away", "Buju Banton", 219),
			),
		)

		assertEquals(videoId, attempt?.resolution?.videoId)
		assertTrue(attempt?.resolution?.presentationDurationCorroborated == true)
	}

	@Test
	fun `a second upload one second off does not take the listen`() {
		val attempt = recover(
			videoRow(videoId, "Fade Away", listOf("Buju Banton", "Kabaka Pyramid"), "3:39"),
			videoRow(
				"oThErKiNd12", "Fade Away", listOf("Buju Banton", "Kabaka Pyramid"), "3:40",
				musicVideoType = "MUSIC_VIDEO_TYPE_UGC",
			),
			pages = listOf(
				page(videoId, "Fade Away", "Buju Banton", 219),
				page("oThErKiNd12", "Fade Away", "Buju Banton", 220),
			),
		)

		assertEquals(videoId, attempt?.resolution?.videoId)
	}

	@Test
	fun `two rows on the player's exact second are still refused`() {
		// Uniqueness is the whole licence. When it is gone the family rule decides,
		// and two uploads of different kinds are not one recording.
		val attempt = recover(
			videoRow("fIrStUpLd12", "Fade Away", listOf("Buju Banton", "Kabaka Pyramid"), "3:39"),
			videoRow(
				"sEcOnDuPl12", "Fade Away", listOf("Buju Banton", "Kabaka Pyramid"), "3:39",
				musicVideoType = "MUSIC_VIDEO_TYPE_UGC",
			),
			pages = listOf(
				page("fIrStUpLd12", "Fade Away", "Buju Banton", 219),
				page("sEcOnDuPl12", "Fade Away", "Buju Banton", 219),
			),
		)

		assertNull(attempt?.resolution)
	}

	// ── one upload, two published names ──────────────────────────────────────

	@Test
	fun `the work YouTube spells differently is not rescued by its own channel`() {
		// The row and the player agree on `Fade Away` and on both
		// artists; the page calls the same upload `Faded Away` and sits on
		// `Kabaka Pyramid Music`, one of those artists' own channels. That owner used
		// to substitute for the missing work match. An uploader is metadata and may
		// not stand in for the work the page failed to name.
		assertNull(
			recover(
				videoRow("8D1RUlTyFpM", "Fade Away", listOf("Buju Banton", "Kabaka Pyramid"), "3:39"),
				pages = listOf(
					page(
						"8D1RUlTyFpM",
						"Kabaka Pyramid - Faded Away ft. Buju Banton (Official Music Video)",
						"Kabaka Pyramid Music",
						219,
					),
				),
			)?.resolution,
		)
	}

	@Test
	fun `a page on someone else's channel is not rescued by the row`() {
		// `KSKZik` is a reupload channel, not either credited artist. Its page names
		// another work, and the owner is not what decides that either way.
		assertNull(
			recover(
				videoRow(videoId, "Fade Away", listOf("Buju Banton", "Kabaka Pyramid"), "3:39"),
				pages = listOf(
					page(videoId, "Kabaka Pyramid & Buju Banton - Faded Away (2022)", "KSKZik", 219),
				),
			)?.resolution,
		)
	}

	@Test
	fun `an uploader named after the artist cannot rescue a page of the wrong length`() {
		// The owner is not a licence in either direction: a credited artist's own
		// channel does not stand in for the length the page failed to corroborate.
		assertNull(
			recover(
				videoRow(videoId, "Fade Away", listOf("Buju Banton", "Kabaka Pyramid"), "3:39"),
				pages = listOf(page(videoId, "Faded Away", "Buju Banton", 400)),
			)?.resolution,
		)
	}

	@Test
	fun `a matching work on a distributor identifies the video without managed-owner provenance`() {
		val resolution = recover(
			videoRow("gEoRgInA123", "Georgina", listOf("Jah Cure"), "4:00"),
			pages = listOf(page("gEoRgInA123", "Georgina", "Zojak World Wide Official", 240)),
			title = "Georgina",
			artist = "Jah Cure",
			durationSec = 240,
			durationMs = 240_000,
		)?.resolution

		assertEquals("gEoRgInA123", resolution?.videoId)
		assertEquals(PerformerCreditEvidence.NONE, resolution?.performerCreditEvidence)
	}

	@Test
	fun `owner metadata cannot replace the player's length check`() {
		assertNull(
			recover(
				videoRow(videoId, "Fade Away", listOf("Buju Banton", "Kabaka Pyramid"), "3:39"),
				pages = listOf(
					page(videoId, "Faded Away", "Kabaka Pyramid Music", 312),
				),
			)?.resolution,
		)
	}

	@Test
	fun `owner metadata cannot resolve two surviving rows`() {
		// Uniqueness first: two uploads of different kinds on the same second are
		// still refused, page owners notwithstanding.
		val attempt = recover(
			videoRow("fIrStUpLd12", "Fade Away", listOf("Buju Banton", "Kabaka Pyramid"), "3:39"),
			videoRow(
				"sEcOnDuPl12", "Fade Away", listOf("Buju Banton", "Kabaka Pyramid"), "3:39",
				musicVideoType = "MUSIC_VIDEO_TYPE_UGC",
			),
			pages = listOf(
				page("fIrStUpLd12", "Faded Away", "Kabaka Pyramid Music", 219),
				page("sEcOnDuPl12", "Faded Away", "Kabaka Pyramid Music", 219),
			),
		)

		assertNull(attempt?.resolution)
	}

	@Test
	fun `a row with different artist metadata is rejected before owner comparison`() {
		assertNull(
			"the shelf may hold another artist's `Fade Away`; the credit test drops it first",
			recover(
				videoRow("rOmAiNvIr12", "Fade Away", listOf("Romain Virgo"), "3:39"),
				pages = listOf(page("rOmAiNvIr12", "Fade Away", "Romain Virgo", 219)),
			)?.resolution,
		)
	}

	// ── legacy descriptive owner-provenance helper ───────────────────────────

	@Test
	fun `the provenance helper recognizes one credited page owner`() {
		// This descriptive comparison is retained for metadata provenance. It is not
		// an identity or write gate.
		val page = page(
			"8D1RUlTyFpM",
			"Kabaka Pyramid - Faded Away ft. Buju Banton (Official Music Video)",
			"Kabaka Pyramid Music",
			219,
		)

		assertTrue(
			NativeStructuredMusicMatcher.ownedByOneCreditedArtist(page, artist, durationSec),
		)
	}

	@Test
	fun `the provenance helper declines another channel or length`() {
		assertFalse(
			NativeStructuredMusicMatcher.ownedByOneCreditedArtist(
				page("8D1RUlTyFpM", "Faded Away", "KSKZik", 219), artist, durationSec,
			),
		)
		assertFalse(
			NativeStructuredMusicMatcher.ownedByOneCreditedArtist(
				page("8D1RUlTyFpM", "Faded Away", "Kabaka Pyramid Music", 312), artist, durationSec,
			),
		)
		assertFalse(
			"a page with no owner at all proves nothing",
			NativeStructuredMusicMatcher.ownedByOneCreditedArtist(
				page("8D1RUlTyFpM", "Faded Away", "", 219), artist, durationSec,
			),
		)
	}

	@Test
	fun `the resolution carries its video-row revalidation route`() {
		// Hosted by a distributor, not by either credited artist: the page names the
		// work at the player's length, so the row identifies the video whoever
		// uploaded it.
		val resolution = recover(
			videoRow("8D1RUlTyFpM", "Fade Away", listOf("Buju Banton", "Kabaka Pyramid"), "3:39"),
			pages = listOf(
				page(
					"8D1RUlTyFpM",
					"Buju Banton & Kabaka Pyramid - Fade Away",
					"Reggae Distribution Ltd",
					219,
				),
			),
		)?.resolution

		assertEquals("8D1RUlTyFpM", resolution?.videoId)
		assertTrue(
			"without this it revalidates as ordinary structured music and refuses again",
			resolution?.musicVideoRow == true,
		)
	}

	// ── descriptive credit provenance across equivalent branches ─────────────

	/*
	 * Regression: equivalent video-row branches recorded artist-credit provenance
	 * from different inputs. They must compare the same row and canonical-page
	 * metadata, while that descriptive grade remains independent of write
	 * eligibility.
	 */

	@Test
	fun `equivalent branches grade descriptive credit metadata from the same row`() {
		// The player publishes the upload's own artist-prefixed title, so the
		// page-title rule has no bare work to compare against and grades NONE.
		// `La India` is the same act as `India` and owns the page.
		val nativeTitle = "India - Vivir Lo Nuestro (Dicen Que Soy) [Official Audio]"
		val resolution = recover(
			videoRow("Ty2wCuHfNN8", nativeTitle, listOf("India"), "6:10"),
			pages = listOf(page("Ty2wCuHfNN8", nativeTitle, "La India", 370)),
			title = nativeTitle,
			artist = "India",
			durationSec = 369,
			durationMs = 369_000,
		)?.resolution

		assertEquals("Ty2wCuHfNN8", resolution?.videoId)
		assertTrue(
			"the page passed the strict match, so the 369s presentation is pinned",
			resolution?.presentationDurationCorroborated == true,
		)
		assertEquals(
			"the row and page record matching descriptive credit provenance",
			PerformerCreditEvidence.YOUTUBE_MUSIC_CATALOG_AND_CANONICAL_OWNER,
			resolution?.performerCreditEvidence,
		)
		assertTrue(
			"and finalization must re-ask the row's question, not the songs shelf's",
			resolution?.musicVideoRow == true,
		)
	}

	@Test
	fun `a distributor page records no managed-owner credit provenance`() {
		// The Georgina shape, with the page title naming the artist. Passing the
		// strict match is about the work; it says nothing about who performed it
		// when the upload sits on a distributor.
		val resolution = recover(
			videoRow("gEoRgInA123", "Georgina", listOf("Jah Cure"), "4:00"),
			pages = listOf(
				page("gEoRgInA123", "Jah Cure - Georgina", "Zojak World Wide Official", 240),
			),
			title = "Georgina",
			artist = "Jah Cure",
			durationSec = 240,
			durationMs = 240_000,
		)?.resolution

		assertEquals("gEoRgInA123", resolution?.videoId)
		assertEquals(
			"a name printed in a title is not a credit the uploader can vouch for",
			PerformerCreditEvidence.NONE,
			resolution?.performerCreditEvidence,
		)
	}

	@Test
	fun `credit provenance uses the same managed-owner comparison at finalization`() {
		// The producer side has always let one credited act own the page under a
		// longer or shorter form of its own name. Asking anything narrower here
		// refused, at finalization, ids that pre-resolution had accepted.
		assertTrue(
			NativeStructuredMusicMatcher.ownedByOneCreditedArtist(
				page("Ty2wCuHfNN8", "India - Vivir Lo Nuestro", "La India", 370), "India", 369,
			),
		)
		assertFalse(
			"a distributor is still not the artist",
			NativeStructuredMusicMatcher.ownedByOneCreditedArtist(
				page("gEoRgInA123", "Jah Cure - Georgina", "Zojak World Wide Official", 240),
				"Jah Cure",
				240,
			),
		)
		assertFalse(
			"and the length still binds",
			NativeStructuredMusicMatcher.ownedByOneCreditedArtist(
				page("Ty2wCuHfNN8", "India - Vivir Lo Nuestro", "La India", 312), "India", 369,
			),
		)
	}

	@Test
	fun `the page corroborates the work it names after its own artist prefix`() {
		val artistPrefixed = page("FN5oLBXiNvM", "Fruko Y Sus Tesos - El Preso", "Fuentes", 292)

		assertTrue(
			"a canonical page publishes `<Artist> - <Work>`; the player publishes the work",
			NativeStructuredMusicMatcher.corroboratesWorkAndLength(artistPrefixed, "El Preso", 291),
		)
		assertFalse(
			"a different work behind the same prefix is still a different work",
			NativeStructuredMusicMatcher.corroboratesWorkAndLength(
				page("FN5oLBXiNvM", "Fruko Y Sus Tesos - El Patillero", "Fuentes", 292),
				"El Preso",
				291,
			),
		)
		assertFalse(
			"and the length is unchanged by any of this",
			NativeStructuredMusicMatcher.corroboratesWorkAndLength(artistPrefixed, "El Preso", 200),
		)
	}

	@Test
	fun `a row the player matched exactly is identified by its own page`() {
		// 227 s were played against this row and scored zero because the page's
		// title carried the uploader's prefix. The id it names is what makes the
		// presentation attributable at all; the uploader credit remains best-effort.
		val resolution = recover(
			videoRow("FN5oLBXiNvM", "El Preso", listOf("Fruko", "Wilson Saoko"), "4:52"),
			pages = listOf(
				page(
					"FN5oLBXiNvM",
					"Fruko Y Sus Tesos - El Preso",
					"Discos Fuentes Edimusica",
					292,
				),
			),
			title = "El Preso",
			artist = "Fruko & Wilson Saoko",
			durationSec = 291,
			durationMs = 291_387,
		)?.resolution

		assertEquals("FN5oLBXiNvM", resolution?.videoId)
		assertTrue(
			"the row's length and the page's are both the player's, so the surface is pinned",
			resolution?.presentationDurationCorroborated == true,
		)
		assertTrue(
			"a video-shelf row must retain its route through final revalidation",
			resolution?.musicVideoRow == true,
		)
		assertEquals(
			"identifying the video does not require managed-owner provenance",
			PerformerCreditEvidence.NONE,
			resolution?.performerCreditEvidence,
		)
	}

	@Test
	fun `the same row on a credited artist's own channel carries the credit too`() {
		val resolution = recover(
			videoRow("FN5oLBXiNvM", "El Preso", listOf("Fruko", "Wilson Saoko"), "4:52"),
			pages = listOf(
				page("FN5oLBXiNvM", "Fruko Y Sus Tesos - El Preso", "Fruko Y Sus Tesos", 292),
			),
			title = "El Preso",
			artist = "Fruko & Wilson Saoko",
			durationSec = 291,
			durationMs = 291_387,
		)?.resolution

		assertEquals(
			PerformerCreditEvidence.YOUTUBE_MUSIC_CATALOG_AND_CANONICAL_OWNER,
			resolution?.performerCreditEvidence,
		)
	}

	@Test
	fun `a different work behind an artist prefix is not recovered`() {
		assertNull(
			recover(
				videoRow("FN5oLBXiNvM", "El Preso", listOf("Fruko", "Wilson Saoko"), "4:52"),
				pages = listOf(
					page(
						"FN5oLBXiNvM",
						"Fruko Y Sus Tesos - El Patillero",
						"Discos Fuentes Edimusica",
						292,
					),
				),
				title = "El Preso",
				artist = "Fruko & Wilson Saoko",
				durationSec = 291,
				durationMs = 291_387,
			)?.resolution,
		)
	}

	// ── which order the page happens to publish its own title in ─────────────

	/*
	 * Two real canonical titles, over rows a YouTube Music shelf matched on work
	 * *and* complete credit, at the player's own length:
	 *
	 * ```
	 * El Preso - Fruko y Sus Tesos (Video Oficial) | Discos Fuentes   291s
	 * Gotas De Lluvia, Grupo Niche - Video Letra                      337s
	 * ```
	 *
	 * `TitleParser.parse` orients neither — the first has two separators and an
	 * owner that agrees with neither side, so it conservatively keeps the whole
	 * string as the track; the second reads as a conventional `Artist - Track` and
	 * yields `Video Letra`. That conservatism is right for building a payload and
	 * wrong for asking whether the page names the work, which both plainly do. The
	 * listens behind them measured 192 s and 235 s and filed zero.
	 *
	 * Nothing below is about a song. The invariant is that a page corroborates the
	 * work it names in either published order, and that identifying a video is
	 * still not vouching for who performed it.
	 */

	@Test
	fun `a page corroborates the work it names in either published order`() {
		assertTrue(
			"artist first, which the parser already orients",
			NativeStructuredMusicMatcher.corroboratesWorkAndLength(
				page(videoId, "Known Artist - Known Work", "Known Artist", 219), "Known Work", 219,
			),
		)
		assertTrue(
			"work first, with a promo suffix and a label pipe the parser will not orient",
			NativeStructuredMusicMatcher.corroboratesWorkAndLength(
				page(videoId, "Known Work - Known Artist (Official Video) | Some Label", "Some Label Distribution", 219),
				"Known Work",
				219,
			),
		)
		assertTrue(
			"work first, separated from the credit by a comma",
			NativeStructuredMusicMatcher.corroboratesWorkAndLength(
				page(videoId, "Known Work, Known Artist - Video Letra", "Some Label", 219),
				"Known Work",
				219,
			),
		)
		assertTrue(
			"a work that carries commas of its own is compared whole, not cut at them",
			NativeStructuredMusicMatcher.corroboratesWorkAndLength(
				page(videoId, "One, Two, Three - Known Artist (Letra Video )", "Some Label", 219),
				"One, Two, Three",
				219,
			),
		)
	}

	@Test
	fun `leading text that is not the whole work corroborates nothing`() {
		assertFalse(
			"a different work is a different work in any order",
			NativeStructuredMusicMatcher.corroboratesWorkAndLength(
				page(videoId, "Another Work - Known Artist (Official Video) | Some Label", "Some Label", 219),
				"Known Work",
				219,
			),
		)
		assertFalse(
			"the work must be the opening run entire, never a prefix of a longer one",
			NativeStructuredMusicMatcher.corroboratesWorkAndLength(
				page(videoId, "Known Work Reprise - Known Artist | Some Label", "Some Label", 219),
				"Known Work",
				219,
			),
		)
		assertFalse(
			"and naming the work never suspends the length",
			NativeStructuredMusicMatcher.corroboratesWorkAndLength(
				page(videoId, "Known Work - Known Artist (Official Video) | Some Label", "Some Label", 312),
				"Known Work",
				219,
			),
		)
	}

	@Test
	fun `a row-backed id whose page publishes the work first is still identified`() {
		val resolution = recover(
			videoRow(videoId, "Fade Away", listOf("Buju Banton", "Kabaka Pyramid"), "3:39"),
			pages = listOf(
				page(
					videoId,
					"Fade Away - Buju Banton (Official Video) | Some Label",
					"Some Label Distribution",
					219,
				),
			),
		)?.resolution

		assertEquals(videoId, resolution?.videoId)
		assertTrue(
			"and the presentation it pins is what lets the listen score at all",
			resolution?.presentationDurationCorroborated == true,
		)
		assertEquals(
			"a company channel records no managed-owner credit provenance",
			PerformerCreditEvidence.NONE,
			resolution?.performerCreditEvidence,
		)
	}

	@Test
	fun `a page that opens with another work is not identified by the row`() {
		assertNull(
			recover(
				videoRow(videoId, "Fade Away", listOf("Buju Banton", "Kabaka Pyramid"), "3:39"),
				pages = listOf(
					page(
						videoId,
						"Faded Away - Buju Banton (Official Video) | Some Label",
						"Some Label Distribution",
						219,
					),
				),
			)?.resolution,
		)
	}

	@Test
	fun `a songs shelf the narrower empties leaves nothing that can describe the listen`() {
		// The songs shelf answers with the release versions; the player is
		// rendering a video presentation whose running time none of them carries.
		// Every row it named is removed, so no song row is left to be the stronger
		// evidence — and the shelf that does hold the presentation must be asked.
		val songRows = rows(
			videoRow("sOnGrOwOnE1", "Fade Away", listOf("Buju Banton", "Kabaka Pyramid"), "3:20"),
			videoRow("sOnGrOwTwO1", "Fade Away", listOf("Buju Banton", "Kabaka Pyramid"), "3:25"),
		).filter { YouTubeMusicCatalogSearchParser.matches(it, work, artist) }

		assertEquals(
			"the songs shelf really did name this work and this complete credit",
			2,
			songRows.size,
		)
		assertTrue(
			"and the narrower really does remove every one of them",
			VideoIdResolver.narrowYouTubeMusicCatalogCandidates(songRows, null, durationSec)
				.isEmpty(),
		)

		val recovered = recover(
			videoRow("vIdEoRoW123", "Fade Away", listOf("Buju Banton", "Kabaka Pyramid"), "3:39"),
			pages = listOf(
				page(
					"vIdEoRoW123",
					"Fade Away - Buju Banton (Official Video) | Some Label",
					"Some Label Distribution",
					219,
				),
			),
		)?.resolution

		assertEquals(
			"the video shelf holds the presentation the songs shelf could not",
			"vIdEoRoW123",
			recovered?.videoId,
		)
	}
}
