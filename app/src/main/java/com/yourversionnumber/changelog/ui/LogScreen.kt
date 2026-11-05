package com.yourversionnumber.changelog.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yourversionnumber.changelog.AppModel
import com.yourversionnumber.changelog.NoteTarget
import com.yourversionnumber.changelog.R
import com.yourversionnumber.changelog.Sent
import com.yourversionnumber.changelog.kit.Hashtags
import com.yourversionnumber.changelog.kit.MediaType
import com.yourversionnumber.changelog.kit.OutboxItem
import com.yourversionnumber.changelog.kit.OutboxOperation
import com.yourversionnumber.changelog.kit.UpcomingDraft
import com.yourversionnumber.changelog.say
import com.yourversionnumber.changelog.sayCount
import com.yourversionnumber.changelog.target
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

/** The home screen: today's version, the box, the tag row, Commit, and what happened today. */
@Composable
fun LogScreen(model: AppModel) {
    val waiting by model.waiting.collectAsStateWithLifecycle()
    val sent by model.sent.collectAsStateWithLifecycle()
    val problem by model.problem.collectAsStateWithLifecycle()
    var fixing by remember { mutableStateOf<OutboxItem?>(null) }
    var opened by remember { mutableStateOf<NoteTarget?>(null) }
    val noteBox = remember { FocusRequester() }
    // A write that sends at once never shows under Waiting to send; one that's been tried, refused or kept
    // a few seconds does, so a note is never out of sight for long.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(waiting.isNotEmpty()) {
        while (waiting.isNotEmpty()) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    val held = waiting.filter { it.refusal != null || it.attempts > 0 || now - queuedAt(it) > HELD_AFTER_MILLIS }
    val player = rememberFilePlayer()
    LaunchedEffect(model.writeNow) {
        if (model.writeNow) {
            runCatching { noteBox.requestFocus() }
            model.writeNow = false
        }
    }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        Bar { Box(Modifier.padding(end = 12.dp)) { StreakNote(model) } }
        LazyColumn(
            Modifier.imePadding(),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 32.dp + LocalBarSpace.current),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "version") { VersionHeader(model) }
            item(key = "field") {
                // A tag tapped in the row, a share or a commit changes the draft from
                // outside the box: the cursor goes to the end, after what was added.
                var field by remember { mutableStateOf(TextFieldValue(model.draft, TextRange(model.draft.length))) }
                if (field.text != model.draft) field = TextFieldValue(model.draft, TextRange(model.draft.length))
                PaperField(
                    field,
                    {
                        if (field.text.isBlank() && it.text.isNotBlank()) model.draftStarted()
                        field = it
                        model.draft = it.text
                    },
                    Modifier.fillMaxWidth().focusRequester(noteBox).semantics { contentDescription = say(R.string.note) },
                    placeholder = say(R.string.what_changed),
                    singleLine = false,
                    minLines = 3,
                )
            }
            if (model.draftFiles.isNotEmpty()) item(key = "attachments") { AttachmentStrip(model) }
            item(key = "tags") { TagRowView(model) }
            item(key = "tools") { ComposerTools(model) }
            if (model.placeLabel != null) item(key = "place") { PlaceLine(model) }
            problem?.let { message ->
                item(key = "problem") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(message, Modifier.weight(1f), color = paper.orangeInk, style = MaterialTheme.typography.bodyMedium)
                        QuietButton(say(R.string.ok), model::clearProblem)
                    }
                }
            }
            if (held.isNotEmpty()) {
                item(key = "waiting") { Heading(say(R.string.waiting_to_send), Modifier.padding(top = 16.dp)) }
                items(held, key = { it.id }) { item ->
                    WaitingRow(item, onDiscard = { model.discard(item.id) }, onFix = { fixing = item })
                }
            }
            val notes = model.dayNotes
            item(key = "day") { DayHeading(model) }
            if (notes != null) {
                // The notes on the day, with their words and files: this token's own, or every channel's with notes:all.
                if (notes.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            if (model.isToday) model.me?.let { say(R.string.no_changes_yet_in, it.version) } ?: say(R.string.nothing_yet)
                            else say(R.string.nothing_filed_this_day),
                            color = paper.ink3,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                items(notes, key = { it.id }) { note ->
                    DayNote(model, note, player, onOpen = { opened = note.target })
                }
                if (model.moreOnDay) {
                    item(key = "more") { QuietButton(say(R.string.older), { model.loadDay(more = true) }, enabled = !model.dayLoading) }
                }
            } else if (!model.isToday) {
                item(key = "empty") {
                    Text(
                        if (model.dayLoading) say(R.string.nothing_yet) else say(R.string.cant_reach_release_notes),
                        color = paper.ink3,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else if (model.dayRead != model.day) {
                // The day's first read is still out: nothing yet, rather than something swapped out a moment later.
            } else {
                // Offline: the notes this phone filed today. Log shows notes only; activity is on its own tab.
                if (sent.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            model.me?.let { say(R.string.no_changes_yet_in, it.version) } ?: say(R.string.nothing_yet),
                            color = paper.ink3,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                items(sent, key = { it.note.id }) { note ->
                    SentRow(note, onOpen = { opened = note.target })
                }
            }
        }
    }

    Toast(model, Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp))
    }

    // A send that delivered something changes what the server has for today.
    val sends by model.graph.sends.collectAsStateWithLifecycle()
    LaunchedEffect(sends, model.me?.today) {
        model.loadDay()
        model.syncTags()
    }

    opened?.let { target -> NoteViewer(model, target, player, onClose = { opened = null }) }

    fixing?.let { item ->
        var text by remember(item.id) { mutableStateOf(item.text.orEmpty()) }
        AlertDialog(
            onDismissRequest = { fixing = null },
            title = { Text(say(R.string.fix_and_send_again)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item.refusal?.let { Text(it, color = paper.orangeInk) }
                    PaperField(text, { text = it }, singleLine = false)
                }
            },
            confirmButton = { QuietButton(say(R.string.send), { model.fix(item.id, text); fixing = null }) },
            dismissButton = { QuietButton(say(R.string.cancel), { fixing = null }, color = paper.ink2) },
        )
    }
}

