package com.yourversionnumber.changelog.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.semantics.Role
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
import kotlin.math.abs

internal val dayline = DateTimeFormatter.ofPattern("EEEE, MMMM d")
private val clock = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

/**
 * One recording plays at a time. Its own button pauses and plays it on; at its end it pauses back at
 * the start, ready to play again. Another file or leaving the screen lets it go. The server keeps no
 * length, so it's known once loaded.
 */
@Stable
internal class FilePlayer {
    /** The file loaded, playing or paused. */
    var current by mutableStateOf<String?>(null)
        private set
    var paused by mutableStateOf(false)
        private set
    /** Its length in milliseconds once it's loaded; 0 until then. */
    var duration by mutableStateOf(0)
        private set
    private var player: MediaPlayer? = null
    private var ready = false
    /** Where a seek is going, until it's there: the player reports where it was until then. */
    private var seekingTo: Int? = null
    /** When the last seek was asked for: the media clock's readings from before it are of the old place. */
    private var seekedAt = 0L

    fun isPlaying(key: String) = current == key && !paused

    /** Starts a file from its signed address, `from` a fraction of the way in. */
    fun play(key: String, url: String, from: Float = 0f) {
        stop()
        current = key
        player = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).setUsage(AudioAttributes.USAGE_MEDIA).build())
            setOnPreparedListener {
                ready = true
                this@FilePlayer.duration = it.duration.coerceAtLeast(0)
                if (from > 0f) seek((from * this@FilePlayer.duration).toInt())
                it.start()
            }
            setOnSeekCompleteListener { seekingTo = null }
            // At the end: paused, back at the start, still loaded, so play starts it again.
            setOnCompletionListener {
                this@FilePlayer.paused = true
                seek(0)
            }
            setOnErrorListener { _, _, _ -> stop(); true }
            try {
                setDataSource(url)
                prepareAsync()
            } catch (e: Exception) {
                stop()
            }
        }
    }

    /** Pauses the file loaded, or plays it on. */
    fun pauseOrResume() {
        val p = player ?: return
        if (!ready) return
        if (paused) p.start() else p.pause()
        paused = !paused
    }

    /**
     * Where the file loaded is, in milliseconds: from the player's media clock where the phone keeps one
     * apart from its position (on a Pixel they're the same), otherwise its position.
     */
    fun position(): Int {
        if (!ready) return 0
        seekingTo?.let { return it }
        val p = player ?: return 0
        return runCatching {
            val clock = p.timestamp
            if (clock != null && clock.anchorSystemNanoTime > seekedAt) {
                val sinceUs = (System.nanoTime() - clock.anchorSystemNanoTime) / 1000 * clock.mediaClockRate
                ((clock.anchorMediaTimeUs + sinceUs.toLong()) / 1000).toInt()
            } else {
                p.currentPosition
            }
        }.getOrDefault(0).coerceIn(0, duration.coerceAtLeast(0))
    }

    fun seek(to: Int) {
        if (!ready) return
        val at = to.coerceIn(0, duration)
        seekingTo = at
        seekedAt = System.nanoTime()
        player?.seekTo(at)
    }

    fun stop() {
        player?.release()
        player = null
        ready = false
        seekingTo = null
        current = null
        paused = false
        duration = 0
    }
}

@Composable
internal fun rememberFilePlayer(): FilePlayer {
    val player = remember { FilePlayer() }
    DisposableEffect(player) { onDispose { player.stop() } }
    return player
}

/** One note of the day: its words, its files and where it came from. Tap it to open it, to edit or delete. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DayNote(model: AppModel, note: NoteDetail, player: FilePlayer, showVersion: Boolean = false, onOpen: () -> Unit) {
    Column(Modifier.clickable(onClickLabel = say(R.string.open), role = Role.Button, onClick = onOpen)) {
        if (note.text.isNotBlank()) {
            Text(note.text, maxLines = 12, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
        }
        if (note.media.isNotEmpty()) NoteFiles(model, note, player)
        // A line that's too long moves the version down whole, never breaks it.
        val version = note.version.takeIf { showVersion }
        FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.Center) {
            Caption(if (version != null) sourceLine(note) + " ·" else sourceLine(note))
            if (version != null) VersionText(version, size = 13.sp)
        }
        Rule(Modifier.padding(top = 6.dp))
    }
}

/** The time it was written, and where it came from. */
private fun sourceLine(note: NoteDetail): String {
    val time = noteTime(note)
    val from = noteSource(note)
    return if (time == null) from else "$time · $from"
}

/** The time it was written, in the phone's zone. */
internal fun noteTime(note: NoteDetail): String? = note.writtenAt?.let {
    runCatching { OffsetDateTime.parse(it).atZoneSameInstant(ZoneId.systemDefault()).format(clock) }.getOrNull()
}

