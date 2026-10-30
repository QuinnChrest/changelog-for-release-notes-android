package com.yourversionnumber.changelog.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.yourversionnumber.changelog.R
import com.yourversionnumber.changelog.say

// Release Notes' own look (web/src/styles/site.css in yvn-release-notes):
// type on paper, the email's colours, light then dark. No boxes and no
// shadows: rules under fields and the tangerine button are the only marks.
// Versions are Martian Mono in cobalt with tangerine dots.

/** The site's colours, named as site.css names them. */
@Immutable
data class Paper(
    val paper: Color,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    val blue: Color,
    val orange: Color,
    val orangeInk: Color,
    val onOrange: Color,
    val rule: Color,
    val dotOff: Color,
)

private val Light = Paper(
    paper = Color(0xFFFFFBF2),
    ink = Color(0xFF14163A),
    ink2 = Color(0xFF3B3D63),
    ink3 = Color(0xFF6E7092),
    blue = Color(0xFF1A4FE0),
    orange = Color(0xFFFF5A1F),
    orangeInk = Color(0xFFC43C00),
    onOrange = Color(0xFF14163A),
    rule = Color(0x1F14163A),
    dotOff = Color(0x2914163A),
)

private val Dark = Paper(
    paper = Color(0xFF14163A),
    ink = Color(0xFFFFFBF2),
    ink2 = Color(0xFFC9CBE6),
    ink3 = Color(0xFF8A8CA8),
    blue = Color(0xFF8FB0FF),
    orange = Color(0xFFFF5A1F),
    orangeInk = Color(0xFFFF8A5C),
    onOrange = Color(0xFF14163A),
    rule = Color(0x29FFFBF2),
    dotOff = Color(0x2EFFFBF2),
)

val LocalPaper = staticCompositionLocalOf { Light }

/** The other palette, for a small thing drawn in reverse (ink paper, paper ink). */
val Paper.inverse: Paper get() = if (this == Light) Dark else Light

/** The colours in use, light or dark. */
val paper: Paper @Composable get() = LocalPaper.current

private fun weights(res: Int) = FontFamily(
    listOf(300, 400, 500, 600, 700, 800).map {
        Font(res, FontWeight(it), variationSettings = FontVariation.Settings(FontVariation.weight(it)))
    },
)

/** Bricolage Grotesque: the text and headings. */
val Grotesque = weights(R.font.bricolage_grotesque)

/** Martian Mono: versions. */
val Mono = weights(R.font.martian_mono)

private fun scheme(p: Paper, dark: Boolean): ColorScheme {
    val tint = p.blue.copy(alpha = 0.10f)
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = p.blue,
        onPrimary = p.paper,
        primaryContainer = tint,
        onPrimaryContainer = p.ink,
        secondary = p.orange,
        onSecondary = p.onOrange,
        secondaryContainer = tint,
        onSecondaryContainer = p.ink,
        tertiary = p.orange,
        onTertiary = p.onOrange,
        background = p.paper,
        onBackground = p.ink,
        surface = p.paper,
        onSurface = p.ink,
        surfaceVariant = p.rule,
        onSurfaceVariant = p.ink2,
        surfaceTint = Color.Transparent,
        surfaceBright = p.paper,
        surfaceDim = p.paper,
        surfaceContainerLowest = p.paper,
        surfaceContainerLow = p.paper,
        surfaceContainer = p.paper,
        surfaceContainerHigh = p.paper,
        surfaceContainerHighest = p.paper,
        inverseSurface = p.ink,
        inverseOnSurface = p.paper,
        outline = p.ink,
        outlineVariant = p.rule,
        error = p.orangeInk,
        onError = p.paper,
        errorContainer = p.orange.copy(alpha = 0.12f),
        onErrorContainer = p.ink,
        scrim = Color(0x9914163A),
    )
}

private val type = Typography().let { t ->
    fun TextStyle.g(size: Int, weight: Int, line: Double = 1.4, track: Double = 0.0) = copy(
        fontFamily = Grotesque,
        fontSize = size.sp,
        fontWeight = FontWeight(weight),
        lineHeight = (size * line).sp,
        letterSpacing = (size * track).sp,
    )
    Typography(
        displayLarge = t.displayLarge.g(56, 800, 1.05, -0.015),
        displayMedium = t.displayMedium.g(44, 800, 1.05, -0.015),
        displaySmall = t.displaySmall.g(36, 800, 1.08, -0.01),
        headlineLarge = t.headlineLarge.g(34, 800, 1.08, -0.01),
        headlineMedium = t.headlineMedium.g(28, 800, 1.1, -0.015),
        headlineSmall = t.headlineSmall.g(24, 800, 1.15, -0.015),
        titleLarge = t.titleLarge.g(22, 800, 1.2, -0.01),
        titleMedium = t.titleMedium.g(17, 700, 1.35),
        titleSmall = t.titleSmall.g(14, 600, 1.35),
        bodyLarge = t.bodyLarge.g(18, 400, 1.5),
        bodyMedium = t.bodyMedium.g(16, 400, 1.5),
        bodySmall = t.bodySmall.g(13, 400, 1.45),
        labelLarge = t.labelLarge.g(16, 600, 1.3),
        labelMedium = t.labelMedium.g(14, 600, 1.3),
        labelSmall = t.labelSmall.g(12, 600, 1.3),
    )
}

