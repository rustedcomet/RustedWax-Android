package com.rustedwax.app.detect

import com.rustedwax.core.TextNormalizer
/**
 * Splits `"Korn - Trash (Official Audio)"` into artist and track.
 *
 * Phase 0 run 2 established this is mandatory, not cosmetic: a browser media
 * session reports `ARTIST` as the *channel* ("KornVEVO") and packs the real
 * artist into the video title. Broadcasting raw would write
 * `artist: "KornVEVO"` to the chain, which matches no desktop scrobble of the
 * same song and poisons per-artist indexes.
 *
 * A minimal port of the YouTube rules from `@web-scrobbler/metadata-filter`
 * plus the extension's `pipeline/normalize`. Upstream's tables are far larger
 * and encode years of edge cases; this covers the common shapes.
 *
 * ## Phase 4: normalize to the original recording (decision D7)
 *
 * Cover, live, instrumental and karaoke markers are now **stripped**, so a
 * guitar cover lands on the same entry as the studio track and play counts
 * accumulate in one place instead of scattering. This is a deliberate
 * divergence from the desktop extension, which keeps them — see <redacted-private-path>.
 *
 * Two limits worth knowing:
 *
 *  - `(Live)` is stripped; `(Live at Wembley)` is kept, because a group is only
 *    dropped when *every* word in it is a known qualifier and no table can
 *    enumerate venue names. A named live recording surviving is the better
 *    failure anyway.
 *  - `(Remix)` is kept. A remix is a distinct work, usually by a different
 *    artist, so folding it into the original would be wrong rather than tidy.
 *
 * ## Trailing hashtags are stripped too
 *
 * Short-form titles are largely tag runs, and leaving them in the title makes
 * the same clip dedup as a different listen every time its tags change. See
 * [TRAILING_HASHTAGS], and [isHashtagOnly] for the case where the tags are all
 * there is.
 */
object TitleParser {

	data class Parsed(val artist: String?, val track: String)

	/** Separators between artist and track, longest/most specific first. */
	private val SEPARATORS = listOf(
		" -- ", " ~ ", " — ", " – ", " - ", " | ", "｜", "／", "：",
	)

	/**
	 * Words that, on their own or in combination, mark a bracketed group as
	 * noise rather than part of the title.
	 *
	 * A group is stripped only when **every** word in it is on this list, which
	 * is why `(Radio Edit)`, `(Sol Invicto Remix)` and `(MAPHRA Vocal Cover)`
	 * survive — an unrecognised word means we don't understand the group well
	 * enough to throw it away.
	 *
	 * Enumerating words beats enumerating phrases: an earlier version listed
	 * `(Official Video)` and `(HD)` separately and let
	 * `"Thoughtless (Official HD Video)"` straight through to the chain.
	 */
	private val PROMO_WORDS = setOf(
		// Promotional / format noise.
		"official", "oficial", "video", "vídeo", "audio", "música", "music",
		"lyric", "lyrics", "visualizer", "visualiser", "hd", "hq", "uhd",
		"4k", "8k", "1080p", "720p", "full", "mv", "m/v", "clip", "stream",
		"version", "explicit", "new", "hi-res", "with", "and", "the",
		"remaster", "remastered", "restored",
		// Performance qualifiers — stripped under D7 so covers and live takes
		// fold into the original recording.
		"cover", "covers", "covered", "instrumental", "karaoke", "backing",
		"playthrough", "live", "acoustic", "performance", "session", "sessions",
		// Instrument names, which only ever appear here as part of a qualifier
		// like "(Guitar Cover)" — the rule needs every word to be known.
		"guitar", "bass", "drum", "drums", "piano", "keyboard", "vocal", "vocals",
		// Tab/gear mentions common on cover channels.
		"tab", "tabs", "screen",
	)

	/**
	 * A bracketed group and its contents, ASCII and CJK.
	 *
	 * CJK brackets were added in Phase 4: `【Guitar Cover】` is the same
	 * construct as `(Guitar Cover)` and was previously invisible to the parser.
	 */
	private val BRACKETED = Regex(
		"""\s*(?:\(([^()]*)\)|\[([^\[\]]*)\]|【([^【】]*)】|""" +
			"""「([^「」]*)」|『([^『』]*)』)""",
	)

	/** Trailing `| Official Video`-style suffixes, which use no brackets. */
	private val PIPE_SUFFIX = Regex("""\s*\|\s*([^|]+)\s*$""")

