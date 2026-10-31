package com.yourversionnumber.changelog.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yourversionnumber.changelog.AppModel
import com.yourversionnumber.changelog.BuildConfig
import com.yourversionnumber.changelog.Locator
import com.yourversionnumber.changelog.PhoneNotices
import com.yourversionnumber.changelog.R
import com.yourversionnumber.changelog.kit.ActivityNoticeKind
import com.yourversionnumber.changelog.kit.Api
import com.yourversionnumber.changelog.kit.DailyPrompt
import com.yourversionnumber.changelog.kit.Hashtags
import com.yourversionnumber.changelog.say
import com.yourversionnumber.changelog.ui.ThemeMode
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
private fun Toggle(title: String, about: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    // The whole row is the switch, so TalkBack says its title with it and any of it can be tapped.
    Row(
        Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title)
            about?.let { Caption(it) }
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = null, colors = switchColors())
    }
}

/** A scope as the iPhone app says it. */
private fun scopeName(scope: String) = when (scope) {
    "notes:write" -> say(R.string.add_notes)
    "media:write" -> say(R.string.add_files)
    "upcoming" -> say(R.string.upcoming)
    "activity" -> say(R.string.read_activity)
    "notes:all" -> say(R.string.read_every_note)
    else -> scope
}

/** A token's expiry as a date, in the phone's way of writing one. */
private fun expiry(at: String): String? = runCatching {
    java.time.OffsetDateTime.parse(at).atZoneSameInstant(java.time.ZoneId.systemDefault())
        .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
}.getOrNull()

/** "06:30", the account's send time, as this phone writes a time. */
private fun clock(time: String): String? = runCatching {
    LocalTime.parse(time).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
}.getOrNull()

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(model: AppModel) {
    var signingOut by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val problem by model.problem.collectAsStateWithLifecycle()
    // What to turn on once Android allows notifications.
    var afterAllowed by remember { mutableStateOf<(() -> Unit)?>(null) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) afterAllowed?.invoke()
        else model.graph.report(say(R.string.changelog_cant_notify_you_until))
        afterAllowed = null
    }

    /** Turns a notification on, asking Android first if it must. */
    fun turnOn(action: () -> Unit) = when {
        PhoneNotices.allowed(context) -> action()
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
            afterAllowed = action
            ask.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        else -> model.graph.report(say(R.string.notifications_are_off_for_changelog))
    }
    val whereAbouts = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) model.addWhereIAm(true)
        else model.graph.report(say(R.string.changelog_cant_add_where_you))
    }
    val me = model.me

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(say(R.string.settings)) })
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 16.dp + LocalBarSpace.current),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Heading(say(R.string.appearance), Modifier.padding(top = 12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                listOf(
                    ThemeMode.System to say(R.string.same_as_the_phone),
                    ThemeMode.Light to say(R.string.light),
                    ThemeMode.Dark to say(R.string.dark),
                ).forEach { (mode, name) -> Pick(name, selected = model.theme == mode, onClick = { model.chooseTheme(mode) }) }
            }
            Caption(say(R.string.the_home_screen_widget_follows))

            Heading(say(R.string.this_connection), Modifier.padding(top = 12.dp))
            me?.token?.let { token ->
                Text(token.name)
                val scopes = token.scopes.joinToString(", ") { scopeName(it) }
                Caption(say(R.string.can, scopes))
                Caption(token.expiresAt?.let { expiry(it) }?.let { say(R.string.expires, it) } ?: say(R.string.never_expires))
            }
            if (model.server != Api.PRODUCTION) Caption(say(R.string.server, model.server))

            Heading(say(R.string.place), Modifier.padding(top = 12.dp))
            Toggle(
                say(R.string.add_where_i_am),
                say(R.string.each_note_from_this_phone),
                checked = model.hereByDefault,
            ) { on ->
                when {
                    !on -> model.addWhereIAm(false)
                    Locator.allowed(context) -> model.addWhereIAm(true)
                    else -> whereAbouts.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
                }
            }

            Heading(say(R.string.pinned_tags), Modifier.padding(top = 12.dp))
            PinnedTags(model)
            val sticky = model.sticky
            if (sticky != null) {
                QuietButton(say(R.string.stop_adding_to_every_note, sticky), { model.stick(null) })
            } else {
                Caption(say(R.string.touch_and_hold_a_tag))
            }

            Heading(say(R.string.notifications), Modifier.padding(top = 12.dp))
            problem?.let { Text(it, color = paper.orangeInk) }
            Toggle(
                say(R.string.remind_me_on_this_phone),
                if (model.hasUpcoming) say(R.string.upcoming_reminders_when_each_mornings)
                else say(R.string.this_token_cant_read_your),
                checked = model.remindersOn,
                enabled = model.hasUpcoming,
            ) { on -> if (on) turnOn { model.setReminders(true) } else model.setReminders(false) }
            Toggle(
                say(R.string.ask_me_each_morning),
                me?.let { m ->
                    val prompt = DailyPrompt.text(m.version)
                    clock(m.sendTime)?.let { t -> say(R.string.when_your_email_goes_at, prompt, t) }
                        ?: say(R.string.when_your_email_goes_with, prompt)
                } ?: say(R.string.each_morning_when_your_email),
                checked = model.promptOn,
            ) { on -> if (on) turnOn { model.setPrompt(true) } else model.setPrompt(false) }

            Heading(say(R.string.activity_notifications), Modifier.padding(top = 12.dp))
            if (model.hasActivity) {
                ActivityNoticeKind.entries.forEach { kind ->
                    Toggle(kind.label, null, checked = kind in model.noticeKinds) { on ->
                        if (on) turnOn { model.setNotice(kind, true) } else model.setNotice(kind, false)
                    }
                }
                Caption(
                    say(R.string.notes_from_elsewhere_added_by),
                )
            } else {
                Caption(say(R.string.to_get_them_here_make))
            }

            Heading(say(R.string.release_notes), Modifier.padding(top = 12.dp))
            // Always the real site: these are for the account, whichever server signed in.
            listOf(
                say(R.string.open_release_notes) to "",
                say(R.string.settings_on_the_web) to "settings/",
                say(R.string.the_guide) to "guide/",
                say(R.string.delete_your_account) to "delete/",
            ).forEach { (label, path) ->
                QuietButton(label, { context.startActivity(Intent(Intent.ACTION_VIEW, "${Api.PRODUCTION}/$path".toUri())) })
            }

            QuietButton(say(R.string.sign_out), { signingOut = true }, color = paper.orangeInk)
            Caption(say(R.string.changelog_build, BuildConfig.VERSION_NAME, BuildConfig.COMMIT))
        }
    }

    if (signingOut) {
        AlertDialog(
            onDismissRequest = { signingOut = false },
            title = { Text(say(R.string.sign_out_2)) },
            text = { Text(say(R.string.anything_still_waiting_to_send)) },
            confirmButton = {
                TextButton(onClick = { signingOut = false; model.signOut(revoke = true); model.screen = "log" }) {
                    Text(say(R.string.sign_out_and_revoke_this))
                }
            },
            dismissButton = {
                TextButton(onClick = { signingOut = false; model.signOut(revoke = false); model.screen = "log" }) {
                    Text(say(R.string.sign_out_keep_the_token))
                }
            },
        )
    }
}