/** Release Notes' paper and ink, light or dark as the phone is. */
@Composable
fun ChangelogTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val p = if (dark) Dark else Light
    CompositionLocalProvider(LocalPaper provides p) {
        MaterialTheme(colorScheme = scheme(p, dark), typography = type, content = content)
    }
}

/** A version as the site writes it: Martian Mono in cobalt, the dots tangerine. */
@Composable
fun VersionText(version: String?, size: TextUnit = 48.sp, modifier: Modifier = Modifier) {
    val p = paper
    val text = buildAnnotatedString {
        if (version == null) {
            withStyle(SpanStyle(color = p.ink3)) { append("?.?.???") }
        } else {
            version.forEach { c -> if (c == '.') withStyle(SpanStyle(color = p.orange)) { append(c) } else append(c) }
        }
    }
    Text(
        text,
        modifier.semantics { contentDescription = version?.let { say(R.string.version_2, it) } ?: say(R.string.version_loading) },
        color = p.blue,
        style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = size, lineHeight = size, letterSpacing = (-0.02).em(size)),
    )
}

private fun Double.em(size: TextUnit) = (size.value * this).sp

/** The tangerine button: the one thing on a screen to do. */
@Composable
fun GoButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val p = paper
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = p.orange,
            contentColor = p.onOrange,
            disabledContainerColor = p.orange.copy(alpha = 0.35f),
            disabledContentColor = p.onOrange.copy(alpha = 0.6f),
        ),
        elevation = null,
    ) { Text(text, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp) }
}

/** A quiet button: blue words, nothing around them. */
@Composable
fun QuietButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = paper.blue) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = color, disabledContentColor = paper.ink3),
    ) { Text(text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp) }
}

/**
 * A choice among a few, as the site's tag picks are: underlined blue words,
 * and the one chosen in ink, bold, without the line.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Pick(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null) {
    val p = paper
    val tap = if (onLongClick == null) {
        Modifier.clickable(role = Role.Checkbox, onClick = onClick)
    } else {
        Modifier.combinedClickable(role = Role.Checkbox, onClick = onClick, onLongClick = onLongClick)
    }
    Text(
        text,
        modifier
            .semantics { this.selected = selected }
            .then(tap)
            .defaultMinSize(minHeight = 40.dp)
            .padding(vertical = 8.dp),
        color = if (selected) p.ink else p.blue,
        style = TextStyle(
            fontFamily = Grotesque,
            fontSize = 16.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            textDecoration = if (selected) TextDecoration.None else TextDecoration.Underline,
        ),
    )
}

/** Colours for a field drawn as the site's are: no box, a rule under it that turns tangerine. */
@Composable
fun fieldColors() = paper.let { p ->
    TextFieldDefaults.colors(
        focusedContainerColor = Color.Transparent,
        unfocusedContainerColor = Color.Transparent,
        disabledContainerColor = Color.Transparent,
        errorContainerColor = Color.Transparent,
        focusedIndicatorColor = p.orange,
        unfocusedIndicatorColor = p.ink,
        focusedLabelColor = p.ink2,
        unfocusedLabelColor = p.ink2,
        focusedPlaceholderColor = p.ink3,
        unfocusedPlaceholderColor = p.ink3,
        cursorColor = p.orange,
        focusedSupportingTextColor = p.ink3,
        unfocusedSupportingTextColor = p.ink3,
    )
}

/** The site's switch: tangerine when on. */
@Composable
fun switchColors() = paper.let { p ->
    SwitchDefaults.colors(
        checkedTrackColor = p.orange,
        checkedThumbColor = p.paper,
        checkedBorderColor = p.orange,
        uncheckedTrackColor = Color.Transparent,
        uncheckedThumbColor = p.ink3,
        uncheckedBorderColor = p.ink3,
    )
}

/** A faint rule between things, the only line the site draws besides a field's. */
@Composable
fun Rule(modifier: Modifier = Modifier) = HorizontalDivider(modifier, thickness = 1.dp, color = paper.rule)