	/**
	 * A leading CJK bracket naming the artist — `【Bring Me The Horizon】Song`.
	 *
	 * A common shape on Japanese-style cover channels, and the reason the
	 * reported guitar-cover scrobble landed with the channel as its artist:
	 * there is no separator anywhere in the title, so the parser fell through
	 * to the channel name while the real artist sat in the brackets.
	 *
	 * Restricted to CJK brackets on purpose. A leading `[…]` in ASCII is far
	 * more often a tag (`[FREE]`, `[4K]`) than an artist.
	 */
	private val LEADING_BRACKET_ARTIST =
		Regex("""^[【「『]([^】」』]{1,60})[】」』]\s*(.+)$""")

	/**
	 * A bare `MV` suffix — `Song Title MV`, with no brackets to mark it.
	 *
	 * `(MV)` and `【MV】` are already handled as promo brackets; the unbracketed
	 * form is the common one on K-pop and J-pop uploads and was reaching the
	 * chain as part of the track name, so the same recording dedups differently
	 * depending on how its uploader punctuated the suffix.
	 *
	 * Requires a preceding word so a title that *is* `MV` survives, and requires
	 * the whole token so `… Live at MVP Arena` is untouched.
	 */
	private val BARE_MV_SUFFIX = Regex("""\s+MV\s*$""")

	/** Gear/tab mentions cover channels append to the title. */
	private val TRAILING_JUNK = listOf(
		Regex("""[＋+]\s*(?:screen\s+)?tabs?\s*$""", RegexOption.IGNORE_CASE),
		Regex("""\bw/\s*(?:screen\s+)?tabs?\s*$""", RegexOption.IGNORE_CASE),
		Regex("""[|｜/／]\s*(?:screen\s+)?tabs?\s*$""", RegexOption.IGNORE_CASE),
		BARE_MV_SUFFIX,
	)

	/** A bare year, as cover uploads tend to append (`… Medusa 2023`). */
	private val TRAILING_YEAR = Regex("""\s+(?:19|20)\d{2}\s*$""")

	/**
	 * A trailing run of hashtags, plus whatever emoji or punctuation trails it.
	 *
	 * Short-form titles are mostly tags. From the 2026-07-29 session, on-chain
	 * verbatim: `katter — #guitar #dubstep #djdubstep #fnaf #fivenightsatfreddy`
	 * and `Rony González — #plena#panama 🇵🇦`.
	 *
	 * Two costs, and the second is the one that matters. The entries read as
	 * spam — and because the tag run is part of the title, the same clip
	 * reposted with different tags dedups as a different listen and lands twice
	 * on a chain that can't be edited.
	 *
	 * Only a *trailing* run is stripped. A hashtag mid-sentence is doing work
	 * ("Song Title #2 of the series"), and no table can tell those apart.
	 * `[\p{L}\p{N}_]` rather than `\w` so non-Latin tags are recognised — the
	 * session was full of Turkish, Korean and Chinese ones. The trailing
	 * `[^\p{L}\p{N}]*` is what catches `#plena#panama 🇵🇦`, where a flag emoji
	 * sits after the last tag.
	 */
	private val TRAILING_HASHTAGS =
		Regex("""(?:\s*#[\p{L}\p{N}_]+)+[^\p{L}\p{N}]*$""")

	/**
	 * A leading `[tag]` that is *noise* rather than an artist — `[FREE]`,
	 * `[Future Bass]`, `[4K]`.
	 *
	 * Adapted from the desktop extension's `processYtVideoTitle`, which strips a
	 * leading bracket before anything else. **ASCII only**, deliberately: this
	 * project already decided the opposite for CJK brackets, because
	 * `【Bring Me The Horizon】…` names the artist (see [LEADING_BRACKET_ARTIST]).
	 * An earlier version of this rule stripped both and broke exactly that case —
	 * `【Artist】Song - Live` became artist `Song`.
	 */
	private val LEADING_TAG = Regex("""^\s*\[[^\]]{1,30}\]\s*-*\s*""")

	/**
	 * A track-number prefix, as album and vinyl rips carry: `03. `, `A1. `,
	 * `12) `. From the extension's `removeNumericPrefix` and its CD/vinyl rule.
	 *
	 * Bounded to two leading characters so a real title starting with a number
	 * (`1999`, `7 Rings`) is untouched — those have no separator after the digits.
	 */
	private val TRACK_NUMBER_PREFIX =
		Regex("""^\s*(?:[A-Za-z]{1,2}\d{0,2}|\d{1,2})\s*[.)]\s+""")

	/**
	 * `Artist "Track"` / `Artist - "Track"`. The quotes name the track outright,
	 * which is stronger than any separator guess. From the extension's
	 * `ytTitleRegExps`.
	 */
	private val QUOTED_TRACK = Regex(
		"""^(.{1,80}?)\s*[-–—:]?\s*["“]([^"”]{1,120})["”]""" +
			"""(?:\s*[(\[].*[)\]])?\s*$""",
	)

