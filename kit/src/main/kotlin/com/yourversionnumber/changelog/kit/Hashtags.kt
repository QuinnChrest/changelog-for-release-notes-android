package com.yourversionnumber.changelog.kit

import java.text.Normalizer
import java.util.Locale
import kotlinx.serialization.Serializable

/**
 * A tag (Release Notes' `docs/TAGS.md`, API 1.21.0; Jamie, 2026-11-08:
 * "Approved: build first-class tags in Release Notes as proposed, with calls
 * 1 to 8 as recommended"). Its name is as it was typed and is shown ("Road
 * Trip", "🚗 Lake", "東京"); its key is what matches, so "Road Trip", "road
 * trip" and "#road-trip" are one tag, `road-trip`.
 */
@Serializable
data class Tag(val key: String, val name: String)

/** Why a tags field won't take a name (call 4), as the server names it. */
enum class TagRefusal {
    Many,
    Break,
    Comma,
    Long,
    Empty;

    /** What the field says, as the web's says it (`web/src/lib/tags.ts`). */
    val said: String
        get() = when (this) {
            Many -> Words.say(Phrase.TagMany, Hashtags.MAX_TAGS)
            Break -> Words.say(Phrase.TagBreak)
            Comma -> Words.say(Phrase.TagComma)
            Long -> Words.say(Phrase.TagLong, Hashtags.MAX_TAG)
            Empty -> Words.say(Phrase.TagEmpty)
        }
}

/** A refusal as a [Result]'s failure, so `getOrElse` can say why. */
class TagRefused(val why: TagRefusal) : Exception() {
    override val message: String get() = why.said
}

/** A name added to a list: the list as it is now, and the tag added, null when it was there already. */
data class TagAdded(val tags: List<Tag>, val added: Tag?)

/**
 * What is typed in a tags field and isn't a chip yet, with why the box kept it. The form around the field holds
 * it, so its Save or Commit takes a tag typed without Return rather than losing it, as leaving the box does
 * (Changelog for iPhone, from Codex's review of its #57).
 */
data class TagTyping(val text: String = "", val problem: String? = null) {
    /** What taking it did. */
    sealed interface Taken {
        /** An empty box, or a tag already there by any spelling: carry on. */
        data object None : Taken

        /** One more tag, for the form to put on before it saves. */
        data class Added(val tag: Tag) : Taken

        /** Refused (call 4): the box keeps it and says why, and nothing is saved. */
        data object Refused : Taken
    }

    val isEmpty: Boolean get() = text.isBlank()

    /** What is typed, as one more tag for `tags`, and the box as it is after: emptied, or keeping it with the reason. */
    fun take(tags: List<Tag>): Pair<TagTyping, Taken> {
        if (isEmpty) return this to Taken.None
        return Hashtags.adding(text, tags).fold(
            onSuccess = { done -> TagTyping() to (done.added?.let { Taken.Added(it) } ?: Taken.None) },
            onFailure = { e -> copy(problem = e.message) to Taken.Refused },
        )
    }
}

/**
 * How Release Notes reads tags: a port of `packages/domain/tags.ts`, by way of
 * Changelog for iPhone's `Hashtags.swift`; keep the three in step.
 * `contracts/tag-vectors.json` there, copied beside the kit's tests, holds
 * the cases they must agree on. Everything here counts code points, as the
 * server's `Array.from` does, never UTF-16 units.
 *
 * - A name's key: Latin letters alone keep the legacy slug (#Café is `cafe`),
 *   other scripts alone their letters, lowercased (#東京 is `東京`, #Москва
 *   is `москва`), and a name that mixes them or has emoji keeps every part
 *   (`café-東京` is `cafe-東京`, "🚗 Road Trip" is `🚗-road-trip`).
 * - A hashtag in a note's words is a `#` not after a letter, digit or one of
 *   `_#&/=`, then letters and digits joined by single hyphens, with a letter:
 *   the words still make tags (call 1), and `#Road Trip` there is the tag `road`.
 * - A tags field takes a name with spaces and emoji, at most 50 characters,
 *   20 to an item, and refuses commas and line breaks (call 4).
 */
