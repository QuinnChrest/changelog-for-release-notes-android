package com.yourversionnumber.changelog.ui

import android.content.Intent
import android.graphics.RectF
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yourversionnumber.changelog.AppModel
import com.yourversionnumber.changelog.R
import com.yourversionnumber.changelog.kit.ApiException
import com.yourversionnumber.changelog.kit.CheckIn
import com.yourversionnumber.changelog.kit.CheckInMap
import com.yourversionnumber.changelog.kit.Hashtags
import com.yourversionnumber.changelog.kit.MapTags
import com.yourversionnumber.changelog.kit.Tag
import com.yourversionnumber.changelog.kit.TagTyping
import com.yourversionnumber.changelog.sayCount
import com.yourversionnumber.changelog.kit.CheckInPin
import com.yourversionnumber.changelog.kit.NotePin
import com.yourversionnumber.changelog.kit.CountPhrase
import com.yourversionnumber.changelog.kit.NoteDetail
import com.yourversionnumber.changelog.kit.NoteMedia
import com.yourversionnumber.changelog.kit.Words
import com.yourversionnumber.changelog.NoteTarget
import com.yourversionnumber.changelog.kit.CheckInWords
import com.yourversionnumber.changelog.say
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.BackgroundLayer
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/**
 * Everywhere you've stopped, and the notes written somewhere, picked at the top (notes need a token that reads every
 * note, `GET /v1/map/pins`). A stop is a cobalt dot, notes a cobalt ring; as the iPhone app's map, a pin for each place (a remembered place, or one name
 * within 100 m), opening its stops. Check-ins only, read whole (`GET /v1/check-ins`) and held in memory. The
 * map is MapLibre over OpenFreeMap's vector tiles (OpenStreetMap's data, no key), recoloured in the app's
 * own paper: the land is the paper, the roads its rules, water a wash of cobalt, place names in ink and road names
 * in lighter ink; route shields left off. Pins are cobalt dots ringed in paper, and the chosen one tangerine. OpenFreeMap's servers
 * see which tiles are shown: roughly where the pins are.
 */
