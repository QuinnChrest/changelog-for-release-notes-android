package com.yourversionnumber.changelog

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yourversionnumber.changelog.ui.ActivityScreen
import com.yourversionnumber.changelog.ui.ChangelogTheme
import com.yourversionnumber.changelog.ui.LogScreen
import com.yourversionnumber.changelog.ui.Rule
import com.yourversionnumber.changelog.ui.SettingsScreen
import com.yourversionnumber.changelog.ui.SignInScreen
import com.yourversionnumber.changelog.ui.UpcomingScreen
import com.yourversionnumber.changelog.ui.paper

/** Log, Upcoming, Activity and Settings, along the bottom. Back from any but Log goes to Log. */
@Composable
private fun Tabs(model: AppModel) {
    val tabs = listOf(
        "log" to say(R.string.log),
        "upcoming" to say(R.string.upcoming),
        "activity" to say(R.string.activity),
        "settings" to say(R.string.settings),
    )
    BackHandler(enabled = model.screen != "log") { model.screen = "log" }
    // Four words share the width, so the labels stop growing past 130% text size; the screens beside them go on to 200%.
    val density = LocalDensity.current
    val labelSize = with(density) { (16f * minOf(fontScale, 1.3f)).dp.toSp() }
    // The tab's own screen handles the status bar; this one only makes room for the tabs.
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            // The site's nav: words on paper under a rule, the one you're on in
            // ink with the tangerine dot of a version beneath it.
            Column(Modifier.background(paper.paper).navigationBarsPadding()) {
                Rule()
                Row(Modifier.fillMaxWidth().selectableGroup()) {
                    tabs.forEach { (id, label) ->
                        val on = model.screen == id
                        Column(
                            Modifier
                                .weight(1f)
                                .selectable(selected = on, role = Role.Tab, onClick = { model.screen = id })
                                .padding(top = 12.dp, bottom = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                label,
                                maxLines = 1,
                                softWrap = false,
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = if (on) FontWeight.ExtraBold else FontWeight.SemiBold,
                                    fontSize = labelSize,
                                ),
                                color = if (on) paper.ink else paper.ink3,
                            )
                            Box(
                                Modifier
                                    .padding(top = 6.dp)
                                    .size(6.dp)
                                    .background(if (on) paper.orange else Color.Transparent, CircleShape),
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(bottom = padding.calculateBottomPadding()).consumeWindowInsets(padding)) {
            when (model.screen) {
                "upcoming" -> UpcomingScreen(model)
                "activity" -> ActivityScreen(model)
                "settings" -> SettingsScreen(model)
                else -> LogScreen(model)
            }
        }
    }
}

class MainActivity : ComponentActivity() {
    companion object {
        /** The screen a notification opens. */
        const val OPEN = "open"

        /** Opened to write: the note box takes the keyboard. */
        const val WRITE = "write"
    }

    private val model: AppModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) receive(intent)
        setContent {
            ChangelogTheme(model.theme) {
                val signedIn by model.signedIn.collectAsStateWithLifecycle()
                if (signedIn) Tabs(model) else SignInScreen(model)
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
        if (intent?.getBooleanExtra(WRITE, false) == true) model.writeNow = true
        val action = intent?.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return
        // The draft, and any message about it, are on Log, wherever the app was left.
        model.screen = "log"
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
