package com.yourversionnumber.changelog.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.yourversionnumber.changelog.AppModel
import com.yourversionnumber.changelog.NoteTarget
import com.yourversionnumber.changelog.R
import com.yourversionnumber.changelog.kit.Hashtags
import com.yourversionnumber.changelog.kit.NoteDetail
import com.yourversionnumber.changelog.kit.UpcomingDraft
import com.yourversionnumber.changelog.say
import com.yourversionnumber.changelog.target

/**
 * One note, full screen over Log: all of its words and files, where it came
 * from, and Edit and Delete where this token may change it. Back closes it.
 */
@Composable
internal fun NoteViewer(model: AppModel, target: NoteTarget, player: FilePlayer, onClose: () -> Unit) {
    var note by remember(target) { mutableStateOf(model.dayNotes?.firstOrNull { it.id == target.id }) }
    var editing by remember(target) { mutableStateOf(false) }
    var deleting by remember(target) { mutableStateOf(false) }
    // A note not on the day's page (a row from the activity log, say) is read as this opens.
    LaunchedEffect(target) {
        if (note == null) {
            note = model.load(target)
            if (note == null) onClose()
        }
    }
    Dialog(
        // Back leaves the editor for the note, and the note for the day.
        onDismissRequest = { if (editing) editing = false else onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // Dark icons in the status bar over light paper, light over dark.
        val view = LocalView.current
        val lightPaper = paper.paper.luminance() > 0.5f
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = lightPaper
                    isAppearanceLightNavigationBars = lightPaper
                }
            }
        }
        Box(Modifier.fillMaxSize().background(paper.paper)) {
            val shown = note
            when {
                shown == null -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = paper.orange)
                editing -> NoteEditor(model, shown, player, onCancel = { editing = false }, onSaved = onClose)
                else -> NoteReader(model, shown, player, onBack = onClose, onEdit = { editing = true }, onDelete = { deleting = true })
            }
        }
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(say(R.string.delete_this_note)) },
            text = {
                Text(
                    target.version?.let { say(R.string.it_comes_out_of_in, it) }
                        ?: say(R.string.it_comes_out_of_its),
                )
            },
            confirmButton = {
                QuietButton(say(R.string.delete), { deleting = false; model.delete(note?.target ?: target); onClose() }, color = paper.orangeInk)
            },
            dismissButton = { QuietButton(say(R.string.keep), { deleting = false }) },
        )
    }
}

@Composable
private fun NoteReader(model: AppModel, note: NoteDetail, player: FilePlayer, onBack: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            QuietButton("‹ " + say(R.string.back), onBack)
            Spacer(Modifier.weight(1f))
            if (model.canChange(note)) {
                QuietButton(say(R.string.edit), onEdit)
                Spacer(Modifier.padding(horizontal = 8.dp))
                QuietButton(say(R.string.delete), onDelete, color = paper.orangeInk)
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp)) {
            val day = UpcomingDraft.parse(note.date)?.format(dayline).orEmpty()
            Caption(listOfNotNull(day.ifBlank { null }, noteTime(note)).joinToString(" · "))
            if (note.text.isNotBlank()) {
                SelectionContainer {
                    Text(note.text, Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodyLarge.copy(fontSize = 19.sp, lineHeight = 28.sp))
                }
            }
            if (note.media.isNotEmpty()) NoteFiles(model, note, player)
            Rule(Modifier.padding(top = 16.dp, bottom = 8.dp))
            Caption(noteSource(note))
            if (note.version != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Caption(say(R.string.filed_in_2))
                    VersionText(note.version, size = 13.sp)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * The note as the box on Log is: its words, the tag row, photos, camera and
 * voice memo, and Save where Commit is. Files already on it are shown and stay.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NoteEditor(model: AppModel, note: NoteDetail, player: FilePlayer, onCancel: () -> Unit, onSaved: () -> Unit) {
    // Read again as this opens, so an edit made on the web since isn't lost.
    var fresh by remember(note.id) { mutableStateOf<NoteDetail?>(null) }
    var text by remember(note.id) { mutableStateOf(TextFieldValue("")) }
    var saved by remember(note.id) { mutableStateOf(false) }
    val box = remember { FocusRequester() }
    LaunchedEffect(note.id) {
        val read = model.load(note.target)
        if (read == null) {
            onCancel()
        } else {
            fresh = read
            text = TextFieldValue(read.text, TextRange(read.text.length))
        }
    }
    // The box is there once the note is read: then the keyboard comes up.
    LaunchedEffect(fresh != null) { if (fresh != null) runCatching { box.requestFocus() } }
    DisposableEffect(note.id) {
        model.editHeld = note.media.size
        onDispose {
            model.editHeld = 0
            // Left without saving: the files picked for it go.
            if (!saved) model.editFiles.toList().forEach { model.remove(it, edit = true) }
        }
    }
    val words = text.text.trim()
    val changed = fresh != null && words != fresh?.text?.trim()
    val canSave = fresh != null && (changed || model.editFiles.isNotEmpty()) && (!changed || words.isNotBlank())

    Column(Modifier.fillMaxSize().imePadding().navigationBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            QuietButton(say(R.string.cancel), onCancel, color = paper.ink2)
            Spacer(Modifier.weight(1f))
            Caption(note.version?.let { say(R.string.edit_in, it) } ?: say(R.string.edit))
            Spacer(Modifier.padding(end = 12.dp))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (fresh == null) {
                Box(Modifier.fillMaxWidth().padding(top = 24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = paper.orange) }
            } else {
                PaperField(
                    text,
                    { text = it },
                    Modifier.fillMaxWidth().focusRequester(box).semantics { contentDescription = say(R.string.note) },
                    singleLine = false,
                    minLines = 3,
                )
                if (model.editFiles.isNotEmpty()) AttachmentStrip(model, edit = true)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    model.tagRow.forEach { tag ->
                        Pick("#$tag", selected = Hashtags.contains(tag, text.text), onClick = {
                            val next = Hashtags.toggling(tag, text.text)
                            text = TextFieldValue(next, TextRange(next.length))
                        })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (model.canAddFiles) FileTools(model, edit = true)
                    Spacer(Modifier.weight(1f))
                    GoButton(say(R.string.save), {
                        fresh?.let { read ->
                            saved = true
                            model.saveEdit(read, if (changed) words else read.text, model.editFiles.toList())
                            onSaved()
                        }
                    }, enabled = canSave)
                }
                if (note.media.isNotEmpty()) {
                    Rule()
                    Caption(say(R.string.files_cant_come_off))
                    NoteFiles(model, note, player)
                }
            }
        }
    }
}