@Composable
internal fun CheckInMapScreen(model: AppModel, onClose: () -> Unit) {
    // Read as it opens, and again after anything sends (a check-in from here, a stop from the background).
    val sends by model.graph.sends.collectAsStateWithLifecycle()
    LaunchedEffect(sends) { model.loadCheckIns() }
    LaunchedEffect(model.readsNotes) { model.loadNotePins() }
    var showStops by rememberSaveable { mutableStateOf(true) }
    var showNotes by rememberSaveable { mutableStateOf(true) }
    // The tag picked (call 8): it keeps the notes and the check-ins with it. Memory only.
    var tagPicked by remember { mutableStateOf<Tag?>(null) }
    val key = tagPicked?.key
    val tagged = remember(model.checkIns, key) { MapTags.stays(model.checkIns.orEmpty(), key) }
    val taggedNotes = remember(model.notePins, key) { MapTags.notes(model.notePins.orEmpty(), key) }
    val stops = remember(tagged) { CheckInMap.pins(tagged) }
    val notes = remember(taggedNotes) { NotePin.pins(taggedNotes) }
    val spots = remember(stops, notes, showStops, showNotes) {
        (if (showStops) stops.map { Spot(STOP + it.id, it.lat, it.lon, note = false) } else emptyList()) +
            (if (showNotes && model.readsNotes) notes.map { Spot(NOTE + it.id, it.lat, it.lon, note = true) } else emptyList())
    }
    var chosen by remember { mutableStateOf<String?>(null) }
    var opened by remember { mutableStateOf<NoteTarget?>(null) }
    val player = rememberFilePlayer()

    Dialog(
        onDismissRequest = { if (chosen != null) chosen = null else onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
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
        Column(Modifier.fillMaxSize().background(paper.paper)) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                QuietButton("‹ " + say(R.string.back), onClose)
                Spacer(Modifier.weight(1f))
                if (model.canCheckIn) {
                    QuietButton(
                        if (model.checkingIn) say(R.string.checking_in) else say(R.string.check_in),
                        model::checkInNow,
                        enabled = !model.checkingIn,
                    )
                }
            }
            // What the map shows: the places you stopped, the notes written somewhere, or both, and the tag.
            Row(
                Modifier.padding(start = 24.dp, end = 24.dp, bottom = if (tagPicked == null) 8.dp else 0.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Pick(say(R.string.check_ins), selected = showStops, onClick = { showStops = !showStops; chosen = null })
                if (model.readsNotes) Pick(say(R.string.notes_pick), selected = showNotes, onClick = { showNotes = !showNotes; chosen = null })
                val tags = remember(model.checkIns, model.notePins, model.readsNotes) {
                    MapTags.tags(model.checkIns.orEmpty(), if (model.readsNotes) model.notePins.orEmpty() else emptyList())
                }
                TagMenu(tags, tagPicked) { tagPicked = it; chosen = null }
            }
            tagPicked?.let { tag ->
                Caption(
                    tagSummary(
                        stays = if (showStops) tagged.size else null,
                        notes = if (showNotes && model.readsNotes) taggedNotes.size else null,
                        name = tag.name,
                    ),
                    Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                    color = paper.ink2,
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                PinMap(spots, chosen, paper, onPick = { chosen = it })
                // With a tag picked, the line above says what it keeps.
                Status(model, spots.isEmpty() && tagPicked == null, Modifier.align(Alignment.TopCenter))
                // The credit OpenMapTiles and OpenStreetMap require, small and quiet in a corner; it opens
                // OpenStreetMap's copyright page. (OpenFreeMap's own name is optional, so it's left off.)
                val context = LocalContext.current
                Text(
                    say(R.string.osm_credit),
                    Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .padding(6.dp)
                        .clickable { context.startActivity(Intent(Intent.ACTION_VIEW, "https://www.openstreetmap.org/copyright".toUri())) }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    color = paper.ink3,
                    style = MaterialTheme.typography.labelSmall,
                )
                val sheet = Modifier.align(Alignment.BottomCenter)
                when {
                    chosen?.startsWith(STOP) == true -> stops.firstOrNull { STOP + it.id == chosen }?.let { StopsSheet(model, it, sheet) { chosen = null } }
                    chosen?.startsWith(NOTE) == true -> notes.firstOrNull { NOTE + it.id == chosen }?.let { pin ->
                        NotesSheet(model, pin, sheet, onOpen = { opened = it }) { chosen = null }
                    }
                }
            }
        }
        opened?.let { target -> NoteViewer(model, target, player, onClose = { opened = null }) }
    }
}

/**
 * The Tag menu, beside the filters: Every tag, then each tag on what the map has, by name. A tag picked stays listed
 * while nothing has it. Hidden until something has a tag.
 */
@Composable
private fun TagMenu(tags: List<Tag>, picked: Tag?, onPick: (Tag?) -> Unit) {
    val listed = picked?.let { p -> if (tags.any { it.key == p.key }) tags else listOf(p) + tags } ?: tags
    if (listed.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    val on = picked != null
    val p = paper
    val every = say(R.string.every_tag)
    Box {
        Text(
            (picked?.name ?: every) + " ▾",
            Modifier
                .clickable(role = Role.DropdownList, onClickLabel = say(R.string.change, say(R.string.tag))) { open = true }
                .semantics { contentDescription = say(R.string.tag) + ", " + (picked?.name ?: every) }
                .defaultMinSize(minHeight = 48.dp)
                .padding(vertical = 12.dp)
                .drawBehind {
                    if (on) {
                        val y = size.height + 4.dp.toPx()
                        drawLine(p.orange, Offset(0f, y), Offset(size.width, y), 2.dp.toPx())
                    }
                },
            color = if (on) p.ink else p.ink2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFamily = Grotesque, fontSize = 16.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(every) }, onClick = { onPick(null); open = false })
            listed.forEach { tag ->
                DropdownMenuItem(
                    text = { Text(tag.name, fontWeight = if (tag.key == picked?.key) FontWeight.SemiBold else null) },
                    onClick = { onPick(tag); open = false },
                )
            }
        }
    }
}

