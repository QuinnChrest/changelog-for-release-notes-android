package com.yourversionnumber.changelog.kit

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Release Notes' `contracts/tag-vectors.json`, copied into `src/test/resources`
 * as it is: how the server reads a name typed into a tags field, what a field
 * refuses, and the tags a note's #words make. Changelog for iPhone checks
 * itself against the same file. Copy it again whenever the server's changes;
 * never edit the copy.
 */
class TagVectorTest {
    @Serializable
    data class Vectors(
        val names: List<Name>,
        val refused: List<Refused>,
        val text: List<Text>,
        val keysAreStable: Boolean,
    )

    @Serializable
    data class Name(val typed: String, val name: String, val key: String)

    @Serializable
    data class Refused(val typed: String, val reason: String)

    @Serializable
    data class Text(val text: String, val tags: List<Tag>)

    private val vectors: Vectors = run {
        val raw = checkNotNull(javaClass.getResource("/tag-vectors.json")) { "tag-vectors.json is missing" }.readText()
        Json(Api.json) { ignoreUnknownKeys = true }.decodeFromString(Vectors.serializer(), raw)
    }

    @Test
    fun theVectorsAreThere() {
        assertTrue(vectors.names.size > 30)
        assertTrue(vectors.refused.size > 5)
        assertTrue(vectors.text.isNotEmpty())
        assertTrue(vectors.keysAreStable)
    }

    @Test
    fun spellsEachNameAsTheServerDoes() {
        for (v in vectors.names) {
            assertEquals(Tag(v.key, v.name), Hashtags.spelling(v.typed).getOrNull(), v.typed)
            // A key is its own key, so a key from the server matches itself.
            assertEquals(v.key, Hashtags.key(v.key), v.key)
            assertTrue(Hashtags.isKey(v.key), v.key)
        }
    }

    @Test
    fun refusesWhatTheServerRefuses() {
        for (v in vectors.refused) {
            val why = (Hashtags.spelling(v.typed).exceptionOrNull() as? TagRefused)?.why
            assertEquals(v.reason, why?.name?.lowercase(), v.typed)
        }
    }

    @Test
    fun readsHashtagsAsTheServerDoes() {
        for (v in vectors.text) assertEquals(v.tags, Hashtags.textTags(v.text), v.text)
    }
}
