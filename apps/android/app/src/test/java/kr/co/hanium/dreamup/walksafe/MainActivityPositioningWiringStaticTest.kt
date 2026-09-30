package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source-level wiring checks for positioning inputs that cannot run on the JVM. */
class MainActivityPositioningWiringStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun actorChangeStartsFromANewCoordinatorBeforeRestoringThatActorsProfile() {
        val profile = functionBlock("private fun ensurePositioningProfileForCurrentActor(")
        val replaced = profile.indexOf("positioningCoordinator = PositioningCoordinator()")
        val actorBound = profile.indexOf("positioningProfileActorId = actorId")
        val restored = profile.indexOf("restored?.let(positioningCoordinator::restoreProfile)")
        assertTrue(replaced >= 0 && actorBound > replaced && restored > actorBound)
        // S09-56 (09.txt:154): the load status is kept so default step length and storage failure differ.
        assertTrue(profile.contains("pedestrianProfileStore?.loadResult(id)"))

        val persist = functionBlock("private fun persistPositioningProfileForCurrentActor(")
        assertTrue(persist.contains("if (actorId != positioningProfileActorId) return"))
    }

    @Test
    fun zuptKeepsSensorMeasurementTimeAndCallbackReceiptTimeSeparate() {
        val sources = functionBlock("private fun startPositioningObservationSources(")
        assertTrue(sources.contains("ZuptObservation(decision.sample?.elapsedRealtimeMs ?: nowMs)"))
        assertTrue(sources.contains("receivedAtElapsedRealtimeMs = nowMs"))
    }

    @Test
    fun zuptIsRateLimitedAndRequiresPhysicalStationaryEvidence() {
        val sources = functionBlock("private fun startPositioningObservationSources(")
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
        val handler = functionBlock("private fun handleLocationUpdate(")
        val gate = handler.indexOf("val matchedEvidenceCurrent = !positionGuidancePaused &&")
        val guidance = handler.indexOf("updateRouteGuidance(filteredTrusted, rawRouteLocation)")
        assertTrue(gate >= 0 && guidance > gate)
        assertTrue(handler.substring(gate, guidance).contains("if (matchedEvidenceCurrent) {"))
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
