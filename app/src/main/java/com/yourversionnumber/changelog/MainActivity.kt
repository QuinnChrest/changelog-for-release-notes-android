package com.yourversionnumber.changelog

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yourversionnumber.changelog.ui.ActivityScreen
import com.yourversionnumber.changelog.ui.UpcomingScreen
import com.yourversionnumber.changelog.ui.ChangelogTheme
import com.yourversionnumber.changelog.ui.LogScreen
import com.yourversionnumber.changelog.ui.SettingsScreen
import com.yourversionnumber.changelog.ui.SignInScreen

/** Log, Upcoming and Activity, along the bottom. Back from Upcoming or Activity goes to Log. */
@Composable
private fun Tabs(model: AppModel) {
    val tabs = listOf("log" to "Log", "upcoming" to "Upcoming", "activity" to "Activity")
    BackHandler(enabled = model.screen != "log") { model.screen = "log" }
    // The tab's own screen handles the status bar; this one only makes room for the tabs.
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            NavigationBar {
                tabs.forEach { (id, label) ->
                    NavigationBarItem(
                        selected = model.screen == id,
                        onClick = { model.screen = id },
                        icon = { Text(label, style = MaterialTheme.typography.labelLarge) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(bottom = padding.calculateBottomPadding()).consumeWindowInsets(padding)) {
            when (model.screen) {
                "upcoming" -> UpcomingScreen(model)
                "activity" -> ActivityScreen(model)
                else -> LogScreen(model, onSettings = { model.screen = "settings" })
            }
        }
    }
}

class MainActivity : ComponentActivity() {
    companion object {
        /** The screen a notification opens. */
        const val OPEN = "open"
    }

    private val model: AppModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) receive(intent)
        setContent {
            ChangelogTheme {
                val signedIn by model.signedIn.collectAsStateWithLifecycle()
                when {
                    !signedIn -> SignInScreen(model)
                    model.screen == "settings" -> SettingsScreen(model, onBack = { model.screen = "log" })
                    else -> Tabs(model)
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

    /** Text, a link or files shared from another app: the start of one note. Or a tapped reminder, which opens Upcoming. */
    private fun receive(intent: Intent?) {
        intent?.getStringExtra(OPEN)?.let { model.screen = it }
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
