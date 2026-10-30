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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yourversionnumber.changelog.AppModel
import com.yourversionnumber.changelog.kit.UpcomingDraft
import com.yourversionnumber.changelog.kit.UpcomingEntry
import com.yourversionnumber.changelog.kit.UpcomingKind
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import androidx.compose.ui.platform.LocalConfiguration

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
                title = { Text("Upcoming") },
                actions = { if (model.hasUpcoming) TextButton(onClick = { adding = true }) { Text("Add a date") } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!model.hasUpcoming) {
                item {
                    Text(
                        "This token can't read your Upcoming dates. Make one with “Read and change your Upcoming dates” ticked to see them here.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (list != null && list.entries.isEmpty()) {
                item {
                    Text(
                        "Nothing ahead. Add a birthday, an anniversary, a trip or an event, and the morning email reminds you.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(list?.entries.orEmpty(), key = { it.id }) { entry ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button, onClickLabel = "Edit") { editing = entry }
                        .padding(vertical = 10.dp),
                ) {
                    Text(entry.line)
                    Caption(listOfNotNull(whenSaid(entry), entry.kind.label, entry.tag?.let { "#$it" }).joinToString(" · "))
                }
                HorizontalDivider()
            }
            list?.let {
                val notes = listOfNotNull(
                    it.pastLeftOut.takeIf { n -> n > 0 }?.let { n -> if (n == 1) "1 date already over isn't shown." else "$n dates already over aren't shown." },
                    it.unreadable.takeIf { n -> n > 0 }?.let { n -> "$n of a kind this version can't show yet: see them on the web." },
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
    "today" -> "Today"
    "ongoing" -> "On now" + (e.through?.let { UpcomingDraft.parse(it) }?.let { ", through ${it.format(dayFormat)}" } ?: "")
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
                    title = { Text(if (editing == null) "Add a date" else "Edit") },
                    navigationIcon = { TextButton(onClick = onDone) { Text("Cancel") } },
                    actions = { TextButton(onClick = ::save, enabled = !busy) { Text("Save") } },
                )
            },
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    UpcomingKind.entries.forEach { kind ->
                        FilterChip(selected = draft.kind == kind, onClick = { draft = draft.withKind(kind); found = null }, label = { Text(kind.label) })
                    }
                }
                OutlinedTextField(
                    value = draft.title,
                    onValueChange = { draft = draft.copy(title = it.replace("\n", " ")) },
                    label = { Text(if (draft.kind.annual) "Whose" else "Title") },
                    placeholder = { Text(placeholder(draft.kind)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                when (draft.kind) {
                    UpcomingKind.Birthday, UpcomingKind.Anniversary -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Picker(
                                label = Month.of(draft.month).getDisplayName(TextStyle.FULL, locale),
                                options = (1..12).map { it to Month.of(it).getDisplayName(TextStyle.FULL, locale) },
                                onPick = { draft = draft.withMonth(it) },
                            )
                            Picker(
                                label = draft.day.toString(),
                                options = (1..UpcomingDraft.daysIn(draft.month)).map { it to it.toString() },
                                onPick = { draft = draft.copy(day = it) },
                            )
                        }
                        OutlinedTextField(
                            value = draft.year,
                            onValueChange = { draft = draft.copy(year = it.filter(Char::isDigit).take(4)) },
                            label = { Text(if (draft.kind == UpcomingKind.Birthday) "Year born (optional)" else "Year it began (optional)") },
                            supportingText = { Text("With a year, the line says which one it is.") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    UpcomingKind.Trip -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DayButton("First day", draft.start) { draft = draft.withStart(it) }
                            DayButton("Last day", draft.end) { draft = draft.copy(end = it) }
                        }
                        Destination(draft.place, "Destination", found) { draft = draft.copy(place = it); found = null }
                        OutlinedButton(
                            onClick = {
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
                        ) { Text("Find the city") }
                    }
                    UpcomingKind.Event -> {
                        DayButton("Day", draft.start) { draft = draft.withStart(it) }
                        Destination(draft.place, "Place (optional)", null) { draft = draft.copy(place = it) }
                    }
                }

                OutlinedTextField(
                    value = draft.tag,
                    onValueChange = { draft = draft.copy(tag = it) },
                    label = { Text("Tag (optional)") },
                    placeholder = { Text("family") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text("Remind me", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (reminderChoices.keys + draft.reminders).distinct().sortedDescending().forEach { days ->
                        FilterChip(
                            selected = days in draft.reminders,
                            onClick = {
                                draft = draft.copy(reminders = if (days in draft.reminders) draft.reminders - days else draft.reminders + days)
                            },
                            label = { Text(reminderChoices[days] ?: "$days days before") },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("In the morning email")
                        Caption("Off, this date reminds nowhere, this phone included.")
                    }
                    Switch(checked = draft.email, onCheckedChange = { draft = draft.copy(email = it) })
                }

                problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }

                Button(onClick = ::save, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Save") }
                if (editing != null) {
                    TextButton(onClick = { removing = true }, enabled = !busy) {
                        Text("Delete this date", color = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.padding(8.dp))
            }
        }
    }

    if (removing && editing != null) {
        AlertDialog(
            onDismissRequest = { removing = false },
            title = { Text("Delete this date?") },
            text = { Text("Its reminders stop, in the email and on this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    removing = false
                    busy = true
                    scope.launch {
                        problem = model.remove(editing)
                        busy = false
                        if (problem == null) onDone()
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { removing = false }) { Text("Keep") } },
        )
    }
}

private val reminderChoices = linkedMapOf(
    30 to "A month before",
    14 to "Two weeks before",
    7 to "A week before",
    3 to "3 days before",
    1 to "The day before",
    0 to "On the day",
)

private fun placeholder(kind: UpcomingKind) = when (kind) {
    UpcomingKind.Birthday -> "Mom"
    UpcomingKind.Anniversary -> "Our wedding"
    UpcomingKind.Trip -> "Lake weekend"
    UpcomingKind.Event -> "Recital"
}

@Composable
private fun Destination(value: String, label: String, found: String?, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text("Zürich, or Paris, Texas") },
        supportingText = found?.let { { Text("Found: $it") } },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun <T> Picker(label: String, options: List<Pair<T, String>>, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text(label) }
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
private fun DayButton(label: String, day: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val date = UpcomingDraft.parse(day)
    Column {
        Caption(label)
        OutlinedButton(onClick = { open = true }) { Text(date?.format(DateTimeFormatter.ofPattern("EEE, MMM d, yyyy")) ?: day) }
    }
    if (open) {
        val state = rememberDatePickerState(initialSelectedDateMillis = date?.atStartOfDay()?.toInstant(ZoneOffset.UTC)?.toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) }
                    open = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
}
