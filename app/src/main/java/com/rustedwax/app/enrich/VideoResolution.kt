package com.rustedwax.app.enrich

import com.rustedwax.app.detect.SessionProbe
import com.rustedwax.app.detect.SessionSnapshot
import com.rustedwax.app.detect.TitleParser
import com.rustedwax.app.detect.VideoTitleMatcher
import com.rustedwax.app.detect.YouTubeProbe
import com.rustedwax.youtube.identity.VideoResolution

/**
 * The only two authorities allowed to attach a YouTube id to a finalized
 * listen. Browser visibility and accessibility coverage are deliberately not
 * inputs: they can supply an explicit ad signal, but they do not strengthen or
 * weaken video identity.
 *
 * Keep this boundary synchronized with `Documentation/Product/IDENTITY.md`, which is the
 * current product contract. Historical phase and field documents do not
 * override it.
 */
enum class FinalizedVideoIdentityAuthority {
	/** An exact id was frozen while the track was active. */
	FROZEN_EXACT_ID,

	/** One bounded resolver candidate corroborated the finalized track. */
	UNIQUE_FINALIZED_LOOKUP,
}

object FinalizedVideoIdentityContract {
	fun authority(
		frozenVideoId: String?,
		resolution: VideoResolution,
	): FinalizedVideoIdentityAuthority? = when {
		frozenVideoId == resolution.videoId ->
			FinalizedVideoIdentityAuthority.FROZEN_EXACT_ID
		frozenVideoId == null && resolution.uniquelyResolved ->
			FinalizedVideoIdentityAuthority.UNIQUE_FINALIZED_LOOKUP
		else -> null
	}

	fun refusalReason(
		frozenVideoId: String?,
		resolution: VideoResolution,
	): String = when {
		frozenVideoId != null ->
			"resolved id ${resolution.videoId} did not preserve frozen id $frozenVideoId"
		else ->
			"resolver returned ${resolution.videoId} without a unique finalized-track match"
	}
}

/**
 * Final defense against assembling one payload from two adjacent tracks.
 *
 * The URL/id, resolver candidate and enriched page facts are checked only
 * against the immutable ended [SessionSnapshot]. No live package evidence is
 * an input. Null fields are absence of evidence; present contradictory fields
 * refuse the id before metadata can replace the session values.
 */
object VideoIdentityCorroborator {
	/** Resolver evidence describes the current exact item, not a logical listen's longest length. */
	private fun identityDurationMs(session: SessionSnapshot): Long? =
		session.resolverContext.presentationDurationMs ?: session.durationMs

	/**
	 * Whether a live rejection was only ever about the title, and an independent
	 * exact-id route has since named that same id.
	 *
	 * Measured 2026-08-16 for `dE8D6WY6tQQ`. The address bar named the right
	 * video; its watch page is titled `Bounce` while its MediaSession published
	 * `Ladii Rose ft Dej RoseGold Bounce (Official Video)`; the difference was
	 * graded a contradiction and the id was filed as rejected. Eighty seconds
	 * later the Mix queue independently returned *that same id*, having matched
	 * the entry's own title, channel and duration — and this veto threw a
	 * complete 138-of-137-second listen away for the earlier, weaker disagreement.
	 *
	 * The narrowness is the whole point:
	 *
	 * - only a rejection whose sole cause was the title qualifies. A page whose
	 *   *duration* disagreed is structurally a different video and still vetoes.
	 * - only the playlist and watch-history routes qualify. Both prove an id by
	 *   matching title, channel and duration against a bounded entry list that
	 *   this phone was actually playing from; search does not, and is not
	 *   admitted here.
	 * - nothing is accepted by this. It only declines to short-circuit, and every
	 *   check below — the frozen id, both titles, the channel, the duration, the
	 *   owner handle and the final watch-page facts — still has to pass.
	 */
	private fun independentlyCorroboratedAfterTitleOnlyRejection(
		session: SessionSnapshot,
		resolution: VideoResolution,
	): Boolean = resolution.videoId in session.resolverContext.titleOnlyRejectedVideoIds &&
		(resolution.playlistVerified || resolution.historyVerified)


