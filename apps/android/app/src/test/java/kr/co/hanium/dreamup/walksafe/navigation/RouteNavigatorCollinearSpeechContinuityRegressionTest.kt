package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.*
import org.junit.Test

/** Real Navigator inputs/reservation/completion, no state or accepted-match injection.
 * The fixed heading and fresh +/-0.3 m positions cross a provider's straight polyline vertex.
 * Coordinates are synthetic; this does not establish physical stationary or native TTS output.
 */
class RouteNavigatorCollinearSpeechContinuityRegressionTest {
    @Test fun aStraightVertexInsideReportedPositionUncertaintyKeepsThePlayingCue() {
        val fixture=Fixture()
        val first=fixture.firstCue()
        assertTrue(fixture.nav.isInstructionCurrent(first,1_001L,heading(1_001L)))
        val firstSegment=requireNotNull(fixture.nav.currentRouteMatch()).segmentIndex
        val jitter=fixture.update(50.3,2_000L)
        assertNotEquals("The reproduction must actually cross the provider polyline vertex",firstSegment,
            fixture.nav.currentRouteMatch()?.segmentIndex)
        assertFalse("Same target/bearing/clock/metres support must not cancel the playing sentence: $jitter",
            jitter.cancelStaleNavigationSpeech)
        assertEquals("guidance_in_flight",jitter.reason)
        assertTrue("The original reservation must still accept its real terminal callback",
            fixture.nav.isInstructionCompletionCurrent(first,2_001L))
        val back=fixture.update(49.7,3_000L)
        assertFalse(back.cancelStaleNavigationSpeech)
        assertFalse(back.arrivalCandidate)
        assertFalse(back.arrived)
        assertNull("Stationary jitter must not create a new route/arrival decision",back.pendingUserDecision)
        assertTrue(fixture.nav.isInstructionCompletionCurrent(first,3_001L))
        fixture.nav.acknowledgeInstruction(first,3_001L)
        assertFalse(fixture.nav.isInstructionCompletionCurrent(first,3_002L))
        assertNull("DONE starts the existing completed-cue cadence; jitter must not manufacture a new instruction",
            fixture.update(50.3,4_000L).instruction)
    }

    @Test fun aQueuedNormalPacketCanUseANewActualHeadingAcrossTheSameStraightVertex() {
        val fixture=Fixture()
        val first=fixture.firstCue()
        val original=requireNotNull(first.speechCueToken)
        assertEquals(1_000L,original.facingObservedAtElapsedRealtimeMs)
        fixture.update(50.3,2_000L)
        assertTrue("A new real heading must qualify the same queued NORMAL packet after its original lease",
            fixture.nav.prepareFreshDirectionStart(first,2_000L,heading(2_000L)))
        assertTrue(fixture.nav.isInstructionCurrent(first,2_001L,heading(2_001L)))
        assertEquals("Fresh START authorization must not rewrite the packet's original observation",1_000L,
            original.facingObservedAtElapsedRealtimeMs)
        assertTrue(fixture.nav.isInstructionCompletionCurrent(first,2_100L))
        fixture.nav.acknowledgeInstruction(first,2_100L)
        assertFalse(fixture.nav.isInstructionCompletionCurrent(first,2_101L))
    }

    @Test fun aStraightVertexDoesNotRestartOrDiscardTheSameDirectionGraceEpisode() {
        val fixture=Fixture()
        val first=fixture.firstCue()
        fixture.nav.retryGuidance(1_200L,badHeading(1_200L))
        val original=requireNotNull(fixture.nav.currentDirectionGraceSnapshot())
        assertEquals(RouteDirectionGraceMode.GRACE_RETAINED,original.mode)
        assertEquals(1_200L,original.onsetElapsedRealtimeMs)
        assertEquals(10_200L,original.deadlineElapsedRealtimeMs)
        for((north,at) in listOf(50.3 to 2_000L,49.7 to 3_000L,50.3 to 4_000L)) {
            val jitter=fixture.update(north,at,badHeading(at))
            val current=requireNotNull(fixture.nav.currentDirectionGraceSnapshot())
            assertFalse("A straight segmentation change is not a new direction failure",jitter.cancelStaleNavigationSpeech)
            assertEquals(original.episodeId,current.episodeId)
            assertEquals(original.onsetElapsedRealtimeMs,current.onsetElapsedRealtimeMs)
            assertEquals(original.deadlineElapsedRealtimeMs,current.deadlineElapsedRealtimeMs)
            assertEquals(1_000L,current.retainedObservedAtElapsedRealtimeMs)
            assertTrue("The actual original NORMAL packet keeps its separate grace grant",
                fixture.nav.isInstructionCurrent(first,at,badHeading(at)))
            assertFalse(jitter.arrivalCandidate)
            assertFalse(jitter.arrived)
        }
        val recovery=fixture.nav.retryGuidance(4_200L,heading(4_200L))
        assertFalse(requireNotNull(recovery).cancelStaleNavigationSpeech)
        assertFalse(fixture.nav.isDirectionRecheckPending())
        assertTrue(fixture.nav.isInstructionCompletionCurrent(first,4_201L))
        fixture.nav.acknowledgeInstruction(first,4_201L)
        assertFalse(fixture.nav.isInstructionCompletionCurrent(first,4_202L))
    }

