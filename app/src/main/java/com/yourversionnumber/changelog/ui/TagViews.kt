package com.yourversionnumber.changelog.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yourversionnumber.changelog.AppModel
import com.yourversionnumber.changelog.NoteTarget
import com.yourversionnumber.changelog.R
import com.yourversionnumber.changelog.kit.Hashtags
import com.yourversionnumber.changelog.kit.Tag
import com.yourversionnumber.changelog.kit.TagTotal
import com.yourversionnumber.changelog.kit.TagTotals
import com.yourversionnumber.changelog.kit.TagTyping
import com.yourversionnumber.changelog.kit.CheckInWords
import com.yourversionnumber.changelog.say
import com.yourversionnumber.changelog.sayCount
import com.yourversionnumber.changelog.target

/**
 * A tags field, as the web's (`tag-field.tsx`) and the iPhone app's: the tags as chips, each with its own remove
 * button, and a box to type one more. Return adds what is typed, and so does leaving the box; a leading # goes, and
 * spaces and emoji stay ("Road Trip", "🚗 Lake"). It refuses a tag over 50 characters, a 21st tag, a comma and a
 * line break, saying why (call 4). A tag already there by any spelling stays as it is. What a chip's removal does
 * is the caller's: a note's #words leave its words too (call 1). What is typed is the caller's too ([typing]), so a
 * form's Save or Commit takes a tag typed without Return (Codex on iPhone #57).
 *
 * A chip is drawn as the app draws a thing that's on (`Pick`): its name in semibold ink over a 2dp tangerine rule,
 * with a quiet × beside it. No box and no Material chip.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagField(
    tags: List<Tag>,
    typing: TagTyping,
    onTyping: (TagTyping) -> Unit,
    add: (Tag) -> Unit,
    remove: (Tag) -> Unit,
    modifier: Modifier = Modifier,
    label: String = say(R.string.tags),
    heading: String? = null,
    hint: String? = null,
    /** Tags to offer as one is typed: the ones used before. */
    offer: List<Tag> = emptyList(),
    /** Saving the last change, where each one saves: the box keeps what is typed until it is done. */
    busy: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }

    fun commit(next: TagTyping = typing) {
        if (busy) return
        val (left, taken) = next.take(tags)
        onTyping(left)
        if (taken is TagTyping.Taken.Added) add(taken.tag)
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        heading?.let { Heading(it) }
        if (tags.isNotEmpty()) {
            FlowRow(
                Modifier.semantics { contentDescription = label },
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                tags.forEach { tag ->
                    TagChip(tag, enabled = !busy) {
                        onTyping(typing.copy(problem = null))
                        remove(tag)
                    }
                }
            }
        }
        PaperField(
            typing.text,
            { onTyping(TagTyping(it, problem = null)) },
            Modifier
                .fillMaxWidth()
                .onFocusChanged { state ->
                    val now = state.hasFocus
                    // Leaving the box adds what's typed, as Return does.
                    if (focused && !now) commit()
                    focused = now
                }
                .semantics { contentDescription = label },
            placeholder = say(if (tags.isEmpty()) R.string.add_a_tag else R.string.add_another),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { commit() }),
        )
        val offered = suggestions(typing.text, tags, offer, focused)
        if (offered.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                offered.forEach { tag ->
                    QuietButton(
                        tag.name,
                        { commit(TagTyping(tag.name)) },
                        Modifier.semantics { contentDescription = say(R.string.add_tag, tag.name) },
                    )
                }
            }
        }
        val problem = typing.problem
        when {
            problem != null -> Caption(problem, color = paper.orangeInk)
            hint != null && focused -> Caption(hint, color = paper.ink2)
        }
    }
}

