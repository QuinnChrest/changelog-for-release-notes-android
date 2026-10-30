package com.yourversionnumber.changelog.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.yourversionnumber.changelog.AppModel
import com.yourversionnumber.changelog.Recorder
import com.yourversionnumber.changelog.kit.QueuedFile
import kotlinx.coroutines.delay
import java.io.File

/** Photos, the camera and a voice memo, beside Commit. Only for a token that can add files. */
@Composable
fun ComposerTools(model: AppModel) {
    val context = LocalContext.current
    var recording by remember { mutableStateOf(false) }
    var cameraFile by rememberSaveable { mutableStateOf<String?>(null) }
    val room = model.roomForFiles

    val photos = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = room.coerceIn(2, 20)),
    ) { uris -> if (uris.isNotEmpty()) model.addPhotos(uris) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val file = cameraFile?.let(::File)
        cameraFile = null
        if (taken && file != null && file.length() > 0) model.addCameraPhoto(file) else file?.delete()
    }
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) recording = true else model.graph.report("Changelog can't record without the microphone. Allow it in Settings, Apps, Changelog.")
    }

    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        QuietButton("Photos", { photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = room > 0)
        QuietButton(
            "Camera",
            {
                val file = model.scratch("jpg")
                cameraFile = file.path
                camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.files", file))
            },
            enabled = room > 0,
        )
        QuietButton(
            "Record",
            {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    recording = true
                } else {
                    microphone.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            enabled = room > 0,
            modifier = Modifier.semantics { contentDescription = "Record a voice memo" },
        )
    }

    if (recording) RecorderDialog(model, onDone = { recording = false })
}

/** Records while open: Stop puts the memo on the note, Cancel throws it away. */
@Composable
private fun RecorderDialog(model: AppModel, onDone: () -> Unit) {
    val context = LocalContext.current
    val recorder = remember { Recorder(context, model.scratch("m4a")) }
    var failed by remember { mutableStateOf(false) }
    var elapsed by remember { mutableLongStateOf(0L) }

    fun stop() {
        recorder.stop()?.let(model::addRecording) ?: model.graph.report("That recording didn't work. Try again.")
        onDone()
    }

    LaunchedEffect(Unit) {
        if (!recorder.start(onLongest = ::stop)) {
            failed = true
            return@LaunchedEffect
        }
        while (true) {
            elapsed = recorder.elapsedMillis
            delay(250)
        }
    }
    // Left any other way (the screen turned off, say): nothing keeps the microphone on.
    DisposableEffect(Unit) { onDispose { recorder.cancel() } }

    AlertDialog(
        onDismissRequest = {},
        title = { Text(if (failed) "The microphone isn't free" else "Recording") },
        text = {
            if (failed) {
                Text("Another app may be using it. Try again in a moment.")
            } else {
                val s = elapsed / 1000
                Text(
                    "%d:%02d".format(s / 60, s % 60),
                    style = MaterialTheme.typography.displaySmall.copy(fontFamily = Mono, color = paper.blue),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { contentDescription = "Recorded ${s / 60} minutes ${s % 60} seconds" },
                )
            }
        },
        confirmButton = { if (!failed) GoButton("Stop", ::stop) },
        dismissButton = { QuietButton(if (failed) "OK" else "Cancel", { recorder.cancel(); onDone() }, color = paper.ink2) },
    )
}

/** The draft's files, each with a way to take it off. */
@Composable
fun AttachmentStrip(model: AppModel) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(model.draftFiles, key = { it.id }) { file -> Attachment(file, model) }
    }
}

@Composable
private fun Attachment(file: QueuedFile, model: AppModel) {
    val thumbnail = model.thumbnails[file.id]
    Box(Modifier.size(72.dp).semantics(mergeDescendants = true) { contentDescription = file.name }) {
        if (thumbnail != null) {
            Image(
                thumbnail.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)),
            )
        } else {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp), modifier = Modifier.size(72.dp)) {
                Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (file.isRecording) "♪" else "PDF", style = MaterialTheme.typography.titleMedium)
                    Text(file.name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(2.dp)
                .size(28.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center,
        ) {
            TextButton(
                onClick = { model.remove(file) },
                modifier = Modifier.size(28.dp).semantics { contentDescription = "Take off ${file.name}" },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            ) { Text("✕", color = Color.White) }
        }
    }
}