	private data class SingleQuotedShape(
		val artist: String,
		val track: String,
		val trailing: String,
	)

	/** Bare suffixes that make a balanced single-quoted work structurally explicit. */
	private val SINGLE_QUOTED_PROMO_SUFFIX = Regex(
		"""^(?:mv|m/v|official\s+(?:music\s+)?video|music\s+video|lyric\s+video)$""",
		RegexOption.IGNORE_CASE,
	)

	/** `Publisher "Track" - Artist, Featured Artist` from channel-led series. */
	private val QUOTED_TRACK_WITH_TRAILING_CREDITS = Regex(
		"""^(.{1,80}?)\s*["“]([^"”]{1,120})["”]\s*[-–—]\s*(.{1,160})$""",
	)

	/** `Artist Performs "Track" ... | Event` — the event is not the track. */
	private val PERFORMANCE_TITLE = Regex(
		"""^(.{1,80}?)\s+performs?\s+["“]([^"”]{1,120})["”].*$""",
		RegexOption.IGNORE_CASE,
	)

	/**
	 * `Track (by Artist)` / `Track (performed by Artist)` — the one common shape
	 * where the artist comes *second*. Also from `ytTitleRegExps`.
	 */
	private val TRACK_BY_ARTIST =
		Regex("""^(.{1,80}?)\s*\((?:performed\s+)?by\s+([^)]{1,60})\)\s*$""", RegexOption.IGNORE_CASE)

	private val TRAILING_PUNCT = Regex("""[\s\-–—_|｜/／+＋~]+$""")

	private val WORD_SPLIT = Regex("""[\s\-_/]+""")

	/** True when every word is a known qualifier — so the group carries no meaning. */
	private fun isPromoOnly(inner: String): Boolean {
		val words = inner.trim().lowercase()
			.split(WORD_SPLIT)
			.filter { it.isNotBlank() }
		if (words.isEmpty()) return false
		val promoWords = if (words.lastOrNull()?.matches(GROUP_YEAR) == true) {
			words.dropLast(1)
		} else {
			words
		}
		// Require at least one strong marker so a bare "(the)" isn't stripped.
		if (promoWords.none { it in STRONG_MARKERS }) return false
		return promoWords.isNotEmpty() && promoWords.all { it in PROMO_WORDS }
	}

	private val GROUP_YEAR = Regex("""(?:19|20)\d{2}""")

	private val STRONG_MARKERS = setOf(
		"official", "oficial", "video", "vídeo", "audio", "lyric", "lyrics",
		"visualizer", "visualiser", "hd", "hq", "uhd", "4k", "8k", "1080p",
		"720p", "mv", "m/v",
		"cover", "covers", "covered", "instrumental", "karaoke", "backing",
		"playthrough", "live", "acoustic", "remaster", "remastered",
		"guitar", "bass", "drum", "drums", "piano", "vocal", "vocals",
		"tab", "tabs",
	)

	/**
	 * `…AtVEVO` where the `At` is a capitalized word of its own.
	 *
	 * Stripping bare "VEVO" off `NickiMinajAtVEVO` leaves `NickiMinajAt`, whose
	 * key never equals `nickiminaj` — measured 2026-08-05, 14 rejections in one
	 * session, every Nicki Minaj upload. The preceding character has to be
	 * lower-case or a digit and the `At` has to be capitalized, which is what
	 * separates the marker from the ordinary letters of `dojacatVEVO`.
	 */
	private val AT_VEVO = Regex("""[\p{Ll}\p{N}]At[Vv][Ee][Vv][Oo]$""")

	/** Channel suffixes that mark an artist-owned channel. */
	private val CHANNEL_SUFFIXES =
		listOf(
		"VEVO",
		" - Topic",
		"Official",
		"Music",
		"TV",
		"Records",
	)