/** What a tag keeps on the map, as the iPhone's line: "2 check-ins and 1 note tagged Road Trip." */
private fun tagSummary(stays: Int?, notes: Int?, name: String): String {
    fun checkInsSaid(n: Int) = sayCount(R.plurals.check_ins_count, n, n)
    fun notesSaid(n: Int) = sayCount(R.plurals.notes_count, n, n)
    return when {
        stays == null && notes == null -> say(R.string.map_turn_on)
        notes == null -> if (stays == 0) say(R.string.map_no_check_ins_tagged, name) else say(R.string.map_tagged, checkInsSaid(stays!!), name)
        stays == null -> if (notes == 0) say(R.string.map_no_notes_tagged, name) else say(R.string.map_tagged, notesSaid(notes), name)
        stays == 0 && notes == 0 -> say(R.string.map_none_tagged, name)
        else -> say(R.string.map_both_tagged, checkInsSaid(stays), notesSaid(notes), name)
    }
}

/** One pin on the map: a place stopped at, or notes written there. */
private data class Spot(val id: String, val lat: Double, val lon: Double, val note: Boolean)

private const val STOP = "stop:"
private const val NOTE = "note:"

/**
 * The notes written at one place, newest first: the day, the opening words, and its photos' small copies, with Open
 * in blue so each row reads as one to tap. A pin carries only the words, so each note is read as the sheet opens
 * (in memory only, for this sheet), for its files; a note with no words and no photo says what it holds instead.
 */
