package com.yourversionnumber.changelog.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yourversionnumber.changelog.AppModel
import com.yourversionnumber.changelog.R
import com.yourversionnumber.changelog.kit.UpcomingDraft
import com.yourversionnumber.changelog.kit.UpcomingEntry
import com.yourversionnumber.changelog.kit.UpcomingKind
import com.yourversionnumber.changelog.say
import com.yourversionnumber.changelog.sayCount
import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import kotlinx.coroutines.launch

/** Birthdays, anniversaries, trips and events ahead, each in the line the email gives it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpcomingScreen(model: AppModel) {
    var editing by remember { mutableStateOf<UpcomingEntry?>(null) }
    var adding by remember { mutableStateOf(false) }
    LaunchedEffect(model.hasUpcoming) { model.loadUpcoming() }
    val list = model.upcoming

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(say(R.string.upcoming)) },
                actions = { if (model.hasUpcoming) QuietButton(say(R.string.add_a_date), { adding = true }) },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!model.hasUpcoming) {
                item {
                    Text(
                        say(R.string.this_token_cant_read_your_2),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (list != null && list.entries.isEmpty()) {
                item {
                    Text(
                        say(R.string.nothing_ahead_add_a_birthday),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(list?.entries.orEmpty(), key = { it.id }) { entry ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button, onClickLabel = say(R.string.edit)) { editing = entry }
                        .padding(vertical = 10.dp),
                ) {
                    Text(entry.line, style = MaterialTheme.typography.bodyLarge)
                    Caption(listOfNotNull(whenSaid(entry), entry.kind.label, entry.tag?.let { "#$it" }).joinToString(" · "))
                }
                Rule()
            }
            list?.let {
                val notes = listOfNotNull(
                    it.pastLeftOut.takeIf { n -> n > 0 }?.let { n -> sayCount(R.plurals.past_left_out, n, n) },
                    it.unreadable.takeIf { n -> n > 0 }?.let { n -> sayCount(R.plurals.unreadable, n, n) },
                )
                if (notes.isNotEmpty()) item { Caption(notes.joinToString(" "), Modifier.padding(top = 12.dp)) }
            }
        }
    }

    if (adding || editing != null) {
        UpcomingEditor(model, editing, onDone = { adding = false; editing = null })
    }
}

private val dayFormat = DateTimeFormatter.ofPattern("EEE, MMM d")

private fun whenSaid(e: UpcomingEntry): String = when (e.status) {
    "today" -> say(R.string.today)
    "ongoing" -> e.through?.let { UpcomingDraft.parse(it) }?.let { say(R.string.on_now_through, it.format(dayFormat)) } ?: say(R.string.on_now)
    else -> UpcomingDraft.parse(e.next)?.format(dayFormat) ?: e.next
}

/** Adds or changes one date. Save sends it at once; a refusal is shown here, to fix. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun UpcomingEditor(model: AppModel, editing: UpcomingEntry?, onDone: () -> Unit) {
    val today = model.upcoming?.today ?: model.me?.today ?: LocalDate.now().toString()
    var draft by remember { mutableStateOf(editing?.let { UpcomingDraft.of(it, today) } ?: UpcomingDraft.new(today)) }
    var problem by remember { mutableStateOf<String?>(null) }
    var found by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val locale = LocalConfiguration.current.locales[0]

    fun save() {
        busy = true
        scope.launch {
            problem = model.save(draft, editing)
            busy = false
            if (problem == null) onDone()
        }
    }

    Dialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(if (editing == null) say(R.string.add_a_date) else say(R.string.edit)) },
                    navigationIcon = { QuietButton(say(R.string.cancel), onDone, color = paper.ink2) },
                    actions = { QuietButton(say(R.string.save), ::save, enabled = !busy) },
                )
            },
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    UpcomingKind.entries.forEach { kind ->
                        Pick(kind.label, selected = draft.kind == kind, onClick = { draft = draft.withKind(kind); found = null })
                    }
                }
                PaperField(
                    draft.title,
                    { draft = draft.copy(title = it.replace("\n", " ")) },
                    Modifier.fillMaxWidth(),
                    label = if (draft.kind.annual) say(R.string.whose) else say(R.string.title),
                    placeholder = placeholder(draft.kind),
                )

                when (draft.kind) {
                    UpcomingKind.Birthday, UpcomingKind.Anniversary -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            Picker(
                                title = say(R.string.month),
                                label = Month.of(draft.month).getDisplayName(TextStyle.FULL, locale),
                                options = (1..12).map { it to Month.of(it).getDisplayName(TextStyle.FULL, locale) },
                                onPick = { draft = draft.withMonth(it) },
                                modifier = Modifier.weight(2f),
                            )
                            Picker(
                                title = say(R.string.day),
                                label = draft.day.toString(),
                                options = (1..UpcomingDraft.daysIn(draft.month)).map { it to it.toString() },
                                onPick = { draft = draft.copy(day = it) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        PaperField(
                            draft.year,
                            { draft = draft.copy(year = it.filter(Char::isDigit).take(4)) },
                            Modifier.fillMaxWidth(),
                            label = if (draft.kind == UpcomingKind.Birthday) say(R.string.year_born_optional) else say(R.string.year_it_began_optional),
                            placeholder = "YYYY",
                            supporting = say(R.string.with_a_year_the_line),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    }
                    UpcomingKind.Trip -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            DayButton(say(R.string.first_day), draft.start, Modifier.weight(1f)) { draft = draft.withStart(it) }
                            DayButton(say(R.string.last_day), draft.end, Modifier.weight(1f)) { draft = draft.copy(end = it) }
                        }
                        Destination(draft.place, say(R.string.destination), found) { draft = draft.copy(place = it); found = null }
                        QuietButton(
                            say(R.string.find_the_city),
                            {
                                busy = true
                                scope.launch {
                                    model.checkPlace(draft, editing).fold(
                                        onSuccess = { found = it; problem = null },
                                        onFailure = { problem = it.message; found = null },
                                    )
                                    busy = false
                                }
                            },
                            enabled = !busy && draft.place.isNotBlank(),
                        )
                    }
                    UpcomingKind.Event -> {
                        DayButton(say(R.string.day), draft.start, Modifier.fillMaxWidth()) { draft = draft.withStart(it) }
                        Destination(draft.place, say(R.string.place_optional), null) { draft = draft.copy(place = it) }
                    }
                }

                PaperField(draft.tag, { draft = draft.copy(tag = it) }, Modifier.fillMaxWidth(), label = say(R.string.tag_optional), placeholder = "family")

                Heading(say(R.string.remind_me))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    (reminderChoices.keys + draft.reminders).distinct().sortedDescending().forEach { days ->
                        Pick(
                            reminderChoices[days] ?: sayCount(R.plurals.days_before, days, days),
                            selected = days in draft.reminders,
                            onClick = {
                                draft = draft.copy(reminders = if (days in draft.reminders) draft.reminders - days else draft.reminders + days)
                            },
                        )
                    }
                }
                Row(
                    Modifier.toggleable(value = draft.email, role = Role.Switch, onValueChange = { draft = draft.copy(email = it) }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(say(R.string.in_the_morning_email))
                        Caption(say(R.string.off_this_date_reminds_nowhere))
                    }
                    Switch(checked = draft.email, onCheckedChange = null, colors = switchColors())
                }

                problem?.let { Text(it, color = paper.orangeInk) }

                GoButton(say(R.string.save), ::save, enabled = !busy)
                if (editing != null) QuietButton(say(R.string.delete_this_date), { removing = true }, enabled = !busy, color = paper.orangeInk)
                Spacer(Modifier.padding(8.dp))
            }
        }
    }

    if (removing && editing != null) {
        AlertDialog(
            onDismissRequest = { removing = false },
            title = { Text(say(R.string.delete_this_date_2)) },
            text = { Text(say(R.string.its_reminders_stop_in_the)) },
            confirmButton = {
                TextButton(onClick = {
                    removing = false
                    busy = true
                    scope.launch {
                        problem = model.remove(editing)
                        busy = false
                        if (problem == null) onDone()
                    }
                }) { Text(say(R.string.delete), color = paper.orangeInk) }
            },
            dismissButton = { QuietButton(say(R.string.keep), { removing = false }) },
        )
    }
}

private val reminderChoices: Map<Int, String> get() = linkedMapOf(
    30 to say(R.string.a_month_before),
    14 to say(R.string.two_weeks_before),
    7 to say(R.string.a_week_before),
    3 to say(R.string.n_3_days_before),
    1 to say(R.string.the_day_before),
    0 to say(R.string.on_the_day),
)

private fun placeholder(kind: UpcomingKind) = when (kind) {
    UpcomingKind.Birthday -> say(R.string.mom)
    UpcomingKind.Anniversary -> say(R.string.our_wedding)
    UpcomingKind.Trip -> say(R.string.lake_weekend)
    UpcomingKind.Event -> say(R.string.recital)
}

@Composable
private fun Destination(value: String, label: String, found: String?, onChange: (String) -> Unit) {
    PaperField(
        value,
        onChange,
        Modifier.fillMaxWidth(),
        label = label,
        placeholder = say(R.string.z_rich_or_paris_texas),
        supporting = found?.let { say(R.string.found, it) },
    )
}

@Composable
private fun <T> Picker(title: String, label: String, options: List<Pair<T, String>>, onPick: (T) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Select(title, label, { open = true }, Modifier.fillMaxWidth())
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (value, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { onPick(value); open = false })
            }
        }
    }
}

/** A day, `YYYY-MM-DD`, chosen on the calendar. The picker counts in UTC days, so no zone moves it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayButton(label: String, day: String, modifier: Modifier = Modifier, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val date = UpcomingDraft.parse(day)
    Select(label, date?.format(DateTimeFormatter.ofPattern("EEE, MMM d")) ?: day, { open = true }, modifier)
    if (open) {
        val state = rememberDatePickerState(initialSelectedDateMillis = date?.atStartOfDay()?.toInstant(ZoneOffset.UTC)?.toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) }
                    open = false
                }) { Text(say(R.string.ok), color = paper.blue) }
            },
            dismissButton = { QuietButton(say(R.string.cancel), { open = false }, color = paper.ink2) },
        ) { DatePicker(state) }
    }
}
