package com.yourversionnumber.changelog.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.yourversionnumber.changelog.AppModel
import com.yourversionnumber.changelog.BuildConfig
import com.yourversionnumber.changelog.kit.Api
import kotlinx.coroutines.launch

/** Sign-in by token. The owner makes one in Release Notes' Settings and pastes it here. */
@Composable
fun SignInScreen(model: AppModel) {
    var pasted by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    val lost by model.problem.collectAsState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val context = LocalContext.current

    fun check(raw: String) {
        checking = true
        scope.launch {
            problem = model.signIn(raw)
            checking = false
            if (problem != null) pasted = ""
        }
    }

    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Changelog", style = MaterialTheme.typography.headlineLarge)
            Text("Changelog sends notes into your Release Notes. It can read back only the notes it sent, never the rest.")
            Text(
                "Make a token in Release Notes under Settings, API tokens, named for this phone, " +
                    "with “Add notes” ticked. Then paste it here.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            (problem ?: lost)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedTextField(
                value = pasted,
                onValueChange = { pasted = it },
                label = { Text("Token") },
                placeholder = { Text("rn_…") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = {
                    scope.launch { clipboard.getClipEntry()?.clipData?.getItemAt(0)?.text?.toString()?.let(::check) }
                }, enabled = !checking) {
                    Text("Paste token")
                }
                Button(onClick = { check(pasted) }, enabled = !checking && pasted.isNotBlank()) {
                    Text(if (checking) "Checking…" else "Sign in")
                }
            }
            TextButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, "${Api.PRODUCTION}/settings/".toUri()))
            }) { Text("Open Release Notes Settings") }

            if (BuildConfig.DEBUG) {
                Text("Development", style = MaterialTheme.typography.titleSmall)
                listOf("Release Notes" to Api.PRODUCTION, "Dev server" to BuildConfig.DEV_SERVER).forEach { (name, url) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = model.server == url, onClick = { model.server = url })
                        Column {
                            Text(name)
                            Text(url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Text(
                    "The dev server prints a token for its fictional account when it starts.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
