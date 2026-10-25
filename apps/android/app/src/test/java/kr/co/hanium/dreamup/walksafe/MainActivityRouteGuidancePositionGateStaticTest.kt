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
        val owned = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/RouteGuidanceController.kt")
        fun method(name: String, occurrence: Int = 0) = MainActivityRefactorSource.member(owned, name, occurrence)
        assertEquals(0, Regex("""routeNavigator\.update\(""").findAll(main).count())
        assertEquals(1, Regex("""routeNavigator\.update\(""").findAll(owned).count())
        val guidance = method("updateRouteGuidance")
        val gate = guidance.indexOf("if (!scope.developmentBypass &&\n            (positionGuidancePaused || directionGuidancePauseReason == \"tmap_unavailable\")\n        ) {")
        val update = guidance.indexOf("routeNavigator.update(")
        assertTrue(gate in 0 until update)
        assertTrue(guidance.substring(gate, update).contains("            return\n"))
        val position = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/PositioningSessionController.kt")
        val acquisition = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/RouteAcquisitionController.kt")
        val handler = NavigationPolicySource.observation(main)
        val install = MainActivityRefactorSource.member(acquisition, "requestRoute", 1)
        assertEquals(1, Regex("guidance\\(\\)\\.updateRouteGuidance\\(").findAll(handler).count())
        assertTrue(handler.contains("guidance().updateRouteGuidance(navigationTestFix, navigationTestFix)"))
        assertEquals(1, Regex("effects\\.updateGuidance\\(").findAll(install).count())
        assertTrue(install.contains("effects.updateGuidance(current, facts.latestRawLocation ?: current)"))
        val connected = install.indexOf("val connectedInstallLocation = requireNotNull(completedLocation)")
        val installed = install.indexOf("installRequestedRoute(route, destination, origin,")
        val fresh = install.indexOf("facts.currentLocation?.let { current ->")
        assertTrue(connected in 0 until installed)
        assertTrue(installed in 0 until fresh)
        assertTrue(fresh in 0 until install.indexOf("effects.updateGuidance(current, facts.latestRawLocation ?: current)"))
        val compatibility = MainActivityRefactorSource.member(acquisition, "installRequestedRoute")
        assertTrue(compatibility.contains("installRequestedRoute(route, destination, origin, coordinateEvidence, reason,"))
        assertTrue(compatibility.contains("routeResponseLocationStatus(route, origin, origin, origin.elapsedRealtimeMs) =="))
        assertTrue(compatibility.contains("RouteResponseInstallLocationStatus.APPROACH_REQUIRED"))
        assertTrue(install.contains("completedLocationStatus == RouteResponseInstallLocationStatus.APPROACH_REQUIRED)"))
        val installer = MainActivityRefactorSource.member(acquisition, "installRequestedRoute", 1)
        assertTrue(installer.contains("approachRequired: Boolean"))
        assertTrue(installer.contains("routeNavigator.setRoute("))
        assertTrue(installer.contains("origin = origin"))
        assertTrue(installer.contains("waitForInitialRouteJoin = approachRequired"))
        val matched = handler.indexOf("val matchedEvidenceCurrent = guidance().dispatchAcceptedRouteFix(")
        assertTrue(handler.indexOf("val positionGuidancePaused = guidance().applyPositionConfidenceDecision(positioningSnapshot,") in 0 until matched)
        assertTrue(handler.substring(matched).contains("filteredTrusted, rawRouteLocation, positionGuidancePaused,"))
        assertTrue(handler.contains("routeConfidence = warmingSelection?.confidence ?: positioningSnapshot.confidence"))
        assertTrue(handler.indexOf("appendGnssTrace(positioningSnapshot, matchedEvidenceCurrent)") > matched)
        val accepted = method("dispatchAcceptedRouteFix")
        assertEquals(1, Regex("::updateRouteGuidance").findAll(owned).count())
        assertTrue(accepted.contains("dispatchGuidance: (TrustedLocation, TrustedLocation) -> Unit = ::updateRouteGuidance"))
        assertTrue(accepted.contains("val matchedEvidenceCurrent = !positionPaused && directionGuidancePauseReason != \"tmap_unavailable\""))
        assertTrue(accepted.contains("if (matchedEvidenceCurrent) dispatchGuidance(location, rawLocation)"))
        assertTrue(accepted.contains("return matchedEvidenceCurrent && !location.navigationOnly && !rawLocation.navigationOnly"))
        assertTrue(accepted.contains("publishDecision(decision.copy(reason = \"tmap_unavailable\"))"))
        val testFix = handler.indexOf("val navigationTestFix = if (scope.developmentBypass) {")
        assertTrue(testFix in 0 until handler.indexOf("guidance().updateRouteGuidance(navigationTestFix, navigationTestFix)"))
        assertTrue(handler.substring(testFix).contains("} else null"))
        assertTrue(MainActivityRefactorSource.member(position, "currentRouteLocationOrNull").contains("if (!scope.developmentBypass) return freshTrustedLocationOrNull()"))
        assertTrue(MainActivityRefactorSource.composition(main, "positioningSessionController").contains("{ guidance.value }"))
        val acquisitionWire = MainActivityRefactorSource.composition(main, "routeAcquisitionController")
        assertTrue(acquisitionWire.contains("override fun updateGuidance(location: TrustedLocation, rawLocation: TrustedLocation) = guidance.value.updateRouteGuidance(location, rawLocation)"))
        assertTrue(MainActivityRefactorSource.member(main, "updateRouteGuidance").contains("routeGuidanceController.updateRouteGuidance(location, rawLocation)"))
    }

    @Test
    fun manualAndRetryGuidanceReuseOnlyAGatedContextThatLowQualityInterrupts() {
        val owned = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/RouteGuidanceController.kt")
        fun method(name: String, occurrence: Int = 0) = MainActivityRefactorSource.member(owned, name, occurrence)
        val voice = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/voice/interaction/VoiceCommandInteractionController.kt")
        val manual = MainActivityRefactorSource.member(voice, "speakNextNavigationInstruction")
        assertEquals(1, Regex("""routeRun\(\)\.navigator\.currentGuidance\(""").findAll(voice).count())
        assertEquals(1, Regex("""routeNavigator\.currentGuidance\(""").findAll(owned).count())
        assertEquals(2, Regex("""routeNavigator\.retryGuidance\(""").findAll(owned).count())
        assertTrue(manual.contains("routeRun().navigator.currentGuidance(nowMs, compass?.observation, compass?.reason)"))
        assertTrue(manual.indexOf("!route.navigationCollectionAllowsWork()") in 0 until manual.indexOf("navigator.currentGuidance("))
        val scheduled = method("scheduleNavigationSpeechRetry")
        assertTrue(scheduled.contains("retryCurrentNavigationSpeech(elapsedRealtimeMs(), epoch, requestGeneration, lifecycleGeneration)"))
        val retry = method("retryCurrentNavigationSpeech")
        assertTrue(retry.indexOf("!isRouteSpeechCurrent(epoch, requestGeneration, lifecycleGeneration) || positionGuidancePaused") in 0 until retry.indexOf("retryAdmittedNavigationSpeech(nowMs, epoch, compass)"))
        val admitted = method("retryAdmittedNavigationSpeech", 1)
        assertTrue(admitted.contains("if (directionGuidancePauseReason == \"tmap_unavailable\") return"))
        assertTrue(admitted.contains("val snapshotNowMs = maxOf(nowMs, elapsedRealtimeMs())"))
        assertTrue(admitted.contains("snapshotNowMs, facingObservation = compass?.observation,"))
        assertTrue(admitted.contains("routeNavigator.retryGuidance(snapshotNowMs, compass?.observation, compass?.reason, firstBadAtMs)"))
        val decision = method("applyPositionConfidenceDecision")
        val interrupted = decision.indexOf("routeNavigator.onPositioningEvidenceInterrupted()")
        assertTrue(decision.indexOf("val positioningEvidenceInterrupted = decision.guidancePaused && !positionGuidancePaused") in 0 until interrupted)
        assertTrue(decision.indexOf("if (positioningEvidenceInterrupted)") in 0 until interrupted)
        assertTrue(interrupted < decision.indexOf("positionGuidancePaused = decision.guidancePaused"))
        assertTrue(MainActivityRefactorSource.member(main, "createVoiceInteractionControllers").contains("routeRun = { navigationRunState }"))
        val peers = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/voice/interaction/VoiceInteractionControllers.kt")
        assertTrue(peers.contains("VoiceCommandInteractionController(search, routeRun, walk, route,"))
        assertTrue(voice.contains("AndroidVoiceAction.SpeakNextNavigationInstruction -> speakNextNavigationInstruction()"))
        assertTrue(MainActivityRefactorSource.composition(main, "routeGuidanceController").contains("RouteGuidanceController(run,"))
    }

    @Test
    fun theDevelopmentBypassAndTestModeAreDebugOnly() {
        MainActivityRefactorSource.composition(main, "positioningSessionController")
        val owned = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/PositioningSessionController.kt")
        fun method(name: String, occurrence: Int = 0) = MainActivityRefactorSource.member(owned, name, occurrence)
        val graph = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/NavigationSessionComposition.kt")
        val run = MainActivityRefactorSource.property(graph, "run")
        val activityFactory = MainActivityRefactorSource.factory(main, "createNavigationSessions")
        assertTrue(MainActivityRefactorSource.property(main, "navigationRunState").contains("get() = navigationSessions.run"))
        assertTrue(run.contains("NavigationRunState(RouteNavigator("))
        assertTrue(run.contains("allowDegradedRouteGuidance = allowDegradedRouteGuidance"))
        assertTrue(activityFactory.contains("allowDegradedRouteGuidance = BuildConfig.DEBUG && BuildConfig.WALKSAFE_DEBUG_GUIDANCE_START_BYPASS"))
        val bypass = MainActivityRefactorSource.property(main, "developmentGuidanceStartBypassEnabled")
        assertTrue(bypass.contains("get() = BuildConfig.DEBUG && BuildConfig.WALKSAFE_DEBUG_GUIDANCE_START_BYPASS &&"))
        assertEquals(2, Regex("""NavigationTestLocationPolicy\.freshOrNull\(""").findAll(owned).count())
        assertTrue(method("currentRouteLocationOrNull").contains("if (!scope.developmentBypass) return freshTrustedLocationOrNull()"))
        assertTrue(NavigationPolicySource.observation(main).contains("val navigationTestFix = if (scope.developmentBypass) {"))
        assertTrue(ReportStaticSourceInspector.blockAfter(MainActivityRefactorSource.factory(main, "createNavigationSessions"), "scope = { object : PositioningSessionController.Scope").contains("override val developmentBypass get() = developmentGuidanceStartBypassEnabled"))
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
