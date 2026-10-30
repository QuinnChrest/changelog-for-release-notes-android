package com.yourversionnumber.changelog.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.yourversionnumber.changelog.AppModel
import com.yourversionnumber.changelog.Locator
import com.yourversionnumber.changelog.R
import com.yourversionnumber.changelog.Recorder
import com.yourversionnumber.changelog.kit.QueuedFile
import com.yourversionnumber.changelog.say
import java.io.File
import kotlinx.coroutines.delay

/** Photos, the camera and a voice memo (for a token that can add files), and Here, as icons, with Commit at the right. */
@OptIn(ExperimentalLayoutApi::class)
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
    val whereAbouts = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) model.locate()
        else model.graph.report(say(R.string.changelog_cant_add_where_you))
    }
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) recording = true else model.graph.report(say(R.string.changelog_cant_record_without_the))
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        if (model.canAddFiles) FileTools(model, room, context, photos, camera, { cameraFile = it }, microphone, { recording = true })
        if (model.locating) {
            Box(Modifier.size(48.dp).semantics { contentDescription = say(R.string.finding) }, contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(22.dp), color = paper.orange, strokeWidth = 2.dp)
            }
        } else {
            ToolIcon(
                R.drawable.ic_here,
                if (model.placeLabel == null) say(R.string.add_where_i_am) else say(R.string.find_where_i_am_again),
                {
                    if (Locator.allowed(context)) model.locate()
                    else whereAbouts.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
                },
            )
        }
        Spacer(Modifier.weight(1f))
        GoButton(say(R.string.commit), model::commit, enabled = model.canCommit)
    }

    if (recording) RecorderDialog(model, onDone = { recording = false })
}

/** Photos, the camera and a voice memo, for a token that can add files. */
@Composable
private fun FileTools(
    model: AppModel,
    room: Int,
    context: android.content.Context,
    photos: androidx.activity.result.ActivityResultLauncher<PickVisualMediaRequest>,
    camera: androidx.activity.result.ActivityResultLauncher<android.net.Uri>,
    setCameraFile: (String) -> Unit,
    microphone: androidx.activity.result.ActivityResultLauncher<String>,
    record: () -> Unit,
) {
    ToolIcon(
        R.drawable.ic_photos,
        say(R.string.photos),
        { photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        enabled = room > 0,
    )
    ToolIcon(
        R.drawable.ic_camera,
        say(R.string.camera),
        {
            val file = model.scratch("jpg")
            setCameraFile(file.path)
            camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.files", file))
        },
        enabled = room > 0,
    )
    ToolIcon(
        R.drawable.ic_mic,
        say(R.string.record_a_voice_memo),
        {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                record()
            } else {
                microphone.launch(Manifest.permission.RECORD_AUDIO)
            }
        },
        enabled = room > 0,
    )
}

/** One tool of the composer: an outline icon in blue, a 48dp target, named for TalkBack. */
@Composable
private fun ToolIcon(@DrawableRes icon: Int, description: String, onClick: () -> Unit, enabled: Boolean = true) {
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            Modifier.size(26.dp),
            tint = if (enabled) paper.blue else paper.ink3.copy(alpha = 0.5f),
        )
    }
}

/** Records while open: Stop puts the memo on the note, Cancel throws it away. */
@Composable
private fun RecorderDialog(model: AppModel, onDone: () -> Unit) {
    val context = LocalContext.current
    val recorder = remember { Recorder(context, model.scratch("m4a")) }
    var failed by remember { mutableStateOf(false) }
    var elapsed by remember { mutableLongStateOf(0L) }

    fun stop() {
        recorder.stop()?.let(model::addRecording) ?: model.graph.report(say(R.string.that_recording_didnt_work_try))
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
        title = { Text(if (failed) say(R.string.the_microphone_isnt_free) else say(R.string.recording)) },
        text = {
            if (failed) {
                Text(say(R.string.another_app_may_be_using))
            } else {
                val s = elapsed / 1000
                Text(
                    "%d:%02d".format(s / 60, s % 60),
                    style = MaterialTheme.typography.displaySmall.copy(fontFamily = Mono, color = paper.blue),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { contentDescription = say(R.string.recorded_minutes_seconds, s / 60, s % 60) },
                )
            }
        },
        confirmButton = { if (!failed) GoButton(say(R.string.stop), ::stop) },
        dismissButton = { QuietButton(if (failed) say(R.string.ok) else say(R.string.cancel), { recorder.cancel(); onDone() }, color = paper.ink2) },
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
                modifier = Modifier.size(28.dp).semantics { contentDescription = say(R.string.take_off, file.name) },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            ) { Text("✕", color = Color.White) }
        }
    }
}

/**
 * Where the note was written: the neighbourhood or city Here named, a
 * business nearby to pick (never picked for the owner), or none.
 */
@Composable
fun PlaceLine(model: AppModel) {
    val label = model.placeLabel ?: return
    var open by remember { mutableStateOf(false) }
    val here = model.here
    Box {
        Select(say(R.string.place), label, { open = true }, Modifier.fillMaxWidth())
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (here != null) {
                val place = here.place
                DropdownMenuItem(
                    text = { Text(place.district?.let { "$it, ${place.name}" } ?: place.name) },
                    onClick = { model.pick(null); open = false },
                )
                here.nearby.forEach { n ->
                    DropdownMenuItem(
                        text = { Text("${n.name} · ${n.distanceM.toInt()} m") },
                        onClick = { model.pick(n.name); open = false },
                    )
                }
            }
            DropdownMenuItem(text = { Text(say(R.string.leave_the_place_off), color = paper.orangeInk) }, onClick = { model.clearPlace(); open = false })
        }
    }
}
