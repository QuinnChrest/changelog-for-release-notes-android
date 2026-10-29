package com.yourversionnumber.changelog

import android.content.Intent
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
                var settings by rememberSaveable { mutableStateOf(false) }
                when {
                    !signedIn -> SignInScreen(model)
                    settings -> SettingsScreen(model, onBack = { settings = false })
                    else -> LogScreen(model, onSettings = { settings = true })
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

    /** Text or a link shared from another app, with its subject if it has one. */
    private fun receive(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
        model.receive(if (subject.isNullOrBlank() || text.contains(subject)) text else "$subject\n$text")
    }
}