private val dateline = DateTimeFormatter.ofPattern("EEEE, MMMM d")

/** How long a write may wait before Log says so. */
private const val HELD_AFTER_MILLIS = 4_000L

/** When a write was queued, in milliseconds; 0 (long ago) if it can't be read. */
private fun queuedAt(item: OutboxItem): Long =
    runCatching { OffsetDateTime.parse(item.queuedAt).toInstant().toEpochMilli() }.getOrDefault(0L)

/** The day, then its version, large, as the site's Today. */
@Composable
private fun VersionHeader(model: AppModel) {
    val me = model.me
    Column(Modifier.padding(top = 20.dp, bottom = 4.dp)) {
        Text(
            me?.today?.let { UpcomingDraft.parse(it)?.format(dateline) } ?: " ",
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
            color = paper.ink2,
        )
        Spacer(Modifier.height(8.dp))
        VersionText(me?.version, size = 52.sp)
    }
}

/** The email's state, or the streak, at the right of the top bar. */
@Composable
private fun StreakNote(model: AppModel) {
    val me = model.me ?: return
    when {
        me.status == "stopped" -> Caption(say(R.string.your_email_is_stopped), color = paper.orangeInk)
        me.streak > 1 -> Caption(sayCount(R.plurals.streak, me.streak, me.streak))
        me.wroteToday -> Caption(say(R.string.written_today))
    }
}

/**
 * Pinned tags, then the ones used most lately, as the site's tag picks: tap
 * to add or take off; touch and hold to keep one on every note.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagRowView(model: AppModel) {
    val haptics = LocalHapticFeedback.current
    Column {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            model.tagRow.forEach { tag ->
                Pick(
                    "#$tag",
                    selected = Hashtags.contains(tag, model.draft) || model.sticky == tag,
                    onClick = { model.toggle(tag) },
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        model.stick(if (model.sticky == tag) null else tag)
                    },
                )
            }
        }
        model.sticky?.let { Caption(say(R.string.goes_on_every_note_touch, it)) }
    }
}

@Composable
private fun WaitingRow(item: OutboxItem, onDiscard: () -> Unit, onFix: () -> Unit) {
    val summary = when (val op = item.operation) {
        is OutboxOperation.Note -> op.text
        is OutboxOperation.Edit -> say(R.string.edit_2, op.text)
        is OutboxOperation.Delete -> say(R.string.delete_a_note)
        is OutboxOperation.Attach -> say(R.string.adding_files_to_a_note, MediaType.count(op.files))
        is OutboxOperation.Pins -> say(R.string.pinned_tags)
        is OutboxOperation.Post -> op.text.ifBlank { MediaType.count(op.files) }
    }
    Column {
        Text(summary, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
        if (item.files.isNotEmpty() && item.text?.isNotBlank() == true) Caption(MediaType.count(item.files))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Caption(
                item.refusal ?: if (item.attempts > 0) say(R.string.will_try_again) else say(R.string.waiting_for_a_connection),
                Modifier.weight(1f),
                color = if (item.refusal != null) paper.orangeInk else paper.ink3,
            )
            if (item.refusal != null && item.isNote) QuietButton(say(R.string.fix), onFix)
            QuietButton(say(R.string.discard), onDiscard, color = paper.ink2)
        }
        Rule()
    }
}

@Composable
private fun SentRow(sent: Sent, onOpen: () -> Unit) {
    Column(Modifier.clickable(onClickLabel = say(R.string.open), role = Role.Button, onClick = onOpen)) {
        Text(sent.text, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Caption(say(R.string.filed_in_2))
                VersionText(sent.note.version, size = 13.sp)
            }
        }
        Rule()
    }
}

/** A word after a note is filed, in an ink capsule that goes by itself. */
@Composable
private fun Toast(model: AppModel, modifier: Modifier = Modifier) {
    val toast by model.graph.toast.collectAsStateWithLifecycle()
    AnimatedVisibility(toast != null, modifier, enter = fadeIn(), exit = fadeOut()) {
        var shown by remember { mutableStateOf("") }
        toast?.let { shown = it }
        // Drawn in reverse: the other palette's paper and ink, so its version reads.
        CompositionLocalProvider(LocalPaper provides paper.inverse) {
            Text(
                withVersion(shown, Regex("""\d+\.\d+\.\d+""").find(shown)?.value),
                Modifier
                    .background(paper.paper, RoundedCornerShape(50))
                    .padding(horizontal = 18.dp, vertical = 10.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                color = paper.ink,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
    LaunchedEffect(toast) {
        if (toast != null) {
            delay(2800)
            model.graph.clearToast()
        }
    }
}
