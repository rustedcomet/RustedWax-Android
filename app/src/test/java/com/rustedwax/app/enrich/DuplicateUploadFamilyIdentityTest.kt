package com.rustedwax.app.enrich

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * One recording published twice by the same owner.
 *
	 * Regression: two catalog entries can represent the same official video with
	 * the same duration and byline
	 * in the listing — one lives on the artist's own channel and one on their VEVO
	 * mirror:
 *
 * | id | watch author | channelId | length |
 * |---|---|---|---|
	 * | synthetic A | `KnownArtistVEVO` | managed channel | 272 |
	 * | synthetic B | `Known Artist` | artist channel | 272 |
 *
 * That mirroring is how a large share of official music video is published, so
	 * treating the pair as unrelated would make VEVO-mirrored videos ambiguous.
 *
 * The Music catalog route has collapsed this family since
 * [NativeStructuredMusicMatcher.oneOfDuplicateFamily] was written; the search
 * route simply never asked it. These scenarios pin that it now does, and that it
 * still refuses everything that is not one recording.
 */
class DuplicateUploadFamilyIdentityTest {

	private fun card(
		videoId: String,
		title: String,
		channel: String?,
		lengthSeconds: Long?,
	) = SearchResultsParser.Candidate(videoId, title, channel, lengthSeconds)

	private val title = "Capleton - Real As It Seems (Official Video)"

	/** The two cards exactly as the listing returned them. */
	private val vevoPair = listOf(
		card("WoAlzSVIzRo", title, "Capleton", 272),
		card("xub1SMhQV84", title, "Capleton", 272),
	)

	@Test
	fun `the VEVO mirror and the artist's own upload collapse to one id`() {
		val chosen = VideoIdResolver.duplicateFamilyAmong(vevoPair, playerSeconds = 271)
		assertEquals(
			"one recording, so one id rather than a refusal",
			"WoAlzSVIzRo",
			chosen?.videoId,
		)
	}

	@Test
	fun `the choice is deterministic, not order-dependent`() {
		assertEquals(
			VideoIdResolver.duplicateFamilyAmong(vevoPair, 271)?.videoId,
			VideoIdResolver.duplicateFamilyAmong(vevoPair.reversed(), 271)?.videoId,
		)
	}

	@Test
	fun `the upload whose own length is the one being published wins`() {
		val nearlyIdentical = listOf(
			card("aaaaaaaaaaa", title, "Capleton", 272),
			card("bbbbbbbbbbb", title, "Capleton", 269),
		)
		assertEquals(
			"269s is what the player published, so that member is the one",
			"bbbbbbbbbbb",
			VideoIdResolver.duplicateFamilyAmong(nearlyIdentical, playerSeconds = 269)?.videoId,
		)
	}

	// ── everything that is not one recording is still refused ────────────────

	@Test
	fun `two different owners are two uploads, not one recording`() {
		assertNull(
			"a label's repost is not the artist's own second copy",
			VideoIdResolver.duplicateFamilyAmong(
				listOf(
					card("WoAlzSVIzRo", title, "Capleton", 272),
					card("PtM5iHEDnno", title, "Reggae Translate", 272),
				),
				playerSeconds = 271,
			),
		)
	}

	@Test
	fun `two different works on one channel are never a family`() {
		assertNull(
			VideoIdResolver.duplicateFamilyAmong(
				listOf(
					card("WoAlzSVIzRo", title, "Capleton", 272),
					card("npOgFWW4b6w", "Capleton - Jah Jah is Real (Official Music Video)", "Capleton", 272),
				),
				playerSeconds = 271,
			),
		)
	}

	@Test
	fun `lengths that disagree beyond the duplicate spread are not a family`() {
		assertNull(
			"a longer cut of the same title is a different recording",
			VideoIdResolver.duplicateFamilyAmong(
				listOf(
					card("aaaaaaaaaaa", title, "Capleton", 272),
					card("bbbbbbbbbbb", title, "Capleton", 340),
				),
				playerSeconds = 271,
			),
		)
	}

	@Test
	fun `a single candidate is not a family and needs no collapse`() {
		assertNull(
			VideoIdResolver.duplicateFamilyAmong(
				listOf(card("WoAlzSVIzRo", title, "Capleton", 272)),
				playerSeconds = 271,
			),
		)
	}

	@Test
	fun `a candidate with no published length is never collapsed`() {
		assertNull(
			VideoIdResolver.duplicateFamilyAmong(
				listOf(
					card("aaaaaaaaaaa", title, "Capleton", 272),
					card("bbbbbbbbbbb", title, "Capleton", null),
				),
				playerSeconds = 271,
			),
		)
	}

	@Test
	fun `the collapse survives the owner being written two ways`() {
		// The watch pages say `capletonVEVO` and `Capleton`; both clean to the
		// same owner, which is what makes them one artist's two copies.
		assertEquals(
			"WoAlzSVIzRo",
			VideoIdResolver.duplicateFamilyAmong(
				listOf(
					card("WoAlzSVIzRo", title, "capletonVEVO", 272),
					card("xub1SMhQV84", title, "Capleton", 272),
				),
				playerSeconds = 271,
			)?.videoId,
		)
	}
}