	fun contradiction(
		session: SessionSnapshot,
		resolution: VideoResolution,
		facts: VideoFacts?,
	): String? {
		if (resolution.videoId in session.resolverContext.rejectedVideoIds &&
			!independentlyCorroboratedAfterTitleOnlyRejection(session, resolution)
		) {
			return "video id ${resolution.videoId} was rejected while the track was active"
		}
		session.confirmed?.let { frozen ->
			if (frozen.videoId != resolution.videoId) {
				return "resolved id ${resolution.videoId} replaced frozen id ${frozen.videoId}"
			}
		}
		// One video, two titles. YouTube auto-translates for the viewer, so a
		// native observer reads the *displayed* title off the screen while
		// `videoDetails` keeps the uploaded one — measured 2026-08-05, the
		// resolver found `QnRnooyKeZk` correctly and this guard then threw it
		// away because "El Día que Karol G Vivió…" is not "The Day Karol G
		// Experienced…". They are the same video, and both names come from its
		// own page.
		//
		// Both are offered, and agreement with *either* is agreement. Choosing
		// one and comparing only that was itself a defect: measured 2026-08-06,
		// `RTQFqbCPUGg` is titled entirely in hashtags, its title key is
		// therefore empty, every title matched it vacuously, and the guard
		// substituted the English rendering of a Spanish title the screen had
		// shown in Spanish — then refused the listen for the difference. The
		// same page fetched as `es-419` renders it in Spanish, so which of the
		// two names the resolver sees is a property of the fetch, not of the
		// video. Duration, channel/handle and the unique-id rule all still bind
		// independently, and both names come from the one page being verified,
		// so this admits no new candidate.
		val localizedTitle = resolution.localizedTitle

		session.ownerHandle?.let { wantedHandle ->
			if (!resolution.uniquelyResolved) {
				return "foreground Short id was not the only corroborated candidate"
			}
			// A missing title is no longer a refusal. YouTube's footer lost its
			// resource ids, and a Short sent straight to picture-in-picture never
			// exposes a readable title at all — measured 2026-08-06, a Short
			// counted to 100% and was rejected here for having none. The title
			// was only ever one of three agreeing fields; duration, owner handle
			// and the watch page's own handle all still bind below, and the id
			// itself was resolved from the account's watch history against those
			// same two fields. When a title *is* present it must still agree.
			val frozenTitle = session.title
			val resolvedTitle = resolution.title ?: localizedTitle
				?: return "resolved candidate omitted the canonical title"
			if (frozenTitle != null &&
				listOfNotNull(resolution.title, localizedTitle).none {
					SearchResultsParser.shortTitleMatches(frozenTitle, it)
				}
			) {
				return "resolved candidate title \"$resolvedTitle\"" +
					localizedTitle?.takeIf { it != resolvedTitle }?.let { " (displayed \"$it\")" }
						.orEmpty() +
					" contradicts foreground title \"$frozenTitle\""
			}
			// A seekbar that was never rendered publishes no length, and absence of
			// evidence is not contradiction — measured 2026-08-06 late, YouTube
			// stopped drawing the Shorts progress bar entirely. What remains is
			// the exact owner handle on both the candidate and the final page,
			// the title when there is one, and the single-match rule; a length
			// that *is* published must still agree.
			val identityDuration = identityDurationMs(session)
			val durationKnown = identityDuration != null && identityDuration > 0
			if (durationKnown &&
				!SessionProbe.durationsCorroborate(identityDuration, resolution.lengthSeconds)
			) {
				return "resolved candidate duration did not corroborate the foreground seekbar"
			}
			// Neither a title nor a length leaves the handle alone, which the
			// resolver may only answer with a single recent match — no recency
			// tie-break. The uniqueness check above and the two handle checks
			// below are then the whole gate, which is why they are not relaxed.
			if (!OwnerHandle.matches(wantedHandle, resolution.ownerHandle)) {
				return "resolved candidate owner handle ${resolution.ownerHandle ?: "<missing>"} " +
					"contradicts foreground handle $wantedHandle"
			}
			val finalFacts = facts ?: return "final watch-page handle evidence was unavailable"
			// A handle the enrichment fetch simply did not carry is absence, not
			// contradiction — and the candidate's own page has already proven it
			// above, for this same id. Measured 2026-08-07: an 83-second listen
			// was refused because the second fetch of a page whose *first* fetch
			// had supplied `@colewalliser` came back without the field. A handle
			// that is present and different still contradicts.
			if (finalFacts.ownerHandle != null &&
				!OwnerHandle.matches(wantedHandle, finalFacts.ownerHandle)
			) {
				return "final watch-page owner handle ${finalFacts.ownerHandle} " +
					"contradicts foreground handle $wantedHandle"
			}
		}
		val structuredNativeCompatible = if (resolution.structuredNativeMusic) {
			val frozenTitle = session.title
			val frozenArtist = session.artist
			val frozenDuration = identityDurationMs(session)?.div(1000)
			if (!session.profile.packageProvesSource || !resolution.uniquelyResolved ||
				frozenTitle.isNullOrBlank() || frozenArtist.isNullOrBlank() ||
				frozenDuration == null || facts == null
			) {
				return "structured native music proof was incomplete at finalization"
			}
			val resolutionMatches = NativeStructuredMusicMatcher.matches(
				resolution, frozenTitle, frozenArtist, frozenDuration,
			)
			val finalWatchFacts = VideoResolution(
				videoId = facts.videoId,
				source = "final watch facts",
				title = facts.title,
				channel = facts.author,
				lengthSeconds = facts.lengthSeconds,
			)
			val factsMatch = NativeStructuredMusicMatcher.matches(
				finalWatchFacts,
				frozenTitle,
				frozenArtist,
				frozenDuration,
			) ||
				// The collaboration case. See [VideoResolution.creditedArtists]:
				// on a multi-artist art track the page has one `- Topic` channel
				// and cannot restate the whole credit, so requiring it to do so
				// refused every correct id. The complete credit is already proven
				// by the catalog row that produced this resolution; here the page
				// only has to name one of those same artists, over the same work
				// at the same length.
				NativeStructuredMusicMatcher.matchesOneCredit(
					finalWatchFacts,
					frozenTitle,
					resolution.creditedArtists,
					frozenDuration,
				)
			// The alias case, and why the watch page cannot settle it.
			//
			// A `- Topic` channel routinely carries a *different name for the same
			// act* than the catalogue does. Measured 2026-08-23, from an overnight
			// run in which all three were fully played and all three were refused:
			//
			// ```
			//                page author              catalogue / player credit
			// Who Dem        Mr. Lexx - Topic         Lexxus
			// Wine Pon It    Munga Honorable - Topic  Munga
			// Patience       Nas - Topic              Nas & Damian "Jr. Gong" Marley
			// ```
			//
			// The answer is not to stop checking the credit — that check is what
			// stops a catalogue row whose id points at another artist's same-named
			// song of the same length. It is to ask the source that actually knows.
			// The music client, queried for this same id, returns exactly what the
			// player published: `Lexxus`, `Munga`, `Nas & Damian "Jr. Gong" Marley`.
			//
			// So this no longer requires the watch page to have been *unavailable*.
			// A music-client credit for this id is positive evidence, and it does
			// not become less true because the page happened to load as well.
			//
			// What it may stand in for is exactly one field: the **name**. A page
			// that did load must still agree on the work and the length, so a page
			// describing something else keeps vetoing however well the credit
			// reads. Every other condition is unchanged: same package, same id,
			// recognised as music, and the credit itself must agree.
			val sameIdMusicClientMatch =
				session.packageName == YouTubeProbe.YOUTUBE_MUSIC_PACKAGE &&
				facts.videoId == resolution.videoId &&
				(
					!facts.resolvedOnWatchPage ||
						NativeStructuredMusicMatcher.corroboratesWorkAndLength(
							finalWatchFacts, frozenTitle, frozenDuration,
						)
					) &&
				facts.recognisedByYouTubeMusic &&
				facts.originalArtist?.takeIf(String::isNotBlank)?.let { musicCredit ->
					NativeStructuredMusicMatcher.creditsAgree(
						listOf(musicCredit), frozenArtist,
					)
				} == true
			if (!resolutionMatches || !(factsMatch || sameIdMusicClientMatch)) {
				return "structured native music candidate did not re-corroborate against " +
					"the finalized title, artist and duration"
			}
			true
		} else {
			false
		}
		// Chromium can omit duration after a listener/process rebuild. A unique
		// bounded route may still bind the finalized title to one candidate while
		// that id's canonical page independently binds the session channel. This is
		// two strong available fields plus uniqueness, not an absent-field guess.
		val resolvedTitleForComparison = resolution.title
		val finalizedTwoFieldIdentity = resolution.uniquelyResolved &&
			(identityDurationMs(session) ?: 0) <= 0 &&
			facts?.videoId == resolution.videoId &&
			!session.title.isNullOrBlank() && !resolvedTitleForComparison.isNullOrBlank() &&
			VideoTitleMatcher.compare(session.title, resolvedTitleForComparison) in setOf(
				VideoTitleMatcher.Evidence.EXACT,
				VideoTitleMatcher.Evidence.STRONG_CONTAINMENT,
			) &&
			SearchResultsParser.channelKey(session.artist) != null &&
			SearchResultsParser.channelKey(session.artist) ==
				SearchResultsParser.channelKey(facts.author)
		val exactNativeMediaId = session.profile.packageProvesSource &&
			session.confirmed?.videoId == resolution.videoId &&
			session.confirmed?.exactIdRoute != null
		val sameObservedGeneration = exactNativeMediaId || resolution.videoId ==
			session.resolverContext.observedVideoId &&
			session.resolverContext.urlGeneration != null &&
			(session.confirmed?.urlGeneration == null ||
				 session.confirmed?.urlGeneration == session.resolverContext.urlGeneration)
		val collaborativeCompatibility = collaborativeCandidateCompatible(
			session, resolution, facts,
		)
		val enrichedBylineMatchesCandidate = collaborativeCompatibility &&
			SearchResultsParser.channelKey(facts?.author) ==
			SearchResultsParser.channelKey(resolution.channel)

		contradictionFor(
			session = session,
			videoId = resolution.videoId,
			title = resolution.title,
			alternateTitle = localizedTitle,
			channel = resolution.channel,
			lengthSeconds = resolution.lengthSeconds,
			label = "${resolution.source} candidate",
			sameObservedGeneration = sameObservedGeneration,
			allowCollaborativeByline = collaborativeCompatibility,
			allowStructuredNativeMusic = structuredNativeCompatible,
			allowChannelAlias = finalizedTwoFieldIdentity,
			allowFinalizedTwoFieldIdentity = finalizedTwoFieldIdentity,
		)?.let { return it }

		// YouTube spells one channel two ways, and the second spelling is not a
		// second opinion.
		//
		// Measured 2026-08-04 for `7J6xA1_f8as`: the playlist page and the
		// MediaSession both said "Cosculluela El Principe" while the watch page
		// said "Cosculluela - Topic". Stripping " - Topic" leaves "Cosculluela",
		// which still is not the artist's full stage name, so the watch page
		// vetoed a correct id that the playlist had already corroborated.
		//
		// Measured 2026-08-09, the same shape on every VEVO upload — the native
		// MediaSession names the *artist* and the watch page names the label's
		// channel: "BMTHOfficialVEVO" against "Bring Me The Horizon",
		// "dojacatVEVO" against "Doja Cat", "NickiMinajAtVEVO" against
		// "Nicki Minaj", "IamdoechiiVEVO" against "Doechii", "FloLikeThisVEVO"
		// against "FLO". 46 refusals of correct ids in one day's log, none of
		// them from a playlist, so keying the exception to `playlistVerified`
		// left every history- and search-resolved VEVO video unrescued. The
		// names do not normalize to each other and never will: no rule turns
		// "BMTHOfficialVEVO" into "Bring Me The Horizon".
		//
		// What licenses the exception is the *id*, not the route. These facts
		// were fetched for the id the resolver returned, so the two names
		// describe one video's one uploader by construction — they cannot be
		// two different channels. The evidence that the id itself is right is
		// the resolving route's own three-field match, which already included
		// the channel; re-reading the channel off the page that id points at is
		// not independent corroboration of anything.
		//
		// So: the route must have matched a channel of its own against the
		// session artist, the facts must belong to that same id, and title and
		// duration must still agree on this pass. A route that never matched a
		// channel gets nothing here.
		//
		// Measured 2026-08-10, the same shape in Brave: "Happy Song" resolved
		// to `GBRAnuT48qo` from the playlist being played, whose watch page
		// names "BMTHOfficialVEVO" — the session's own artist string — and the
		// enrichment then came back from YouTube's music client, which credits
		// "Bring Me The Horizon". A 216-of-236-second listen was refused for
		// that difference. Nothing in the argument above is about native
		// playback, so the source capability expresses what the former kind check was
		// asserting: an id some route uniquely resolved by its own three-field
		// match. An id the address bar merely named is not that — its
		// resolution carries the enriched page's channel rather than one a
		// route matched against the session — and it stays exactly as strict.
		val channelAlias = (session.profile.packageProvesSource || resolution.uniquelyResolved) &&
			facts?.videoId == resolution.videoId &&
			SearchResultsParser.channelKey(resolution.channel) != null &&
			SearchResultsParser.channelKey(resolution.channel) ==
			SearchResultsParser.channelKey(session.artist)

		// The same argument as [channelAlias], for the other field YouTube
		// spells two ways.
		//
		// Measured 2026-08-11 in Brave: "Bring Me The Horizon - Avalanche
		// (Official Video)" resolved to `UNaYpBpRJOY` from the playlist being
		// played — that entry's title, channel and duration all matched the
		// session — and the enrichment fetch came back with the page's own name
		// for it, "Avalanche (Official Video)". One token after the presentation
		// core is taken, so [VideoTitleMatcher] grades it
		// WEAK_SHORT_CANONICAL_CORE, and a 277-of-275-second listen was refused.
		// Every VEVO upload has this shape: the listing row says "Artist - Work"
		// and the watch page says "Work".
		//
		// The weak rank exists so that a one-token name cannot *establish* an id
		// by itself. It is not establishing anything here. The id was already
		// established by a route that matched this same title against the
		// session, and these facts were fetched for that id, so the shorter name
		// is one video's other rendering rather than a second opinion — the
		// identical reasoning that licenses the channel alias, and it admits no
		// new candidate for the same reason.
		//
		// Duration still binds whenever the session published it. If the session
		// omitted it, [finalizedTwoFieldIdentity] requires the route's exact/strong
		// full title, the canonical page's exact channel and one surviving id. A
		// page title that genuinely contradicts still refuses in either case.
		val resolvedTitleAgreement = session.title
			?.takeIf(String::isNotBlank)
			?.let { frozen ->
				listOfNotNull(resolution.title, localizedTitle)
					.filter(String::isNotBlank)
					.map { VideoTitleMatcher.compare(frozen, it) }
					.minByOrNull(::rank)
			}
		val titleAlias = resolution.uniquelyResolved &&
			facts?.videoId == resolution.videoId &&
			(
				resolvedTitleAgreement == VideoTitleMatcher.Evidence.EXACT ||
					resolvedTitleAgreement == VideoTitleMatcher.Evidence.STRONG_CONTAINMENT
				)

		contradictionFor(
			session = session,
			videoId = resolution.videoId,
			title = facts?.title,
			// The displayed name belongs to the resolved id's own page, so it is
			// only the second name of *these* facts when the facts describe that
			// same id.
			alternateTitle = localizedTitle?.takeIf { facts?.videoId == resolution.videoId },
			channel = facts?.author,
			lengthSeconds = facts?.lengthSeconds,
			label = "enriched watch facts",
			sameObservedGeneration = sameObservedGeneration,
			allowCollaborativeByline = enrichedBylineMatchesCandidate,
			allowStructuredNativeMusic = structuredNativeCompatible,
			allowChannelAlias = channelAlias,
			allowWeakTitleAlias = titleAlias,
			allowFinalizedTwoFieldIdentity = finalizedTwoFieldIdentity,
		)?.let { return it }

		val firstObservedId = session.resolverContext.observedVideoId
		val identityMoved = firstObservedId != null && firstObservedId != resolution.videoId
		val hasCorroboratingMetadata = resolution.title != null ||
			resolution.channel != null || resolution.lengthSeconds != null ||
			facts?.title != null || facts?.author != null || facts?.lengthSeconds != null
		if (identityMoved && !hasCorroboratingMetadata) {
			return "resolved id ${resolution.videoId} replaced observed id $firstObservedId " +
				"without frozen candidate facts"
		}

		return null
	}