	fun parse(rawTitle: String, channel: String?): Parsed {
		// Note this is [cleanCore], not [clean]: the trailing year must not be
		// stripped until we know which part of the title is the *track*.
		// Otherwise "The Smashing Pumpkins - 1979" loses its track before the
		// separator is ever considered, and the song becomes the band.
		val title = cleanCore(rawTitle)

		// Checked before separators: in `【Artist】Song - Live` the separator
		// would otherwise split the artist bracket into the artist field.
		LEADING_BRACKET_ARTIST.find(title)?.let { m ->
			val artist = tidy(m.groupValues[1])
			val track = finishTrack(m.groupValues[2])
			if (artist.isNotEmpty() && track.isNotEmpty()) {
				return Parsed(artist, track)
			}
		}

		// Explicit shapes beat separator guessing, because they name which side
		// is which instead of assuming artist-first. Both adapted from the
		// desktop extension's `ytTitleRegExps`.
		PERFORMANCE_TITLE.find(title)?.let { m ->
			val artist = tidy(m.groupValues[1])
			val track = finishTrack(m.groupValues[2])
			if (artist.isNotEmpty() && track.isNotEmpty()) {
				return Parsed(artist, track)
			}
		}
		QUOTED_TRACK_WITH_TRAILING_CREDITS.find(title)?.let { m ->
			val prefix = tidy(m.groupValues[1])
			val track = finishTrack(m.groupValues[2])
			val credits = tidy(m.groupValues[3])
			val prefixAgreement = channelAgreement(prefix, channel)
			val creditAgreement = channelAgreement(credits, channel)
			if (prefix.isNotEmpty() && track.isNotEmpty() && credits.isNotEmpty() &&
				prefixAgreement > 0 && prefixAgreement > creditAgreement &&
				looksLikeTrailingCredits(credits)
			) {
				return Parsed(credits, track)
			}
			// Quotes prove the work boundary, but not that arbitrary text after a
			// dash is an artist. Preserve the whole title rather than turning an
			// event/format suffix into an immutable credit.
			return Parsed(cleanChannel(channel), finishTrack(title))
		}
		QUOTED_TRACK.find(title)?.let { m ->
			val artist = tidy(m.groupValues[1])
			val track = finishTrack(m.groupValues[2])
			if (artist.isNotEmpty() && track.isNotEmpty()) {
				return Parsed(artist, track)
			}
		}
		val singleQuoted = singleQuotedShape(title)
		if (singleQuoted != null) {
			val safeSuffix = singleQuoted.trailing.isEmpty() ||
				SINGLE_QUOTED_PROMO_SUFFIX.matches(singleQuoted.trailing)
			if (safeSuffix) {
				return Parsed(singleQuoted.artist, finishTrack(singleQuoted.track))
			}
			// A balanced quote protects the work, but event/promo text after it
			// does not prove another artist/work boundary.
			return Parsed(cleanChannel(channel), finishTrack(title))
		}
		if (hasUnbalancedStructuralSingleQuote(title)) {
			return Parsed(cleanChannel(channel), finishTrack(title))
		}
		TRACK_BY_ARTIST.find(title)?.let { m ->
			val track = finishTrack(m.groupValues[1])
			val artist = tidy(m.groupValues[2])
			if (artist.isNotEmpty() && track.isNotEmpty()) {
				return Parsed(artist, track)
			}
		}

		topLevelSeparator(title)?.let { (idx, sep) ->
			val left = tidy(title.substring(0, idx))
			val rightRaw = tidy(title.substring(idx + sep.length))
			val right = finishTrack(rightRaw)
			if (left.isNotEmpty() && right.isNotEmpty()) {
				val nested = topLevelSeparator(rightRaw)
				// A conventional primary dash plus a pipe display/album suffix is
				// still an artist/work boundary when the channel proves the left.
				// Other multi-separator shapes remain conservative.
				if (nested != null) {
					val (nestedIndex, nestedSep) = nested
					val primaryTrack = finishTrack(rightRaw.substring(0, nestedIndex))
					val versionSuffix = tidy(
						rightRaw.substring(nestedIndex + nestedSep.length),
					)
					if (isConventionalDash(sep) && isPipe(nestedSep) &&
						channelAgreement(left, channel) > 0 && primaryTrack.isNotEmpty()
					) {
						return Parsed(provenLeftArtist(left, channel), primaryTrack)
					}
					if (isConventionalDash(sep) && isConventionalDash(nestedSep) &&
						exactCollapsedOwnerAgreement(left, channel) &&
						looksLikeVersionSuffix(versionSuffix)
					) {
						return Parsed(left, right)
					}
					return Parsed(cleanChannel(channel), finishTrack(title))
				}
				val leftAgreement = channelAgreement(left, channel)
				val rightAgreement = channelAgreement(right, channel)
				return when {
					isConventionalDash(sep) && exactCollapsedOwnerAgreement(left, channel) ->
						Parsed(left, right)
					isConventionalDash(sep) && looksLikeExplicitMultiArtistCredits(right) &&
						!looksLikeExplicitMultiArtistCredits(left) &&
						(!hasWorkPrefixBeforeFeature(right) || looksStronglyWorkShaped(left)) ->
						Parsed(right, finishTrack(left))
					// `Work - Owner (Album)`, which is the same proof as the left-hand
					// case above, read from the other end.
					//
					// Measured 2026-08-11: "Runaway - Linkin Park (Hybrid Theory)"
					// on the channel "Linkin Park" went on chain as
					// `artist: "Runaway", title: "Linkin Park (Hybrid Theory)"`,
					// because the conventional-dash rule below never consults the
					// channel and artist-first is only a convention.
					//
					// Deliberately not `rightAgreement > leftAgreement`: a shared
					// word is not proof. This wants the right side, with its
					// bracketed groups taken off, to *be* the owner — and the left
					// to share nothing with the owner at all, so a title that names
					// the channel on both sides is never flipped. The artist
					// recorded is the owner's own name, not the bracketed form,
					// because that is what was proven.
					isConventionalDash(sep) && rightSideIsTheOwner(right, left, channel) ->
						Parsed(cleanChannel(channel), finishTrack(left))
					// A featured artist named by the uploader/channel on the right
					// does not reverse conventional `Artist - Track ft. Featured`.
					isConventionalDash(sep) -> Parsed(provenLeftArtist(left, channel), right)
					rightAgreement > leftAgreement -> Parsed(right, finishTrack(left))
					leftAgreement > rightAgreement -> Parsed(left, right)
					// A pipe has no conventional artist-first orientation. With no
					// channel agreement, retain the whole title instead of guessing.
					(sep == " | " || sep == "｜") && !channel.isNullOrBlank() ->
						Parsed(cleanChannel(channel), finishTrack(title))
					// Dash/tilde remain conventional Artist - Track shapes unless
					// the channel positively says the orientation is reversed.
					else -> Parsed(left, right)
				}
			}
		}

		// No separator: the channel is the best artist guess we have.
		return Parsed(cleanChannel(channel), finishTrack(title))
	}