@Composable
private fun NotesSheet(model: AppModel, pin: NotePin, modifier: Modifier, onOpen: (NoteTarget) -> Unit, onClose: () -> Unit) {
    val details = remember(pin.id) { mutableStateMapOf<String, NoteDetail>() }
    LaunchedEffect(pin.id) {
        pin.notes.forEach { note -> model.readNote(note.date, note.id)?.let { details[note.id] = it } }
    }
    Column(
        modifier
            .fillMaxWidth()
            .heightIn(max = 360.dp)
            .background(paper.paper)
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        Rule()
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Heading(pin.label, Modifier.weight(1f))
            QuietButton(say(R.string.close), onClose)
        }
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            pin.notes.forEach { note ->
                val open = { onOpen(NoteTarget(note.date, note.id, note.version)) }
                val detail = details[note.id]
                val photos = detail?.media.orEmpty().filter { it.kind == "image" && it.thumb != null }
                Column(Modifier.fillMaxWidth().clickable(onClickLabel = say(R.string.open), role = Role.Button, onClick = open)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Caption(dayLabel(note.date, model.me?.today), Modifier.weight(1f))
                        QuietButton(say(R.string.open), open)
                    }
                    if (note.text.isNotBlank()) {
                        Text(note.text, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = paper.ink)
                    }
                    if (photos.isNotEmpty() && detail != null) {
                        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            photos.take(3).forEach { Thumbnail(model, detail, it, height = 72, onClick = null) }
                        }
                    } else if (note.text.isBlank() && detail != null && detail.media.isNotEmpty()) {
                        Text(filesSaid(detail.media), style = MaterialTheme.typography.bodyMedium, color = paper.ink2)
                    }
                    Rule(Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}

/** "1 photo, 1 voice memo": what a note with no words holds. */
private fun filesSaid(media: List<NoteMedia>): String {
    val photos = media.count { it.kind == "image" }
    val memos = media.count { it.kind == "audio" }
    val others = media.size - photos - memos
    fun said(n: Int, phrase: CountPhrase) = if (n == 0) null else Words.count(phrase, n)
    return listOfNotNull(said(photos, CountPhrase.PhotoCount), said(memos, CountPhrase.MemoCount), said(others, CountPhrase.FileCount))
        .joinToString(", ")
}

/** Reading, a problem with Try again, or no check-ins at all, over the top of the map. */
@Composable
private fun Status(model: AppModel, empty: Boolean, modifier: Modifier) {
    val problem = model.checkInsProblem
    val shown = problem != null || empty
    if (!shown) return
    Column(
        modifier.padding(top = 12.dp, start = 16.dp, end = 16.dp).background(paper.paper).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        when {
            problem != null -> {
                Text(problem, color = paper.orangeInk, style = MaterialTheme.typography.bodyMedium)
                QuietButton(say(R.string.try_again), model::loadCheckIns)
            }
            model.checkInsLoading || model.checkIns == null -> CircularProgressIndicator(color = paper.orange)
            else -> Text(say(R.string.no_check_ins_yet), color = paper.ink2, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * A place's stops, newest first: the day, and how long it was, and each one's tags. With a token that writes notes,
 * Tags opens a stay's tags field, saved as each is added or taken off (API 1.21.0); the iPhone has this on its
 * check-in page, which the map here doesn't have.
 */
@Composable
private fun StopsSheet(model: AppModel, pin: CheckInPin, modifier: Modifier, onClose: () -> Unit) {
    var tagging by remember { mutableStateOf<String?>(null) }
    Column(
        modifier
            .fillMaxWidth()
            .heightIn(max = 360.dp)
            .background(paper.paper)
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        Rule()
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Heading(pin.label, Modifier.weight(1f))
            QuietButton(say(R.string.close), onClose)
        }
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            pin.stays.forEach { stay ->
                val date = dayLabel(stay.day, model.me?.today)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        CheckInWords.stay(stay)?.let { "$date, $it" } ?: date,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = paper.ink,
                    )
                    if (model.canCheckIn && tagging != stay.id) QuietButton(say(R.string.tags), { tagging = stay.id })
                }
                if (tagging == stay.id) {
                    StayTags(model, stay)
                } else {
                    val tags = stay.tagList
                    if (tags.isNotEmpty()) Caption(say(R.string.tags_line, Hashtags.line(tags)))
                }
                Rule()
            }
        }
    }
}

/**
 * One stay's tags, the whole list, saved as each is added or taken off. Should it have changed since it was read, the
 * map reads again and says so.
 */
@Composable
private fun StayTags(model: AppModel, stay: CheckIn) {
    val scope = rememberCoroutineScope()
    var typing by remember(stay.id) { mutableStateOf(TagTyping()) }
    var busy by remember(stay.id) { mutableStateOf(false) }
    var problem by remember(stay.id) { mutableStateOf<String?>(null) }

    fun retag(tags: List<Tag>) {
        if (busy) return
        busy = true
        problem = null
        scope.launch {
            model.tagStay(stay, tags).onFailure { e ->
                if ((e as? ApiException)?.code == "revision_mismatch") {
                    model.loadCheckIns()
                    problem = say(R.string.check_in_changed_add_again)
                } else {
                    problem = e.message
                }
            }
            busy = false
        }
    }

    val tags = stay.tagList
    TagField(
        tags,
        typing,
        { typing = it },
        add = { tag -> retag(Hashtags.merge(tags, listOf(tag))) },
        remove = { tag -> retag(tags.filter { it.key != tag.key }) },
        hint = say(R.string.tags_hint_stay),
        offer = model.tagRow,
        busy = busy,
    )
    problem?.let { Caption(it, color = paper.orangeInk) }
}

/** OpenFreeMap's plainest style, the base the app's own colours go over. */
private const val STYLE_URL = "https://tiles.openfreemap.org/styles/positron"

/**
 * This colour laid over the paper at that strength, as one solid colour (an ARGB int for MapLibre): solid, so
 * where roads cross or shapes overlap nothing darkens.
 */
private fun Paper.tint(color: Color, strength: Float): Int = lerp(paper, color, strength).toArgb()

/**
 * The base map in the app's paper, whichever palette is on: land as the paper itself, parks, towns and buildings
 * a faint wash of ink, water a wash of cobalt, roads drawn as the site's rules (the big ones a little darker),
 * place names in ink and road names in the lighter ink, each with a paper halo. Route shields, casings and rail
 * dashes go, so the pins are what stands out. Layers are known by OpenMapTiles' names, the same in every style of theirs.
 */
private fun paperStyle(style: Style, p: Paper) {
    for (layer in style.layers) {
        val id = layer.id
        when (layer) {
            is BackgroundLayer -> layer.setProperties(PropertyFactory.backgroundColor(p.paper.toArgb()))
            is FillLayer -> layer.setProperties(
                when {
                    id == "water" -> PropertyFactory.fillColor(p.tint(p.blue, 0.12f))
                    id == "building" -> PropertyFactory.fillColor(p.tint(p.ink, 0.05f))
                    else -> PropertyFactory.fillColor(p.tint(p.ink, 0.025f))
                },
                PropertyFactory.fillOpacity(1f),
                PropertyFactory.fillOutlineColor(p.tint(p.ink, 0.025f)),
            )
            is LineLayer -> when {
                id == "waterway" -> layer.setProperties(PropertyFactory.lineColor(p.tint(p.blue, 0.25f)), PropertyFactory.lineOpacity(1f))
                id.contains("casing") || id.contains("subtle") || id.contains("dashline") -> layer.setProperties(PropertyFactory.visibility(Property.NONE))
                id.startsWith("boundary") -> layer.setProperties(PropertyFactory.lineColor(p.tint(p.ink3, 0.35f)), PropertyFactory.lineOpacity(1f))
                id.contains("motorway") || id.contains("major") || id.contains("runway") ->
                    layer.setProperties(PropertyFactory.lineColor(p.tint(p.ink3, 0.2f)), PropertyFactory.lineOpacity(1f))
                else -> layer.setProperties(PropertyFactory.lineColor(p.rule.toArgb()), PropertyFactory.lineOpacity(1f))
            }
            is SymbolLayer -> when {
                // Route shields are coloured pictures the palette can't reach, and airports a picture too.
                id.contains("shield") || id == "airport" -> layer.setProperties(PropertyFactory.visibility(Property.NONE))
                // Street and highway names, quieter than places': the paper's lighter ink.
                id.startsWith("highway") -> layer.setProperties(
                    PropertyFactory.textColor(p.ink3.toArgb()),
                    PropertyFactory.textHaloColor(p.paper.toArgb()),
                    PropertyFactory.textHaloWidth(1.5f),
                    PropertyFactory.textHaloBlur(0f),
                )
                else -> layer.setProperties(
                    PropertyFactory.textColor(if (id.startsWith("water")) p.tint(p.blue, 0.7f) else p.ink2.toArgb()),
                    PropertyFactory.textHaloColor(p.paper.toArgb()),
                    PropertyFactory.textHaloWidth(1.5f),
                    PropertyFactory.textHaloBlur(0f),
                )
            }
        }
    }
}

/** The map itself: a MapView kept in step with the screen's lifecycle, the pins as cobalt dots. */
@Composable
private fun PinMap(pins: List<Spot>, chosen: String?, p: Paper, onPick: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val description = say(R.string.map_of_your_check_ins)
    val pick by rememberUpdatedState(onPick)
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var framed by remember { mutableStateOf(false) }
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply { onCreate(null) }
    }
    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }
    LaunchedEffect(mapView) {
        mapView.getMapAsync { m ->
            map = m
            m.uiSettings.isLogoEnabled = false
            m.uiSettings.isAttributionEnabled = false
            m.uiSettings.isRotateGesturesEnabled = false
            m.uiSettings.isTiltGesturesEnabled = false
            m.addOnMapClickListener { at ->
                val point = m.projection.toScreenLocation(at)
                val reach = 24 * context.resources.displayMetrics.density
                m.queryRenderedFeatures(RectF(point.x - reach, point.y - reach, point.x + reach, point.y + reach), PINS, NOTES)
                    .firstOrNull()?.getStringProperty("id")?.let { pick(it); true } ?: false
            }
        }
    }
    // The style, in this palette: loaded again when the phone goes light or dark.
    LaunchedEffect(map, p) {
        val m = map ?: return@LaunchedEffect
        style = null
        m.setStyle(Style.Builder().fromUri(STYLE_URL)) { loaded ->
            paperStyle(loaded, p)
            loaded.addSource(GeoJsonSource(PINS))
            val radius = { small: Float, big: Float ->
                PropertyFactory.circleRadius(Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(8, small), Expression.stop(16, big)))
            }
            val isNote = Expression.eq(Expression.get("note"), Expression.literal(true))
            // Notes as cobalt rings on paper, under the stops, so a stop at the same spot still shows.
            loaded.addLayer(
                CircleLayer(NOTES, PINS).withProperties(
                    PropertyFactory.circleColor(p.paper.toArgb()),
                    PropertyFactory.circleStrokeColor(p.blue.toArgb()),
                    PropertyFactory.circleStrokeWidth(2.5f),
                    radius(4.5f, 7f),
                ).withFilter(isNote),
            )
            // Stops as solid cobalt dots, over everything, place names too, so a town's name never hides a pin.
            loaded.addLayer(
                CircleLayer(PINS, PINS).withProperties(
                    PropertyFactory.circleColor(p.blue.toArgb()),
                    PropertyFactory.circleStrokeColor(p.paper.toArgb()),
                    PropertyFactory.circleStrokeWidth(2f),
                    radius(5f, 8f),
                ).withFilter(Expression.not(isNote)),
            )
            // The chosen pin: tangerine, a little larger, as the one thing picked on the screen.
            loaded.addLayer(
                CircleLayer(CHOSEN, PINS).withProperties(
                    PropertyFactory.circleColor(p.orange.toArgb()),
                    PropertyFactory.circleStrokeColor(p.paper.toArgb()),
                    PropertyFactory.circleStrokeWidth(2.5f),
                    radius(7f, 10f),
                ).withFilter(Expression.eq(Expression.get("id"), Expression.literal(""))),
            )
            style = loaded
        }
    }
    // The pins, whenever they change (or the style is loaded again); the first time there are any, the camera
    // takes them all in.
    LaunchedEffect(style, pins) {
        val s = style ?: return@LaunchedEffect
        val m = map ?: return@LaunchedEffect
        val features = pins.map { pin ->
            Feature.fromGeometry(Point.fromLngLat(pin.lon, pin.lat)).apply {
                addStringProperty("id", pin.id)
                addBooleanProperty("note", pin.note)
            }
        }
        s.getSourceAs<GeoJsonSource>(PINS)?.setGeoJson(FeatureCollection.fromFeatures(features))
        if (!framed && pins.isNotEmpty()) {
            framed = true
            val spots = pins.map { LatLng(it.lat, it.lon) }.distinct()
            val padding = (64 * context.resources.displayMetrics.density).toInt()
            m.moveCamera(
                if (spots.size == 1) CameraUpdateFactory.newLatLngZoom(spots[0], 14.0)
                else CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(spots).build(), padding),
            )
        }
    }
    LaunchedEffect(style, chosen) {
        val s = style ?: return@LaunchedEffect
        (s.getLayer(CHOSEN) as? CircleLayer)?.setFilter(Expression.eq(Expression.get("id"), Expression.literal(chosen.orEmpty())))
    }
    AndroidView({ mapView }, Modifier.fillMaxSize().semantics { contentDescription = description })
}

private const val PINS = "pins"
private const val CHOSEN = "pins-chosen"
private const val NOTES = "pins-notes"