object Hashtags {
    const val MAX_TAG = 50
    const val MAX_TAGS = 20

    // Characters, as the server's regular expressions class them.

    /** `\p{L}`. */
    internal fun letter(cp: Int) = Character.isLetter(cp)

    /** `[\p{L}\p{N}]`: Python's `isalnum`, which the server's tables are made from. */
    internal fun alnum(cp: Int) = letter(cp) || Character.getType(cp).let {
        it == Character.DECIMAL_DIGIT_NUMBER.toInt() || it == Character.LETTER_NUMBER.toInt() || it == Character.OTHER_NUMBER.toInt()
    }

    internal fun mark(cp: Int?) = cp != null && Character.getType(cp).let {
        it == Character.NON_SPACING_MARK.toInt() || it == Character.COMBINING_SPACING_MARK.toInt() || it == Character.ENCLOSING_MARK.toInt()
    }

    internal fun joiner(cp: Int?) = cp == 0x200C || cp == 0x200D

    private fun inWord(cp: Int?) = cp != null && (alnum(cp) || mark(cp))

    internal fun latinScript(cp: Int): Boolean =
        if (cp < 0x80) cp in 'a'.code..'z'.code || cp in 'A'.code..'Z'.code
        else Character.UnicodeScript.of(cp) == Character.UnicodeScript.LATIN

    internal fun emoji(cp: Int) = Emoji.emoji(cp)

    private fun nfc(text: String) = Normalizer.normalize(text, Normalizer.Form.NFC)

    private fun cps(text: String) = text.codePoints().toArray()

    private fun string(cps: List<Int>) = String(cps.toIntArray(), 0, cps.size)

    private fun IntArray.at(i: Int): Int? = if (i in indices) this[i] else null

    /** NFKD, then only what is ASCII, as stored slugs are made. */
    private fun asciiDecompose(cps: IntArray): String = buildString {
        for (cp in cps) {
            if (cp < 0x80) {
                appendCodePoint(cp)
            } else {
                Normalizer.normalize(String(Character.toChars(cp)), Normalizer.Form.NFKD).forEach { if (it.code < 0x80) append(it) }
            }
        }
    }

    // Keys

    /** The legacy slug: ASCII letters and digits, lowercased, joined by single hyphens, at most 50. */
    fun slug(name: String): String {
        val ascii = asciiDecompose(cps(name)).lowercase(Locale.ROOT)
        val out = StringBuilder()
        var gap = false
        for (c in ascii) {
            if (c in 'a'..'z' || c in '0'..'9') {
                if (gap && out.isNotEmpty()) out.append('-')
                out.append(c)
                gap = false
            } else {
                gap = true
            }
        }
        if (out.length > MAX_TAG) out.setLength(MAX_TAG)
        while (out.endsWith("-")) out.setLength(out.length - 1)
        return out.toString()
    }

    /** Has a letter, in any script. */
    fun valid(tag: String): Boolean = tag.codePoints().anyMatch(::letter)

    /** Has an a-z letter: a tag the old slug makes. */
    private fun latin(tag: String): Boolean = tag.any { it in 'a'..'z' }

    /** Past one word from `at` (its first alphanumeric): letters, numbers and marks in any script, a joiner only inside, words joined by single hyphens. */
    private fun wordsFrom(chars: IntArray, at: Int): Int {
        var end = at
        fun word() {
            end++
            while (inWord(chars.at(end)) || (joiner(chars.at(end)) && inWord(chars.at(end + 1)))) end++
        }
        word()
        while (chars.at(end) == '-'.code && chars.at(end + 1)?.let(::alnum) == true) {
            end++
            word()
        }
        return end
    }

