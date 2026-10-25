package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidanceStartBypassBuildContractTest {
    private val buildScript = File("build.gradle.kts").readText()
    private val mainSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    private fun section(source: String, start: String, end: String): String {
        val startIndex = source.indexOf(start)
        assertTrue("Missing section start: $start", startIndex >= 0)
        val contentStart = startIndex + start.length
        val endIndex = source.indexOf(end, contentStart)
        assertTrue("Missing section end: $end", endIndex >= 0)
        return source.substring(contentStart, endIndex)
    }

    @Test
    fun onlyLiteralTrueEnablesDebugGuidanceStartBypass() {
        assertTrue(
            Regex(
                """val debugGuidanceStartBypass\s*=\s*providers\.gradleProperty\("walksafe\.guidanceStartBypass"\)\.orNull == "true"""",
            ).containsMatchIn(buildScript),
        )
        val expected = BuildConfig.DEBUG &&
            System.getProperty("walksafe.guidanceStartBypass") == "true"
        assertEquals(expected, BuildConfig.WALKSAFE_DEBUG_GUIDANCE_START_BYPASS)
        assertEquals(
            when {
                !BuildConfig.DEBUG -> "walksafe-release-v1"
                expected -> "walksafe-debug-v1-bypass-on"
                else -> "walksafe-debug-v1"
            },
            BuildConfig.WALKSAFE_BUILD_MARKER,
        )
    }

    @Test
    fun bypassBuildHasDistinctEmbeddedMarkerWhileReleaseRemainsOff() {
        val buildTypes = section(buildScript, "    buildTypes {", "    compileOptions {")
        val debug = section(buildTypes, "        debug {", "        release {")
        val release = section(buildTypes, "        release {", "\n    }")
        assertTrue(buildScript.contains("if (debugGuidanceStartBypass) \"walksafe-debug-v1-bypass-on\" else \"walksafe-debug-v1\""))
        assertTrue(debug.contains("buildConfigString(debugGuidanceBuildMarker)"))
        assertTrue(debug.contains("buildConfigField(\"boolean\", \"WALKSAFE_DEBUG_GUIDANCE_START_BYPASS\", \"\$debugGuidanceStartBypass\")"))
        assertTrue(release.contains("buildConfigField(\"boolean\", \"WALKSAFE_DEBUG_GUIDANCE_START_BYPASS\", \"false\")"))
        assertTrue(release.contains("walksafe-release-v1"))
    }

    @Test
    fun debugAssembleExportsLabeledApkAndMachineReadableReceipt() {
        val export = section(buildScript, "androidComponents {", "tasks.withType<org.gradle.api.tasks.testing.Test>()")
        assertTrue(export.contains("variant.artifacts.get(SingleArtifact.APK)"))
        assertTrue(export.contains("variant.artifacts.getBuiltArtifactsLoader()"))
        assertTrue(export.contains("apkLoader.load(apkDirectory.get())"))
        assertTrue(export.contains("builtApks.elements.size == 1"))
        assertTrue(export.contains("walksafe-debug-guidance-start-bypass-\$debugGuidanceArtifactLabel.apk"))
        assertTrue(export.contains("walksafe-debug-guidance-start-bypass-\$debugGuidanceArtifactLabel.json"))
        assertTrue(export.contains("\\\"guidance_start_bypass_enabled\\\""))
        assertTrue(export.contains("\\\"apk_sha256\\\""))
        assertTrue(export.contains("finalizedBy(exportTask)"))
    }

    @Test
    fun failedOrUnrunDebugAssembleCannotExportAReceipt() {
        val export = section(buildScript, "androidComponents {", "tasks.withType<org.gradle.api.tasks.testing.Test>()")
        val guard = section(
            export,
            "onlyIf(\"assembleDebug completed successfully\") {",
            "\n            }",
        )
        assertTrue(guard.contains("assembleDebugTasks.singleOrNull()?.state?.let"))
        assertTrue(guard.contains("state.executed && state.failure == null"))
        assertTrue(guard.contains("!state.skipped || state.upToDate"))
        assertTrue(guard.contains("} == true"))
    }

    @Test
    fun activeBypassIsLabeledOnGuidanceScreenAndStartLog() {
        val guidanceStatus = MainActivityRefactorSource.member(mainSource, "nativeGuidanceStatusMessage")
        assertTrue(guidanceStatus.contains("NativeGuidanceTextPresentation.guidanceStatus("))
        assertTrue(guidanceStatus.contains("developmentBypassEnabled = { developmentGuidanceStartBypassEnabled }"))
        assertTrue(guidanceStatus.contains("buildMarker = BuildConfig.WALKSAFE_BUILD_MARKER"))
        val update = MainActivityRefactorSource.member(mainSource, "updateNativeGuidanceStatus")
        assertTrue(update.contains("destinationSearchPresenter.renderGuidanceStatus(nativeGuidanceStatusMessage(presentation, false)"))
        val presentation = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/NativeGuidanceTextPresentation.kt").readText()
        val render = MainActivityRefactorSource.member(presentation, "guidanceStatus")
        assertTrue(render.contains("developmentBypassEnabled() ->"))
        assertTrue(render.contains("시험용 우회 켜짐 [\$buildMarker]"))
        assertTrue("the guidance screen must keep the bypass notice when measurements are omitted",
            render.indexOf("developmentBypassEnabled() ->") < render.indexOf("!includeMeasurements -> null"))
        val bypassStart = MainActivityRefactorSource.member(mainSource, "beginDevelopmentGuidanceImmediately")
        assertTrue(bypassStart.contains("event=GUIDANCE_START_BYPASS_REQUESTED"))
        assertTrue(bypassStart.contains("build_marker=\${BuildConfig.WALKSAFE_BUILD_MARKER}"))
    }
}
