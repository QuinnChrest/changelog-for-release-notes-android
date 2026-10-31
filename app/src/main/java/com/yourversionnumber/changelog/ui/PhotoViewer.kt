package com.yourversionnumber.changelog.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yourversionnumber.changelog.AppModel
import com.yourversionnumber.changelog.R
import com.yourversionnumber.changelog.kit.NoteDetail
import com.yourversionnumber.changelog.kit.NoteMedia
import com.yourversionnumber.changelog.say
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The longest side a photo is decoded to: a phone screen can't show more, and a full original could be too big to hold. */
private const val LONGEST_SIDE = 2560

private const val MAX_ZOOM = 6f

/**
 * A photo of a note, full screen over the app: pinch to zoom, drag to move
 * once zoomed, double-tap to zoom in and out. The photo is read from its
 * signed address as this opens and kept only while it's showing.
 */
@Composable
internal fun PhotoViewer(model: AppModel, note: NoteDetail, file: NoteMedia, onClose: () -> Unit, onOpenOutside: () -> Unit) {
    // The first is true until the bytes are read; the photo is null if the phone can't decode it.
    val photo by produceState<Pair<Boolean, ImageBitmap?>>(true to null, note.id, file.n) {
        val bytes = model.photo(note, file.n)
        value = false to bytes?.let { decode(it) }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // Over black, in the reverse palette so the words read.
        CompositionLocalProvider(LocalPaper provides paper.inverse) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                val (loading, bitmap) = photo
                when {
                    loading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = paper.orange)
                    bitmap != null -> ZoomableImage(bitmap, file.name ?: say(R.string.photo))
                    else -> Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(say(R.string.cant_show_this_photo), color = paper.ink)
                        QuietButton(say(R.string.open), { onClose(); onOpenOutside() })
                    }
                }
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    // Over a bright photo the word needs something dark behind it.
                    QuietButton(
                        say(R.string.close),
                        onClose,
                        Modifier.background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50)).padding(horizontal = 8.dp),
                        color = paper.ink,
                    )
                }
            }
        }
    }
}

@Composable
private fun ZoomableImage(bitmap: ImageBitmap, description: String) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    // Pans stop at the photo's edge: a zoomed photo can move by half of what it has grown by.
    fun clamp(o: Offset, s: Float): Offset {
        val x = size.width * (s - 1f) / 2f
        val y = size.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-x, x), o.y.coerceIn(-y, y))
    }

    LaunchedEffect(bitmap) {
        scale = 1f
        offset = Offset.Zero
    }
    Image(
        bitmap,
        description,
        Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val next = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                    // The point under the fingers stays under them as it grows, then the photo follows them.
                    val about = centroid - Offset(size.width / 2f, size.height / 2f)
                    val moved = about * (1f - next / scale) + offset * (next / scale) + pan
                    scale = next
                    offset = if (next == 1f) Offset.Zero else clamp(moved, next)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { at ->
                    if (scale > 1f) {
                        scale = 1f
                        offset = Offset.Zero
                    } else {
                        val about = at - Offset(size.width / 2f, size.height / 2f)
                        scale = 3f
                        offset = clamp(about * (1f - 3f), 3f)
                    }
                })
            }
            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y)
            .semantics { contentDescription = description },
        contentScale = ContentScale.Fit,
    )
}

/** The photo, sampled down so its longest side is no more than [LONGEST_SIDE]; null when the phone can't read it. */
private suspend fun decode(bytes: ByteArray): ImageBitmap? = withContext(Dispatchers.Default) {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= LONGEST_SIDE) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap() }.getOrNull()
}
