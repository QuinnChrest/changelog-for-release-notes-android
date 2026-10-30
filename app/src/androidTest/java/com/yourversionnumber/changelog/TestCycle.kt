package com.yourversionnumber.changelog

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yourversionnumber.changelog.kit.Api
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import org.junit.runner.RunWith
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * The test cycle, as Changelog for iPhone's ui-test-cycle.sh: the real app on
 * an emulator against the Release Notes dev server and its fictional account,
 * checking the server for each thing done. It skips without a dev token and
 * never runs against production (debug builds start on the dev server).
 *
 *     ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.devToken=rn_...
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class TestCycle {
    private val token: String = InstrumentationRegistry.getArguments().getString("devToken").orEmpty()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ChangelogApp

    /** Before the app opens: signed out, on the dev server, nothing waiting. */
    // A plain rule, not a TestWatcher, which would swallow the skip and run the test anyway.
    private val fresh = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue("Pass -Pandroid.testInstrumentationRunnerArguments.devToken=rn_... to run the test cycle", token.isNotEmpty())
                // Android 17: the dev server is on the local network.
                if (android.os.Build.VERSION.SDK_INT >= 37) {
                    InstrumentationRegistry.getInstrumentation().uiAutomation
                        .grantRuntimePermission(app.packageName, "android.permission.ACCESS_LOCAL_NETWORK")
                }
                val graph = app.graph
                graph.signOut()
                graph.server = BuildConfig.DEV_SERVER
                runBlocking { graph.outbox.items.value.forEach { graph.outbox.discard(it.id) } }
                base.evaluate()
            }
        }
    }

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(fresh).around(compose)

    private val api get() = Api(BuildConfig.DEV_SERVER, token, "Changelog/0.0.0 (TestCycle)")

    private fun AndroidComposeTestRule<*, *>.see(text: String, substring: Boolean = true, timeout: Long = 20_000) {
        waitUntil(timeout) { onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun AndroidComposeTestRule<*, *>.tap(text: String): SemanticsNodeInteraction =
        onAllNodesWithText(text).onFirst().also {
            // Into view first when it's in a list; a bar's button is already there.
            runCatching { it.performScrollTo() }
            it.performClick()
        }

    private fun AndroidComposeTestRule<*, *>.tab(name: String) = onNode(hasText(name) and isSelectable()).performClick()

    @Test
    fun theCycle() {
        val mark = UUID.randomUUID().toString().take(8)

        // Sign in with the dev server's token.
        compose.see("Notes in, two taps.")
        compose.onNode(hasSetTextAction()).performTextInput(token)
        compose.tap("Sign in")
        compose.see("What changed?")

        // A note, through the Outbox, filed once.
        val words = "Test cycle $mark #devtest"
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput(words)
        compose.tap("Commit")
        compose.see("Filed in")
        val filed = runBlocking {
            api.activity(kinds = listOf("note"), limit = 20).items.mapNotNull { row ->
                // Rows for notes deleted since are still in the log: skip those.
                row.note?.let { id -> row.day?.let { runCatching { api.note(it, id) }.getOrNull() } }
            }.filter { it.text == words }
        }
        assertEquals("the note filed exactly once", 1, filed.size)

        // Upcoming: a birthday added, then read back from the server.
        compose.tab("Upcoming")
        compose.tap("Add a date")
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput("Cycle $mark")
        compose.tap("Save")
        compose.see("Cycle $mark")
        val date = runBlocking { api.upcoming().entries.single { it.title == "Cycle $mark" } }

        // Activity tells of the note and the date.
        compose.tab("Activity")
        compose.see("Filed in")
        compose.see("Cycle $mark")

        // Settings names the build.
        compose.tab("Log")
        compose.tap("Settings")
        compose.see("build ${BuildConfig.COMMIT}")
        assertTrue(BuildConfig.VERSION_NAME.matches(Regex("""\d+\.\d+\.\d+""")))

        // Leave the fictional account as it was.
        runBlocking {
            api.deleteUpcoming(date.id, date.revision, UUID.randomUUID().toString())
            filed.forEach { api.deleteNote(it.date, it.id, UUID.randomUUID().toString()) }
        }
    }
}
