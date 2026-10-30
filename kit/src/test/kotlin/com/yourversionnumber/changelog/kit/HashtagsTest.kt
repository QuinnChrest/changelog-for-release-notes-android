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
            "a#b c&#d /#e =#f" to emptyList(),
            "#2026 is not a tag, #y2026 is" to listOf("y2026"),
            "##double and #trailing-" to listOf("trailing"),
        )
        for ((text, tags) in cases) assertEquals(tags, Hashtags.found(text), text)
    }

    // The server's own cases (tests-node/domain/tags.test.ts, API 1.7.1).
    @Test
    fun lettersInAnyScriptMakeATag() {
        assertEquals(listOf("שלום", "москва", "αθήνα"), Hashtags.found("#שלום עולם, #Москва и #Αθήνα"))
        assertEquals(listOf("東京", "東京2026", "القاهرة", "한국"), Hashtags.found("#東京 #東京2026 #القاهرة #한국"))
        // Combining marks stay in the word: niqqud, a virama, Thai vowels.
        assertEquals(listOf("שָׁלוֹם", "नमस्ते", "हिन्दी", "สวัสดี"), Hashtags.found("#שָׁלוֹם #नमस्ते #हिन्दी #สวัสดี"))
        assertEquals(listOf(Hashtags.Span(0, 7, "नमस्ते")), Hashtags.spans("#नमस्ते!"))
        // A joiner inside a word is its spelling, never at its end.
        assertEquals(listOf("می‌خواهم", "a"), Hashtags.found("#می‌خواهم #a‍"))
        assertEquals(listOf("москва-2026", "東京-旅行"), Hashtags.found("#Москва-2026 #東京-旅行"))
        assertEquals(listOf("한"), Hashtags.found("#한"))
    }

    @Test
    fun aTagWithAToZLettersKeepsTheLegacyTag() {
        assertEquals(listOf("cafe", "manana", "ok", "strae", "istanbul"), Hashtags.found("#café #Mañana #ok #straße #İstanbul"))
        assertEquals(listOf(0 to 8, 9 to 17), Hashtags.spans("#Tokyo東京 #東京tokyo").map { it.start to it.end })
        // Read in full it has a-z letters after the mark, so it stays no tag.
        assertEquals(emptyList(), Hashtags.found("#Σ́Dö32"))
        // Numbers alone are still no tag, in any script.
        assertEquals(emptyList(), Hashtags.found("PR #31, #2016, #١٢٣ and #２０２６"))
    }

    @Test
    fun aTagInAnyScriptKeeps50CodePointsNeverCutInsideALetter() {
        val long = Hashtags.found("#" + "東".repeat(60)).single()
        assertEquals(50, long.codePointCount(0, long.length))
        val source = "नमस्ते".repeat(10)
        val hindi = Hashtags.found("#$source").single()
        assertTrue(hindi.codePointCount(0, hindi.length) <= 50 && source.startsWith(hindi))
        // What it leaves off starts with a letter, not the marks of its last one.
        assertTrue(Character.isLetter(source.substring(hindi.length).codePointAt(0)))
    }

    @Test
    fun namesBecomeTagsAsHashtagsDo() {
        assertEquals("москва", Hashtags.tagName("Москва"))
        assertEquals("東京-旅行", Hashtags.tagName("東京 旅行"))
        assertEquals("maine-2016", Hashtags.tagName("Maine 2016"))
        assertEquals("東京", Hashtags.normalize("#東京"))
        assertNull(Hashtags.normalize("2016"))
        // The tag row's tap works for them too.
        assertEquals("Lunch #東京", Hashtags.adding("東京", "Lunch"))
        assertEquals("Lunch #cabin", Hashtags.removing("東京", "Lunch #東京 #cabin"))
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
