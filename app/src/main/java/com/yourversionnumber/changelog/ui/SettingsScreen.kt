package com.yourversionnumber.changelog.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Switch
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yourversionnumber.changelog.Locator
import com.yourversionnumber.changelog.PhoneNotices
import com.yourversionnumber.changelog.kit.ActivityNoticeKind
import com.yourversionnumber.changelog.kit.Hashtags
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import com.yourversionnumber.changelog.kit.DailyPrompt
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yourversionnumber.changelog.AppModel
import com.yourversionnumber.changelog.BuildConfig
import com.yourversionnumber.changelog.kit.Api

@Composable
private fun Toggle(title: String, about: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title)
            about?.let { Caption(it) }
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onChange, colors = switchColors())
    }
}

/** "06:30", the account's send time, as this phone writes a time. */
private fun clock(time: String): String? = runCatching {
    LocalTime.parse(time).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
}.getOrNull()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(model: AppModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    var signingOut by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val problem by model.problem.collectAsStateWithLifecycle()
    // What to turn on once Android allows notifications.
    var afterAllowed by remember { mutableStateOf<(() -> Unit)?>(null) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) afterAllowed?.invoke()
        else model.graph.report("Changelog can't notify you until notifications are allowed, in Settings, Apps, Changelog.")
        afterAllowed = null
    }

    /** Turns a notification on, asking Android first if it must. */
    fun turnOn(action: () -> Unit) = when {
        PhoneNotices.allowed(context) -> action()
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
            afterAllowed = action
            ask.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        else -> model.graph.report("Notifications are off for Changelog. Turn them on in Settings, Apps, Changelog.")
    }
    val whereAbouts = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) model.addWhereIAm(true)
        else model.graph.report("Changelog can't add where you are without location. Allow it in Settings, Apps, Changelog.")
    }
    val me = model.me

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { QuietButton("Done", onBack) },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Heading("Token", Modifier.padding(top = 12.dp))
            me?.token?.let { token ->
                Text(token.name)
                Caption("Can: " + token.scopes.joinToString(", "))
                Caption(token.expiresAt?.let { "Expires $it" } ?: "Never expires")
            }
            if (model.server != Api.PRODUCTION) Caption("Server: ${model.server}")

            Heading("Place", Modifier.padding(top = 12.dp))
            Toggle(
                "Add where I am",
                "Each note from this phone takes the neighbourhood or city it was written in, and you can pick a business " +
                    "nearby. Off, tap Here for a note that should have one.",
                checked = model.hereByDefault,
            ) { on ->
                when {
                    !on -> model.addWhereIAm(false)
                    Locator.allowed(context) -> model.addWhereIAm(true)
                    else -> whereAbouts.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
                }
            }

            Heading("Pinned tags", Modifier.padding(top = 12.dp))
            PinnedTags(model)
            val sticky = model.sticky
            if (sticky != null) {
                QuietButton("Stop adding #$sticky to every note", { model.stick(null) })
            } else {
                Caption("Touch and hold a tag on Log to add it to every note from this phone.")
            }

            Heading("Notifications", Modifier.padding(top = 12.dp))
            problem?.let { Text(it, color = paper.orangeInk) }
            Toggle(
                "Remind me on this phone",
                if (model.hasUpcoming) "Upcoming reminders, when each morning’s email is due, in its words."
                else "This token can't read your Upcoming dates.",
                checked = model.remindersOn,
                enabled = model.hasUpcoming,
            ) { on -> if (on) turnOn { model.setReminders(true) } else model.setReminders(false) }
            Toggle(
                "Ask me each morning",
                me?.let { "“${DailyPrompt.text(it.version)}” when your email goes${clock(it.sendTime)?.let { t -> " at $t" }.orEmpty()}, with that day’s version. Tap it to write." }
                    ?: "Each morning, when your email goes, a notification asks what changed.",
                checked = model.promptOn,
            ) { on -> if (on) turnOn { model.setPrompt(true) } else model.setPrompt(false) }

            Heading("Activity notifications", Modifier.padding(top = 12.dp))
            if (model.hasActivity) {
                ActivityNoticeKind.entries.forEach { kind ->
                    Toggle(kind.label, null, checked = kind in model.noticeKinds) { on ->
                        if (on) turnOn { model.setNotice(kind, true) } else model.setNotice(kind, false)
                    }
                }
                Caption(
                    "Notes from elsewhere: added by an agent, another token, an import or an email reply. Problems: an email that " +
                        "wasn't filed, your email stopping, a connection ending. Never this phone's own notes, and never the daily " +
                        "email. Changelog looks about once an hour, so they can come a while after.",
                )
            } else {
                Caption("To get them here, make a token with “Read the activity log” ticked.")
            }

            QuietButton("Sign out", { signingOut = true }, color = paper.orangeInk)
            Caption("Changelog ${BuildConfig.VERSION_NAME}, build ${BuildConfig.COMMIT}")
        }
    }

    if (signingOut) {
        AlertDialog(
            onDismissRequest = { signingOut = false },
            title = { Text("Sign out?") },
            text = { Text("Anything still waiting to send stays on this phone for the next sign-in.") },
            confirmButton = {
                TextButton(onClick = { signingOut = false; model.signOut(revoke = true); onBack() }) {
                    Text("Sign out and revoke this token")
                }
            },
            dismissButton = {
                TextButton(onClick = { signingOut = false; model.signOut(revoke = false); onBack() }) {
                    Text("Sign out, keep the token")
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

    if (pinned.isEmpty()) Caption("None yet. Pinned tags come first on Log, and on the web's Today.")
    pinned.forEach { t ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("#$t", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            QuietButton("Unpin", { model.setPinned(pinned - t) }, color = paper.ink2)
        }
    }
    if (pinned.size < MAX_PINS) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            PaperField(
                adding,
                { adding = it },
                Modifier.weight(1f),
                placeholder = "#family",
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onDone = { add() }),
            )
            QuietButton("Pin", ::add, enabled = canAdd)
        }
        if (adding.isNotBlank() && tag == null) Caption("A tag is letters and numbers, with at least one letter.", color = paper.orangeInk)
    } else {
        Caption("That's 12, the most that can be pinned.")
    }
}

private const val MAX_PINS = 12