    /** A name's tag in any script: its words, lowercased (NFC), joined by hyphens, at most 50 code points, never cut inside a letter's marks. */
    private fun wide(name: String): String {
        val chars = cps(nfc(name.lowercase(Locale.ROOT)))
        val words = mutableListOf<List<Int>>()
        var at = 0
        while (at < chars.size) {
            if (!alnum(chars[at])) {
                at++
                continue
            }
            var end = at + 1
            while (inWord(chars.at(end)) || (joiner(chars.at(end)) && inWord(chars.at(end + 1)))) end++
            words += chars.slice(at until end)
            at = end
        }
        return cut(joined(words), beforeJoiner = false)
    }

    private fun joined(words: List<List<Int>>): List<Int> = buildList {
        words.forEachIndexed { i, word ->
            if (i > 0) add('-'.code)
            addAll(word)
        }
    }

    /**
     * At most 50 code points, never inside a letter's marks or just after a joiner (with `beforeJoiner`, never
     * just before one either), and no hyphen or joiner at the end.
     */
    private fun cut(out: List<Int>, beforeJoiner: Boolean): String {
        var end = minOf(out.size, MAX_TAG)
        while (end > 0 && end < out.size && (mark(out[end]) || joiner(out[end - 1]) || (beforeJoiner && joiner(out[end])))) end--
        while (end > 0 && (out[end - 1] == '-'.code || joiner(out[end - 1]))) end--
        return string(out.subList(0, end))
    }

    /**
     * A name with emoji, or letters from Latin and another script: each part kept, Latin letters without their
     * accents as the slug has them, the rest lowercased with their marks, emoji as they are, and anything else
     * between words one hyphen.
     */
    private fun mixed(name: String): String {
        val chars = cps(nfc(name).lowercase(Locale.ROOT)).filter { it != 0xFE0E && it != 0xFE0F }
        fun kept(cp: Int?) = cp != null && (alnum(cp) || emoji(cp) || mark(cp))
        val words = mutableListOf<List<Int>>()
        var current = mutableListOf<Int>()
        var folded = false
        chars.forEachIndexed { at, c ->
            if (alnum(c)) {
                val ascii = asciiDecompose(intArrayOf(c))
                val lettersOnly = ascii.isNotEmpty() && ascii.all { it in 'a'..'z' }
                val digit = ascii.length == 1 && ascii[0] in '0'..'9'
                if ((latinScript(c) && lettersOnly) || digit) {
                    ascii.forEach { current += it.code }
                    folded = true
                } else {
                    current += c
                    folded = false
                }
            } else if (mark(c)) {
                // A mark stays with a letter kept as it is; an accent goes with its folded letter, as the slug drops it.
                if (current.isNotEmpty() && !folded) current += c
            } else if (emoji(c)) {
                current += c
                folded = false
            } else if (joiner(c) && current.isNotEmpty() && kept(chars.getOrNull(at + 1))) {
                current += c
            } else if (current.isNotEmpty()) {
                words += current
                current = mutableListOf()
            }
        }
        if (current.isNotEmpty()) words += current
        return cut(joined(words), beforeJoiner = true)
    }

    /**
     * A name's key: what matches, so "Road Trip" and "road-trip" are one tag. Latin letters alone keep the legacy
     * slug, and other scripts alone their letters, so no key from before moves; a mix, or emoji, keeps each part.
     */
    fun key(name: String): String {
        val text = nfc(name)
        val letters = cps(text).filter(::letter)
        val latinOnly = letters.all(::latinScript)
        val noLatin = letters.none(::latinScript)
        if (text.codePoints().noneMatch(::emoji)) {
            if (latinOnly) {
                val old = slug(text)
                if (latin(old)) return old
            } else if (noLatin) {
                return wide(text)
            }
        }
        return mixed(text)
    }

    /** A key as tags are matched by: already a key, at most 50 code points. */
    fun isKey(tag: String): Boolean = tag.isNotEmpty() && tag.codePointCount(0, tag.length) <= MAX_TAG && key(tag) == tag

    // A tags field

    private val lineBreaks = setOf(0x0A, 0x0D, 0x2028, 0x2029, 0x85)
    private val commas = setOf(','.code, 0xFF0C)
    private val spaces = setOf(
        0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x20, 0xA0, 0x1680, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000, 0xFEFF, 0x180E, 0x200B, 0x2060,
    )

