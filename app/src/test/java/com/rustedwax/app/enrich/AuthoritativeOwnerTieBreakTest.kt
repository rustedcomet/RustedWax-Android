package com.rustedwax.app.enrich

import com.rustedwax.youtube.identity.VideoResolution
import com.rustedwax.youtube.identity.VideoResolutionFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One recording, two publishers, and the one YouTube says is the artist's.
 *
	 * Regression: structured work, artist metadata and duration can leave two
	 * uploads for one recording. One is on an artist-managed channel and the other
	 * is a repost; the identity tie-break must remain bounded to that already
	 * matched candidate set.
 *
 * The duplicate-family rules decline this pair and stay declining it: they ask
 * whether one upload was ingested twice, and two uploaders means it was not. The
 * tie-break added here asks a different question with an answer YouTube supplies
 * itself — `…VEVO` and `… - Topic` are the channel forms it generates for a rights
 * holder — and it only ever answers when exactly one survivor has one.
 *
 * Nothing else moved. Every candidate still has to pass [NativeStructuredMusicMatcher.matches]
 * first, and none of views, category, upload date, description or result order is
 * read anywhere in this path.
 */
class AuthoritativeOwnerTieBreakTest {

	private val work = "School"
	private val artist = "Vybz Kartel"
	private val durationSec = 162L

	private fun page(
		videoId: String,
		title: String,
		channel: String,
		lengthSeconds: Long,
	) = VideoResolution(
		videoId = videoId,
		source = "structured native music search",
		title = title,
		channel = channel,
		lengthSeconds = lengthSeconds,
	)

	/** Synthetic pair with the same matched-work and distinct-publisher shape. */
	private val vevo = page("f4kWklrXqTA", "Vybz Kartel - School", "VybzKartelVEVO", 163)
	private val repost =
		page("ki9b-TXBh8M", "Vybz Kartel  - School (Official Video)", "Genius Sound", 162)

	private fun select(vararg candidates: VideoResolution) =
		NativeStructuredMusicMatcher.select(candidates.toList(), work, artist, durationSec)

	// ── A. representative matched pair ───────────────────────────────────────

	@Test
	fun `the artist's own upload wins a tie against a stranger's repost`() {
		val attempt = select(repost, vevo)

		assertEquals("f4kWklrXqTA", attempt.resolution?.videoId)
		assertTrue(
			"the chosen page's length was already the player's, so the surface is pinned",
			attempt.resolution?.presentationDurationCorroborated == true,
		)
		assertTrue(attempt.resolution?.uniquelyResolved == true)
		assertTrue(attempt.resolution?.structuredNativeMusic == true)
	}

	@Test
	fun `the order the candidates arrive in decides nothing`() {
		assertEquals("f4kWklrXqTA", select(vevo, repost).resolution?.videoId)
		assertEquals("f4kWklrXqTA", select(repost, vevo).resolution?.videoId)
	}

	@Test
	fun `a Topic channel is authoritative in the same way VEVO is`() {
		val topic = page("tOpIcOnE123", "School", "Vybz Kartel - Topic", 162)

		assertEquals("tOpIcOnE123", select(repost, topic).resolution?.videoId)
	}

	// ── B / C. uniqueness carries the whole licence ──────────────────────────

	@Test
	fun `two strangers are still ambiguous`() {
		val other = page("sTrAnGeR123", "Vybz Kartel - School", "Reggae Uploads TV", 162)
		val attempt = select(repost, other)

		assertNull(attempt.resolution)
		assertEquals(VideoResolutionFailure.AMBIGUOUS, attempt.failure)
		assertTrue(attempt.refusalReason.orEmpty().contains("refusing every id"))
	}

	@Test
	fun `two authoritative owners are still ambiguous`() {
		// A VEVO programme and a Topic art track of one work: both are the rights
		// holder's, and nothing here may choose between them.
		val topic = page("tOpIcOnE123", "School", "Vybz Kartel - Topic", 162)
		val attempt = select(vevo, topic)

		assertNull(attempt.resolution)
		assertEquals(VideoResolutionFailure.AMBIGUOUS, attempt.failure)
	}

	@Test
	fun `an Official or Records channel is not authoritative`() {
		// The markers anyone can claim, which `ownerIsAuthoritativeArtistChannel`
		// deliberately excludes.
		listOf("Vybz Kartel Official", "Vybz Kartel Records", "Vybz Kartel Music").forEach { name ->
			val claimed = page("cLaImEdOnE1", "Vybz Kartel - School", name, 162)
			assertNull(
				"\"$name\" says nothing about who recorded the work",
				select(repost, claimed).resolution,
			)
		}
	}

	// ── D / E / F. the existing evidence still decides who survives ──────────

	@Test
	fun `an authoritative upload of a different work never reaches the tie-break`() {
		val otherWork = page("wRoNgWoRk12", "Vybz Kartel - School Bus", "VybzKartelVEVO", 162)

		// Only the repost matches, so this is the ordinary single-match path.
		assertEquals("ki9b-TXBh8M", select(repost, otherWork).resolution?.videoId)
		// And on its own it matches nothing at all.
		assertNull(select(otherWork).resolution)
	}