	/** Strip noise and collapse whitespace. */
	fun clean(value: String): String = finishTrack(cleanCore(value))

	/**
	 * Narrow presentation cleanup for identity corroboration.
	 *
	 * Only recognized promo-only brackets and pipe suffixes are removed. Unlike
	 * payload cleanup this preserves arbitrary leading tags, track numbers,
	 * hashtags, tab markers and years: those may distinguish two uploads, and an
	 * identity check must not discard them merely because payload display rules
	 * would.
	 */
	internal fun presentationCore(value: String): String {
		var out = BRACKETED.replace(value) { m ->
			if (isPromoOnly(bracketInner(m))) "" else m.value
		}
		PIPE_SUFFIX.find(out)?.let { m ->
			if (isPromoOnly(m.groupValues[1])) out = out.removeRange(m.range)
		}
		return tidy(out)
	}

	/**
	 * Everything except the trailing year — the part that's safe to do before
	 * the artist/track split.
	 */
	private fun cleanCore(value: String): String {
		// §3.5, and it has to be first: every rule below looks for a separator, a
		// bracket or a word boundary, and a zero-width space sitting on one makes
		// the rule miss on a title that looks perfectly ordinary on screen. The
		// same class of assumption already cost every non-ASCII creator their
		// listens once, through an ASCII-only handle pattern.
		val normalized = TextNormalizer.clean(value)
		// Prefixes first, and never down to nothing — `[Remix]` alone is a title
		// of sorts, and an empty one is worse.
		var out = TRACK_NUMBER_PREFIX.replace(normalized, "").ifBlank { normalized }
		LEADING_TAG.find(out)?.let { m ->
			out = out.removeRange(m.range).ifBlank { out }
		}
		out = BRACKETED.replace(out) { m ->
			if (isPromoOnly(bracketInner(m))) "" else m.value
		}
		PIPE_SUFFIX.find(out)?.let { m ->
			if (isPromoOnly(m.groupValues[1])) out = out.removeRange(m.range)
		}

		// Trailing junk comes in layers — "…【Guitar Cover】＋Screen Tabs" only
		// exposes what's behind it once the tab suffix is gone, and a tag run
		// hides behind a promo bracket in "…#shorts (Official Video)". Loop
		// until stable so the order the uploader chose doesn't matter.
		var changed = true
		while (changed) {
			val before = out
			TRAILING_JUNK.forEach { out = it.replace(out, "") }
			out = stripTrailingHashtags(out)
			out = TRAILING_PUNCT.replace(out, "")
			changed = out != before
		}
		return tidy(out)
	}

	/** Final pass over whatever ended up being the track. */
	private fun finishTrack(value: String): String =
		tidy(
			collapseRepeatedTrailingFeature(
				stripTrailingYear(tidy(value)),
			),
		)

	private fun tidy(value: String): String =
		TRAILING_PUNCT.replace(value.replace(Regex("""\s{2,}"""), " ").trim(), "").trim()

	/**
	 * Drops a trailing hashtag run, but never everything.
	 *
	 * When the tags *are* the title (`"#guitar"`, `"#plena#panama 🇵🇦"`) the
	 * original is returned unchanged — a payload needs some title, and an empty
	 * string is worse than an ugly one. [isHashtagOnly] is how a caller detects
	 * that case and declines to call it a song.
	 */
	fun stripTrailingHashtags(value: String): String {
		val m = TRAILING_HASHTAGS.find(value) ?: return value
		val remainder = value.removeRange(m.range).trim()
		return remainder.ifEmpty { value }
	}