/** The pinned tags, first in the tag row: the same here, on the web and on every phone. Up to 12. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PinnedTags(model: AppModel) {
    val pinned = model.tags?.pinned.orEmpty()
    var adding by remember { mutableStateOf("") }
    val tag = Hashtags.normalize(adding)
    val canAdd = tag != null && tag !in pinned && pinned.size < MAX_PINS

    fun add() {
        if (canAdd) model.setPinned(pinned + tag!!)
        adding = ""
    }

    if (pinned.isEmpty()) Caption(say(R.string.none_yet_pinned_tags_come))
    // In the order they come in the tag row. Up and Down rather than dragging,
    // so the order can be changed with a screen reader too.
    pinned.forEachIndexed { i, t ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("#$t", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            // Fixed slots, so the buttons line up from row to row.
            Box(Modifier.width(56.dp)) {
                if (i > 0) {
                    QuietButton(say(R.string.up), { model.setPinned(pinned.moved(i, i - 1)) }, Modifier.semantics { contentDescription = say(R.string.move_up, t) })
                }
            }
            Box(Modifier.width(72.dp)) {
                if (i < pinned.lastIndex) {
                    QuietButton(say(R.string.down), { model.setPinned(pinned.moved(i, i + 1)) }, Modifier.semantics { contentDescription = say(R.string.move_down, t) })
                }
            }
            QuietButton(say(R.string.unpin), { model.setPinned(pinned - t) }, Modifier.semantics { contentDescription = say(R.string.unpin_2, t) }, color = paper.ink2)
        }
    }
    if (pinned.size < MAX_PINS) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            PaperField(
                adding,
                { adding = it },
                Modifier.weight(1f),
                placeholder = say(R.string.pin_a_tag_like_garden),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onDone = { add() }),
            )
            QuietButton(say(R.string.pin), ::add, enabled = canAdd)
        }
        if (adding.isNotBlank() && tag == null) Caption(say(R.string.a_tag_is_letters_and), color = paper.orangeInk)
    } else {
        Caption(say(R.string.thats_12_the_most_that))
    }
}

private const val MAX_PINS = 12

private fun <T> List<T>.moved(from: Int, to: Int): List<T> = toMutableList().apply { add(to, removeAt(from)) }
