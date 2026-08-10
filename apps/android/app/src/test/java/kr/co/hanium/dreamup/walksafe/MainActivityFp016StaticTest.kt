package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityFp016StaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun releaseActiveScreenHidesControlsAndShowsReadOnlySafetyOverlay() {
        assertTrue(source.contains("private lateinit var controlsScroll: ScrollView"))
        assertTrue(source.contains("private lateinit var walkSafetyOverlay: LinearLayout"))

        val predicate = Regex(
            """val\s+([A-Za-z_][A-Za-z0-9_]*)\s*=\s*!BuildConfig\.DEBUG\s*&&\s*isWalkSessionRuntimeActive\(\)""",
        ).find(source)
        assertTrue("release ACTIVE screen predicate is missing", predicate != null)
        val releaseActive = requireNotNull(predicate).groupValues[1]
        val policy = compact(enclosingFunction(predicate.range.first))

        assertTrue(
            policy.contains(
                "controlsScroll.visibility = if ($releaseActive) View.GONE else View.VISIBLE",
            ),
        )
        assertTrue(
            policy.contains(
                "walkSafetyOverlay.visibility = if ($releaseActive) View.VISIBLE else View.GONE",
            ),
        )
        assertTrue(
            policy.contains(
                "val releasePaused = !BuildConfig.DEBUG && " +
                    "walkSessionLifecycle.snapshot().state == WalkSessionState.PAUSED",
            ),
        )
        assertTrue(
            policy.contains(
                "if (releasePaused) { controlsScroll.visibility = View.GONE " +
                    "walkSafetyOverlay.visibility = View.VISIBLE",
            ),
        )

        val overlay = blockAt("walkSafetyOverlay = LinearLayout(this).apply {")
        assertTrue(overlay.contains("addView(safetySummaryText)"))
        assertFalse(overlay.contains("addView(statusText)"))
        assertFalse(overlay.contains("Button("))
        assertFalse(overlay.contains("EditText("))
        assertFalse(overlay.contains("setOnClickListener"))
    }

    @Test
    fun developerControlsAreConstructedAndAttachedOnlyInsideDebugBlocks() {
        val debugBlocks = Regex("""if\s*\(\s*BuildConfig\.DEBUG\s*\)\s*\{""")
            .findAll(source)
            .map { bracedBlockRange(it.range.first) }
            .toList()
        assertTrue("BuildConfig.DEBUG UI block is missing", debugBlocks.isNotEmpty())

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
            assertOnlyInsideDebugBlocks("addView($name)", debugBlocks)
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
    fun systemBackRechecksAndCancelsOutputsBeforeShowingExitConfirmation() {
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
        val recheck = Regex(
            """enterWalkSessionForegroundRecheckAndCancelOutputs\s*\(\s*"system_back_exit_confirmation"\s*\)""",
        ).find(handler)?.range?.first ?: -1
        val automaticResumeGuard =
            handler.indexOf("walkSessionResumeRetryRequiresUserAction = true")
        val showDialog = handler.indexOf("showWalkExitConfirmationDialog()")

        assertTrue(recheck >= 0)
        assertTrue(automaticResumeGuard > recheck)
        assertTrue(showDialog > automaticResumeGuard)
        assertFalse(handler.contains("system_back_exit_confirmation_already_paused"))

        val dialog = blockAt("private fun showWalkExitConfirmationDialog()")
        assertTrue(dialog.contains("AlertDialog.Builder(this)"))
        assertTrue(dialog.contains(".setPositiveButton("))
        assertTrue(dialog.contains(".setNegativeButton("))
        assertTrue(dialog.contains(".show()"))
        assertFalse(dialog.contains("walkSessionResumeRetryRequiresUserAction = false"))
        assertFalse(
            Regex(
                """(?i)\b(?:resume[A-Za-z0-9_]*|startDepthSession|handleWalkSessionForegroundReturn)\s*\(""",
            ).containsMatchIn(dialog),
        )
    }

    @Test
    fun safetySummaryIsPoliteWhileHighFrequencyStatusIsNotLive() {
        val safetySummary = blockAt("safetySummaryText = TextView(this).apply {")
        val highFrequencyStatus = blockAt("statusText = TextView(this).apply {")

        assertTrue(
            safetySummary.contains(
                "accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE",
            ),
        )
        assertTrue(
            highFrequencyStatus.contains(
                "accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE",
            ),
        )
        assertFalse(
            highFrequencyStatus.contains(
                "accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE",
            ),
        )
        assertFalse(
            highFrequencyStatus.contains(
                "accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE",
            ),
        )
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
