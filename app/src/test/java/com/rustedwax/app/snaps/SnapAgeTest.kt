package com.rustedwax.app.snaps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The single token under a posted Snap's handle.
 *
 * Tested at the boundaries, because those are the only places it can be wrong:
 * everything between them is the same division.
 */
class SnapAgeTest {

	private val now = 1_800_000_000L

	private fun ago(seconds: Long) = SnapAge.label(now, now - seconds)

	@Test
	fun `the first minute is now`() {
		assertEquals("now", ago(0))
		assertEquals("now", ago(1))
		assertEquals("now", ago(59))
	}

	@Test
	fun `minutes up to the hour`() {
		assertEquals("1m", ago(60))
		assertEquals("1m", ago(119))
		assertEquals("2m", ago(120))
		assertEquals("59m", ago(3_599))
	}

	@Test
	fun `hours up to the day`() {
		assertEquals("1h", ago(3_600))
		assertEquals("2h", ago(7_200))
		assertEquals("23h", ago(86_399))
	}

	@Test
	fun `days after that, and no other form`() {
		assertEquals("1d", ago(86_400))
		assertEquals("6d", ago(6 * 86_400))
		assertEquals("30d", ago(30 * 86_400))
		assertEquals("400d", ago(400 * 86_400))
	}

	/**
	 * The device clock and the chain do not have to agree. A Snap that looks like
	 * it is from the future reads as `now`, never as a negative age.
	 */
	@Test
	fun `a future timestamp reads as now`() {
		assertEquals("now", SnapAge.label(now, now + 5))
		assertEquals("now", SnapAge.label(now, now + 100_000))
	}

	/** Nonsense in, something renderable out. Nothing here may throw. */
	@Test
	fun `extreme values still produce a label`() {
		assertEquals("now", SnapAge.label(0L, Long.MAX_VALUE / 2))
		assertEquals("now", SnapAge.label(0L, 0L))
		// Absurd, but it still answers in days rather than overflowing or throwing.
		assertEquals("${Long.MAX_VALUE / 86_400}d", SnapAge.label(Long.MAX_VALUE, 0L))
	}
}

/**
 * Which strings are account names, and therefore which ones are worth asking an
 * image host about.
 *
 * The earlier version of this check tested the *charset* only, so `...`, `---`,
 * `.alice` and `a..b` all produced a request for an avatar that cannot exist.
 * These are the cases that separate a structural validator from a character
 * filter.
 */
class HiveAccountNameTest {

	@Test
	fun `accepts the account names Hive actually has`() {
		listOf(
			"alice",
			"bob",
			"skiptvads.vidz",
			"peak.snaps",
			"a1b2c3",
			"one-two",
			// Consecutive interior hyphens are legal. Hive constrains a label's
			// first and last character and its alphabet, and nothing else — the
			// rule that used to reject this one was invented here.
			"ab--cd",
			"a--b--c",
			"ab--cd.ef--gh",
			"abc",
			"a".repeat(16),
			"aaa.bbb.ccc.ddd",
			"user2024",
		).forEach {
			assertTrue("should be valid: $it", HiveAccountName.isValid(it))
		}
	}

	@Test
	fun `rejects a leading or trailing dot`() {
		listOf(".alice", "alice.", ".alice.", ".", "...").forEach {
			assertFalse("should be invalid: $it", HiveAccountName.isValid(it))
		}
	}

	@Test
	fun `rejects consecutive dots`() {
		listOf("a..b", "alice..vidz", "abc..def").forEach {
			assertFalse("should be invalid: $it", HiveAccountName.isValid(it))
		}
	}

	@Test
	fun `rejects names made of punctuation`() {
		listOf("---", "-.-", "--------", "...", ".-.").forEach {
			assertFalse("should be invalid: $it", HiveAccountName.isValid(it))
		}
	}

	@Test
	fun `rejects malformed segments`() {
		listOf(
			"ab",               // too short overall
			"a.bcd",            // one-character segment
			"abcd.ef",          // two-character segment
			"1abc",             // must start with a letter
			"-abc",             // likewise
			"abc-",             // must end with a letter or digit
			"--abc",            // still must begin with a letter
			"abc--",            // and still must end with a letter or digit
			"a".repeat(17),     // too long overall
		).forEach {
			assertFalse("should be invalid: $it", HiveAccountName.isValid(it))
		}
	}

