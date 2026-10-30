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
import com.yourversionnumber.changelog.PhoneReminders
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(model: AppModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    var signingOut by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val problem by model.problem.collectAsStateWithLifecycle()
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) model.setReminders(true)
        else model.graph.report("Changelog can't remind you without notifications. Allow them in Settings, Apps, Changelog.")
    }
    val me = model.me

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Done") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Token", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            me?.token?.let { token ->
                Text(token.name)
                Caption("Can: " + token.scopes.joinToString(", "))
                Caption(token.expiresAt?.let { "Expires $it" } ?: "Never expires")
            }
            if (model.server != Api.PRODUCTION) Caption("Server: ${model.server}")

            Text("Tags", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            val sticky = model.sticky
            if (sticky != null) {
                OutlinedButton(onClick = { model.stick(null) }) { Text("Stop adding #$sticky to every note") }
            } else {
                Caption("Touch and hold a tag on Log to add it to every note.")
            }

            Text("Notifications", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Remind me on this phone")
                    Caption(
                        if (model.hasUpcoming) "Upcoming reminders, when each morning’s email is due, in its words."
                        else "This token can't read your Upcoming dates.",
                    )
                }
                Switch(
                    checked = model.remindersOn,
                    enabled = model.hasUpcoming,
                    onCheckedChange = { on ->
                        when {
                            !on -> model.setReminders(false)
                            PhoneReminders.allowed(context) -> model.setReminders(true)
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> ask.launch(Manifest.permission.POST_NOTIFICATIONS)
                            else -> model.graph.report("Notifications are off for Changelog. Turn them on in Settings, Apps, Changelog.")
                        }
                    },
                )
            }

            OutlinedButton(onClick = { signingOut = true }) { Text("Sign out") }
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
