package com.yourversionnumber.changelog.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yourversionnumber.changelog.AppModel
import com.yourversionnumber.changelog.R
import com.yourversionnumber.changelog.kit.ActivityFilter
import com.yourversionnumber.changelog.kit.ActivityItem
import com.yourversionnumber.changelog.kit.detail
import com.yourversionnumber.changelog.kit.headline
import com.yourversionnumber.changelog.kit.isProblem
import com.yourversionnumber.changelog.say
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.launch

/**
 * The activity log, newest first, from every channel: what came in, what
 * changed, what went out. It never shows what a note said; the server leaves
 * the words out.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ActivityScreen(model: AppModel) {
    var filter by rememberSaveable { mutableStateOf(ActivityFilter.All) }
    var items by remember { mutableStateOf<List<ActivityItem>>(emptyList()) }
    var next by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var reloads by remember { mutableIntStateOf(0) }
    // Where "Since you last looked" goes: the newest row when Activity was last left.
    val seenAtOpen = remember { model.graph.seenActivity }
    val scope = rememberCoroutineScope()

    suspend fun load(more: Boolean) {
        if (!model.hasActivity) return
        loading = true
        model.activity(before = if (more) next else null, kinds = filter.kinds)?.let { page ->
            items = if (more) items + page.items else page.items
            next = page.next
        }
        loading = false
    }

    LaunchedEffect(filter, reloads) { load(more = false) }
    DisposableEffect(Unit) {
        onDispose { items.firstOrNull()?.let { model.graph.seenActivity = it.id } }
    }

    val shown = if (filter == ActivityFilter.Problems) items.filter { it.isProblem } else items

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(say(R.string.activity)) },
                actions = { QuietButton(say(R.string.refresh), { reloads++ }, enabled = !loading) },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding),
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 16.dp + LocalBarSpace.current),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    ActivityFilter.entries.forEach { Pick(it.label, selected = filter == it, onClick = { filter = it }) }
                }
            }
            if (!model.hasActivity) {
                item {
                    Text(
                        say(R.string.this_token_cant_read_the),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (shown.isEmpty() && !loading) {
                item { Text(say(R.string.nothing_here_yet), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(shown, key = { it.id }) { item ->
                if (item.id == seenAtOpen && item.id != items.firstOrNull()?.id) {
                    Caption(say(R.string.since_you_last_looked))
                }
                ActivityRow(item)
            }
            if (next != null) {
                item {
                    QuietButton(say(R.string.older), { scope.launch { load(more = true) } }, enabled = !loading)
                }
            }
        }
    }
}

/** One row of the log: what happened, through what, and when. `actions` for a note this token wrote. */
@Composable
internal fun ActivityRow(item: ActivityItem, actions: (@Composable () -> Unit)? = null) {
    val detail = item.detail
    Column(Modifier.semantics(mergeDescendants = true) {}) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                withVersion(item.headline, item.version),
                Modifier.weight(1f),
                color = if (item.isProblem) paper.orangeInk else paper.ink,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
            )
            Caption(time(item.at))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Caption(detail.orEmpty(), Modifier.weight(1f))
            actions?.invoke()
        }
        Rule(Modifier.padding(top = 4.dp))
    }
}

/** A time today, or a day before it, in the phone's own zone. */
private fun time(at: String): String {
    val local = runCatching { OffsetDateTime.parse(at).atZoneSameInstant(ZoneId.systemDefault()) }.getOrNull() ?: return ""
    return if (local.toLocalDate() == LocalDate.now()) {
        local.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
    } else {
        local.format(DateTimeFormatter.ofPattern("MMM d"))
    }
}