/** One tag as a chip: its name as written, on a tangerine rule, and a button that takes it off. */
@Composable
private fun TagChip(tag: Tag, enabled: Boolean, remove: () -> Unit) {
    val p = paper
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            tag.name,
            Modifier.drawBehind {
                val y = size.height + 2.dp.toPx()
                drawLine(p.orange, Offset(0f, y), Offset(size.width, y), 2.dp.toPx())
            },
            color = p.ink,
            maxLines = 1,
            style = TextStyle(fontFamily = Grotesque, fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
        )
        Box(
            Modifier
                .size(40.dp, 48.dp)
                .clickable(enabled = enabled, role = Role.Button, onClickLabel = say(R.string.remove_tag, tag.name), onClick = remove)
                .semantics { contentDescription = say(R.string.remove_tag, tag.name) },
            contentAlignment = Alignment.Center,
        ) {
            Text("×", color = if (enabled) p.ink2 else p.ink3, style = MaterialTheme.typography.titleMedium)
        }
    }
}

/**
 * While typing: the tags used before that begin with what is typed, or have a word that does, and aren't here yet;
 * six at most.
 */
private fun suggestions(typed: String, tags: List<Tag>, offer: List<Tag>, focused: Boolean): List<Tag> {
    val start = typed.trim().trimStart('#').lowercase()
    if (!focused || start.isEmpty()) return emptyList()
    return offer.filter { tag ->
        val name = tag.name.lowercase()
        tags.none { it.key == tag.key } && (name.startsWith(start) || name.split(' ').any { it.startsWith(start) })
    }.take(6)
}

/**
 * Tags as a line of links to their pages, as a note has the tags its words don't show under it: "Tags: Road Trip,
 * lake". Each opens its page in Notes; with no [onOpen] (a token that can't read the notes a page lists) they are
 * words only.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagLinks(tags: List<Tag>, onOpen: ((Tag) -> Unit)?, modifier: Modifier = Modifier) {
    if (tags.isEmpty()) return
    if (onOpen == null) {
        Caption(say(R.string.tags_line, Hashtags.line(tags)), modifier)
        return
    }
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.Center) {
        Caption(say(R.string.tags_colon), Modifier.align(Alignment.CenterVertically))
        tags.forEachIndexed { i, tag ->
            Box(
                Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .clickable(role = Role.Button, onClickLabel = say(R.string.open)) { onOpen(tag) }
                    .semantics { contentDescription = say(R.string.tag_link, tag.name) },
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(tag.name + if (i < tags.lastIndex) "," else "", color = paper.blue, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** "3 days, 1 date, 2 check-ins": what carries a tag, each only when it does, as the web's tags list says it. */
private fun carried(total: TagTotal): String = listOfNotNull(
    total.days.takeIf { it > 0 }?.let { sayCount(R.plurals.days_count, it, it) },
    total.upcoming?.takeIf { it > 0 }?.let { sayCount(R.plurals.dates_count, it, it) },
    total.checkIns?.takeIf { it > 0 }?.let { sayCount(R.plurals.check_ins_count, it, it) },
).joinToString(", ")

/** Every tag, as the web's Search lists them with the box empty: its latest name, what has it, and a page of its own. */
@Composable
internal fun TagsPage(model: AppModel, modifier: Modifier) {
    var all by remember { mutableStateOf<TagTotals?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        model.allTags().fold(onSuccess = { all = it; problem = null }, onFailure = { if (all == null) problem = it.message })
    }
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 24.dp, top = 8.dp, end = 24.dp, bottom = 24.dp + LocalBarSpace.current),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val read = all
        when {
            read != null && read.tags.isEmpty() -> item(key = "none") {
                Text(say(R.string.no_tags_yet), color = paper.ink2, style = MaterialTheme.typography.bodyMedium)
            }
            read != null -> items(read.tags, key = { it.tag }) { total ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button, onClickLabel = say(R.string.open)) { model.openTag(total.named) }
                        .padding(vertical = 10.dp),
                ) {
                    Text(total.named.name, style = MaterialTheme.typography.bodyLarge, color = paper.ink)
                    carried(total).takeIf { it.isNotEmpty() }?.let { Caption(it) }
                }
                Rule()
            }
            problem != null -> item(key = "problem") {
                Text(problem.orEmpty(), color = paper.orangeInk, style = MaterialTheme.typography.bodyMedium)
            }
            else -> item(key = "loading") { CircularProgressIndicator(Modifier.padding(top = 24.dp), color = paper.orange) }
        }
    }
}

