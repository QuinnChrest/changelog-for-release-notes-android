package com.yourversionnumber.changelog.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yourversionnumber.changelog.AppModel
import com.yourversionnumber.changelog.R
import com.yourversionnumber.changelog.kit.NoteDetail
import com.yourversionnumber.changelog.kit.NoteMedia
import com.yourversionnumber.changelog.kit.UpcomingDraft
import com.yourversionnumber.changelog.say
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val dayline = DateTimeFormatter.ofPattern("EEEE, MMMM d")
private val clock = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

/** One file plays at a time; a second tap, another file or leaving the screen stops it. */
@Stable
internal class FilePlayer {
    var playing by mutableStateOf<String?>(null)
        private set
    private var player: MediaPlayer? = null

    fun toggle(key: String, url: String) {
        val was = playing
        stop()
        if (was == key) return
        playing = key
        player = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).setUsage(AudioAttributes.USAGE_MEDIA).build())
            setOnPreparedListener { start() }
            setOnCompletionListener { stop() }
            setOnErrorListener { _, _, _ -> stop(); true }
            try {
                setDataSource(url)
                prepareAsync()
            } catch (e: Exception) {
                stop()
            }
        }
    }

    fun stop() {
        player?.release()
        player = null
        playing = null
    }
}

@Composable
internal fun rememberFilePlayer(): FilePlayer {
    val player = remember { FilePlayer() }
    DisposableEffect(player) { onDispose { player.stop() } }
    return player
}

/** The day Log shows, with a way a day back, or on, and today again. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DayHeading(model: AppModel) {
    val label = if (model.isToday) say(R.string.today) else model.day?.let { UpcomingDraft.parse(it)?.format(dayline) } ?: " "
    Column(Modifier.padding(top = 16.dp)) {
        Heading(label)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            QuietButton(say(R.string.earlier), { model.stepDay(-1) })
            if (!model.isToday) {
                QuietButton(say(R.string.later), { model.stepDay(1) })
                QuietButton(say(R.string.today), model::showToday)
            }
        }
    }
}

/** One note of the day: its words, its files, where it came from, and Edit and Delete where this token may. */
@Composable
internal fun DayNote(model: AppModel, note: NoteDetail, player: FilePlayer, onEdit: () -> Unit, onDelete: () -> Unit) {
    Column {
        if (note.text.isNotBlank()) {
            Text(note.text, maxLines = 12, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
        }
        if (note.media.isNotEmpty()) NoteFiles(model, note, player)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Caption(sourceLine(note))
                if (note.version != null && !model.isToday) {
                    Caption(" · ")
                    VersionText(note.version, size = 13.sp)
                }
            }
            if (model.canChange(note)) {
                QuietButton(say(R.string.edit), onEdit)
                QuietButton(say(R.string.delete), onDelete)
            }
        }
        Rule()
    }
}

/** The time it was written, and where it came from. */
private fun sourceLine(note: NoteDetail): String {
    val time = note.writtenAt?.let {
        runCatching { OffsetDateTime.parse(it).atZoneSameInstant(ZoneId.systemDefault()).format(clock) }.getOrNull()
    }
    val from = when {
        note.mine -> say(R.string.from_this_phone)
        note.source == "email" -> say(R.string.from_email)
        note.source == "web" -> say(R.string.from_the_web)
        note.token != null -> say(R.string.via_name, note.token!!)
        else -> note.source.orEmpty()
    }
    return if (time == null) from else "$time · $from"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NoteFiles(model: AppModel, note: NoteDetail, player: FilePlayer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // A signed address lasts ten minutes and is never kept: it's asked for as a file is opened or played.
    fun address(file: NoteMedia, then: (String) -> Unit) {
        scope.launch { model.fileAddress(note, file.n, thumb = false)?.let { then(model.absolute(it)) } }
    }
    // A photo opens in the app, to zoom and look at; anything else goes to what the phone opens it with.
    var viewing by remember { mutableStateOf<NoteMedia?>(null) }
    fun open(file: NoteMedia) = address(file) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    FlowRow(
        Modifier.padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        note.media.forEach { file ->
            val key = "${note.id}/${file.n}"
            when {
                file.kind == "image" && file.thumb != null -> Thumbnail(model, note, file) { viewing = file }
                file.kind == "image" -> Row(verticalAlignment = Alignment.CenterVertically) {
                    QuietButton(say(R.string.open), { viewing = file })
                    Caption(fileLabel(file))
                }
                file.kind == "audio" -> Row(verticalAlignment = Alignment.CenterVertically) {
                    val on = player.playing == key
                    QuietButton(if (on) say(R.string.stop) else say(R.string.play), { address(file) { player.toggle(key, it) } })
                    Caption(fileLabel(file))
                }
                else -> Row(verticalAlignment = Alignment.CenterVertically) {
                    QuietButton(say(R.string.open), { open(file) })
                    Caption(fileLabel(file))
                }
            }
        }
    }
    viewing?.let { file -> PhotoViewer(model, note, file, onClose = { viewing = null }, onOpenOutside = { open(file) }) }
}

private fun fileLabel(file: NoteMedia): String {
    val what = file.name?.takeIf { it.isNotBlank() } ?: when (file.kind) {
        "audio" -> say(R.string.voice_memo)
        "image" -> say(R.string.photo)
        else -> say(R.string.file)
    }
    val size = when {
        file.size >= 1_000_000 -> "%.1f MB".format(file.size / 1_000_000.0)
        file.size >= 1_000 -> "${file.size / 1_000} KB"
        else -> null
    }
    return if (size == null) what else "$what · $size"
}

/** A photo's small copy, read when it scrolls in and kept for the session; tap for the photo itself. */
@Composable
private fun Thumbnail(model: AppModel, note: NoteDetail, file: NoteMedia, onClick: () -> Unit) {
    val key = "${note.id}/${file.n}"
    val bitmap by produceState(Thumbs[key], key) {
        if (value == null) {
            value = model.thumbnail(note, file.n)?.let { bytes ->
                withContext(Dispatchers.Default) {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }
            }?.also { Thumbs[key] = it }
        }
    }
    val shape = RoundedCornerShape(6.dp)
    val description = say(R.string.photo)
    // As wide as the photo is, within reason, 112dp tall; a square while it loads.
    val ratio = ((file.thumb?.width ?: 1).toFloat() / (file.thumb?.height ?: 1).coerceAtLeast(1)).coerceIn(0.5f, 2f)
    val modifier = Modifier.size(width = (112f * ratio).dp, height = 112.dp).clip(shape).clickable(onClick = onClick)
        .semantics { contentDescription = description }
    bitmap?.let { Image(it, null, modifier, contentScale = ContentScale.Crop) } ?: Box(modifier)
}

/** The small copies seen this session, so scrolling back doesn't ask again. */
private object Thumbs {
    private val cache = object : android.util.LruCache<String, ImageBitmap>(48) {}
    operator fun get(key: String): ImageBitmap? = cache.get(key)
    operator fun set(key: String, value: ImageBitmap) {
        cache.put(key, value)
    }
}
