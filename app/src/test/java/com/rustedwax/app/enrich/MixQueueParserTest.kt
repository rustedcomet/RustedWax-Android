package com.rustedwax.app.enrich

import org.junit.Assert.assertEquals
import org.junit.Test

class MixQueueParserTest {

	@Test
	fun `reads only exact rows from the bounded Mix queue`() {
		val json =
			"""{
			  "contents": [
			    {"playlistPanelVideoRenderer": {
			      "videoId": "WL-HrHoJIQk",
			      "title": {"simpleText": "2Pac - Makaveli - Blasphemy"},
			      "longBylineText": {"runs": [{"text": "Multimedia Madhouse"}]},
			      "lengthText": {"simpleText": "4:41"}
			    }},
			    {"playlistPanelVideoRenderer": {
			      "videoId": "F6DSPLNKezk",
			      "title": {"runs": [{"text": "Krazy"}]},
			      "shortBylineText": {"simpleText": "2Pac"},
			      "lengthText": {"runs": [{"text": "5:16"}]}
			    }},
			    {"videoRenderer": {
			      "videoId": "abcdefghijk",
			      "title": {"simpleText": "A recommendation, not a queue entry"},
			      "lengthText": {"simpleText": "5:16"}
			    }}
			  ]
			}""".trimIndent()

		val entries = MixQueueParser.entries(json)
		assertEquals(2, entries.size)
		assertEquals("WL-HrHoJIQk", entries[0].videoId)
		assertEquals("2Pac - Makaveli - Blasphemy", entries[0].title)
		assertEquals("Multimedia Madhouse", entries[0].channel)
		assertEquals(281L, entries[0].lengthSeconds)
		assertEquals("F6DSPLNKezk", entries[1].videoId)
		assertEquals("Krazy", entries[1].title)
		assertEquals("2Pac", entries[1].channel)
		assertEquals(316L, entries[1].lengthSeconds)
	}

	@Test
	fun `malformed and duplicate queue rows cannot create candidates`() {
		val json =
			"""{"items": [
			  {"playlistPanelVideoRenderer": {
			    "videoId": "WL-HrHoJIQk", "title": {"simpleText": "First"}
			  }},
			  {"playlistPanelVideoRenderer": {
			    "videoId": "WL-HrHoJIQk", "title": {"simpleText": "Duplicate"}
			  }},
			  {"playlistPanelVideoRenderer": {
			    "videoId": "too-short", "title": {"simpleText": "Bad id"}
			  }},
			  {"playlistPanelVideoRenderer": {"videoId": "abcdefghijk"}}
			]}"""

		val entries = MixQueueParser.entries(json)
		assertEquals(1, entries.size)
		assertEquals("First", entries.single().title)
	}
}
