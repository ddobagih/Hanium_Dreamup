package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityFp016StaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun activeAndPausedScreensUseTheSingleScrollableProductSurfaceInEveryBuild() {
        assertTrue(source.contains("private lateinit var controlsScroll: ScrollView"))
        assertTrue(source.contains("private lateinit var walkSafetyOverlay: LinearLayout"))

        val policy = compact(blockAt("private fun syncActiveSessionScreenPolicy()"))

        assertTrue(policy.contains("val walkSessionScreenVisible ="))
        assertTrue(policy.contains("WalkSessionState.ACTIVE"))
        assertTrue(policy.contains("WalkSessionState.PAUSED"))

        assertTrue(
            policy.contains(
                "controlsScroll.visibility = View.VISIBLE",
            ),
        )
        assertTrue(
            policy.contains(
                "walkSafetyOverlay.visibility = View.GONE",
            ),
        )
        assertFalse(policy.contains("!BuildConfig.DEBUG"))

        val overlay = blockAt("walkSafetyOverlay = LinearLayout(this).apply {")
        assertFalse(overlay.contains("addView(safetySummaryText)"))
        assertFalse(overlay.contains("addView(runtimeControls)"))
        assertFalse(overlay.contains("Button("))
        assertFalse(overlay.contains("EditText("))
        assertFalse(overlay.contains("setOnClickListener"))
    }

    @Test
    fun developerControlsRemainDebugOnlyWithBracedOrSingleStatementGuards() {
        val debugBlocks = Regex("""if\s*\(\s*BuildConfig\.DEBUG\s*\)\s*\{""")
            .findAll(source).map { bracedBlockRange(it.range.first) }.toList() +
            Regex("""if\s*\(\s*BuildConfig\.DEBUG\s*\)\s*addView\([A-Za-z0-9_]+\)""")
                .findAll(source).map { it.range }.toList()
        assertTrue("BuildConfig.DEBUG UI guard is missing", debugBlocks.isNotEmpty())
        val developerControls = listOf(
            "backendUrlInput" to "EditText(",
            "destinationLatInput" to "EditText(",
            "destinationLngInput" to "EditText(",
            "debugUploadButton" to "Button(",
            "debugFrameCaptureButton" to "Button(",
            "fieldSessionLogButton" to "Button(",
        )
        developerControls.forEach { (name, type) ->
            assertOnlyInsideDebugBlocks("$name = $type", debugBlocks)
            val attachment = "addView($name)"
            if (source.contains(attachment)) assertOnlyInsideDebugBlocks(attachment, debugBlocks)
        }
    }

    @Test
    fun releaseGatewaySurfaceIsAvailableOnlyForDeletionRecovery() {
        val tokenCreation = occurrenceIndices("backendFieldTokenInput = EditText(")
        val buttonCreation = occurrenceIndices("backendAuthApplyButton = Button(")
        val tokenAttachment = occurrenceIndices("addView(backendFieldTokenInput)")
        val buttonAttachment = occurrenceIndices("addView(backendAuthApplyButton)")
        listOf(tokenCreation, buttonCreation, tokenAttachment, buttonAttachment).forEach {
            assertTrue(it.size == 1)
        }

        val click = blockAt("private fun onGatewaySessionButtonClicked()")
        assertTrue(
            click.contains(
                "if (!BuildConfig.DEBUG && !deletionRecoverySurface) return",
            ),
        )
        val visibility = blockAt("private fun updateBackendAuthButtonText()")
        assertTrue(
            visibility.contains(
                "val surfaceVisible = BuildConfig.DEBUG || deletionRecoverySurface",
            ),
        )
        assertTrue(visibility.contains("backendFieldTokenInput.visibility ="))
        assertTrue(visibility.contains("backendAuthApplyButton.visibility ="))
        assertTrue(visibility.contains("if (!surfaceVisible) backendFieldTokenInput.text?.clear()"))
        assertTrue(
            visibility.contains(
                "backendFieldTokenInput.isEnabled = backendAuthApplyButton.isEnabled",
            ),
        )
    }

    @Test
    fun systemBackReturnsFromFeaturesOrEndsTheWalkAfterCancellingOutputs() {
        assertTrue(source.contains("class MainActivity : Activity()"))
        val callback = blockAt(
            "private val walkScreenBackCallback = object : OnBackPressedCallback(true)",
        )
        assertTrue(callback.contains("handleWalkScreenBackPressed()"))
        assertTrue(callback.contains("isEnabled = false"))
        assertTrue(callback.contains("walkBackDispatcher.onBackPressed()"))
        val registration = blockAt("override fun onCreate(savedInstanceState: Bundle?)")
        assertTrue(
            registration.contains(
                "walkBackDispatcher = OnBackPressedDispatcher { finishAfterTransition() }",
            ),
        )
        assertTrue(
            registration.contains(
                "walkBackDispatcher.addCallback(walkScreenBackCallback)",
            ),
        )
        assertTrue(
            registration.contains(
                "walkBackDispatcher.setOnBackInvokedDispatcher(onBackInvokedDispatcher)",
            ),
        )
        val legacyBack = blockAt("override fun onBackPressed()")
        assertTrue(legacyBack.contains("walkBackDispatcher.onBackPressed()"))

        val handler = blockAt("private fun handleWalkScreenBackPressed()")
        val featureBack = handler.indexOf("if (handleNativeFeatureBackPressed()) return true")
        val end = handler.indexOf("transitionWalkSession(WalkSessionEvent.EndRequested)")
        val marker = handler.indexOf("persistWalkSessionInterruptionMarker()")
        val cancellation = handler.indexOf("cancelWalkSessionOutputs(\"system_back_exit_confirmed\")")
        val finish = handler.indexOf("finish()")
        assertTrue(featureBack >= 0)
        assertTrue(handler.contains("state != WalkSessionState.ACTIVE && state != WalkSessionState.PAUSED"))
        assertTrue(end > featureBack)
        assertTrue(marker > end)
        assertTrue(cancellation > marker)
        assertTrue(finish > cancellation)
        val features = blockAt("private fun handleNativeFeatureBackPressed()")
        assertTrue(features.contains("cancelNativeDestinationSearchAndReturnHome()"))
        assertTrue(features.contains("cancelNativeVoiceCommandAndReturnHome()"))
        assertTrue(features.contains("showNativeUiPage(NativeUiPage.HOME)"))
    }

    @Test
    fun appOwnedSpeechStatusRemainsDiscoverableWithoutDuplicateLiveAnnouncements() {
        listOf("safetySummaryText", "statusText", "gatewayVoiceStatusText", "walkSafetyVoiceStatusText").forEach { name ->
            val block = blockAt("$name = TextView(this).apply {")
            assertTrue(block.contains("importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES"))
            assertTrue(block.contains("accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE"))
            assertFalse(block.contains("accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE"))
            assertFalse(block.contains("accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE"))
        }
    }

    private fun assertOnlyInsideDebugBlocks(anchor: String, debugBlocks: List<IntRange>) {
        val occurrences = occurrenceIndices(anchor)
        assertTrue("$anchor is missing", occurrences.isNotEmpty())
        assertTrue(
            "$anchor must not be created or attached outside BuildConfig.DEBUG",
            occurrences.all { index -> debugBlocks.any { index in it } },
        )
    }

    private fun occurrenceIndices(needle: String): List<Int> {
        val result = mutableListOf<Int>()
        var fromIndex = 0
        while (fromIndex < source.length) {
            val index = source.indexOf(needle, fromIndex)
            if (index < 0) break
            result += index
            fromIndex = index + needle.length
        }
        return result
    }

    private fun blockAt(marker: String): String {
        val markerIndex = source.indexOf(marker)
        assertTrue("missing block marker: $marker", markerIndex >= 0)
        val range = bracedBlockRange(markerIndex)
        return source.substring(range.first, range.last + 1)
    }

    private fun enclosingFunction(index: Int): String {
        val functionStart = maxOf(
            source.lastIndexOf("private fun ", index),
            source.lastIndexOf("internal fun ", index),
            source.lastIndexOf("override fun ", index),
        )
        assertTrue("enclosing function is missing", functionStart >= 0)
        val range = bracedBlockRange(functionStart)
        return source.substring(range.first, range.last + 1)
    }

    private fun bracedBlockRange(start: Int): IntRange {
        val openingBrace = source.indexOf('{', start)
        require(openingBrace >= 0) { "opening brace is missing after index $start" }
        var depth = 0
        for (index in openingBrace until source.length) {
            when (source[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return openingBrace..index
                }
            }
        }
        error("closing brace is missing after index $openingBrace")
    }

    private fun compact(value: String): String = value.replace(Regex("""\s+"""), " ")
}
