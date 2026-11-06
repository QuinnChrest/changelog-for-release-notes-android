package com.yourversionnumber.changelog.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.yourversionnumber.changelog.AppModel
import com.yourversionnumber.changelog.NoteTarget
import com.yourversionnumber.changelog.R
import com.yourversionnumber.changelog.kit.Api
import com.yourversionnumber.changelog.kit.DaySummary
import com.yourversionnumber.changelog.kit.NoteDetail
import com.yourversionnumber.changelog.kit.UpcomingDraft
import com.yourversionnumber.changelog.say
import com.yourversionnumber.changelog.sayCount
import com.yourversionnumber.changelog.target
import java.time.format.DateTimeFormatter

/**
 * Reading, as the iPhone app's Notes tab: today's notes, then the days before it, newest first and a page at
 * a time (`GET /v1/days`). A day opens here, with its place, weather and every note on it; a note opens full
 * screen, as on Log, to read, edit or delete. It needs a token that reads every note (`notes:all` or
 * `notes:read`); what it reads is held in memory only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(model: AppModel) {
    val player = rememberFilePlayer()
    var opened by remember { mutableStateOf<NoteTarget?>(null) }
    // Kept here, so back from a day finds the list where it was left.
    val listPlace = rememberLazyListState()
    // Read as the tab opens, not again on the way back from a day, so the pages read stay.
    LaunchedEffect(model.readsNotes) { if (model.readsNotes) model.loadDays() }
    val date = model.shownDate
    // Back from a day goes to the list; from the list, the bar takes it to Log.
    BackHandler(enabled = date != null) { model.closeDay() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(if (date == null) say(R.string.notes) else dayLabel(date, model.me?.today))
                },
                navigationIcon = {
                    if (date != null) QuietButton("‹ " + say(R.string.back), model::closeDay, Modifier.padding(start = 8.dp))
                },
            )
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when {
            // Offline since the app opened: nothing says yet what this token may read.
            model.me == null -> Explained(say(R.string.notes_opens_once_release_notes), modifier)
            !model.readsNotes -> AllowReading(modifier)
            date != null -> DayPage(model, date, player, modifier) { opened = it }
            else -> DaysList(model, player, listPlace, modifier) { opened = it }
        }
    }
    opened?.let { target -> NoteViewer(model, target, player, onClose = { opened = null }) }
}

/** A token that reads only its own notes: what Notes needs, and where to get it. */
@Composable
private fun AllowReading(modifier: Modifier) {
    val context = LocalContext.current
    Column(modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(say(R.string.read_your_notes_here), style = MaterialTheme.typography.bodyLarge)
        Text(say(R.string.notes_needs_a_token), color = paper.ink2, style = MaterialTheme.typography.bodyMedium)
        QuietButton(say(R.string.open_release_notes_settings), {
            context.startActivity(Intent(Intent.ACTION_VIEW, "${Api.PRODUCTION}/settings/".toUri()))
        })
    }
}

@Composable
private fun Explained(text: String, modifier: Modifier) {
    Text(text, modifier.padding(horizontal = 24.dp, vertical = 16.dp), color = paper.ink2, style = MaterialTheme.typography.bodyMedium)
}

