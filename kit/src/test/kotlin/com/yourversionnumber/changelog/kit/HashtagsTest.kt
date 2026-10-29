package com.yourversionnumber.changelog.kit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HashtagsTest {
    @Test
    fun findsTagsAsTheServerDoes() {
        val cases = listOf(
            "Ran the #river-loop today" to listOf("river-loop"),
            "#Maine2016 and #maine2016" to listOf("maine2016"),
            "Café #Crème-Brûlée" to listOf("creme-brulee"),
            "Café #Crème-Brûlée" to listOf("creme-brulee"),
            "a#b c&#d /#e =#f" to emptyList(),
            "#2026 is not a tag, #y2026 is" to listOf("y2026"),
            "##double and #trailing-" to listOf("trailing"),
        )
        for ((text, tags) in cases) assertEquals(tags, Hashtags.found(text), text)
    }

    @Test
    fun normalizesWhatWasTyped() {
        assertEquals("maine-2016", Hashtags.normalize("#Maine 2016"))
        assertNull(Hashtags.normalize("2016"))
        assertEquals(50, Hashtags.normalize("a".repeat(60))?.length)
    }

    @Test
    fun togglesATag() {
        assertEquals("Fixed the faucet #house", Hashtags.toggling("house", "Fixed the faucet"))
        assertEquals("Fixed the faucet", Hashtags.toggling("house", "Fixed the faucet #house"))
        assertEquals("#house ", Hashtags.toggling("house", ""))
        assertEquals("Fixed the #House faucet", Hashtags.adding("house", "Fixed the #House faucet"))
    }

    @Test
    fun theRowIsPinnedThenRecentThenQuick() {
        val tags = Tags(
            pinned = listOf("house", "work"),
            recent = listOf(RecentTag("running", 6), RecentTag("work", 3)),
            windowDays = 30,
        )
        assertEquals(listOf("house", "work", "running", "added", "changed"), TagRow.tags(tags, limit = 5))
        assertEquals(TagRow.quick, TagRow.tags(null))
    }

    @Test
    fun recognizesATokenInWhatWasPasted() {
        val token = "rn_" + "x".repeat(42) + "Q"
        assertTrue(TokenFormat.looksValid(token))
        assertEquals(token, TokenFormat.extract("Your token: $token\n"))
        assertNull(TokenFormat.extract("rn_short"))
        assertFalse(TokenFormat.looksValid("rn_" + "x".repeat(44)))
    }
}