	private fun contradictionFor(
		session: SessionSnapshot,
		videoId: String,
		title: String?,
		channel: String?,
		lengthSeconds: Long?,
		label: String,
		sameObservedGeneration: Boolean,
		allowCollaborativeByline: Boolean,
		allowStructuredNativeMusic: Boolean,
		/** Relaxes the *channel* comparison only; title and duration still bind. */
		allowChannelAlias: Boolean = false,
		/**
		 * Relaxes only the *weak* title rank, and only with a corroborating
		 * duration. A contradicting title still refuses, and the channel is
		 * untouched.
		 */
		allowWeakTitleAlias: Boolean = false,
		/** Unique title + canonical channel when the finalized source omitted duration. */
		allowFinalizedTwoFieldIdentity: Boolean = false,
		/** The same page's other name for the same id, when it publishes two. */
		alternateTitle: String? = null,
	): String? {
		val frozenDurationMs = identityDurationMs(session)
		val frozenTitle = session.title
		val published = listOfNotNull(title, alternateTitle).filter(String::isNotBlank)
		// The strongest of the names this one page publishes. A translated
		// rendering is not a second candidate, so it cannot weaken the evidence
		// either — only the best of them decides.
		val titleEvidence = if (published.isNotEmpty() && !frozenTitle.isNullOrBlank()) {
			published.map { VideoTitleMatcher.compare(frozenTitle, it) }.minByOrNull(::rank)
		} else {
			null
		}
		if (!allowStructuredNativeMusic &&
			titleEvidence == VideoTitleMatcher.Evidence.CONTRADICTION
		) {
			return "$label title \"$title\"" +
				alternateTitle?.takeIf { it != title }?.let { " (displayed \"$it\")" }.orEmpty() +
				" contradicts ended title \"$frozenTitle\""
		}
		if (!allowStructuredNativeMusic &&
			titleEvidence == VideoTitleMatcher.Evidence.WEAK_SHORT_CANONICAL_CORE &&
			!(
				allowWeakTitleAlias &&
					(SessionProbe.durationsCorroborate(frozenDurationMs, lengthSeconds) ||
						allowFinalizedTwoFieldIdentity)
				) &&
			!(session.profile.packageProvesSource && session.confirmed?.exactIdRoute != null &&
				session.confirmed?.videoId == videoId &&
				SessionProbe.durationsCorroborate(frozenDurationMs, lengthSeconds)) &&
			!SessionProbe.titleEvidenceMayRetainObservedId(
				evidence = titleEvidence,
				candidateVideoId = videoId,
				candidateGeneration = session.confirmed?.urlGeneration
					?: session.resolverContext.urlGeneration,
				observedVideoId = session.resolverContext.observedVideoId,
				observedGeneration = session.resolverContext.urlGeneration,
				sessionDurationMs = frozenDurationMs,
				pageDurationSeconds = lengthSeconds,
			)
		) {
			return "$label title \"$title\" is only weak evidence and cannot establish " +
				"video id $videoId for the ended track"
		}

		val frozenChannel = session.artist
		if (session.ownerHandle == null && !channel.isNullOrBlank() && !frozenChannel.isNullOrBlank()) {
			val wanted = SearchResultsParser.channelKey(frozenChannel)
			val actual = SearchResultsParser.channelKey(channel)
			val titleAndDurationCorroborate = titleEvidence != null &&
				titleEvidence != VideoTitleMatcher.Evidence.CONTRADICTION &&
				SessionProbe.durationsCorroborate(frozenDurationMs, lengthSeconds)
			// A watch page credits collaborators where the media session names
			// only the uploader: "La Melma Music and 2 more" against
			// "La Melma Music", "Eladio Carrion and CAZZU" against
			// "Eladio Carrion". Same channel, longer byline — measured
			// 2026-08-05, 12 rejections in one session. The *leader* of the
			// byline is the uploader, so matching it is not a relaxation: title
			// and duration still have to agree, and a byline whose leader is a
			// different channel still contradicts.
			// Requires the title and the duration to agree as well, so this is
			// three matching fields, not a relaxation to one. A byline whose
			// leader is a different channel still contradicts, and a name with no
			// separator ("Owner Collaborator") is not a byline at all.
			val bylineLeaderMatches = wanted != null && titleAndDurationCorroborate &&
				SearchResultsParser.collaboratorLeader(channel)
					?.let { SearchResultsParser.channelKey(it) } == wanted
			if (wanted != null && actual != null && wanted != actual &&
				!bylineLeaderMatches &&
				!(sameObservedGeneration && titleAndDurationCorroborate) &&
				!allowCollaborativeByline && !allowStructuredNativeMusic &&
				!(allowChannelAlias &&
					(titleAndDurationCorroborate || allowFinalizedTwoFieldIdentity))
			) {
				return "$label channel \"$channel\" contradicts ended channel \"$frozenChannel\""
			}
		}

		if (SessionProbe.durationsDisagree(frozenDurationMs, lengthSeconds)) {
			return "$label duration ${lengthSeconds}s contradicts ended duration " +
				"${frozenDurationMs?.div(1000)}s"
		}
		return null
	}

