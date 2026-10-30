package com.yourversionnumber.changelog

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yourversionnumber.changelog.ui.ActivityScreen
import com.yourversionnumber.changelog.ui.ChangelogTheme
import com.yourversionnumber.changelog.ui.LocalBarSpace
import com.yourversionnumber.changelog.ui.LogScreen
import com.yourversionnumber.changelog.ui.Rule
import com.yourversionnumber.changelog.ui.SettingsScreen
import com.yourversionnumber.changelog.ui.SignInScreen
import com.yourversionnumber.changelog.ui.UpcomingScreen
import com.yourversionnumber.changelog.ui.paper

/** One tab of the bar along the bottom. */
private class Tab(val id: String, @StringRes val label: Int, @DrawableRes val icon: Int)

private val tabs = listOf(
    Tab("log", R.string.log, R.drawable.ic_tab_log),
    Tab("upcoming", R.string.upcoming, R.drawable.ic_tab_upcoming),
    Tab("activity", R.string.activity, R.drawable.ic_tab_activity),
    Tab("settings", R.string.settings, R.drawable.ic_tab_settings),
)

/** How tall the floating bar is, without its margin. */
private val BarHeight = 76.dp

/** Log, Upcoming, Activity and Settings, in a bar that floats above the screen. Back from any but Log goes to Log. */
@Composable
private fun Tabs(model: AppModel) {
    BackHandler(enabled = model.screen != "log") { model.screen = "log" }
    val inset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Box(Modifier.fillMaxSize().background(paper.paper)) {
        // The screens run to the foot of the window and leave room at the end of their lists for the bar.
        Box(Modifier.fillMaxSize().consumeWindowInsets(WindowInsets.navigationBars)) {
            CompositionLocalProvider(LocalBarSpace provides BarHeight + inset + 16.dp) {
                when (model.screen) {
                    "upcoming" -> UpcomingScreen(model)
                    "activity" -> ActivityScreen(model)
                    "settings" -> SettingsScreen(model)
                    else -> LogScreen(model)
                }
            }
        }
        // A fade of paper under the bar, so what scrolls past its edge doesn't show in the gap.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(BarHeight + inset + 24.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, paper.paper))),
        )
        FloatingTabs(model.screen, { model.screen = it }, Modifier.align(Alignment.BottomCenter))
    }
}

/**
 * The tabs in a rounded bar lifted off the screen, as the iPhone app's: an
 * icon over its word, the one you're on in blue on a soft pill. The one
 * place Changelog casts a shadow.
 */
@Composable
private fun FloatingTabs(current: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    // Four words share the width, so the labels stop growing past 130% text size; the screens beside them go on to 200%.
    val density = LocalDensity.current
    val labelSize = with(density) { (12f * minOf(fontScale, 1.3f)).dp.toSp() }
    val shape = RoundedCornerShape(36.dp)
    Row(
        modifier
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
            .shadow(14.dp, shape, ambientColor = Color.Black.copy(alpha = 0.3f), spotColor = Color.Black.copy(alpha = 0.3f))
            .background(paper.paper, shape)
            .border(1.dp, paper.rule, shape)
            .padding(6.dp)
            .selectableGroup(),
    ) {
        tabs.forEach { tab ->
            val on = current == tab.id
            val tint = if (on) paper.blue else paper.ink2
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(30.dp))
                    .background(if (on) paper.ink.copy(alpha = 0.08f) else Color.Transparent)
                    .selectable(selected = on, role = Role.Tab, onClick = { onSelect(tab.id) })
                    .padding(top = 8.dp, bottom = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(painterResource(tab.icon), contentDescription = null, Modifier.size(26.dp), tint = tint)
                Text(
                    say(tab.label),
                    maxLines = 1,
                    softWrap = false,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = if (on) FontWeight.ExtraBold else FontWeight.SemiBold,
                        fontSize = labelSize,
                    ),
                    color = tint,
                )
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