	/**
	 * True when nothing but hashtags, emoji and punctuation is left.
	 *
	 * A title like `"#guitar #dubstep #fnaf"` names no work. YouTube's own
	 * `Music` category was enough to make one of those a `song` on-chain, and a
	 * song with no title isn't a listen — so [MusicClassifier] uses this to
	 * refuse the `song` kind outright, keeping the entry as the video it
	 * usefully is.
	 */
	fun isHashtagOnly(rawTitle: String): Boolean {
		val m = TRAILING_HASHTAGS.find(rawTitle) ?: return false
		return rawTitle.removeRange(m.range).trim().isEmpty()
	}

	/**
	 * Drops a trailing year, but only when something is left to say. Without
	 * the word guard this erases `"1979"` and `"1999"` — real song titles that
	 * are nothing but a year.
	 */
	private fun stripTrailingYear(value: String): String {
		val m = TRAILING_YEAR.find(value) ?: return value
		val remainder = value.removeRange(m.range).trim()
		val words = remainder.split(WORD_SPLIT).filter { it.isNotBlank() }
		return if (words.size >= 2) remainder else value
	}

	private fun bracketInner(match: MatchResult): String =
		match.groupValues.drop(1).firstOrNull(String::isNotEmpty).orEmpty()

	private fun singleQuotedShape(value: String): SingleQuotedShape? {
		val straight = value.indices.filter { value[it] == '\'' }
		val curlyOpen = value.indices.filter { value[it] == '‘' }
		val curlyClose = value.indices.filter { value[it] == '’' }
		val pair = when {
			straight.size == 2 -> straight[0] to straight[1]
			straight.isEmpty() && curlyOpen.size == 1 && curlyClose.size == 1 &&
				curlyOpen.single() < curlyClose.single() -> curlyOpen.single() to curlyClose.single()
			else -> return null
		}
		val (open, close) = pair
		if (open <= 0 || close <= open + 1) return null
		val beforeQuote = value[open - 1]
		if (!beforeQuote.isWhitespace() && beforeQuote !in setOf('-', '–', '—', ':')) return null

		val rawPrefix = value.substring(0, open).trim()
		val artist = rawPrefix.replace(Regex("""\s*[-–—:]\s*$"""), "").trim()
		val track = value.substring(open + 1, close).trim()
		val trailing = value.substring(close + 1).trim()
		if (artist.isEmpty() || track.isEmpty() || topLevelSeparator(artist) != null) return null
		return SingleQuotedShape(artist, track, trailing)
	}

	private fun hasUnbalancedStructuralSingleQuote(value: String): Boolean =
		value.indices.any { index ->
			value[index] == '‘' ||
				(value[index] == '\'' && index > 0 && value[index - 1].isWhitespace())
		}

	/**
	 * Whether the *title shape* suggests music — an artist-owned channel, or a
	 * title that yields an artist through a separator or a leading bracket.
	 *
	 * One input among several now; [MusicClassifier] makes the actual call.
	 */
	fun looksLikeMusic(rawTitle: String, channel: String?): Boolean {
		val ch = channel?.trim().orEmpty()
		if (ch.endsWith("VEVO", ignoreCase = true) ||
			ch.endsWith("- Topic", ignoreCase = true)
		) {
			return true
		}
		return parse(rawTitle, channel).artist != null && yieldsArtist(rawTitle)
	}

	/** True when the title itself names an artist, rather than the channel doing it. */
	private fun yieldsArtist(rawTitle: String): Boolean {
		val title = cleanCore(rawTitle)
		if (LEADING_BRACKET_ARTIST.containsMatchIn(title)) return true
		if (PERFORMANCE_TITLE.containsMatchIn(title) ||
			QUOTED_TRACK_WITH_TRAILING_CREDITS.containsMatchIn(title) ||
			QUOTED_TRACK.containsMatchIn(title)
		) return true
		return topLevelSeparator(title) != null
	}