    @Test fun anActualCornerStillRetiresThePreviousRelativeDirection() {
        val fixture=Fixture(corner=true)
        val first=fixture.firstCue()
        val changed=fixture.update(50.0,2_000L,east=0.3)
        assertTrue("The next leg points east instead of north",changed.cancelStaleNavigationSpeech)
        assertFalse(fixture.nav.isInstructionCurrent(first,2_001L,heading(2_001L)))
        assertFalse(fixture.nav.isInstructionCompletionCurrent(first,2_001L))
        assertFalse(changed.arrivalCandidate)
        assertFalse(changed.arrived)
    }

    @Test fun aFreshMeasuredReversalStillRetiresTheStraightVertexCue() {
        val fixture=Fixture()
        val first=fixture.firstCue()
        assertTrue(fixture.nav.hasHardInvalidatedDirectionClaim(1_200L,heading(1_200L,180.0)))
        val changed=requireNotNull(fixture.nav.retryGuidance(1_200L,heading(1_200L,180.0)))
        assertTrue(changed.cancelStaleNavigationSpeech)
        assertFalse(fixture.nav.isInstructionCompletionCurrent(first,1_201L))
    }

    @Test fun untrustedPositionAndRouteReplacementCannotReuseTheStraightVertexCue() {
        for(replaceRoute in listOf(false,true)) {
            val fixture=Fixture()
            val first=fixture.firstCue()
            if(replaceRoute) fixture.nav.setRoute(route("replacement",false))
            else assertTrue(requireNotNull(fixture.nav.onUntrustedLocation()).cancelStaleNavigationSpeech)
            assertFalse(fixture.nav.isInstructionCurrent(first,1_200L,heading(1_200L)))
            assertFalse(fixture.nav.isInstructionCompletionCurrent(first,1_200L))
            assertNotEquals(RouteNavigatorUserDecision.ARRIVAL_CONFIRMATION,fixture.nav.pendingUserDecision())
        }
    }

    private class Fixture(corner:Boolean=false) {
        var now=1_000L
        val nav=RouteNavigator(RouteNavigatorConfig(directionRecheckGraceMs=9_000L),
            nowElapsedRealtimeMs={now}).apply {setRoute(route("collinear",corner))}
        fun firstCue():RouteNavigatorUpdate {
            val first=update(49.7,1_000L)
            assertTrue("The fixture must emit a real single clock/metres cue: ${first.instruction}",
                first.instruction.orEmpty().contains("12시 방향"))
            assertTrue(first.instruction.orEmpty().contains("미터"))
            assertTrue(nav.reserveInstruction(first))
            return first
        }
        fun update(north:Double,at:Long,facing:RouteFacingObservation=heading(at),east:Double=0.0):RouteNavigatorUpdate {
            now=at
            val point=point(east,north)
            return nav.update(TrustedLocation(point.latitude,point.longitude,3f,at),at,false,
                stepProgressM=0.0,facingObservation=facing)
        }
    }

    companion object {
        private fun route(id:String,corner:Boolean):WalkingRoute {
            val endpoint=if(corner) point(100.0,50.0) else point(0.0,150.0)
            return WalkingRoute(id,WalkingRouteSummary(150,150),listOf(point(0.0,0.0),point(0.0,50.0),endpoint),
                listOf(WalkingRouteGuidePoint(0,endpoint,"직진",150,0,bearingDeg=if(corner)90f else 0f,turnType=11,pointType="guide")))
        }
        private fun point(east:Double,north:Double)=RoutePoint(37.0+north/111_194.92664455874,
            127.0+east/(111_194.92664455874*kotlin.math.cos(Math.toRadians(37.0))))
        private fun heading(at:Long,degrees:Double=0.0)=RouteFacingObservation(degrees,5.0,at,
            RouteFacingSource.ROTATION_VECTOR,RouteFacingSensorQuality.REPORTED_ANGULAR_ERROR,
            reportedHeadingAccuracyDegrees=5.0)
        private fun badHeading(at:Long)=heading(at).copy(headingAccuracyDegrees=80.0,reportedHeadingAccuracyDegrees=80.0)
    }
}
