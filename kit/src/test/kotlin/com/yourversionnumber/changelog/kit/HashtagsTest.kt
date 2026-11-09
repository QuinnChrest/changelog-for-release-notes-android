package com.yourversionnumber.changelog.kit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HashtagsTest {
    init {
        English.use()
    }

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
    fun spellsWhatWasTyped() {
        assertEquals(Tag("maine-2016", "Maine 2016"), Hashtags.spelling("#Maine 2016").getOrThrow())
        assertEquals("2016", Hashtags.spelling("2016").getOrThrow().key)
        assertEquals(TagRefusal.Long, refusal(Hashtags.spelling("a".repeat(51))))
        assertEquals(50, Hashtags.spelling("a".repeat(50)).getOrThrow().name.length)
        assertEquals(Tag("москва", "Москва"), Hashtags.spelling("#Москва").getOrThrow())
        assertEquals("🎉", Hashtags.spelling("#🎉").getOrThrow().key)
        assertEquals(TagRefusal.Empty, refusal(Hashtags.spelling("#")))
        assertEquals(TagRefusal.Comma, refusal(Hashtags.spelling("Road, Trip")))
        // 50 is counted in characters, as the server counts them, not UTF-16 units.
        assertEquals(50, Hashtags.spelling("🚗".repeat(50)).getOrThrow().name.codePointCount(0, 100))
    }

    @Test
    fun addsToAField() {
        val one = Hashtags.adding("Road Trip", emptyList()).getOrThrow()
        assertEquals(listOf(Tag("road-trip", "Road Trip")), one.tags)
        assertEquals(Tag("road-trip", "Road Trip"), one.added)
        // The same tag by another spelling adds nothing.
        val again = Hashtags.adding("road trip", one.tags).getOrThrow()
        assertEquals(one.tags, again.tags)
        assertNull(again.added)
        val full = (1..20).map { Tag("t$it", "t$it") }
        assertEquals(TagRefusal.Many, refusal(Hashtags.adding("one more", full)))
        assertNull(Hashtags.adding("t3", full).getOrThrow().added)
    }

    /** A tag typed without Return goes with a Save or Commit, or stays in its box saying why (Codex on iPhone #57). */
    @Test
    fun aTagTypedWithoutReturnIsTakenOrKept() {
        val trip = Tag("road-trip", "Road Trip")
        val (left, added) = TagTyping("  #Lake ").take(listOf(trip))
        assertEquals(TagTyping.Taken.Added(Tag("lake", "Lake")), added)
        assertEquals(TagTyping(), left)
        // Already there by another spelling, or nothing typed: nothing to add, and the save goes ahead.
        assertEquals(TagTyping() to TagTyping.Taken.None, TagTyping("road trip").take(listOf(trip)))
        assertEquals(TagTyping(" ") to TagTyping.Taken.None, TagTyping(" ").take(listOf(trip)))
        // Refused: the box keeps the words, says why, and nothing is saved.
        val (kept, refused) = TagTyping("Lake, Dock").take(listOf(trip))
        assertEquals(TagTyping.Taken.Refused, refused)
        assertEquals("Lake, Dock", kept.text)
        assertEquals("A tag has no commas. Add each tag on its own.", kept.problem)
        val full = (1..20).map { Tag("t$it", "t$it") }
        assertEquals("That’s 20 tags, the most one can have. Take one off first.", TagTyping("one more").take(full).first.problem)
    }

    @Test
    fun aNoteHasItsFieldTagsThenItsWords() {
        val lake = Tag("lake", "Lake")
        val trip = Tag("road-trip", "Road Trip")
        assertEquals(listOf(trip, Tag("lake", "lake")), Hashtags.chips("At the #lake", listOf(trip)))
        assertEquals(listOf(lake), Hashtags.chips("At the #lake", listOf(lake)))
        assertEquals(listOf(trip), Hashtags.fieldOnly("At the #lake", listOf(trip, lake)))
        assertEquals("Road Trip, Lake", Hashtags.line(listOf(trip, lake)))
        assertEquals(listOf(trip, Tag("lake", "lake")), Hashtags.named(listOf("road-trip", "lake"), listOf("Road Trip")))
        assertTrue(Hashtags.named(null, null).isEmpty())
        // Twenty is a field's most; many lists together keep every tag.
        val many = (1..15).map { Tag("a$it", "a$it") } to (1..15).map { Tag("b$it", "b$it") }
        assertEquals(20, Hashtags.merge(many.first, many.second).size)
        assertEquals(30, Hashtags.mergeAll(listOf(many.first, many.second)).size)
    }

    @Test
    fun findsAKeyInWords() {
        assertTrue(Hashtags.contains("москва", "Snow in #Москва"))
        assertTrue(Hashtags.contains("house", "Fixed the #House faucet"))
        assertFalse(Hashtags.contains("house", "Fixed the house faucet"))
    }

    /** As `removingTag` in Release Notes' `packages/domain/tags.ts`: the space before a tag goes with it, or for a tag leading the text, the one after. */
    @Test
    fun takesATagOffWithTheSpaceBesideIt() {
        val cases = listOf(
            "#fixed the bug" to "the bug",
            "#fixed" to "",
            "#fixed " to "",
            "Fixed the bug #fixed" to "Fixed the bug",
            "the #fixed bug" to "the bug",
            "#fixed the bug #fixed" to "the bug",
            "#fixed #fixed" to "",
            "one\n#fixed two" to "one\ntwo",
            "#fixed  two" to " two",
        )
        for ((text, left) in cases) assertEquals(left, Hashtags.removing("fixed", text), text)
        // Any spelling of the tag goes: its key is what matches.
        assertEquals("Lunch #cabin", Hashtags.removing("東京", "Lunch #東京 #cabin"))
        assertEquals("Snow", Hashtags.removing("москва", "Snow #Москва"))
    }

    @Test
    fun aSearchForOneTagIsATagSearch() {
        assertNull(NoteSearch.of("  "))
        assertEquals(NoteSearch.Tagged("lake"), NoteSearch.of("#Lake"))
        assertEquals(NoteSearch.Tagged("東京"), NoteSearch.of(" #東京 "))
        assertEquals(NoteSearch.Tagged("cafe-東京"), NoteSearch.of("#café-東京"))
        // Anything more than the one tag searches the words, as the server reads them.
        assertEquals(NoteSearch.Worded("#lake #cabin"), NoteSearch.of("#lake #cabin"))
        assertEquals(NoteSearch.Worded("#lake, dock"), NoteSearch.of("#lake, dock"))
        assertEquals(NoteSearch.Worded("#lake,"), NoteSearch.of("#lake,"))
        assertEquals(NoteSearch.Worded("#2026"), NoteSearch.of("#2026"))
        assertEquals(NoteSearch.Worded("cabin \"first snow\""), NoteSearch.of("cabin \"first snow\""))
    }

    @Test
    fun theRowIsPinnedThenRecentThenQuick() {
        val tags = Tags(
            pinned = listOf("house", "road-trip"),
            pinnedNames = listOf("House", "Road Trip"),
            recent = listOf(RecentTag("running", 6, name = "Running"), RecentTag("road-trip", 3, name = "road trip")),
            windowDays = 30,
        )
        assertEquals(listOf("House", "Road Trip", "Running", "added", "changed"), TagRow.tags(tags, limit = 5).map { it.name })
        assertEquals(TagRow.quick, TagRow.tags(null).map { it.key })
        // An older server sends keys alone: each is its own name.
        val older = Tags(pinned = listOf("house"), recent = listOf(RecentTag("work", 1)), windowDays = 30)
        assertEquals(listOf("house", "work"), TagRow.tags(older, limit = 2).map { it.name })
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

private fun refusal(r: Result<*>): TagRefusal? = (r.exceptionOrNull() as? TagRefused)?.why