	/** First separator outside balanced brackets and quoted text. */
	private fun topLevelSeparator(value: String): Pair<Int, String>? {
		var round = 0
		var square = 0
		var cjk = 0
		var quote: Char? = null
		var i = 0
		while (i < value.length) {
			val ch = value[i]
			if (quote != null) {
				if ((quote == '"' && ch == '"') || (quote == '“' && ch == '”')
				) quote = null
				i++
				continue
			}
			when (ch) {
				'"', '“' -> quote = ch
				'(' -> round++
				')' -> round = (round - 1).coerceAtLeast(0)
				'[' -> square++
				']' -> square = (square - 1).coerceAtLeast(0)
				'【', '「', '『' -> cjk++
				'】', '」', '』' -> cjk = (cjk - 1).coerceAtLeast(0)
			}
			if (quote == null && round == 0 && square == 0 && cjk == 0) {
				SEPARATORS.firstOrNull { value.startsWith(it, i) }?.let { return i to it }
			}
			i++
		}
		return null
	}

	private fun isConventionalDash(separator: String): Boolean =
		separator in setOf(" -- ", " — ", " – ", " - ", " ~ ")

	private fun isPipe(separator: String): Boolean = separator == " | " || separator == "｜"

	/** Use the channel's clean spelling only when it exactly proves the left core. */
	private fun provenLeftArtist(left: String, channel: String?): String {
		val cleanedChannel = cleanChannel(channel) ?: return left
		if (SearchKey.of(left) == SearchKey.of(cleanedChannel)) return cleanedChannel
		val parentheticalFeature = PARENTHETICAL_FEATURE.find(left)?.groupValues?.get(1)
		if (parentheticalFeature != null &&
			SearchKey.of(parentheticalFeature) == SearchKey.of(cleanedChannel)
		) return cleanedChannel
		return left
	}

	/**
	 * The right-hand side of a dash *is* the uploader, and the left-hand side is
	 * not part of their name.
	 *
	 * Brackets come off the right first, so `"Linkin Park (Hybrid Theory)"`
	 * proves the owner and `"Fractures (Trap Nation Release)"` does not — in the
	 * second the owner's words are inside the qualifier, which says nothing
	 * about who made the track. The left must share *no* word with the owner, so
	 * `"Linkin Park Tribute - Linkin Park"` keeps its conventional reading.
	 */
	private fun rightSideIsTheOwner(right: String, left: String, channel: String?): Boolean {
		if (channel.isNullOrBlank()) return false
		if (!exactCollapsedOwnerAgreement(withoutBracketedGroups(right), channel)) return false
		return channelAgreement(left, channel) == 0
	}

	/** Every bracketed group removed, whatever it holds — not just promo ones. */
	private fun withoutBracketedGroups(value: String): String =
		tidy(BRACKETED.replace(value, ""))

	/** Exact owner proof after removing punctuation and recognized channel suffixes. */
	private fun exactCollapsedOwnerAgreement(candidate: String, channel: String?): Boolean {
		val cleanedChannel = cleanChannel(channel) ?: return false
		val candidateKey = collapsedOwnerKey(candidate)
		return candidateKey.isNotEmpty() && candidateKey == collapsedOwnerKey(cleanedChannel)
	}

	private fun collapsedOwnerKey(value: String): String = value
		.lowercase()
		.replace(Regex("""[^\p{L}\p{N}]+"""), "")

	private fun looksLikeVersionSuffix(value: String): Boolean {
		if (value.length !in 3..40 || value.any { it in "()[]【】「」『』\"'‘’" }) return false
		val words = value.split(Regex("""\s+""")).filter(String::isNotBlank)
		if (words.size !in 1..4) return false
		return VERSION_MARKER.matches(words.last())
	}

	private val VERSION_MARKER = Regex(
		"""(?:mix|remix|edit|version|rework|dub|remaster|remastered)""",
		RegexOption.IGNORE_CASE,
	)

	/** A credit list needs explicit co-artist structure, not a lone `ft.` phrase. */
	private fun looksLikeExplicitMultiArtistCredits(value: String): Boolean {
		if (NON_CREDIT_TRAILING_PREFIX.containsMatchIn(value.trim())) return false
		if (creditTokens(value).size < 2) return false
		return MULTI_ARTIST_JOINER.containsMatchIn(value)
	}

	/** `Work ft. A, B` is still a conventional right-hand work, not a byline. */
	private fun hasWorkPrefixBeforeFeature(value: String): Boolean {
		val feature = FEATURE_START.find(value) ?: return false
		return tidy(value.substring(0, feature.range.first)).isNotEmpty()
	}

	/** Positive work/version grammar that can still establish track-first form. */
	private fun looksStronglyWorkShaped(value: String): Boolean =
		WORK_VERSION_MARKER.containsMatchIn(value)

	private val WORK_VERSION_MARKER = Regex(
		"""(?:^|[\s\[(])(?:rmx|remix|mix|edit|version|rework|dub|remaster(?:ed)?)(?:$|[\s\])])""",
		RegexOption.IGNORE_CASE,
	)

