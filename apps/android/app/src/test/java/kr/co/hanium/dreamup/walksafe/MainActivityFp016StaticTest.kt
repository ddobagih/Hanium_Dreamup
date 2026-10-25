package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityFp016StaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    private fun ui(name: String) = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/$name.kt").readText()
    private fun member(text: String, name: String) = MainActivityRefactorSource.member(text, name)

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
                "controlsScroll.visibility = if (cameraScreenVisible) View.GONE else View.VISIBLE",
            ),
        )
        val cameraScreen = compact(blockAt("private fun updateLiveCameraScreenVisibility()"))
        assertTrue(cameraScreen.contains("controlsScroll.visibility = if (cameraScreenVisible) View.GONE else View.VISIBLE"))
        assertTrue(cameraScreen.contains("cameraScreenControls.visibility = if (cameraScreenVisible) View.VISIBLE else View.GONE"))
        assertTrue(policy.contains("renderMainUi()"))
        assertTrue(blockAt("private fun renderMainUi()").contains("pages.updateLiveVisibility()"))
        assertTrue(MainActivityRefactorSource.composition(source, "nativeWorkflowController").contains("this@MainActivity.updateLiveCameraScreenVisibility()"))
        assertTrue(
            policy.contains(
                "walkSafetyOverlay.visibility = View.GONE",
            ),
        )
        assertFalse(policy.contains("!BuildConfig.DEBUG"))

        assertTrue(source.contains("walkSafetyOverlay = runtimeComposer.createWalkSafetyOverlay("))
        val overlay = member(ui("NativeRuntimeViewComposer"), "createWalkSafetyOverlay")
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
        val runtime = ui("NativeRuntimeViewComposer")
        val developerControls = listOf(
            Triple("destinationLatInput", "createDestinationLatitudeInput", "EditText(context)"),
            Triple("destinationLngInput", "createDestinationLongitudeInput", "EditText(context)"),
            Triple("debugUploadButton", "createDebugUploadAction", "Button(context)"),
            Triple("debugFrameCaptureButton", "createDebugCaptureAction", "Button(context)"),
            Triple("fieldSessionLogButton", "createFieldSessionAction", "Button(context)"),
        )
        assertTrue(member(source, "buildContentView").contains("val runtimeComposer = NativeRuntimeViewComposer(this, viewStyle)"))
        developerControls.forEach { (name, factory, type) ->
            assertOnlyInsideDebugBlocks("$name = runtimeComposer.$factory(", debugBlocks)
            assertTrue(member(runtime, factory).contains(type))
            val attachment = "addView($name)"
            if (source.contains(attachment)) assertOnlyInsideDebugBlocks(attachment, debugBlocks)
        }
        val url = MainActivityRefactorSource.property(ui("SettingsPrivacyPanel"), "backendUrlInput")
        assertTrue(url.contains("if (BuildConfig.DEBUG) {"))
        assertTrue(url.contains("EditText(context)"))
        assertTrue(url.contains("} else {\n        null"))
        assertTrue(ui("SettingsPrivacyPanel").contains("backendUrlInput?.let { addView(it) }"))
        val build = member(source, "buildContentView")
        assertTrue(build.contains("val settingsPanel = SettingsPrivacyPanel("))
        assertTrue(build.contains("if (BuildConfig.DEBUG) NativeRuntimeViewComposer.DeveloperControls("))
        assertTrue(build.contains("positionFieldExportButton, positionFieldDeleteButton, destinationLatInput, destinationLngInput,"))
        assertTrue(build.contains(") else null,"))
        assertTrue(member(runtime, "createRuntimeDebugControls").contains("if (developer != null)"))
    }

    @Test
    fun releaseGatewaySurfaceIsAvailableOnlyForDeletionRecovery() {
        val panel = ui("SettingsPrivacyPanel")
        listOf("val backendFieldTokenInput: EditText = EditText(context)",
            "val backendAuthApplyButton: Button = Button(context)",
            "addView(backendFieldTokenInput)", "addView(backendAuthApplyButton)").forEach { anchor ->
            assertTrue("one gateway control owner: $anchor", panel.split(anchor).size - 1 == 1)
        }
        val build = member(source, "buildContentView")
        assertTrue(build.contains("settingsPrivacyPresenter = SettingsPrivacyPresenter(settingsPanel, onboardingPanel)"))
        assertTrue(build.contains("settingsPrivacyPresenter.publishGatewayInputTarget()"))
        assertTrue(build.contains("settingsPrivacyPresenter.publishGatewayActionTarget()"))

        val click = blockAt("private fun onGatewaySessionButtonClicked()")
        assertTrue(
            click.contains(
                "if (!config.debug && !deletionRecoverySurface) return",
            ),
        )
        assertTrue(MainActivityRefactorSource.composition(source, "gatewaySessionController").contains(
            "BuildConfig.DEBUG, BuildConfig.WALKSAFE_LONG_LIVED_LOGIN_ENABLED, BuildConfig.WALKSAFE_GATEWAY_ORIGIN,"))
        val visibility = blockAt("private fun updateBackendAuthButtonText()")
        assertTrue(visibility.contains("val passwordRecoverySurface = accountDeletionPasswordRecoveryBindingOrNull() != null"))
        assertTrue(
            visibility.contains(
                "val surfaceVisible = (BuildConfig.DEBUG || deletionRecoverySurface) && !passwordRecoverySurface",
            ),
        )
        assertTrue(visibility.contains("settingsPrivacyPresenter.renderGatewayControls("))
        assertTrue(visibility.contains("surfaceVisible, requestInFlight, deletionRecoverySessionReady,"))
        val presenter = member(ui("SettingsPrivacyPresenter"), "renderGatewayControls")
        assertTrue(presenter.contains("GatewayControlsRenderer.render("))
        assertTrue(presenter.contains("override fun showToken(visible: Boolean) { token.visibility = if (visible) android.view.View.VISIBLE else android.view.View.GONE }"))
        assertTrue(presenter.contains("override fun showAction(visible: Boolean) { action.visibility = if (visible) android.view.View.VISIBLE else android.view.View.GONE }"))
        assertTrue(presenter.contains("override fun clearToken() { token.text?.clear() }"))
        assertTrue(presenter.contains("override fun setTokenEnabled(enabled: Boolean) { token.isEnabled = enabled }"))
        val rendering = member(ui("GatewayControlsRenderer"), "render")
        assertTrue(rendering.contains("target.showToken(surfaceVisible)"))
        assertTrue(rendering.contains("target.showAction(surfaceVisible)"))
        assertTrue(rendering.contains("if (!surfaceVisible) target.clearToken()"))
        assertTrue(rendering.contains("target.setTokenEnabled(target.actionEnabled())"))
    }

    @Test
    fun systemBackReturnsFromFeaturesOrEndsTheWalkAfterCancellingOutputs() {
        assertTrue(source.contains(") : Activity(), GLSurfaceView.Renderer"))
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
        val features = member(source, "handleNativeFeatureBackPressed")
        assertTrue(features.contains("cancelNativeDestinationSearchAndReturnHome()"))
        assertTrue(features.contains("cancelNativeVoiceCommandAndReturnHome()"))
        assertTrue(features.contains("showNativeUiPage(NativeUiPage.HOME)"))
    }

    @Test
    fun appOwnedSpeechStatusRemainsDiscoverableWithoutDuplicateLiveAnnouncements() {
        val build = member(source, "buildContentView")
        val runtime = ui("NativeRuntimeViewComposer")
        val statuses = listOf("safetySummaryText" to "createSafetySummary", "statusText" to "createRuntimeStatus",
            "walkSafetyVoiceStatusText" to "createWalkVoiceStatus")
        val blocks = statuses.map { (name, factory) ->
            assertTrue(build.contains("$name = runtimeComposer.$factory()"))
            member(runtime, factory)
        } + MainActivityRefactorSource.property(ui("DestinationGuidancePanel"), "gatewayVoiceStatusText")
        assertTrue(build.contains("val destinationPanel = DestinationGuidancePanel("))
        assertTrue(build.contains("nativeScreenPresenter.publishVoiceTargets(destinationPanel)"))
        blocks.forEach { block ->
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
        if (marker.startsWith("private fun ")) return MainActivityRefactorSource.function(source, marker)
        if (marker.startsWith("override fun ")) return member(source, marker.substringAfter("fun ").substringBefore('('))
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
