package com.yourversionnumber.changelog.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yourversionnumber.changelog.AppModel
import com.yourversionnumber.changelog.Sent
import com.yourversionnumber.changelog.kit.Hashtags
import com.yourversionnumber.changelog.kit.OutboxItem
import com.yourversionnumber.changelog.kit.OutboxOperation
import com.yourversionnumber.changelog.kit.NoteDetail
import com.yourversionnumber.changelog.kit.MediaType
import com.yourversionnumber.changelog.NoteTarget
import com.yourversionnumber.changelog.target
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect

/** The home screen: today's version, the box, the tag row, Commit, and what happened today. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(model: AppModel, onActivity: () -> Unit, onSettings: () -> Unit) {
    val waiting by model.waiting.collectAsStateWithLifecycle()
    val sent by model.sent.collectAsStateWithLifecycle()
    val problem by model.problem.collectAsStateWithLifecycle()
    var fixing by remember { mutableStateOf<OutboxItem?>(null) }
    var deleting by remember { mutableStateOf<NoteTarget?>(null) }
    var editing by remember { mutableStateOf<NoteTarget?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Changelog") },
                actions = {
                    TextButton(onClick = onActivity) { Text("Activity") }
                    TextButton(onClick = onSettings) { Text("Settings") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).imePadding(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { VersionHeader(model) }
            item {
                // A tag tapped in the row, a share or a commit changes the draft from
                // outside the box: the cursor goes to the end, after what was added.
                var field by remember { mutableStateOf(TextFieldValue(model.draft, TextRange(model.draft.length))) }
                if (field.text != model.draft) field = TextFieldValue(model.draft, TextRange(model.draft.length))
                OutlinedTextField(
                    value = field,
                    onValueChange = {
                        field = it
                        model.draft = it.text
                    },
                    placeholder = { Text("What changed?") },
                    minLines = 3,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Note" },
                )
            }
            if (model.draftFiles.isNotEmpty()) item { AttachmentStrip(model) }
            item { TagRowView(model) }
            model.sticky?.let { item { Caption("#$it on every note") } }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (model.canAddFiles) ComposerTools(model)
                    Spacer(Modifier.weight(1f))
                    Button(onClick = model::commit, enabled = model.canCommit) { Text("Commit") }
                }
            }
            problem?.let { message ->
                item {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
                            TextButton(onClick = model::clearProblem) { Text("OK") }
                        }
                    }
                }
            }
            if (waiting.isNotEmpty()) {
                item { SectionTitle("Waiting to send") }
                items(waiting, key = { it.id }) { item ->
                    WaitingRow(item, onDiscard = { model.discard(item.id) }, onFix = { fixing = item })
                }
            }
            // With the activity log, Today shows every channel; without it, what this phone sent.
            item { SectionTitle(if (model.hasActivity) "Today" else "Sent from this phone") }
            val empty = if (model.hasActivity) model.today.isEmpty() else sent.isEmpty()
            if (empty) {
                item {
                    Text(
                        model.me?.let { "No changes yet in ${it.version}." } ?: "Nothing yet.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                TextButton(onClick = { editing = item.target }) { Text("Edit") }
                                TextButton(onClick = { deleting = item.target }) { Text("Delete") }
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

    // A send that delivered something changes what the server has for today.
    val sends by model.graph.sends.collectAsStateWithLifecycle()
    LaunchedEffect(sends) { model.loadToday() }

    editing?.let { target -> EditNoteDialog(model, target, onDone = { editing = null }) }

    fixing?.let { item ->
        var text by remember(item.id) { mutableStateOf(item.text.orEmpty()) }
        AlertDialog(
            onDismissRequest = { fixing = null },
            title = { Text("Fix and send again") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item.refusal?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    OutlinedTextField(value = text, onValueChange = { text = it }, minLines = 3)
                }
            },
            confirmButton = { TextButton(onClick = { model.fix(item.id, text); fixing = null }) { Text("Send") } },
            dismissButton = { TextButton(onClick = { fixing = null }) { Text("Cancel") } },
        )
    }

    deleting?.let { target ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete this note?") },
            text = { Text("It comes out of ${target.version ?: "its day"} in Release Notes, with anything attached.") },
            confirmButton = { TextButton(onClick = { model.delete(target); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Keep") } },
        )
    }
}

@Composable
private fun VersionHeader(model: AppModel) {
    val me = model.me
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            me?.version ?: "…",
            style = VersionStyle,
            modifier = Modifier.semantics { contentDescription = me?.let { "Version ${it.version}" } ?: "Version loading" },
        )
        Spacer(Modifier.weight(1f))
        when {
            me == null -> {}
            me.status == "stopped" -> Caption("Email stopped")
            me.streak > 1 -> Caption("${me.streak}-day streak")
        }
    }
}

/** Pinned tags, then the ones used most lately. Tap to add or take off; touch and hold to keep on every note. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun TagRowView(model: AppModel) {
    val haptics = LocalHapticFeedback.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        model.tagRow.forEach { tag ->
            val on = Hashtags.contains(tag, model.draft) || model.sticky == tag
            Surface(
                shape = RoundedCornerShape(50),
                color = if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.combinedClickable(
                    onClick = { model.toggle(tag) },
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        model.stick(if (model.sticky == tag) null else tag)
                    },
                ),
            ) {
                Text("#$tag", Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge)
            }
        }
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
        Text(summary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (item.files.isNotEmpty() && item.text?.isNotBlank() == true) Caption(MediaType.count(item.files))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Caption(item.refusal ?: if (item.attempts > 0) "Will try again" else "Waiting for a connection", Modifier.weight(1f))
            if (item.refusal != null && item.isNote) TextButton(onClick = onFix) { Text("Fix") }
            TextButton(onClick = onDiscard) { Text("Discard") }
        }
        HorizontalDivider()
    }
}

@Composable
private fun SentRow(sent: Sent, onEdit: () -> Unit, onDelete: () -> Unit) {
    Column {
        Text(sent.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Caption("Filed in ${sent.note.version}", Modifier.weight(1f))
            TextButton(onClick = onEdit) { Text("Edit") }
            TextButton(onClick = onDelete) { Text("Delete") }
        }
        HorizontalDivider()
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
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                OutlinedTextField(value = text, onValueChange = { text = it }, minLines = 3, maxLines = 12)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { note?.let { model.save(it, text) }; onDone() },
                enabled = note != null && text.isNotBlank() && text != note?.text,
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
}

@Composable
internal fun Caption(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