	@Test
	fun `rejects anything that is not a name at all`() {
		listOf(
			"",
			"   ",
			"UPPERCASE",
			"Alice",
			"has space",
			"a/../../etc/passwd",
			"alice/avatar",
			"alice?x=1",
			"alice#frag",
			"https://evil.example",
			"alice\u202E",
			"alice\n",
			"alice\u0000",
			"al\tice",
			"ali\u00e9ce",
		).forEach {
			assertFalse("should be invalid: $it", HiveAccountName.isValid(it))
		}
	}
}

/**
 * The avatar URL is built from a valid account name, or it is not built at all.
 *
 * Null is what stops the request: `HiveAvatars.load` asks this first and returns
 * before it touches its cache or the network, so an invalid name draws the
 * neutral circle without a byte leaving the device.
 */
class HiveAvatarUrlTest {

	@Test
	fun `builds the image host URL for a real account name`() {
		assertEquals(
			"https://images.hive.blog/u/skiptvads.vidz/avatar/small",
			HiveAvatarUrl.of("skiptvads.vidz"),
		)
	}

	@Test
	fun `refuses to build a URL for anything that is not an account name`() {
		listOf(
			"",
			"ab",
			".alice",
			"alice.",
			"a..b",
			"---",
			"1abc",
			"UPPERCASE",
			"has space",
			"a/../../etc/passwd",
			"alice/avatar",
			"alice?x=1",
			"alice#frag",
			"https://evil.example",
			"a".repeat(64),
			"alice\u202E",
			"alice\n",
		).forEach {
			assertNull("must not build a URL for: $it", HiveAvatarUrl.of(it))
		}
	}

	/** Valid and invalid are the same question, asked once. */
	@Test
	fun `builds a URL exactly when the name is valid`() {
		listOf("alice", "peak.snaps", "abc", "ab--cd", "---", "a..b", ".x", "").forEach {
			assertEquals(
				"disagreement about: $it",
				HiveAccountName.isValid(it),
				HiveAvatarUrl.of(it) != null,
			)
		}
	}
}

/**
 * Chain timestamps, and everything that is not one.
 *
 * The chain emits exactly one shape — `2026-09-17T12:36:00`, UTC, fixed width.
 * Anything else is a field this code could not read, and the card falls back to
 * the locally stored time rather than inventing an age from a partial parse.
 */
class ChainTimeTest {

	@Test
	fun `reads a canonical chain timestamp as UTC`() {
		// 2026-09-17T12:36:00Z
		assertEquals(1_789_648_560L, ChainTime.epochSec("2026-09-17T12:36:00"))
		assertEquals(0L, ChainTime.epochSec("1970-01-01T00:00:00"))
		assertEquals(1_709_164_800L, ChainTime.epochSec("2024-02-29T00:00:00"))
	}

	/** Trailing rubbish used to be ignored, so the whole string is consumed now. */
	@Test
	fun `rejects anything after the timestamp`() {
		listOf(
			"2026-09-17T12:36:00junk",
			"2026-09-17T12:36:00Z",
			"2026-09-17T12:36:00.000",
			"2026-09-17T12:36:00 ",
			"2026-09-17T12:36:00+01:00",
			" 2026-09-17T12:36:00",
		).forEach {
			assertNull("must not parse: $it", ChainTime.epochSec(it))
		}
	}

	/** Field widths are exact: the chain never emits a one-digit month or hour. */
	@Test
	fun `rejects variable-width fields`() {
		listOf(
			"2026-9-7T1:2:3",
			"2026-9-17T12:36:00",
			"2026-09-7T12:36:00",
			"2026-09-17T1:36:00",
			"26-09-17T12:36:00",
			"2026-09-17T12:36",
		).forEach {
			assertNull("must not parse: $it", ChainTime.epochSec(it))
		}
	}

	/** And the numbers have to be a real moment, not merely digits. */
	@Test
	fun `rejects impossible calendar values`() {
		listOf(
			"2026-02-30T00:00:00",
			"2026-02-29T00:00:00",
			"2026-13-01T00:00:00",
			"2026-00-01T00:00:00",
			"2026-09-00T12:36:00",
			"2026-09-31T12:36:00",
			"2026-09-17T24:00:00",
			"2026-09-17T12:60:00",
			"2026-09-17T12:36:60",
			"2026-99-99T99:99:99",
		).forEach {
			assertNull("must not parse: $it", ChainTime.epochSec(it))
		}
	}

	@Test
	fun `fails to null rather than throwing`() {
		listOf(
			null,
			"",
			"   ",
			"not a time",
			"17/09/2026",
			"\u0000",
			"aaaa-aa-aaTaa:aa:aa",
			"2026-09-17X12:36:00",
			"2026:09:17T12-36-00",
		).forEach {
			assertNull("must not parse: $it", ChainTime.epochSec(it))
		}
	}
}
