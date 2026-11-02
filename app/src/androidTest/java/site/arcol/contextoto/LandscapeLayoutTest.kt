package site.arcol.contextoto

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Real Compose + rotation tests. Fixtures use an isolated DB/preferences and no network. */
class LandscapeLayoutTest {
    @get:Rule val ui = createAndroidComposeRule<LayoutTestActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store: UserStore
    private lateinit var content: Content
    private lateinit var settings: SecureSettings
    private var textScale by mutableFloatStateOf(1f)

    @Before fun fixture() {
        context.deleteDatabase("landscape-ui.db")
        store = UserStore(context, "landscape-ui.db")
        content = Content(context)
        AnalysisArchive.installPreset(context, content, store)
        val preferences = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences("landscape-ui-$name", mode).also { it.edit().clear().commit() }
        }
        settings = SecureSettings(preferences).apply { guideStep = 4; dark = true; silentInference = false }
        LearningStore(store, content).addWord("research")
        val engine = AnalysisEngine(content, store, AnalysisTransport { _, _, _, _, _ -> error("Layout tests must not call a model") })
        ui.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, textScale)) { ReaderApp(content, store, settings, engine) }
        }
        rotate(true)
    }

    @After fun cleanup() {
        ui.runOnUiThread { ui.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        ui.activityRule.scenario.close()
        store.close(); context.deleteDatabase("landscape-ui.db")
    }

    private fun rotate(wide: Boolean) {
        val host = ui.activity
        ui.runOnUiThread { host.requestedOrientation = if (wide) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        ui.waitUntil(15_000) {
            runCatching { ui.onNodeWithTag("reader-surface").fetchSemanticsNode().boundsInRoot.let { (it.width > it.height) == wide } }.getOrDefault(false)
        }
        ui.waitForIdle()
        assertSame("Rotation must not replace the screen or its in-flight UI", host, ui.activity)
    }

    private fun token(tag: String, offset: Int, hold: Boolean = false) {
        val layouts = mutableListOf<TextLayoutResult>()
        ui.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val point = layouts.single().getBoundingBox(offset).center
        ui.onNodeWithTag(tag).performTouchInput { if (hold) longClick(point) else click(point) }
        ui.waitForIdle()
    }

    private fun screenshot(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureLayouts") != "true") return
        val folder = File(context.getExternalFilesDir(null), "landscape-evidence").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use {
            ui.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun insideSurface(tag: String) {
        val surface = ui.onNodeWithTag("reader-surface").fetchSemanticsNode().boundsInRoot
        val sheet = ui.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        assertTrue("$tag above usable space", sheet.top >= surface.top - 1)
        assertTrue("$tag below usable space", sheet.bottom <= surface.bottom + 1)
        assertTrue("$tag outside left safe edge", sheet.left >= surface.left - 1)
        assertTrue("$tag outside right safe edge", sheet.right <= surface.right + 1)
    }

    @Test fun wordDetailSurvivesBothRotationsAndKeepsItsBounds() {
        screenshot("01-reading-wide")
        ui.onNodeWithTag("reader-list").performScrollToIndex(1)
        token("reader-paragraph-0", 0)
        ui.onNodeWithTag("word-detail").assertIsDisplayed(); insideSurface("word-detail")
        ui.onNode(hasTestTag("detail-reference") and hasAnyAncestor(hasTestTag("word-detail"))).assertIsDisplayed()
        screenshot("02-word-wide")
        val lookups = store.countLookups(content.articles.first().id, "word")
        rotate(false); ui.onNodeWithTag("word-detail").assertIsDisplayed(); insideSurface("word-detail")
        screenshot("03-word-portrait")
        rotate(true); insideSurface("word-detail")
        assertEquals("Reflow must not record another lookup", lookups, store.countLookups(content.articles.first().id, "word"))
        ui.runOnUiThread { ui.activity.onBackPressedDispatcher.onBackPressed() }
        ui.onNodeWithTag("word-detail").assertDoesNotExist()
    }

    @Test fun nonInitialSentenceWordsRemainInteractiveAfterRotation() {
        ui.onNodeWithTag("reader-list").performScrollToIndex(1)
        val secondSentence = Content.sentences(content.articles.first().paragraphs.first())[1]
        token("reader-paragraph-0", secondSentence.start, true)
        ui.onNodeWithTag("sentence-detail").assertIsDisplayed(); insideSurface("sentence-detail")
        screenshot("04-sentence-wide")
        rotate(false); rotate(true)
        token("sentence-original", Content.tokens(secondSentence.text)[2].start)
        ui.onNodeWithTag("word-detail").assertIsDisplayed(); insideSurface("word-detail")
        screenshot("05-nested-word-wide")
        ui.runOnUiThread { ui.activity.onBackPressedDispatcher.onBackPressed() }
        ui.onNodeWithTag("sentence-detail").assertIsDisplayed()
    }

    @Test fun settingsDraftAndFooterSurviveRotation() {
        ui.onNodeWithTag("article-menu-button").performClick()
        screenshot("06-directory-wide")
        ui.onNodeWithText("接口与外观设置").performClick()
        ui.onNodeWithText("保存并返回").assertIsDisplayed()
        screenshot("07-settings-wide")
        ui.onNodeWithText("READING SCALE").performScrollTo().performClick()
        ui.onNodeWithText("20 sp").assertExists()
        ui.onNodeWithTag("settings-nav-1").performClick()
        ui.onNodeWithText("REVIEW & ENROLLMENT").performScrollTo().performClick()
        ui.onNodeWithText("10").performScrollTo().performClick().performTextReplacement("17")
        screenshot("07b-settings-keyboard-wide")
        rotate(false); rotate(true)
        ui.onNodeWithText("17").assertExists()
        ui.onNodeWithText("保存并返回").assertIsDisplayed().performClick()
        assertEquals(17, settings.randomImportCount)
        ui.onNodeWithTag("reader-list").assertIsDisplayed()
    }

    @Test fun reviewQuestionAndScoreStayStableThroughRotation() {
        ui.onNodeWithTag("reader-surface").performTouchInput { swipeLeft() }
        ui.waitUntil(15_000) { ui.onAllNodesWithText("research").fetchSemanticsNodes().isNotEmpty() }
        screenshot("08-review-wide")
        rotate(false); screenshot("08b-review-portrait"); rotate(true); screenshot("08c-review-reflow-wide")
        ui.onNodeWithText("记住了").assertIsDisplayed().performClick()
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("word-detail").fetchSemanticsNodes().isNotEmpty() }
        ui.waitForIdle()
        ui.onNodeWithTag("word-detail").assertIsDisplayed(); insideSurface("word-detail")
        screenshot("09-answer-wide")
        assertEquals(1, LearningStore(store, content).studyWord("research")!!.memory.reviews)
        rotate(false); rotate(true)
        assertEquals(1, LearningStore(store, content).studyWord("research")!!.memory.reviews)
        ui.runOnUiThread { ui.activity.onBackPressedDispatcher.onBackPressed() }
        ui.onNodeWithTag("word-detail").assertDoesNotExist()
    }

    @Test fun guideHasRealNestedInteractionsAndLargeTextFallsBackToOneColumn() {
        ui.onNodeWithTag("article-menu-button").performClick()
        ui.onNodeWithText("使用引导").performClick()
        screenshot("10-guide-wide")
        ui.onNodeWithText("下一步").performClick()
        token("guide-example", 0, true)
        token("guide-sentence-original", 7)
        ui.onNodeWithTag("word-title").assertTextEquals("sentence")
        rotate(false); rotate(true)
        ui.runOnUiThread { textScale = 1.5f }
        ui.onNodeWithTag("word-detail").assertIsDisplayed(); insideSurface("word-detail")
        ui.onAllNodes(hasTestTag("detail-reference") and hasAnyAncestor(hasTestTag("word-detail"))).assertCountEquals(0)
        screenshot("11-large-text-wide")
        repeat(2) { ui.runOnUiThread { ui.activity.onBackPressedDispatcher.onBackPressed() }; ui.waitForIdle() }
        ui.onNodeWithTag("guide-example").assertIsDisplayed()
    }

    @Test fun wordMenuLibrariesAndCollectionKeepTheirModeThroughRotation() {
        ui.onNodeWithTag("reader-surface").performTouchInput { swipeLeft() }
        ui.onNodeWithTag("study-menu-button").performClick()
        screenshot("12-word-menu-wide")
        ui.onNodeWithText("WORD BANKS").performClick()
        ui.onNodeWithText("DAILY RHYTHM").assertIsDisplayed()
        screenshot("13-libraries-wide")
        rotate(false); rotate(true)
        ui.onNodeWithText("DAILY RHYTHM").assertIsDisplayed()
        ui.onNodeWithTag("study-menu-button").performClick()
        ui.onNodeWithText("COLLECTION").performClick()
        ui.onNodeWithText("搜索单词").assertIsDisplayed()
        screenshot("14-collection-wide")
    }

    @Test fun nativeStatusBarAndLightTasksSheetRemainWithinSafeBounds() {
        ui.onNodeWithTag("article-menu-button").performClick()
        ui.onNodeWithText("接口与外观设置").performClick()
        ui.onNodeWithText("深色主题").performClick()
        ui.onNodeWithText("沉浸状态栏").performClick()
        ui.onNodeWithText("保存并返回").performClick()
        screenshot("15-reading-light-native")
        ui.onNodeWithText("100%").performClick()
        ui.onNodeWithTag("tasks-detail").assertIsDisplayed(); insideSurface("tasks-detail")
        screenshot("16-tasks-light-native")
        rotate(false); rotate(true)
        insideSurface("tasks-detail")
    }
}
