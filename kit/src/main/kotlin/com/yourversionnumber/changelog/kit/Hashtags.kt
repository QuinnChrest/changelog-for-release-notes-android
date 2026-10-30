package com.yourversionnumber.changelog.kit

import java.text.Normalizer
import java.util.Locale

/**
 * Hashtags live in the note's text, as Release Notes reads them
 * (`packages/domain/tags.ts`, and Changelog for iPhone's `Hashtags.swift`):
 * a `#` not after a letter, digit or one of `_#&/=`, then letters and digits
 * joined by single hyphens, at most 50 characters, with at least one letter.
 * A hashtag with a-z letters is lowercased and folded to ASCII, as every tag
 * was before API 1.7.1; one without is a tag of its own letters in any
 * script, lowercased (#Москва is москва, #東京 stays 東京), its marks and
 * joiners kept. The server stays the authority; this is for the tag row's on
 * and off. Keep it in step with both.
 */
object Hashtags {
    const val MAX_TAG = 50

    /**
     * The legacy tag: ASCII letters and digits only. What won't fold to ASCII
     * (a ß, a CJK letter) is dropped, not made a separator, as the server's.
     */
    fun slug(name: String): String {
        val ascii = Normalizer.normalize(name, Normalizer.Form.NFKD).filter { it.code < 128 }.lowercase(Locale.ROOT)
        val tag = ascii.replace(Regex("[^a-z0-9]+"), "-").trim('-')
        return (if (tag.length > MAX_TAG) tag.substring(0, MAX_TAG) else tag).trimEnd('-')
    }

    private fun latin(tag: String): Boolean = tag.any { it in 'a'..'z' }

    /** A letter in any script. */
    fun valid(tag: String): Boolean = tag.codePoints().anyMatch { Character.isLetter(it) }

    private fun alnum(cp: Int) = Character.isLetterOrDigit(cp)

    private fun mark(cp: Int) = Character.getType(cp).let {
        it == Character.NON_SPACING_MARK.toInt() || it == Character.COMBINING_SPACING_MARK.toInt() || it == Character.ENCLOSING_MARK.toInt()
    }

    private fun joiner(cp: Int) = cp == 0x200C || cp == 0x200D

    private fun inWord(cps: IntArray, at: Int) = at in cps.indices && (alnum(cps[at]) || mark(cps[at]))

    /** Past one word from `at`, which is alphanumeric: letters, numbers and marks, a joiner only inside. */
    private fun wordEnd(cps: IntArray, from: Int): Int {
        var end = from + 1
        while (inWord(cps, end) || (end in cps.indices && joiner(cps[end]) && inWord(cps, end + 1))) end++
        return end
    }

    /** Past one hashtag's words from `at`, joined by single hyphens, as the legacy span joins them. */
    private fun wordsEnd(cps: IntArray, at: Int): Int {
        var end = wordEnd(cps, at)
        while (end + 1 in cps.indices && cps[end] == '-'.code && alnum(cps[end + 1])) end = wordEnd(cps, end + 1)
        return end
    }

    /** A name's tag in any script: its words lowercased (NFC), joined by hyphens, never cut inside a letter's marks. */
    private fun wide(name: String): String {
        val cps = Normalizer.normalize(name.lowercase(Locale.ROOT), Normalizer.Form.NFC).codePoints().toArray()
        val words = mutableListOf<String>()
        var at = 0
        while (at < cps.size) {
            if (!alnum(cps[at])) { at++; continue }
            val end = wordEnd(cps, at)
            words += String(cps, at, end - at)
            at = end
        }
        val out = words.joinToString("-").codePoints().toArray()
        var end = minOf(out.size, MAX_TAG)
        while (end > 0 && end < out.size && (mark(out[end]) || joiner(out[end - 1]))) end--
        return String(out, 0, end).replace(Regex("[-\u200c\u200d]+$"), "")
    }

    /** A name as a tag: the legacy tag when it has a-z letters, otherwise its letters in any script. */
    fun tagName(name: String): String = slug(name).let { if (latin(it)) it else wide(name) }