/** "3 notes. 1 Upcoming date and 2 check-ins.", as the web's lede. */
private fun lede(notes: Int, dates: Int, stays: Int): String {
    val lines = mutableListOf<String>()
    if (notes > 0) lines += sayCount(R.plurals.tag_notes, notes, notes)
    val others = listOfNotNull(
        dates.takeIf { it > 0 }?.let { sayCount(R.plurals.upcoming_dates_count, it, it) },
        stays.takeIf { it > 0 }?.let { sayCount(R.plurals.check_ins_count, it, it) },
    )
    if (others.isNotEmpty()) lines += say(R.string.sentence, others.joinToString(say(R.string.and_join)))
    return if (lines.isEmpty()) say(R.string.nothing_has_this_tag) else lines.joinToString(" ")
}

/**
 * One tag's page, as the web's and the iPhone app's (call 5): its Upcoming dates, its check-ins (every one), then
 * its notes, newest first, a page at a time. A note opens full screen, as anywhere in Notes.
 */
@Composable
internal fun TagPage(model: AppModel, tag: Tag, player: FilePlayer, modifier: Modifier, onOpen: (NoteTarget) -> Unit) {
    val today = model.me?.today
    val notes = model.tagNotes
    val dates = model.tagDates
    val stays = model.tagStays
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 24.dp, top = 8.dp, end = 24.dp, bottom = 24.dp + LocalBarSpace.current),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "head") {
            Column {
                Caption(say(R.string.tag))
                Text(tag.name, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge, color = paper.ink)
            }
        }
        when {
            notes != null -> item(key = "lede") {
                Text(lede(model.tagNotesTotal, dates.size, stays.size), color = paper.ink2, style = MaterialTheme.typography.bodyLarge)
            }
            model.tagProblem != null -> item(key = "problem") {
                Text(model.tagProblem.orEmpty(), color = paper.orangeInk, style = MaterialTheme.typography.bodyMedium)
            }
            else -> item(key = "loading") { CircularProgressIndicator(Modifier.padding(top = 12.dp), color = paper.orange) }
        }
        if (dates.isNotEmpty()) {
            item(key = "dates") { Heading(say(R.string.upcoming), Modifier.padding(top = 12.dp).semantics { heading() }) }
            items(dates, key = { "date/" + it.id }) { entry ->
                Column {
                    Text(entry.line, style = MaterialTheme.typography.bodyLarge, color = paper.ink)
                    Caption(dayLabel(entry.next, today))
                }
            }
        }
        if (stays.isNotEmpty()) {
            item(key = "stays") { Heading(say(R.string.check_ins), Modifier.padding(top = 12.dp).semantics { heading() }) }
            items(stays, key = { "stay/" + it.id }) { stay ->
                Column {
                    Text(stay.label, style = MaterialTheme.typography.bodyLarge, color = paper.ink)
                    val date = dayLabel(stay.day, today)
                    Caption(CheckInWords.stay(stay)?.let { "$date, $it" } ?: date)
                }
            }
        }
        val shown = notes.orEmpty()
        if (shown.isNotEmpty() && (dates.isNotEmpty() || stays.isNotEmpty())) {
            item(key = "notes") { Heading(say(R.string.notes), Modifier.padding(top = 12.dp).semantics { heading() }) }
        }
        items(shown, key = { "note/" + it.date + "/" + it.id }) { note ->
            Column {
                Caption(dayLabel(note.date, today), Modifier.padding(bottom = 4.dp))
                DayNote(model, note, player, showVersion = true, onOpen = { onOpen(note.target) })
            }
            if (note == shown.last() && model.moreTagNotes) LaunchedEffect(note.id) { model.readTag(more = true) }
        }
    }
}