	/** Remove only a second, identical feature phrase at the very end. */
	private fun collapseRepeatedTrailingFeature(value: String): String {
		val starts = FEATURE_START.findAll(value).toList()
		if (starts.size < 2) return value
		val first = starts[starts.lastIndex - 1]
		val second = starts.last()
		val firstCredit = value.substring(first.range.last + 1, second.range.first).trim()
		val secondCredit = value.substring(second.range.last + 1).trim()
		if (firstCredit.isEmpty() || first.value.trim() != second.value.trim() ||
			tidy(firstCredit) != tidy(secondCredit)
		) {
			return value
		}
		return tidy(value.substring(0, second.range.first))
	}

	private object SearchKey {
		fun of(value: String): String = value.lowercase()
			.replace(Regex("""[^\p{L}\p{N}]+"""), " ")
			.replace(Regex("""\s+"""), " ")
			.trim()
	}

	/** Conservative channel agreement used only to orient a generic split. */
	private fun channelAgreement(candidate: String, channel: String?): Int {
		val channelTokens = creditTokens(cleanChannel(channel) ?: return 0)
		val candidateTokens = creditTokens(candidate)
		if (channelTokens.isEmpty() || candidateTokens.isEmpty()) return 0
		if (channelTokens == candidateTokens) return 100
		return channelTokens.intersect(candidateTokens).size
	}

	private fun looksLikeTrailingCredits(value: String): Boolean {
		if (NON_CREDIT_TRAILING_PREFIX.containsMatchIn(value.trim())) return false
		val tokens = creditTokens(value)
		return tokens.size >= 2 && CREDIT_JOINER.containsMatchIn(value)
	}

	private val CREDIT_JOINER = Regex(
		"""(?:,|&|×|\b(?:and|x|feat(?:uring)?|ft)\.?\b)""",
		RegexOption.IGNORE_CASE,
	)

	private val MULTI_ARTIST_JOINER = Regex(
		"""(?:,|&|×|\b(?:and|x)\b)""",
		RegexOption.IGNORE_CASE,
	)

	private val FEATURE_START = Regex(
		"""\b(?:ft|feat(?:uring)?)\.?\s+""",
		RegexOption.IGNORE_CASE,
	)

	private val PARENTHETICAL_FEATURE = Regex(
		"""^(.+?)\s*\(\s*(?:ft|feat(?:uring)?)\.?\s+.+\)\s*$""",
		RegexOption.IGNORE_CASE,
	)

	/** Presentation/event suffixes that are not artist-credit structures. */
	private val NON_CREDIT_TRAILING_PREFIX = Regex(
		"""^(?:official|oficial|video|audio|lyrics?|visuali[sz]er|live|performance|""" +
			"""session|concert|festival|awards?|stage|trailer|teaser|clip|remix|""" +
			"""remaster(?:ed)?|acoustic|cover|karaoke|behind|making|from|at)\b""",
		RegexOption.IGNORE_CASE,
	)

	private fun creditTokens(value: String): Set<String> = value
		.lowercase()
		.replace(Regex("""[^\p{L}\p{N}]+"""), " ")
		.split(' ')
		.filter { it.length >= 3 && it !in GENERIC_CHANNEL_WORDS }
		.toSet()

	private val GENERIC_CHANNEL_WORDS = setOf(
		"official", "music", "records", "recordings", "channel", "international",
	)

	/**
	 * `"KornVEVO"` → `"Korn"`, `"Korn - Topic"` → `"Korn"`. Returns null for a
	 * blank channel so the payload omits `artist` rather than inventing one.
	 */
	fun cleanChannel(channel: String?): String? {
		var out = channel?.trim().orEmpty()
		if (out.isEmpty()) return null
		// YouTube composes ownership markers (`SpiceOfficialVEVO`). Strip each
		// recognized trailing layer, while never erasing a channel that consists
		// only of the marker word itself.
		var changed = true
		while (changed) {
			changed = false
			// "At" is an ownership marker only where it is its own capitalized
			// word. Matched case-insensitively it also eats the last two letters
			// of any name ending in "at": `dojacatVEVO` became `dojac`, which is
			// neither the channel nor the artist, so search cards and playlist
			// rows reading "Doja Cat" stopped matching the session — and a title
			// with no separator would have written `dojac` to the chain as the
			// artist. Measured 2026-08-10.
			AT_VEVO.find(out)?.let { match ->
				out = out.dropLast(match.value.length - 1).trim()
				changed = true
				return@let
			}
			if (changed) continue
			for (suffix in CHANNEL_SUFFIXES) {
				if (out.length > suffix.length && out.endsWith(suffix, ignoreCase = true)) {
					out = out.dropLast(suffix.length).trim().trimEnd('-', '–', '—').trim()
					changed = true
					break
				}
			}
		}
		return out.ifEmpty { null }
	}
}
