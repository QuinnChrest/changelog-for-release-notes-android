package com.yourversionnumber.changelog.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.yourversionnumber.changelog.AppModel
import com.yourversionnumber.changelog.BuildConfig
import com.yourversionnumber.changelog.R
import com.yourversionnumber.changelog.kit.Api
import com.yourversionnumber.changelog.say
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

    fun signIn(raw: String) {
        checking = true
        scope.launch {
            problem = model.signIn(raw)
            checking = false
            if (problem != null) pasted = ""
        }
    }

    // Android 17 lets an app reach a private address (the dev server, from the
    // emulator) only with the local network permission, which debug builds ask
    // for as they sign in there. Release Notes itself needs nothing.
    var waiting by remember { mutableStateOf<String?>(null) }
    val localNetwork = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val raw = waiting
        waiting = null
        if (granted && raw != null) signIn(raw)
        else problem = say(R.string.the_dev_server_is_on)
    }

    fun check(raw: String) {
        val needsLocal = BuildConfig.DEBUG && model.server != Api.PRODUCTION && Build.VERSION.SDK_INT >= 37 &&
            ContextCompat.checkSelfPermission(context, LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
        if (needsLocal) {
            waiting = raw
            localNetwork.launch(LOCAL_NETWORK)
        } else {
            signIn(raw)
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
            Text(say(R.string.notes_in_two_taps), style = MaterialTheme.typography.headlineLarge)
            Text(
                say(R.string.changelog_sends_notes_into_your),
                style = MaterialTheme.typography.bodyLarge,
                color = paper.ink2,
            )
            Text(
                say(R.string.make_a_token_in_release),
                style = MaterialTheme.typography.bodyMedium,
                color = paper.ink2,
            )
            (problem ?: lost)?.let { Text(it, color = paper.orangeInk, style = MaterialTheme.typography.bodyMedium) }
            PaperField(
                pasted,
                { pasted = it },
                Modifier.fillMaxWidth(),
                label = say(R.string.token),
                placeholder = "rn_…",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                visualTransformation = PasswordVisualTransformation(),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                GoButton(if (checking) say(R.string.checking) else say(R.string.sign_in), { check(pasted) }, enabled = !checking && pasted.isNotBlank())
                QuietButton(
                    say(R.string.paste_token),
                    { scope.launch { clipboard.getClipEntry()?.clipData?.getItemAt(0)?.text?.toString()?.let(::check) } },
                    enabled = !checking,
                )
            }
            QuietButton(say(R.string.open_release_notes_settings), {
                context.startActivity(Intent(Intent.ACTION_VIEW, "${Api.PRODUCTION}/settings/".toUri()))
            })

            if (BuildConfig.DEBUG) {
                Rule(Modifier.padding(top = 12.dp))
                Heading(say(R.string.development))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    listOf(say(R.string.release_notes) to Api.PRODUCTION, say(R.string.dev_server) to BuildConfig.DEV_SERVER).forEach { (name, url) ->
                        Pick(name, selected = model.server == url, onClick = { model.server = url })
                    }
                }
                Caption(say(R.string.the_dev_server_prints_a, model.server))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Android 17's permission to reach a private address; only debug builds declare it. */
private const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