	/** Ranked strength, strongest first, so the best of two names can be chosen. */
	private fun rank(evidence: VideoTitleMatcher.Evidence): Int = when (evidence) {
		VideoTitleMatcher.Evidence.EXACT -> 0
		VideoTitleMatcher.Evidence.STRONG_CONTAINMENT -> 1
		VideoTitleMatcher.Evidence.WEAK_SHORT_CANONICAL_CORE -> 2
		VideoTitleMatcher.Evidence.CONTRADICTION -> 3
	}

	/** Strong search-presentation exception; never a substring or runtime History lookup. */
	private fun collaborativeCandidateCompatible(
		session: SessionSnapshot,
		resolution: VideoResolution,
		facts: VideoFacts?,
	): Boolean {
		if (!resolution.uniquelyResolved || !resolution.collaborativeChannel ||
			facts?.recognisedByYouTubeMusic != true
		) return false
		val endedTitle = session.title ?: return false
		val candidateTitle = resolution.title ?: return false
		val titleEvidence = VideoTitleMatcher.compare(endedTitle, candidateTitle)
		if (titleEvidence != VideoTitleMatcher.Evidence.EXACT &&
			titleEvidence != VideoTitleMatcher.Evidence.STRONG_CONTAINMENT
		) return false
		if (!SessionProbe.durationsCorroborate(identityDurationMs(session), resolution.lengthSeconds)) {
			return false
		}
		val candidateChannel = resolution.channel ?: return false
		val parts = candidateChannel.split(COLLABORATOR_SEPARATOR)
			.map(String::trim)
		if (parts.size < 2 || parts.any(String::isBlank)) return false
		val leaderKey = SearchResultsParser.channelKey(parts.first()) ?: return false
		val endedChannelKey = SearchResultsParser.channelKey(session.artist) ?: return false
		val parsedArtistKey = SearchResultsParser.channelKey(
			TitleParser.parse(endedTitle, session.artist).artist,
		) ?: return false
		return leaderKey == endedChannelKey && leaderKey == parsedArtistKey
	}

