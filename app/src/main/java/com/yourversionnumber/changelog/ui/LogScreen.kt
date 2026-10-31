package com.yourversionnumber.changelog.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
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
import androidx.compose.material3.CircularProgressIndicator
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
import com.yourversionnumber.changelog.kit.NoteDetail
import com.yourversionnumber.changelog.kit.OutboxItem
import com.yourversionnumber.changelog.kit.OutboxOperation
import com.yourversionnumber.changelog.kit.UpcomingDraft
import com.yourversionnumber.changelog.say
import com.yourversionnumber.changelog.sayCount
import com.yourversionnumber.changelog.target
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

/** The home screen: today's version, the box, the tag row, Commit, and what happened today. */
@Composable
fun LogScreen(model: AppModel) {
    val waiting by model.waiting.collectAsStateWithLifecycle()
    val sent by model.sent.collectAsStateWithLifecycle()
    val problem by model.problem.collectAsStateWithLifecycle()
    var fixing by remember { mutableStateOf<OutboxItem?>(null) }
    var deleting by remember { mutableStateOf<NoteTarget?>(null) }
    var editing by remember { mutableStateOf<NoteTarget?>(null) }
    val noteBox = remember { FocusRequester() }
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
            item { VersionHeader(model) }
            item {
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
            if (model.draftFiles.isNotEmpty()) item { AttachmentStrip(model) }
            item { TagRowView(model) }
            item { ComposerTools(model) }
            if (model.placeLabel != null) item { PlaceLine(model) }
            problem?.let { message ->
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(message, Modifier.weight(1f), color = paper.orangeInk, style = MaterialTheme.typography.bodyMedium)
                        QuietButton(say(R.string.ok), model::clearProblem)
                    }
                }
            }
            if (waiting.isNotEmpty()) {
                item { Heading(say(R.string.waiting_to_send), Modifier.padding(top = 16.dp)) }
                items(waiting, key = { it.id }) { item ->
                    WaitingRow(item, onDiscard = { model.discard(item.id) }, onFix = { fixing = item })
                }
            }
            val notes = model.dayNotes
            item { DayHeading(model) }
            if (notes != null) {
                // The notes on the day, with their words and files: this token's own, or every channel's with notes:all.
                if (notes.isEmpty()) {
                    item {
                        Text(
                            if (model.isToday) model.me?.let { say(R.string.no_changes_yet_in, it.version) } ?: say(R.string.nothing_yet)
                            else say(R.string.nothing_filed_this_day),
                            color = paper.ink3,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                items(notes, key = { it.id }) { note ->
                    DayNote(model, note, player, onEdit = { editing = note.target }, onDelete = { deleting = note.target })
                }
                if (model.moreOnDay) {
                    item { QuietButton(say(R.string.older), { model.loadDay(more = true) }, enabled = !model.dayLoading) }
                }
                // Another channel's notes show here only by their rows, without words, unless this token reads every note.
                if (model.isToday && model.hasActivity && !model.readsAll) {
                    items(model.today.filter { !model.isMine(it) }, key = { it.id }) { ActivityRow(it) }
                }
            } else if (!model.isToday) {
                item {
                    Text(
                        if (model.dayLoading) say(R.string.nothing_yet) else say(R.string.cant_reach_release_notes),
                        color = paper.ink3,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                // Not read yet, or offline: what the activity log or this phone has for today.
                val empty = if (model.hasActivity) model.today.isEmpty() else sent.isEmpty()
                if (empty) {
                    item {
                        Text(
                            model.me?.let { say(R.string.no_changes_yet_in, it.version) } ?: say(R.string.nothing_yet),
                            color = paper.ink3,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                if (model.hasActivity) {
                    items(model.today, key = { it.id }) { item ->
                        val mine = model.isMine(item)
                        ActivityRow(
                            item,
                            actions = if (!mine) null else {
                                {
                                    QuietButton(say(R.string.edit), { editing = item.target })
                                    QuietButton(say(R.string.delete), { deleting = item.target })
                                }
                            },
                        )
                    }
                } else {
                    items(sent, key = { it.note.id }) { note ->
                        SentRow(note, onEdit = { editing = note.target }, onDelete = { deleting = note.target })
                    }
                }
            }
        }
    }

    Toast(model, Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp))
    }

    // A send that delivered something changes what the server has for today.
    val sends by model.graph.sends.collectAsStateWithLifecycle()
    LaunchedEffect(sends, model.me?.today) {
        model.loadToday()
        model.loadDay()
        model.syncTags()
    }

    editing?.let { target -> EditNoteDialog(model, target, onDone = { editing = null }) }

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

    deleting?.let { target ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(say(R.string.delete_this_note)) },
            text = {
                Text(
                    target.version?.let { say(R.string.it_comes_out_of_in, it) }
                        ?: say(R.string.it_comes_out_of_its),
                )
            },
            confirmButton = { QuietButton(say(R.string.delete), { model.delete(target); deleting = null }, color = paper.orangeInk) },
            dismissButton = { QuietButton(say(R.string.keep), { deleting = null }) },
        )
    }
}

private val dateline = DateTimeFormatter.ofPattern("EEEE, MMMM d")

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
private fun SentRow(sent: Sent, onEdit: () -> Unit, onDelete: () -> Unit) {
    Column {
        Text(sent.text, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Caption(say(R.string.filed_in_2))
                VersionText(sent.note.version, size = 13.sp)
            }
            QuietButton(say(R.string.edit), onEdit)
            QuietButton(say(R.string.delete), onDelete)
        }
        Rule()
    }
}

/** Edits a note this token wrote, from the revision it reads as the dialog opens. */
@Composable
private fun EditNoteDialog(model: AppModel, target: NoteTarget, onDone: () -> Unit) {
    var note by remember(target) { mutableStateOf<NoteDetail?>(null) }
    var text by remember(target) { mutableStateOf("") }
    LaunchedEffect(target) {
        note = model.load(target)
        note?.let { text = it.text } ?: onDone()
    }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(target.version?.let { say(R.string.edit_in, it) } ?: say(R.string.edit)) },
        text = {
            if (note == null) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = paper.orange) }
            } else {
                PaperField(text, { text = it }, singleLine = false)
            }
        },
        confirmButton = {
            QuietButton(
                say(R.string.save),
                { note?.let { model.save(it, text) }; onDone() },
                enabled = note != null && text.isNotBlank() && text != note?.text,
            )
        },
        dismissButton = { QuietButton(say(R.string.cancel), onDone, color = paper.ink2) },
    )
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