    /** `[\s\p{Zs}᠎​⁠﻿]`, as the server runs them together. */
    private fun space(cp: Int) = cp in spaces || cp in 0x2000..0x200A || Character.getType(cp) == Character.SPACE_SEPARATOR.toInt()

    /**
     * A name as a tags field takes it: leading #s and the spaces around it gone, runs of spaces made one, composed
     * (NFC). Commas, line breaks and other control characters are refused ([TagRefused]), never dropped, as is a
     * name over 50 characters, or one with no letter, number or emoji.
     */
    fun spelling(raw: String): Result<Tag> {
        val chars = cps(nfc(raw))
        fun refused(why: TagRefusal) = Result.failure<Tag>(TagRefused(why))
        if (chars.any { it in lineBreaks }) return refused(TagRefusal.Break)
        if (chars.any { it in commas }) return refused(TagRefusal.Comma)
        if (chars.any { it != 0x09 && Character.getType(it) == Character.CONTROL.toInt() }) return refused(TagRefusal.Break)
        val spaced = mutableListOf<Int>()
        for (cp in chars) {
            if (space(cp)) {
                if (spaced.lastOrNull() != ' '.code) spaced += ' '.code
            } else {
                spaced += cp
            }
        }
        val name = string(spaced).trim(' ').trimStart('#').trim(' ')
        if (name.codePointCount(0, name.length) > MAX_TAG) return refused(TagRefusal.Long)
        val key = key(name)
        return if (key.isEmpty()) refused(TagRefusal.Empty) else Result.success(Tag(key, name))
    }

    /**
     * A name typed into a tags field, added to `tags`: the list with it, and the tag added, or why not
     * ([TagRefused]). A tag already there by any spelling leaves the list as it was, with nothing added.
     */
    fun adding(typed: String, tags: List<Tag>): Result<TagAdded> {
        val tag = spelling(typed).getOrElse { return Result.failure(it) }
        if (tags.any { it.key == tag.key }) return Result.success(TagAdded(tags, null))
        if (tags.size >= MAX_TAGS) return Result.failure(TagRefused(TagRefusal.Many))
        return Result.success(TagAdded(tags + tag, tag))
    }

    /** Lists of tags as one: each key once, its first name, at most 20. */
    fun merge(vararg lists: List<Tag>): List<Tag> {
        val out = mutableListOf<Tag>()
        for (list in lists) for (tag in list) if (out.size < MAX_TAGS && out.none { it.key == tag.key }) out += tag
        return out
    }

    /** Many lists as one, each key once with its first name, however many. */
    fun mergeAll(lists: List<List<Tag>>): List<Tag> {
        val seen = mutableSetOf<String>()
        return lists.flatten().filter { seen.add(it.key) }
    }

    /** The tags an answer gives, its keys beside their names. */
    fun named(keys: List<String>?, names: List<String>?): List<Tag> =
        merge(keys.orEmpty().mapIndexed { i, key -> Tag(key, names?.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: key) })

    /** A note's chips: the tags from its field, then its #words, as the server keeps them. */
    fun chips(text: String, field: List<Tag>): List<Tag> = merge(field, textTags(text))

    /** The tags a note has that its words don't: the ones a field gave it. */
    fun fieldOnly(text: String, tags: List<Tag>): List<Tag> {
        val typed = textTags(text).map { it.key }.toSet()
        return tags.filter { it.key !in typed }
    }

    /** Names as one line, "Road Trip, lake". */
    fun line(tags: List<Tag>): String = tags.joinToString(", ") { it.name }

    // Hashtags in a note's words

    /** A hashtag's place in the text, in UTF-16 offsets from its # to past its last letter, and its key. */
    data class Span(val start: Int, val end: Int, val tag: String)