	private val COLLABORATOR_SEPARATOR = Regex(
		"""\s+(?:and|&|x|×)\s+""",
		RegexOption.IGNORE_CASE,
	)

	/** The finalized guard uses the same ranked predicate as the live latch. */
	fun titleEvidence(first: String, second: String): VideoTitleMatcher.Evidence =
		VideoTitleMatcher.compare(first, second)

	/** Only fully populated, strong canonical evidence may seed replay recovery. */
	fun cacheable(
		session: SessionSnapshot,
		resolution: VideoResolution,
		facts: VideoFacts?,
	): Boolean {
		// The run-local cache is keyed for raw title/channel corroboration. A
		// structured native recovery must be re-searched and fully re-fetched.
		if (resolution.structuredNativeMusic) return false
		session.ownerHandle?.let { wantedHandle ->
			val title = facts?.title ?: resolution.title ?: return false
			val length = facts?.lengthSeconds ?: resolution.lengthSeconds ?: return false
			// Either published name, for the same reason the corroborator takes
			// either: a page that renders its title in the viewer's language has
			// not become a different video.
			val frozenTitle = session.title ?: return false
			return resolution.uniquelyResolved &&
				listOfNotNull(title, resolution.localizedTitle).any {
					SearchResultsParser.shortTitleMatches(frozenTitle, it)
				} &&
				SessionProbe.durationsCorroborate(identityDurationMs(session), length) &&
				OwnerHandle.matches(wantedHandle, resolution.ownerHandle) &&
				OwnerHandle.matches(wantedHandle, facts?.ownerHandle)
		}
		val title = facts?.title ?: resolution.title ?: return false
		val channel = facts?.author ?: resolution.channel ?: return false
		val length = facts?.lengthSeconds ?: resolution.lengthSeconds ?: return false
		val frozenTitle = session.title ?: return false
		val frozenChannel = session.artist ?: return false
		val evidence = VideoTitleMatcher.compare(frozenTitle, title)
		if (evidence != VideoTitleMatcher.Evidence.EXACT &&
			evidence != VideoTitleMatcher.Evidence.STRONG_CONTAINMENT
		) return false
		if (SearchResultsParser.channelKey(frozenChannel) != SearchResultsParser.channelKey(channel)) {
			return false
		}
		return SessionProbe.durationsCorroborate(identityDurationMs(session), length)
	}
}
