package com.yourversionnumber.changelog.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import kotlinx.coroutines.delay
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yourversionnumber.changelog.AppModel
import com.yourversionnumber.changelog.NoteTarget
import com.yourversionnumber.changelog.Sent
import com.yourversionnumber.changelog.kit.Hashtags
import com.yourversionnumber.changelog.kit.MediaType
import com.yourversionnumber.changelog.kit.NoteDetail
import com.yourversionnumber.changelog.kit.OutboxItem
import com.yourversionnumber.changelog.kit.OutboxOperation
import com.yourversionnumber.changelog.kit.UpcomingDraft
import com.yourversionnumber.changelog.target
import java.time.format.DateTimeFormatter

/** The home screen: today's version, the box, the tag row, Commit, and what happened today. */
@Composable
fun LogScreen(model: AppModel, onSettings: () -> Unit) {
    val waiting by model.waiting.collectAsStateWithLifecycle()
    val sent by model.sent.collectAsStateWithLifecycle()
    val problem by model.problem.collectAsStateWithLifecycle()
    var fixing by remember { mutableStateOf<OutboxItem?>(null) }
    var deleting by remember { mutableStateOf<NoteTarget?>(null) }
    var editing by remember { mutableStateOf<NoteTarget?>(null) }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        Bar { QuietButton("Settings", onSettings) }
        LazyColumn(
            Modifier.imePadding(),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 32.dp),
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
                    Modifier.fillMaxWidth().semantics { contentDescription = "Note" },
                    placeholder = "What changed?",
                    singleLine = false,
                    minLines = 3,
                )
            }
            if (model.draftFiles.isNotEmpty()) item { AttachmentStrip(model) }
            item { TagRowView(model) }
            item { ComposerTools(model) }
            if (model.placeLabel != null) item { PlaceLine(model) }
            item { GoButton("Commit", model::commit, enabled = model.canCommit) }
            problem?.let { message ->
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(message, Modifier.weight(1f), color = paper.orangeInk, style = MaterialTheme.typography.bodyMedium)
                        QuietButton("OK", model::clearProblem)
                    }
                }
            }
            if (waiting.isNotEmpty()) {
                item { Heading("Waiting to send", Modifier.padding(top = 16.dp)) }
                items(waiting, key = { it.id }) { item ->
                    WaitingRow(item, onDiscard = { model.discard(item.id) }, onFix = { fixing = item })
                }
            }
            // With the activity log, Today shows every channel; without it, what this phone sent.
            item { Heading(if (model.hasActivity) "Today" else "Sent from this phone", Modifier.padding(top = 16.dp)) }
            val empty = if (model.hasActivity) model.today.isEmpty() else sent.isEmpty()
            if (empty) {
                item {
                    Text(
                        model.me?.let { "No changes yet in ${it.version}." } ?: "Nothing yet.",
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
                                QuietButton("Edit", { editing = item.target })
                                QuietButton("Delete", { deleting = item.target })
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

    Toast(model, Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp))
    }

    // A send that delivered something changes what the server has for today.
    val sends by model.graph.sends.collectAsStateWithLifecycle()
    LaunchedEffect(sends) {
        model.loadToday()
        model.syncTags()
    }

    editing?.let { target -> EditNoteDialog(model, target, onDone = { editing = null }) }

    fixing?.let { item ->
        var text by remember(item.id) { mutableStateOf(item.text.orEmpty()) }
        AlertDialog(
            onDismissRequest = { fixing = null },
            title = { Text("Fix and send again") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item.refusal?.let { Text(it, color = paper.orangeInk) }
                    PaperField(text, { text = it }, singleLine = false)
                }
            },
            confirmButton = { QuietButton("Send", { model.fix(item.id, text); fixing = null }) },
            dismissButton = { QuietButton("Cancel", { fixing = null }, color = paper.ink2) },
        )
    }

    deleting?.let { target ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete this note?") },
            text = { Text("It comes out of ${target.version ?: "its day"} in Release Notes, with anything attached.") },
            confirmButton = { QuietButton("Delete", { model.delete(target); deleting = null }, color = paper.orangeInk) },
            dismissButton = { QuietButton("Keep", { deleting = null }) },
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
        Spacer(Modifier.height(10.dp))
        when {
            me == null -> {}
            me.status == "stopped" -> Caption("Your email is stopped.", color = paper.orangeInk)
            me.streak > 1 -> Caption("${me.streak} days in a row")
            me.wroteToday -> Caption("Written today")
        }
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
        model.sticky?.let { Caption("#$it goes on every note. Touch and hold it to stop.") }
    }
}

@Composable
private fun WaitingRow(item: OutboxItem, onDiscard: () -> Unit, onFix: () -> Unit) {
    val summary = when (val op = item.operation) {
        is OutboxOperation.Note -> op.text
        is OutboxOperation.Edit -> "Edit: ${op.text}"
        is OutboxOperation.Delete -> "Delete a note"
        is OutboxOperation.Pins -> "Pinned tags"
        is OutboxOperation.Post -> op.text.ifBlank { MediaType.count(op.files) }
    }
    Column {
        Text(summary, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
        if (item.files.isNotEmpty() && item.text?.isNotBlank() == true) Caption(MediaType.count(item.files))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Caption(
                item.refusal ?: if (item.attempts > 0) "Will try again" else "Waiting for a connection",
                Modifier.weight(1f),
                color = if (item.refusal != null) paper.orangeInk else paper.ink3,
            )
            if (item.refusal != null && item.isNote) QuietButton("Fix", onFix)
            QuietButton("Discard", onDiscard, color = paper.ink2)
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
                Caption("Filed in ")
                VersionText(sent.note.version, size = 13.sp)
            }
            QuietButton("Edit", onEdit)
            QuietButton("Delete", onDelete)
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
        title = { Text(target.version?.let { "Edit in $it" } ?: "Edit") },
        text = {
            if (note == null) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = paper.orange) }
            } else {
                PaperField(text, { text = it }, singleLine = false)
            }
        },
        confirmButton = {
            QuietButton(
                "Save",
                { note?.let { model.save(it, text) }; onDone() },
                enabled = note != null && text.isNotBlank() && text != note?.text,
            )
        },
        dismissButton = { QuietButton("Cancel", onDone, color = paper.ink2) },
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
