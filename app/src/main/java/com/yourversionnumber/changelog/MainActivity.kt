package com.yourversionnumber.changelog

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yourversionnumber.changelog.ui.ActivityScreen
import com.yourversionnumber.changelog.ui.ChangelogTheme
import com.yourversionnumber.changelog.ui.LogScreen
import com.yourversionnumber.changelog.ui.SettingsScreen
import com.yourversionnumber.changelog.ui.SignInScreen

class MainActivity : ComponentActivity() {
    private val model: AppModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) receive(intent)
        setContent {
            ChangelogTheme {
                val signedIn by model.signedIn.collectAsStateWithLifecycle()
                var screen by rememberSaveable { mutableStateOf("log") }
                when {
                    !signedIn -> SignInScreen(model)
                    screen == "settings" -> SettingsScreen(model, onBack = { screen = "log" })
                    screen == "activity" -> ActivityScreen(model, onBack = { screen = "log" })
                    else -> LogScreen(model, onActivity = { screen = "activity" }, onSettings = { screen = "settings" })
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        model.refresh()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receive(intent)
    }

    /** Text, a link or files shared from another app: the start of one note. */
    private fun receive(intent: Intent?) {
        val action = intent?.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return
        intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
            val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
            model.receive(if (subject.isNullOrBlank() || text.contains(subject)) text else "$subject\n$text")
        }
        val streams = if (action == Intent.ACTION_SEND) {
            listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        } else {
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        }
        if (streams.isNotEmpty()) model.addShared(streams, intent.type)
    }
}