    /** A tag as the owner typed it ("#Maine 2016"), or null if it has no letter. */
    fun normalize(raw: String): String? {
        val tag = tagName(raw.removePrefix("#"))
        return if (valid(tag)) tag else null
    }

    /** The tags in a text, in order, each once. */
    fun found(text: String): List<String> = spans(text).map { it.tag }.distinct()

    fun contains(tag: String, text: String): Boolean = spans(text).any { it.tag == tag }

    /** Adds `#tag` at the end, on the same line, unless the text has it. */
    fun adding(tag: String, text: String): String {
        if (!valid(tag) || contains(tag, text)) return text
        val trimmed = text.trimEnd()
        return if (trimmed.isEmpty()) "#$tag " else "$trimmed #$tag"
    }

    /** Takes every `#tag` for this tag out, tidying the space it leaves. */
    fun removing(tag: String, text: String): String {
        val out = StringBuilder(text)
        for (span in spans(text).reversed()) {
            if (span.tag != tag) continue
            var start = span.start
            if (start > 0 && out[start - 1] == ' ') start -= 1
            out.delete(start, span.end)
        }
        return out.toString()
    }

    /** The tag row's tap: on if it's off, off if it's on. */
    fun toggling(tag: String, text: String): String =
        if (contains(tag, text)) removing(tag, text) else adding(tag, text)

    /** A hashtag's place in the text, in UTF-16 offsets, and the tag it makes. */
    internal data class Span(val start: Int, val end: Int, val tag: String)

    internal fun spans(text: String): List<Span> {
        val cps = text.codePoints().toArray()
        // Where each code point starts in the string, and one past the last.
        val at = IntArray(cps.size + 1)
        for (i in cps.indices) at[i + 1] = at[i] + Character.charCount(cps[i])
        val out = mutableListOf<Span>()
        var start = 0
        while (start < cps.size) {
            if (cps[start] != '#'.code) { start++; continue }
            if (start > 0 && (alnum(cps[start - 1]) || cps[start - 1].toChar() in "_#&/=")) { start++; continue }
            if (start + 1 >= cps.size || !alnum(cps[start + 1])) { start++; continue }
            var end = start + 1
            while (end < cps.size && alnum(cps[end])) end++
            while (end + 1 < cps.size && cps[end] == '-'.code && alnum(cps[end + 1])) {
                end++
                while (end < cps.size && alnum(cps[end])) end++
            }
            val tag = slug(String(cps, start + 1, end - start - 1))
            if (latin(tag)) {
                out += Span(at[start], at[end], tag)
                start = end
                continue
            }
            // No a-z letter: read on through marks and joiners, and if the whole
            // hashtag still has none, its own letters are the tag.
            val far = wordsEnd(cps, start + 1)
            val body = String(cps, start + 1, far - start - 1)
            val own = wide(body)
            if (!latin(slug(body)) && valid(own) && tagName(own) == own) {
                out += Span(at[start], at[far], own)
                start = far
            } else {
                start = end
            }
        }
        return out
    }
}

/** The row of one-tap tags: pinned first, then the most used recently, then Keep a Changelog's words. */
object TagRow {
    val quick = listOf("added", "changed", "fixed", "removed")

    fun tags(tags: Tags?, limit: Int = 12, quick: List<String> = TagRow.quick): List<String> {
        val pinned = tags?.pinned.orEmpty()
        val recent = tags?.recent.orEmpty().map { it.tag }
        return (pinned + recent + quick).distinct().take(maxOf(limit, pinned.size))
    }
}

/** What a pasted token looks like: `rn_` and 43 URL-safe characters. */
object TokenFormat {
    private val pattern = Regex("rn_[A-Za-z0-9_-]{43}(?![A-Za-z0-9_-])")

    fun looksValid(raw: String): Boolean {
        val token = raw.trim()
        return token.length == 46 && token.startsWith("rn_") &&
            token.drop(3).all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }
    }

    /** Pulls a token out of what was pasted, so a copied line works as well as the token alone. */
    fun extract(raw: String): String? = pattern.find(raw)?.value
}
