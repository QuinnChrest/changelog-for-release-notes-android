package com.yourversionnumber.changelog.kit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `GET /v1/notes` as API 1.9.0 and 1.10.0 answer it, read without a server. */
class NotesPageTest {
    private val answer = """
        {"from":"2026-10-30","to":"2026-10-30","next":"abc","items":[
          {"id":"n1","date":"2026-10-30","version":"4.5.137","text":"Ran #strava","revision":"r1",
           "written_at":"2026-10-30T14:00:00Z","files":2,"token":"Changelog","source":"api","mine":true,
           "media":[{"n":1,"kind":"image","type":"image/jpeg","name":"a.jpg","size":2048,"thumb":{"type":"image/jpeg","width":160,"height":120}},
                    {"n":2,"kind":"audio","type":"audio/mp4","name":null,"size":900,"thumb":null}]},
          {"id":"n2","date":"2026-10-30","version":"4.5.137","text":"From the web","revision":"r2","source":"web","mine":false}
        ]}
    """.trimIndent()

    @Test
    fun readsNotesWithFilesAndWhereTheyCameFrom() {
        val page = Api.json.decodeFromString(NoteList.serializer(), answer)
        assertEquals("abc", page.next)
        val (mine, other) = page.items
        assertTrue(mine.mine)
        assertEquals(listOf("image", "audio"), mine.media.map { it.kind })
        assertEquals(160, mine.media[0].thumb?.width)
        assertNull(mine.media[1].thumb)
        assertFalse(other.mine)
        assertEquals("web", other.source)
        assertTrue(other.media.isEmpty())
    }

    @Test
    fun aNoteReadBeforeNotesAllIsTheTokensOwn() {
        val note = Api.json.decodeFromString(NoteDetail.serializer(), """{"id":"n","date":"2026-10-30","text":"x","revision":"r"}""")
        assertTrue(note.mine)
        assertTrue(note.media.isEmpty())
    }

    @Test
    fun aRecordingSaysItsWordsOrThatTheyAreComing() {
        // API 1.14.0: a file's transcript, description and writing, beside the note's other new fields.
        val note = Api.json.decodeFromString(
            NoteDetail.serializer(),
            """{"id":"n","date":"2026-11-05","text":"","revision":"r","tags":[],"markup":null,"parts":[],"place":null,
               "no_place":false,"edited_at":null,"tz":"America/Chicago","late":false,"author":"user","client":"Changelog",
               "from":null,"attachments":0,"media":[
               {"n":1,"kind":"audio","type":"audio/mp4","name":null,"size":900,"thumb":null,"transcript":"Back from the lake.","description":null,"writing":false},
               {"n":2,"kind":"audio","type":"audio/mp4","name":null,"size":900,"thumb":null,"transcript":null,"description":null,"writing":true},
               {"n":3,"kind":"image","type":"image/jpeg","name":null,"size":900,"thumb":{"type":"image/jpeg","width":1,"height":1,"url":null},"description":"A red canoe."}]}""",
        )
        assertEquals("Back from the lake.", note.media[0].transcript)
        assertTrue(note.media[1].writing)
        assertNull(note.media[1].transcript)
        assertEquals("A red canoe.", note.media[2].description)
        assertFalse(note.media[2].writing)
        assertTrue(note.awaitingWords)
        assertFalse(note.copy(media = note.media.take(1)).awaitingWords)
    }

    @Test
    fun aTokenReadsAllOnlyWithNotesAll() {
        assertTrue(TokenInfo("t", listOf("notes:write", "notes:all")).readsAll)
        assertFalse(TokenInfo("t", listOf("notes:write", "activity")).readsAll)
    }
}
