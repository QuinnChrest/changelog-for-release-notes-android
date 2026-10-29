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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yourversionnumber.changelog.AppModel
import com.yourversionnumber.changelog.Sent
import com.yourversionnumber.changelog.kit.Hashtags
import com.yourversionnumber.changelog.kit.OutboxItem
import com.yourversionnumber.changelog.kit.OutboxOperation

/** The home screen: today's version, the box, the tag row, Commit, and what this phone sent. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(model: AppModel, onSettings: () -> Unit) {
    val waiting by model.waiting.collectAsStateWithLifecycle()
    val sent by model.sent.collectAsStateWithLifecycle()
    val problem by model.problem.collectAsStateWithLifecycle()
    var fixing by remember { mutableStateOf<OutboxItem?>(null) }
    var deleting by remember { mutableStateOf<Sent?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Changelog") },
                actions = { TextButton(onClick = onSettings) { Text("Settings") } },
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
                OutlinedTextField(
                    value = model.draft,
                    onValueChange = { model.draft = it },
                    placeholder = { Text("What changed?") },
                    minLines = 3,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Note" },
                )
            }
            item { TagRowView(model) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    model.sticky?.let {
                        Text("#$it on every note", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
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
            item { SectionTitle("Sent from this phone") }
            if (sent.isEmpty()) {
                item {
                    Text(
                        model.me?.let { "No changes yet in ${it.version}." } ?: "Nothing yet.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(sent, key = { it.note.id }) { note ->
                SentRow(note, onDelete = { deleting = note })
            }
        }
    }

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

    deleting?.let { note ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete this note?") },
            text = { Text("It comes out of ${note.note.version} in Release Notes, with anything attached.") },
            confirmButton = { TextButton(onClick = { model.delete(note); deleting = null }) { Text("Delete") } },
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
    }
    Column {
        Text(summary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Caption(item.refusal ?: if (item.attempts > 0) "Will try again" else "Waiting for a connection", Modifier.weight(1f))
            if (item.refusal != null && item.isNote) TextButton(onClick = onFix) { Text("Fix") }
            TextButton(onClick = onDiscard) { Text("Discard") }
        }
        HorizontalDivider()
    }
}

@Composable
private fun SentRow(sent: Sent, onDelete: () -> Unit) {
    Column {
        Text(sent.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Caption("Filed in ${sent.note.version}", Modifier.weight(1f))
            TextButton(onClick = onDelete) { Text("Delete") }
        }
        HorizontalDivider()
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
}

@Composable
internal fun Caption(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