/** A small heading over a part of a screen, in ink-2 as the site's h2. */
@Composable
fun Heading(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.titleSmall, color = paper.ink2)
}

/** Text in the third ink, for what's said about a thing. */
@Composable
fun Caption(text: String, modifier: Modifier = Modifier, color: Color = paper.ink3) {
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = color)
}

/**
 * A text field drawn as the site's: a label above, the words flush with
 * everything else, and a 2dp rule beneath in ink that turns tangerine while
 * it has the keyboard. No box.
 */
@Composable
fun PaperField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    supporting: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val p = paper
    var focused by remember { mutableStateOf(false) }
    Column(modifier) {
        label?.let { Text(it, style = MaterialTheme.typography.titleSmall, color = p.ink2) }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
            singleLine = singleLine,
            minLines = if (singleLine) 1 else minLines,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            visualTransformation = visualTransformation,
            textStyle = textStyle.copy(color = p.ink),
            cursorBrush = SolidColor(p.orange),
            decorationBox = { inner ->
                Column {
                    Box(Modifier.padding(vertical = 8.dp)) {
                        if (value.text.isEmpty() && placeholder != null) Text(placeholder, style = textStyle, color = p.ink3)
                        inner()
                    }
                    Box(Modifier.fillMaxWidth().height(2.dp).background(if (focused) p.orange else p.ink))
                }
            },
        )
        supporting?.let { Caption(it, Modifier.padding(top = 6.dp)) }
    }
}

/** A text field drawn as the site's, for plain words. */
@Composable
fun PaperField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    supporting: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    // Kept as a value with a selection, so typing never jumps the cursor.
    var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    if (field.text != value) field = TextFieldValue(value, TextRange(value.length))
    PaperField(
        field,
        { field = it; if (it.text != value) onValueChange(it.text) },
        modifier, label, placeholder, supporting, singleLine, minLines, keyboardOptions, visualTransformation,
        keyboardActions = keyboardActions,
    )
}


/**
 * The bar along the top, as the site's: Changelog's lines and dots (or a
 * screen's title), and quiet links at the right. No fill and no shadow.
 */
@Composable
fun Bar(
    title: String? = null,
    leading: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = if (leading == null) 24.dp else 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp)
            .defaultMinSize(minHeight = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        if (title == null) {
            Mark(Modifier.size(26.dp))
            Spacer(Modifier.width(8.dp))
            Text(say(R.string.changelog), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold, fontSize = 18.sp))
        } else {
            Text(title, style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.weight(1f))
        actions()
    }
}

/** Changelog's mark: a dot for each release, a line for what changed, in the site's cobalt and tangerine. */
@Composable
fun Mark(modifier: Modifier = Modifier) {
    val p = paper
    Canvas(modifier.semantics { contentDescription = say(R.string.changelog) }) {
        val u = size.minDimension / 24f
        val lines = listOf(14f to 6.5f, 10f to 12f, 12f to 17.5f)
        lines.forEachIndexed { i, (length, y) ->
            drawCircle(if (i == 0) p.orange else p.blue, radius = 1.9f * u, center = Offset(4f * u, y * u))
            drawRoundRect(
                p.blue,
                topLeft = Offset(8f * u, (y - 1.1f) * u),
                size = Size(length * u, 2.2f * u),
                cornerRadius = CornerRadius(1.1f * u),
            )
        }
    }
}

/** A choice drawn as the site's select: a label, the value on a rule, and a sign it opens. */
@Composable
fun Select(label: String, value: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val p = paper
    Column(modifier.clickable(role = Role.DropdownList, onClickLabel = say(R.string.change, label), onClick = onClick)) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = p.ink2)
        Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = p.ink, maxLines = 1)
            Text("▾", color = p.ink3, style = MaterialTheme.typography.bodyLarge)
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(p.ink))
    }
}

/** Words with a version in them, the version written as the site writes it inline: mono, cobalt, tangerine dots. */
@Composable
fun withVersion(text: String, version: String?): androidx.compose.ui.text.AnnotatedString {
    val p = paper
    val at = version?.let { text.indexOf(it) } ?: -1
    return buildAnnotatedString {
        if (at < 0) {
            append(text)
            return@buildAnnotatedString
        }
        append(text.substring(0, at))
        // Martian Mono runs wide: a size smaller than the words around it reads as the same.
        withStyle(SpanStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, color = p.blue, fontSize = 0.82.em, letterSpacing = (-0.04).em)) {
            version!!.forEach { c -> if (c == '.') withStyle(SpanStyle(color = p.orange)) { append(c) } else append(c) }
        }
        append(text.substring(at + version!!.length))
    }
}
