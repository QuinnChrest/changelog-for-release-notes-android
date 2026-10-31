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
    fun aTokenReadsAllOnlyWithNotesAll() {
        assertTrue(TokenInfo("t", listOf("notes:write", "notes:all")).readsAll)
        assertFalse(TokenInfo("t", listOf("notes:write", "activity")).readsAll)
    }
}