/** The list a pull reads again, with the spinner on the other palette's paper, as Activity's. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Pulled(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier,
    place: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    val pull = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        modifier = modifier,
        state = pull,
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = pull,
                isRefreshing = refreshing,
                modifier = Modifier.align(Alignment.TopCenter),
                containerColor = paper.inverse.paper,
                color = paper.orange,
            )
        },
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = place,
            contentPadding = PaddingValues(start = 24.dp, top = 8.dp, end = 24.dp, bottom = 24.dp + LocalBarSpace.current),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}

/** Today's notes whole, as on Log, then the days before it. */
@Composable
private fun DaysList(model: AppModel, player: FilePlayer, place: LazyListState, modifier: Modifier, onOpen: (NoteTarget) -> Unit) {
    var pulled by remember { mutableStateOf(false) }
    LaunchedEffect(model.daysLoading) { if (!model.daysLoading) pulled = false }
    val today = model.me?.today
    Pulled(pulled && model.daysLoading, { pulled = true; model.loadDays(); model.loadDay() }, modifier, place) {
        item(key = "today") {
            Row(Modifier.semantics(mergeDescendants = true) { heading() }) {
                Heading(say(R.string.today_in) + " ", Modifier.alignByBaseline())
                model.me?.version?.let { VersionText(it, size = 15.sp, modifier = Modifier.alignByBaseline()) }
            }
        }
        val notes = model.dayNotes
        when {
            notes == null && model.dayRead != model.day -> {}
            notes.isNullOrEmpty() -> item(key = "today-empty") {
                Text(
                    model.me?.let { say(R.string.no_changes_yet_in, it.version) } ?: say(R.string.nothing_yet),
                    color = paper.ink3,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            else -> items(notes, key = { "today/" + it.id }) { note ->
                DayNote(model, note, player, onOpen = { onOpen(note.target) })
            }
        }
        val past = model.days.orEmpty().filter { it.date != today }
        if (past.isNotEmpty()) item(key = "earlier") { Heading(say(R.string.earlier), Modifier.padding(top = 16.dp)) }
        items(past, key = { "day/" + it.date }) { day ->
            DayRow(day, today) { model.showDay(day.date) }
            // The next page as the last row comes into view.
            if (day == past.last() && model.moreDays) LaunchedEffect(day.date) { model.loadDays(more = true) }
        }
        model.daysProblem?.let { problem ->
            if (model.days == null) item(key = "problem") { Text(problem, color = paper.orangeInk, style = MaterialTheme.typography.bodyMedium) }
        }
        if (model.days == null && model.daysProblem == null) item(key = "loading") { Spinner() }
    }
}

/** A day in the list: when, its version, and how much it holds. */
@Composable
private fun DayRow(day: DaySummary, today: String?, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClickLabel = say(R.string.open), role = Role.Button, onClick = onClick)) {
        // The version on the date's own line, sharing its baseline.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(dayLabel(day.date, today), Modifier.alignByBaseline().weight(1f, fill = false), style = MaterialTheme.typography.bodyLarge)
            VersionText(day.version, size = 13.sp, modifier = Modifier.alignByBaseline())
        }
        Caption(daySummary(day), Modifier.padding(top = 2.dp))
        Rule(Modifier.padding(top = 10.dp))
    }
}

/** "3 notes · 1 file · Minneapolis · Paused", as the iPhone app says it. */
private fun daySummary(day: DaySummary): String = buildList {
    add(if (day.notesTotal == 0) say(R.string.no_notes) else sayCount(R.plurals.notes_count, day.notesTotal, day.notesTotal))
    if (day.files > 0) add(sayCount(R.plurals.files_count, day.files, day.files))
    day.placeLabel?.let(::add)
    if (day.paused) add(say(R.string.paused))
}.joinToString(" · ")

/** One day: its version, where it was, its weather, and every note on it. */
@Composable
private fun DayPage(model: AppModel, date: String, player: FilePlayer, modifier: Modifier, onOpen: (NoteTarget) -> Unit) {
    var pulled by remember { mutableStateOf(false) }
    val day = model.shownDay?.takeIf { it.date == date }
    LaunchedEffect(day) { pulled = false }
    Pulled(pulled, { pulled = true; model.readShownDay() }, modifier) {
        when {
            day != null -> {
                item(key = "head") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        VersionText(day.version, size = 28.sp)
                        day.placeLabel?.let { Caption(it) }
                        day.weather?.let { weather ->
                            Caption(weather.line)
                            (day.weatherCredit ?: model.weatherCredit)?.let { Caption(it) }
                        }
                        if (day.paused) Caption(say(R.string.paused) + ".")
                    }
                }
                val notes: List<NoteDetail> = model.shownDayNotes
                if (notes.isEmpty()) {
                    item(key = "empty") { Text(say(R.string.nothing_filed_this_day), color = paper.ink3, style = MaterialTheme.typography.bodyMedium) }
                }
                items(notes, key = { it.id }) { note ->
                    DayNote(model, note, player, onOpen = { onOpen(note.target) })
                    if (note == notes.last() && model.moreOnShownDay) LaunchedEffect(note.id) { model.readShownDay(more = true) }
                }
            }
            model.shownDayProblem != null -> item(key = "problem") {
                Text(model.shownDayProblem.orEmpty(), color = paper.orangeInk, style = MaterialTheme.typography.bodyMedium)
            }
            else -> item(key = "loading") { Spinner() }
        }
    }
}

@Composable
private fun Spinner() {
    CircularProgressIndicator(Modifier.padding(top = 24.dp), color = paper.orange)
}

private val withYear = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy")

/** "Yesterday", or the day by name, with its year when it isn't this one. */
internal fun dayLabel(date: String, today: String?): String {
    val day = UpcomingDraft.parse(date) ?: return date
    val now = today?.let(UpcomingDraft::parse)
    return when {
        now != null && day == now -> say(R.string.today)
        now != null && day == now.minusDays(1) -> say(R.string.yesterday)
        now != null && day.year != now.year -> day.format(withYear)
        else -> day.format(dayline)
    }
}
