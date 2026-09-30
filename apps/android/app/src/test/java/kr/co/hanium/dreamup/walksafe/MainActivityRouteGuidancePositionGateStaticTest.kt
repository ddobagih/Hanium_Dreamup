package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S07-22 (Root R8, 07.txt:222): the route engine has no position-quality gate of its own, so every
 * MainActivity entry into it must sit behind the app's position-quality gate, and the development
 * bypass / navigation test mode must be debug-only. Whether 15 m (a 68% radius) is enough is a field
 * decision and is not pinned here.
 */
class MainActivityRouteGuidancePositionGateStaticTest {
    private val main = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private val gradle = File("build.gradle.kts").readText()

    @Test
    fun theOnlyPositionUpdateIntoTheEngineIsBehindThePositionQualityGate() {
        assertEquals(1, Regex("""routeNavigator\.update\(""").findAll(main).count())
        val guidance = block("private fun updateRouteGuidance(")
        val gate = guidance.indexOf(
            "if (!developmentGuidanceStartBypassEnabled &&\n            (positionGuidancePaused || directionGuidancePauseReason == \"tmap_unavailable\")\n        ) {")
        val update = guidance.indexOf("routeNavigator.update(")
        assertTrue(gate in 0 until update)
        assertTrue(guidance.substring(gate, update).contains("            return\n"))
        // The three callers: the gated trusted fix, the debug-only test fix, and the fresh trusted
        // location when a route becomes ready (itself re-gated above).
        val callers = Regex("""updateRouteGuidance\(([^)]*)\)""").findAll(main)
            .map { it.groupValues[1].replace(Regex("""\s+"""), " ").trim().removeSuffix(",") }
            .filterNot { it.contains("location: TrustedLocation") }.toList()
        assertEquals(listOf(
            "navigationTestFix, navigationTestFix",
            "filteredTrusted, rawRouteLocation",
            "connectedInstallLocation, latestRawRouteLocation ?: connectedInstallLocation",
        ).sorted(), callers.sorted())
        val install = block("private fun requestRoute(")
        assertTrue(install.indexOf("val connectedInstallLocation = requireNotNull(installLocation)") in
            0 until install.indexOf("updateRouteGuidance(\n                                connectedInstallLocation,"))
        val handler = block("private fun handleLocationUpdate(")
        val matched = handler.indexOf("val matchedEvidenceCurrent = !positionGuidancePaused &&")
        val gated = handler.indexOf("if (matchedEvidenceCurrent) {\n            updateRouteGuidance(filteredTrusted, rawRouteLocation)")
        assertTrue(handler.indexOf("val positionGuidancePaused = applyPositionConfidenceDecision(positioningSnapshot)") in 0 until matched)
        assertTrue(matched in 0 until gated)
        val testFix = handler.indexOf("val navigationTestFix = if (developmentGuidanceStartBypassEnabled) {")
        assertTrue(testFix in 0 until handler.indexOf("updateRouteGuidance(navigationTestFix, navigationTestFix)"))
        assertTrue(handler.substring(testFix).contains("} else null"))
        val routeLocation = block("private fun currentRouteLocationOrNull(")
        assertTrue(routeLocation.contains("if (!developmentGuidanceStartBypassEnabled) return freshTrustedLocationOrNull()"))
    }

    @Test
    fun manualAndRetryGuidanceReuseOnlyAGatedContextThatLowQualityInterrupts() {
        assertEquals(1, Regex("""routeNavigator\.currentGuidance\(""").findAll(main).count())
        assertEquals(1, Regex("""routeNavigator\.retryGuidance\(""").findAll(main).count())
        // Neither receives a location: they re-render the context of the last gated update.
        assertTrue(block("private fun speakNextNavigationInstruction(").contains(
            "routeNavigator.currentGuidance(nowMs, currentRouteFacingObservation(nowMs), latestRouteFacingUnavailableReason)"))
        assertTrue(block("private fun scheduleNavigationSpeechRetry(").contains(
            "routeNavigator.retryGuidance(\n                nowMs, facingObservation = currentRouteFacingObservation(nowMs),"))
        val decision = block("private fun applyPositionConfidenceDecision(")
        val interrupted = decision.indexOf("routeNavigator.onPositioningEvidenceInterrupted()")
        assertTrue(decision.indexOf("decision.quality in setOf(PositionQuality.LOW, PositionQuality.UNAVAILABLE)") in 0 until interrupted)
        assertTrue(interrupted < decision.indexOf("positionGuidancePaused = decision.guidancePaused"))
    }

    @Test
    fun theDevelopmentBypassAndTestModeAreDebugOnly() {
        assertTrue(main.contains("private val routeNavigator = RouteNavigator(\n" +
            "        allowDegradedRouteGuidance = BuildConfig.DEBUG && BuildConfig.WALKSAFE_DEBUG_GUIDANCE_START_BYPASS,\n"))
        assertTrue(main.contains("private val developmentGuidanceStartBypassEnabled: Boolean\n" +
            "        get() = BuildConfig.DEBUG && BuildConfig.WALKSAFE_DEBUG_GUIDANCE_START_BYPASS &&"))
        // The navigation test location is produced and read only behind the bypass.
        assertEquals(2, Regex("""NavigationTestLocationPolicy\.freshOrNull\(""").findAll(main).count())
        val release = gradle.substringAfter("        release {").substringBefore("\n        }\n")
        assertTrue(release.contains("buildConfigField(\"boolean\", \"WALKSAFE_DEBUG_GUIDANCE_START_BYPASS\", \"false\")"))
    }

    private fun block(marker: String): String {
        val start = main.indexOf(marker)
        require(start >= 0) { "missing $marker" }
        val signatureEnd = requireNotNull(Regex("""\)(: [A-Za-z<>?, .]+)? \{\n""").find(main, start)) { "no body for $marker" }
        val open = signatureEnd.range.last - 1
        var depth = 0
        for (index in open until main.length) {
            when (main[index]) {
                '{' -> depth += 1
                '}' -> if (--depth == 0) return main.substring(start, index + 1)
            }
        }
        error("unterminated $marker")
    }
}