	@Test
	fun `an authoritative upload credited to someone else never reaches the tie-break`() {
		val otherArtist = page("wRoNgArT123", "Popcaan - School", "PopcaanVEVO", 162)

		assertEquals("ki9b-TXBh8M", select(repost, otherArtist).resolution?.videoId)
		assertNull(select(otherArtist).resolution)
	}

	@Test
	fun `an authoritative upload outside the duration tolerance never reaches the tie-break`() {
		val tooLong = page("wRoNgLeN123", "Vybz Kartel - School", "VybzKartelVEVO", 240)

		assertEquals("ki9b-TXBh8M", select(repost, tooLong).resolution?.videoId)
		assertNull(select(tooLong).resolution)
	}

	@Test
	fun `a candidate with no owner at all cannot be authoritative`() {
		val ownerless = page("nOoWnEr1234", "Vybz Kartel - School", "", 162)
		val attempt = select(repost, ownerless)

		assertNull(attempt.resolution)
		assertEquals(VideoResolutionFailure.AMBIGUOUS, attempt.failure)
	}

	@Test
	fun `a foreign authoritative owner cannot win from the title credit`() {
		// Both pages name the listened artist in their titles, so both pass the
		// structured work/credit/length gate. The VEVO marker proves only that the
		// first page belongs to Popcaan; it cannot turn that foreign ownership into
		// authority for a Vybz Kartel listen.
		val foreignVevo = page(
			"fOrEiGnVeVo1",
			"Vybz Kartel - School",
			"PopcaanVEVO",
			162,
		)
		val attempt = select(foreignVevo, repost)

		assertNull(attempt.resolution)
		assertEquals(VideoResolutionFailure.AMBIGUOUS, attempt.failure)
	}

	// ── G. what the tie-break did not touch ──────────────────────────────────

	@Test
	fun `one match is still one match, and none is still none`() {
		assertEquals("f4kWklrXqTA", select(vevo).resolution?.videoId)
		assertEquals(
			"structured native music title+artist+duration",
			select(vevo).resolution?.source,
		)
		assertNull(select().resolution)
		assertEquals(VideoResolutionFailure.NO_MATCH, select().failure)
	}

	@Test
	fun `the duplicate-family rule still refuses two uploaders`() {
		// Unchanged: `sameRecording` asks whether one publisher ingested one work
		// twice, and this pair is two publishers. The tie-break above is a separate
		// question and does not reach into this one.
		assertNull(NativeStructuredMusicMatcher.sameRecording(listOf(vevo, repost)))
		assertNull(
			NativeStructuredMusicMatcher.sameRecordingAmong(
				listOf(vevo, repost), work, artist, durationSec,
			),
		)
	}

	@Test
	fun `one publisher's two ingests are still one family`() {
		val mirrorA = page("mIrRoRoNe12", "Vybz Kartel - School", "VybzKartelVEVO", 163)
		val mirrorB = page("mIrRoRtWo12", "Vybz Kartel - School", "VybzKartelVEVO", 163)

		assertEquals(
			"mIrRoRoNe12",
			NativeStructuredMusicMatcher.sameRecording(listOf(mirrorB, mirrorA))?.videoId,
		)
	}

	// ── the boundary that matters most ───────────────────────────────────────

	@Test
	fun `two of the artist's own uploads stay ambiguous`() {
		// `NativeStructuredMusicMatcherTest` has held this pair since before the
		// tie-break existed: an official audio on `Aventura` beside a `- Topic`
		// live take, both the rights holder's. Only one carries the marker, and
		// preferring it would choose a live recording over a studio one on the
		// strength of a channel suffix. The tie-break is about the artist's upload
		// against a stranger's copy, so it declines the moment another survivor is
		// the artist too.
		val officialAudio = page(
			"ohp8cXhIHXc",
			"Aventura feat. Don Omar - Ella Y Yo (Official Audio)",
			"Aventura",
			269,
		)
		val topicLive = page("3-t7qlOfikI", "Ella y Yo (Live)", "Aventura - Topic", 268)

		val attempt = NativeStructuredMusicMatcher.select(
			listOf(officialAudio, topicLive), "Ella Y Yo (Feat. Don Omar)", "Aventura", 268,
		)

		assertNull(attempt.resolution)
		assertEquals(VideoResolutionFailure.AMBIGUOUS, attempt.failure)
	}

	@Test
	fun `a collaborator's own channel also blocks the tie-break`() {
		// The session credits two acts; an upload on either of their names is the
		// artist's own publication, not a stranger's copy.
		// Both titles carry the whole credit, so both survive `matches`; the only
		// difference is the channel each sits on.
		val vevoPair = page("vEvOpAiR123", "Act One & Act Two - Some Work", "ActOneVEVO", 200)
		val collaborator = page("cOlLaB12345", "Act One & Act Two - Some Work", "Act Two", 200)

		val attempt = NativeStructuredMusicMatcher.select(
			listOf(vevoPair, collaborator), "Some Work", "Act One & Act Two", 200,
		)

		assertNull(attempt.resolution)
		assertEquals(VideoResolutionFailure.AMBIGUOUS, attempt.failure)
	}
}