/** Where it came from. */
internal fun noteSource(note: NoteDetail): String {
    return when {
        note.mine -> say(R.string.from_this_phone)
        note.source == "email" -> say(R.string.from_email)
        note.source == "web" -> say(R.string.from_the_web)
        note.token != null -> say(R.string.via_name, note.token!!)
        else -> note.source.orEmpty()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NoteFiles(model: AppModel, note: NoteDetail, player: FilePlayer, whole: Boolean = false) {
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
    val (recordings, others) = note.media.partition { it.kind == "audio" }
    // Each recording on its own line, wide enough to scrub; photos and other files flow after.
    recordings.forEach { file ->
        val key = "${note.id}/${file.n}"
        VoiceMemo(player, key, fileLabel(file), Modifier.padding(top = 8.dp)) { from ->
            address(file) { player.play(key, it, from) }
        }
        Transcript(file, whole)
    }
    if (others.isNotEmpty()) FlowRow(
        Modifier.padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        others.forEach { file ->
            when {
                file.kind == "image" && file.thumb != null -> Thumbnail(model, note, file) { viewing = file }
                file.kind == "image" -> Row(verticalAlignment = Alignment.CenterVertically) {
                    QuietButton(say(R.string.open), { viewing = file })
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

/**
 * A recording's words under it, once Release Notes has kept them (the owner's opt-in transcript), or word that
 * they're on their way. `whole` on the note's own page, where they can be selected; on Log, the first lines.
 */
@Composable
private fun Transcript(file: NoteMedia, whole: Boolean) {
    val words = file.transcript
    when {
        words != null && whole -> SelectionContainer {
            Text(words, Modifier.padding(top = 4.dp, start = 50.dp), style = MaterialTheme.typography.bodyLarge)
        }
        words != null -> Text(
            words,
            Modifier.padding(top = 4.dp, start = 50.dp),
            maxLines = 6,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyLarge,
        )
        file.writing -> Caption(say(R.string.its_words_are_still_being), Modifier.padding(top = 2.dp, start = 50.dp))
    }
}

/**
 * A recording: play and pause, and a line to scrub along, tangerine as far as it's played.
 * Before it's loaded the line is still; its length comes once the phone has it.
 */
@Composable
private fun VoiceMemo(player: FilePlayer, key: String, label: String, modifier: Modifier = Modifier, onStart: (from: Float) -> Unit) {
    val loaded = player.current == key
    val playing = player.isPlaying(key)
    val duration = if (loaded) player.duration else 0
    var position by remember(key) { mutableStateOf(0) }
    // Where it is, every frame while it plays. A phone's player can count ahead as sound starts and then
    // set itself back once the sound reaches the speaker (a Pixel: about 350 ms, 0.6 s in); followed as
    // read, the dot jumps back, and held from going back, it stops. So the dot runs at its own speed,
    // between half and one and a half times, steered by how far it is from the player: a correction is
    // a slowing, never a jump or a stop. Only a jump of more than a second (a seek) is followed at once.
    var seeks by remember(key) { mutableStateOf(0) }
    LaunchedEffect(loaded, playing, duration, seeks) {
        position = if (loaded) player.position() else 0
        if (!playing) return@LaunchedEffect
        var shown = position.toFloat()
        val startedAt = player.position()
        var moving = false
        var last = withFrameMillis { it }
        while (true) {
            val now = withFrameMillis { it }
            val dt = (now - last).toFloat()
            last = now
            val read = player.position()
            if (!moving && read != startedAt) moving = true
            val off = read - shown
            shown = when {
                abs(off) > 1000f -> read.toFloat()
                moving -> shown + dt * (1f + off / 400f).coerceIn(0.5f, 1.5f)
                else -> shown
            }
            position = shown.toInt().coerceIn(0, duration)
        }
    }
    var dragging by remember(key) { mutableStateOf<Float?>(null) }
    val fraction = dragging ?: if (duration > 0) position.toFloat() / duration else 0f
    val description = say(R.string.place_in_the_recording)
    val p = paper
    // A place on the line: there at once when it's loaded; otherwise it starts playing from there.
    fun goTo(at: Float) {
        if (loaded && duration > 0) {
            position = (at * duration).toInt()
            player.seek(position)
            seeks++
        } else {
            onStart(at)
        }
    }

    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button) { if (loaded) player.pauseOrResume() else onStart(0f) }
                .semantics { contentDescription = if (playing) say(R.string.pause) else say(R.string.play) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play), null, Modifier.size(24.dp), tint = p.blue)
        }
        Column(Modifier.weight(1f).padding(start = 6.dp)) {
            fun at(x: Float, width: Int) = (x / width.coerceAtLeast(1)).coerceIn(0f, 1f)
            val go by rememberUpdatedState<(Float) -> Unit> { goTo(it) }
            Canvas(
                Modifier
                    .fillMaxWidth()
                    // Taller than the line, so a finger finds it.
                    .height(40.dp)
                    // A tap anywhere on the line goes there; a slide along it follows the finger and goes
                    // where it's let go. A slide up or down is the list's, to scroll.
                    .pointerInput(key) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            down.consume()
                            val slop = viewConfiguration.touchSlop
                            var moved = Offset.Zero
                            var sliding = false
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    change.consume()
                                    go(at(change.position.x, size.width))
                                    break
                                }
                                moved += change.positionChange()
                                if (!sliding) {
                                    if (abs(moved.y) > slop && abs(moved.y) > abs(moved.x)) break
                                    if (abs(moved.x) > slop) sliding = true
                                }
                                if (sliding) {
                                    change.consume()
                                    dragging = at(change.position.x, size.width)
                                }
                            }
                            dragging = null
                        }
                    }
                    .semantics {
                        contentDescription = description
                        progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                        setProgress { go(it.coerceIn(0f, 1f)); true }
                    },
            ) {
                val y = size.height / 2
                val line = 2.dp.toPx()
                drawLine(p.rule, Offset(0f, y), Offset(size.width, y), line, StrokeCap.Round)
                if (loaded || dragging != null) {
                    val x = size.width * fraction
                    drawLine(p.orange, Offset(0f, y), Offset(x, y), line, StrokeCap.Round)
                    drawCircle(p.orange, (if (dragging != null) 7 else 5).dp.toPx(), Offset(x, y))
                }
            }
            Row {
                Caption(label, Modifier.weight(1f))
                if (duration > 0) Caption("${clockOf(if (dragging != null) (fraction * duration).toInt() else position)} / ${clockOf(duration)}")
            }
        }
    }
}

/** Milliseconds as m:ss, or h:mm:ss for an hour or more. */
private fun clockOf(ms: Int): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

internal fun fileLabel(file: NoteMedia): String {
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
