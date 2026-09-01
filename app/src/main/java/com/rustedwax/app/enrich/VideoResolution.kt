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

		val localizedTitle = resolution.localizedTitle

		session.ownerHandle?.let { wantedHandle ->
			if (!resolution.uniquelyResolved) {
				return "foreground Short id was not the only corroborated candidate"
			}

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

		val channelAlias = (session.profile.packageProvesSource || resolution.uniquelyResolved) &&
			facts?.videoId == resolution.videoId &&
			SearchResultsParser.channelKey(resolution.channel) != null &&
			SearchResultsParser.channelKey(resolution.channel) ==
			SearchResultsParser.channelKey(session.artist)

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
