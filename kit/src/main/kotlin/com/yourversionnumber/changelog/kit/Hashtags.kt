package com.yourversionnumber.changelog.kit

import java.text.Normalizer
import java.util.Locale

/**
 * Hashtags live in the note's text, as Release Notes reads them
 * (`packages/domain/tags.ts`, and Changelog for iPhone's `Hashtags.swift`):
 * a `#` not after a letter, digit or one of `_#&/=`, then letters and digits
 * joined by single hyphens, lowercased and folded to ASCII, at most 50
 * characters, with at least one letter. The server stays the authority; this
 * is for the tag row's on and off. Keep it in step with both.
 */
object Hashtags {
    const val MAX_TAG = 50

    fun slug(name: String): String {
        val folded = Normalizer.normalize(name, Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
        val out = StringBuilder()
        var gap = false
        for (c in folded) {
            if (c in 'a'..'z' || c in '0'..'9') {
                if (gap && out.isNotEmpty()) out.append('-')
                out.append(c)
                gap = false
            } else {
                gap = true
            }
        }
        var tag = if (out.length > MAX_TAG) out.substring(0, MAX_TAG) else out.toString()
        tag = tag.trimEnd('-')
        return tag
    }

    fun valid(tag: String): Boolean = tag.any { it in 'a'..'z' }

    /** A tag as the owner typed it ("#Maine 2016"), or null if it has no letter. */
    fun normalize(raw: String): String? {
        val tag = slug(raw.removePrefix("#"))
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

    internal data class Span(val start: Int, val end: Int, val tag: String)

    // A combining mark belongs to the letter before it, as in Swift's Characters.
    private fun word(c: Char): Boolean = c.isLetterOrDigit() ||
        Character.getType(c).let { it == Character.NON_SPACING_MARK.toInt() || it == Character.COMBINING_SPACING_MARK.toInt() }

    internal fun spans(text: String): List<Span> {
        val out = mutableListOf<Span>()
        var start = 0
        while (start < text.length) {
            if (text[start] != '#') { start++; continue }
            if (start > 0 && (word(text[start - 1]) || text[start - 1] in "_#&/=")) { start++; continue }
            var end = start + 1
            if (end >= text.length || !word(text[end])) { start++; continue }
            while (end < text.length && word(text[end])) end++
            while (end + 1 < text.length && text[end] == '-' && word(text[end + 1])) {
                end++
                while (end < text.length && word(text[end])) end++
            }
            val tag = slug(text.substring(start + 1, end))
            if (valid(tag)) out += Span(start, end, tag)
            start = end
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
