package com.yourversionnumber.changelog.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.yourversionnumber.changelog.AppModel
import com.yourversionnumber.changelog.BuildConfig
import com.yourversionnumber.changelog.kit.Api
import kotlinx.coroutines.launch

/**
 * Sign-in by token, drawn as the site's front page: a version still to be
 * known, a heading, the one field and the tangerine button. The owner makes
 * a token in Release Notes' Settings and pastes it here.
 */
@OptIn(ExperimentalLayoutApi::class)
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

    Column(Modifier.fillMaxSize()) {
        Bar()
        Column(
            Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(28.dp))
            VersionText(null, size = 52.sp)
            Text("Notes in, two taps.", style = MaterialTheme.typography.headlineLarge)
            Text(
                "Changelog sends notes into your Release Notes. It reads back only the notes it sent, never the rest.",
                style = MaterialTheme.typography.bodyLarge,
                color = paper.ink2,
            )
            Text(
                "Make a token in Release Notes under Settings, API tokens, named for this phone, with “Add notes” ticked. Then paste it here.",
                style = MaterialTheme.typography.bodyMedium,
                color = paper.ink2,
            )
            (problem ?: lost)?.let { Text(it, color = paper.orangeInk, style = MaterialTheme.typography.bodyMedium) }
            PaperField(
                pasted,
                { pasted = it },
                Modifier.fillMaxWidth(),
                label = "Token",
                placeholder = "rn_…",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                visualTransformation = PasswordVisualTransformation(),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                GoButton(if (checking) "Checking…" else "Sign in", { check(pasted) }, enabled = !checking && pasted.isNotBlank())
                QuietButton(
                    "Paste token",
                    { scope.launch { clipboard.getClipEntry()?.clipData?.getItemAt(0)?.text?.toString()?.let(::check) } },
                    enabled = !checking,
                )
            }
            QuietButton("Open Release Notes Settings", {
                context.startActivity(Intent(Intent.ACTION_VIEW, "${Api.PRODUCTION}/settings/".toUri()))
            })

            if (BuildConfig.DEBUG) {
                Rule(Modifier.padding(top = 12.dp))
                Heading("Development")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    listOf("Release Notes" to Api.PRODUCTION, "Dev server" to BuildConfig.DEV_SERVER).forEach { (name, url) ->
                        Pick(name, selected = model.server == url, onClick = { model.server = url })
                    }
                }
                Caption("${model.server}. The dev server prints a token for its fictional account when it starts.")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
