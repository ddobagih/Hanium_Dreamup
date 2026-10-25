package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source-level wiring checks for positioning inputs that cannot run on the JVM. */
class MainActivityPositioningWiringStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun actorChangeStartsFromANewCoordinatorBeforeRestoringThatActorsProfile() {
        val profile = MainActivityRefactorSource.function(source, "private fun ensurePositioningProfileForCurrentActor(")
        val replaced = profile.indexOf("positioningCoordinator = defaultPositioningCoordinator()")
        val actorBound = profile.indexOf("positioningProfileActorId = actorId")
        val restored = profile.indexOf("restored?.let(positioningCoordinator::restoreProfile)")
        assertTrue(replaced >= 0 && actorBound > replaced && restored > actorBound)
        assertTrue(profile.contains("pedestrianProfileStore?.loadResult(id)"))
        val persist = MainActivityRefactorSource.function(source, "private fun persistPositioningProfileForCurrentActor(")
        assertTrue(persist.contains("if (actorId != positioningProfileActorId) return"))
    }

    @Test
    fun zuptKeepsSensorMeasurementTimeAndCallbackReceiptTimeSeparate() {
        val sources = MainActivityRefactorSource.function(source, "private fun startPositioningObservationSources(")
        assertTrue(sources.contains("ZuptObservation(decision.sample?.elapsedRealtimeMs ?: nowMs)"))
        assertTrue(sources.contains("receivedAtElapsedRealtimeMs = nowMs"))
        val androidSources = File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/AndroidNavigationSources.kt").readText()
        val tracker = MainActivityRefactorSource.member(androidSources, "pedestrianTracker")
        assertTrue(tracker.contains("AndroidPedestrianMotionTracker(context, onDecision = onMotion)"))
        val sourcesFactory = MainActivityRefactorSource.factory(source, "createNavigationAndroidSources")
        assertTrue(sourcesFactory.contains("AndroidNavigationSources("))
        assertTrue(sourcesFactory.contains("context = this,"))
        assertTrue(MainActivityRefactorSource.factory(source, "createNavigationSessions").contains("sources = { createNavigationAndroidSources() }"))
        val positioning = MainActivityRefactorSource.composition(source, "positioningSessionController")
        assertTrue(positioning.contains("val sources = positions.sources()"))
        assertTrue(positioning.contains("PositioningSessionController(run, scope, sources, mounting,"))
    }

    @Test
    fun zuptIsRateLimitedAndRequiresPhysicalStationaryEvidence() {
        val sources = MainActivityRefactorSource.function(source, "private fun startPositioningObservationSources(")
        assertTrue(sources.contains("nowMs - it >= 1_000L"))
        assertTrue(sources.contains("decision.shouldApplyZeroVelocityUpdate && userMotion.zeroVelocityUpdateAllowed && zuptDue"))
    }

    @Test
    fun productionRouteMatcherAllowsWalkingBackAlongTheCorridor() {
        val navigator = File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/RouteNavigator.kt").readText()
        val matcher = navigator.substringAfter("private val routeCorridorMatcher = RouteCorridorMatcher(")
            .substringBefore("    )\n")
        assertTrue(matcher.contains("allowReverseTravel = true"))
    }

    @Test
    fun routeGuidanceRequiresThePositionConfidenceGateIndependentlyOfTheCorridorMatch() {
        val handler = NavigationPolicySource.observation(source)
        val dispatch = handler.indexOf("val matchedEvidenceCurrent = guidance().dispatchAcceptedRouteFix(")
        assertTrue(handler.indexOf("val positionGuidancePaused = guidance().applyPositionConfidenceDecision(") in 0 until dispatch)
        assertTrue(handler.substring(dispatch).contains("filteredTrusted, rawRouteLocation, positionGuidancePaused,"))
        assertTrue(handler.indexOf("appendGnssTrace(positioningSnapshot, matchedEvidenceCurrent)") > dispatch)
        val accepted = MainActivityRefactorSource.function(source, "internal fun dispatchAcceptedRouteFix(")
        assertTrue(accepted.contains("val matchedEvidenceCurrent = !positionPaused && directionGuidancePauseReason != \"tmap_unavailable\""))
        assertTrue(accepted.contains("dispatchGuidance: (TrustedLocation, TrustedLocation) -> Unit = ::updateRouteGuidance"))
        assertTrue(accepted.contains("if (matchedEvidenceCurrent) dispatchGuidance(location, rawLocation)"))
        assertTrue(accepted.contains("return matchedEvidenceCurrent"))
        assertTrue(accepted.contains("publishDecision(decision.copy(reason = \"tmap_unavailable\"))"))
        assertTrue(MainActivityRefactorSource.composition(source, "positioningSessionController").contains("{ guidance.value }"))
        assertTrue(MainActivityRefactorSource.composition(source, "routeGuidanceController").contains("RouteGuidanceController(run,"))
        assertTrue(MainActivityRefactorSource.property(source, "routeGuidanceController").contains("by navigationSessions.guidance"))
    }

    private fun functionBlock(marker: String): String {
        val markerIndex = source.indexOf(marker)
        require(markerIndex >= 0) { "missing function marker: $marker" }
        val openingBrace = source.indexOf('{', markerIndex)
        var depth = 0
        for (index in openingBrace until source.length) {
            when (source[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return source.substring(markerIndex, index + 1)
                }
            }
        }
        error("missing closing brace: $marker")
    }
}