    /** The hashtags in a text. */
    fun spans(text: String): List<Span> {
        val chars = cps(text)
        // Where each code point starts in the string, and one past the last.
        val at = IntArray(chars.size + 1)
        for (i in chars.indices) at[i + 1] = at[i] + Character.charCount(chars[i])
        val out = mutableListOf<Span>()
        var start = 0
        while (start < chars.size) {
            val here = start
            start++
            if (chars[here] != '#'.code) continue
            val before = chars.at(here - 1)
            if (before != null && (alnum(before) || before in "_#&/=".map { it.code })) continue
            var end = here + 1
            if (chars.at(end)?.let(::alnum) != true) continue
            while (end < chars.size && alnum(chars[end])) end++
            while (chars.at(end) == '-'.code && chars.at(end + 1)?.let(::alnum) == true) {
                end++
                while (end < chars.size && alnum(chars[end])) end++
            }
            val typed = String(chars, here + 1, end - here - 1)
            if (latin(slug(typed))) {
                // Its key: the legacy slug, unless it mixes in another script.
                out += Span(at[here], at[end], key(typed))
                start = end
                continue
            }
            // No a-z letter: read on through marks and joiners, and if the whole hashtag still has none, its own
            // letters are the tag.
            val far = wordsFrom(chars, here + 1)
            val body = String(chars, here + 1, far - here - 1)
            val own = wide(body)
            if (!latin(slug(body)) && valid(own) && key(own) == own) {
                out += Span(at[here], at[far], own)
                start = far
            } else {
                start = end
            }
        }
        return out
    }

    /** The hashtags in a text as tags, each named as typed, without its #. */
    fun textTags(text: String): List<Tag> {
        val out = mutableListOf<Tag>()
        for (span in spans(text)) {
            if (out.size >= MAX_TAGS) break
            if (out.none { it.key == span.tag }) out += Tag(span.tag, text.substring(span.start + 1, span.end))
        }
        return out
    }

    /** The tags in a text, in order, each once. */
    fun found(text: String): List<String> = textTags(text).map { it.key }

    fun contains(key: String, text: String): Boolean = spans(text).any { it.tag == key }

    /**
     * Takes every `#tag` for this key out with the space beside it: the one before, or for a tag with none before
     * (one leading the text), the one after, as the server's `removingTag` does. Taking a #word's chip off does
     * this (call 1).
     */
    fun removing(key: String, text: String): String {
        val out = StringBuilder(text)
        for (span in spans(text).reversed()) {
            if (span.tag != key) continue
            val before = span.start > 0 && out[span.start - 1] == ' '
            val after = !before && span.end < out.length && out[span.end] == ' '
            out.delete(if (before) span.start - 1 else span.start, if (after) span.end + 1 else span.end)
        }
        return out.toString()
    }
}

/**
 * What Search was asked: a hashtag alone is that tag's notes, by its key, and anything else words, as the iPhone
 * app's `NoteSearch`. Read with the server's own hashtag rules, so `#Road Trip` is words and `#東京` a tag; null
 * for nothing typed.
 */
sealed interface NoteSearch {
    data class Tagged(val key: String) : NoteSearch

    data class Worded(val words: String) : NoteSearch

    companion object {
        fun of(typed: String): NoteSearch? {
            val text = typed.trim()
            if (text.isEmpty()) return null
            val only = Hashtags.spans(text).singleOrNull()
            return if (only != null && only.start == 0 && only.end == text.length) Tagged(only.tag) else Worded(text)
        }
    }
}

/** The row of one-tap tags: pinned first, then the most used recently, then Keep a Changelog's words if room is left, each by its name. */
object TagRow {
    val quick = listOf("added", "changed", "fixed", "removed")

    fun tags(tags: Tags?, limit: Int = 12, quick: List<String> = TagRow.quick): List<Tag> {
        val pinned = tags?.pinnedTags.orEmpty()
        val recent = tags?.recent.orEmpty().map { Tag(it.tag, it.name ?: it.tag) }
        val row = mutableListOf<Tag>()
        for (tag in pinned + recent + quick.map { Tag(it, it) }) if (row.none { it.key == tag.key }) row += tag
        return row.take(maxOf(limit, pinned.size))
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
