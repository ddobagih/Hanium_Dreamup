package kr.co.hanium.dreamup.walksafe.navigation
import kr.co.hanium.dreamup.walksafe.navigation.positioning.*
import org.junit.Assert.*
import org.junit.Test

class RouteGeometryTransientRecoveryProbeTest {
    @Test fun theOriginalHealthyAmbiguousThenTwoHealthySequenceAutomaticallyResumesTheSameRoute() {
        val f=Fixture(20.0);val initial=f.update(5.0,0.0,3.0,1000L)
        assertEquals(RouteMatchQuality.HIGH,f.nav.currentRouteMatch()?.quality)
        assertTrue(f.nav.reserveInstruction(initial));f.nav.acknowledgeInstruction(initial,1000L)
        val before=f.progress()
        val bad=f.update(6.0,2.5,5.0,2000L)
        assertEquals(RouteMatchReason.AMBIGUOUS_CANDIDATES,f.nav.currentRouteMatch()?.reason)
        assertFalse(bad.userDecisionRequired);assertFalse(bad.cancelStaleNavigationSpeech)
        assertNull(f.nav.currentAcceptedRouteMatchFor(2000L));assertEquals(before,f.progress(),0.001)
        assertTrue(grace(f.nav,2000L))
        f.update(7.0,0.0,3.0,3000L)
        assertEquals(RouteMatchQuality.HIGH,f.nav.currentRouteMatch()?.quality)
        assertNull(f.nav.currentAcceptedRouteMatchFor(3000L));assertEquals(before,f.progress(),0.001)
        val recovered=f.update(8.0,0.0,3.0,4000L)
        assertFalse(recovered.userDecisionRequired);assertNull(f.nav.pendingUserDecision())
        assertTrue(f.nav.canResumeAfterPositionRecovery())
        assertNotNull(f.nav.currentAcceptedRouteMatchFor(4000L))
        val requested=f.nav.currentGuidance(4000L,f.heading(4000L))!!
        assertEquals(RouteFacingExpression.CLOCK,requested.facingDiagnostic?.expression)
        assertTrue(f.nav.reserveInstruction(requested))
        assertTrue(f.nav.isInstructionCurrent(requested,4000L,f.heading(4000L)))
    }
    @Test fun theWholeNineSecondsKeepTheOriginalPacketWithoutCreatingPositionOrArrivalEvidence() {
        val f=Fixture(20.0);val action=f.update(5.0,0.0,3.0,1000L)
        assertTrue(f.nav.reserveInstruction(action));val before=f.progress()
        for(at in listOf(2000L,3500L,5000L,6500L,8000L,9500L,10999L)) {
            val bad=f.update(6.0,2.5,5.0,at)
            assertFalse(bad.userDecisionRequired);assertFalse(bad.cancelStaleNavigationSpeech)
            assertFalse(bad.arrivalCandidate);assertFalse(bad.arrived)
            assertNull(f.nav.currentAcceptedRouteMatchFor(at));assertEquals(before,f.progress(),0.001)
            assertTrue(grace(f.nav,at))
            assertTrue(f.nav.prepareFreshDirectionStart(action,at,f.heading(at)))
            assertTrue(f.nav.isInstructionCurrent(action,at,f.heading(at)))
        }
        assertFalse(f.nav.isInstructionCompletionCurrent(action,11000L))
        val limited=f.update(6.0,2.5,5.0,11000L)
        assertTrue(limited.userDecisionRequired);assertTrue(limited.cancelStaleNavigationSpeech)
        assertEquals(RouteNavigatorUserDecision.LOCATION_RECHECK,f.nav.pendingUserDecision())
        assertFalse(f.nav.reserveInstruction(action));assertFalse(grace(f.nav,11000L))
    }
    @Test fun aRealOutsideRecheckRemainsSeparateFromTheTransientGeometryEpisode() {
        val f=Fixture(20.0);f.update(5.0,0.0,3.0,1000L)
        val outside=f.update(6.0,30.0,3.0,2000L)
        assertTrue(outside.userDecisionRequired)
        f.update(7.0,0.0,3.0,3000L);f.update(8.0,0.0,3.0,4000L)
        assertEquals(RouteNavigatorUserDecision.LOCATION_RECHECK,f.nav.pendingUserDecision())
        assertFalse(f.nav.canResumeAfterPositionRecovery());assertFalse(grace(f.nav,4000L))
    }
    @Test fun duplicatedHealthyGpsCannotBecomeTheSecondRecoveryMeasurement() {
        val f=Fixture(20.0);f.update(5.0,0.0,3.0,1000L);val before=f.progress()
        f.update(6.0,2.5,5.0,2000L);f.update(7.0,0.0,3.0,3000L)
        f.now=3500L
        val duplicate=f.nav.update(f.fix(7.0,0.0,3.0,3000L),3500L,false,
            filteredPosition=f.position(7.0,0.0,3.0,3000L),facingObservation=f.heading(3500L))
        assertFalse(duplicate.userDecisionRequired);assertEquals(before,f.progress(),0.001)
        assertNull(f.nav.currentAcceptedRouteMatchFor(3000L))
        f.update(8.0,0.0,3.0,4000L);assertNotNull(f.nav.currentAcceptedRouteMatchFor(4000L))
    }
    @Test fun replacingTheRouteRetiresTheGeometryGrantAndOldSpeechPacket() {
        val f=Fixture(20.0);val old=f.update(5.0,0.0,3.0,1000L);assertTrue(f.nav.reserveInstruction(old))
        f.update(6.0,2.5,5.0,2000L);assertTrue(grace(f.nav,2000L))
        f.nav.setRoute(f.route(),origin=f.fix(0.0,0.0,3.0,2000L))
        assertFalse(grace(f.nav,2000L));assertFalse(f.nav.reserveInstruction(old))
        assertFalse(f.nav.isInstructionCurrent(old,2000L,f.heading(2000L)))
    }
    @Test fun aColdAmbiguousInitialJoinProducesNoClockThenAutomaticallyAcceptsTwoNewHealthySamples() {
        val f=Fixture(20.0);f.nav.setRoute(f.route(),origin=f.fix(0.0,0.0,3.0,0L),waitForInitialRouteJoin=true)
        val cold=f.update(6.0,2.5,5.0,1000L)
        assertEquals(RouteMatchReason.AMBIGUOUS_CANDIDATES,f.nav.currentRouteMatch()?.reason)
        assertNull(cold.instruction);assertFalse(cold.userDecisionRequired)
        assertTrue(f.nav.isWaitingForInitialRouteJoin());assertFalse(grace(f.nav,1000L))
        val first=f.update(7.0,0.0,3.0,2000L)
        assertNull(first.instruction);assertTrue(f.nav.isWaitingForInitialRouteJoin())
        val ready=f.update(8.0,0.0,3.0,3000L)
        assertFalse(ready.userDecisionRequired);assertFalse(f.nav.isWaitingForInitialRouteJoin())
        assertNotNull(f.nav.currentAcceptedRouteMatchFor(3000L));assertNull(f.nav.pendingUserDecision())
        assertEquals(RouteFacingExpression.CLOCK,f.nav.currentGuidance(3000L,f.heading(3000L))!!.facingDiagnostic?.expression)
    }
    @Test fun confirmedGpsAbsenceRetiresOldSpeechAndAutomaticallyResumesAfterTwoRealHealthySamples() {
        val f=Fixture(20.0);val old=f.update(5.0,0.0,3.0,1000L);assertTrue(f.nav.reserveInstruction(old))
        f.now=11000L;val missing=f.nav.onUntrustedLocation()!!
        assertTrue(missing.cancelStaleNavigationSpeech);assertFalse(grace(f.nav,11000L))
        assertFalse(f.nav.isInstructionCurrent(old,11000L,f.heading(11000L)))
        val first=f.update(7.0,0.0,3.0,12000L)
        assertNull(first.instruction);assertNull(f.nav.currentAcceptedRouteMatchFor(12000L))
        val second=f.update(8.0,0.0,3.0,13000L)
        assertFalse(second.userDecisionRequired);assertNull(f.nav.pendingUserDecision())
        assertNotNull(f.nav.currentAcceptedRouteMatchFor(13000L));assertTrue(f.nav.canResumeAfterPositionRecovery())
        assertFalse(f.nav.reserveInstruction(old))
    }
    @Test fun aPositionQualityInterruptionAlsoNeedsTwoNewSamplesAndDoesNotRequireAButton() {
        val f=Fixture(20.0);f.update(5.0,0.0,3.0,1000L)
        f.now=11000L;f.nav.onPositioningEvidenceInterrupted()
        assertFalse(grace(f.nav,11000L))
        assertNull(f.update(7.0,0.0,3.0,12000L).instruction)
        val current=f.update(8.0,0.0,3.0,13000L)
        assertFalse(current.userDecisionRequired);assertNull(f.nav.pendingUserDecision())
        assertNotNull(f.nav.currentAcceptedRouteMatchFor(13000L))
    }
    private fun grace(nav:RouteNavigator,now:Long)=nav.javaClass.methods.firstOrNull {
        it.name=="hasCurrentRouteGeometryGrace" && it.parameterTypes.contentEquals(arrayOf(java.lang.Long.TYPE))
    }?.invoke(nav,now)==true
    private class Fixture(val length:Double) {
        var now=1000L
        val nav=RouteNavigator(config=RouteNavigatorConfig(directionRecheckGraceMs=4500L),nowElapsedRealtimeMs={now}).apply {setRoute(route(),origin=fix(0.0,0.0,3.0,0L))}
        fun p(x:Double,y:Double=0.0)=RoutePoint(y/111194.92664455874,x/111194.92664455874)
        fun route()=WalkingRoute("TRANSIENT",WalkingRouteSummary(200,200),
            listOf(p(0.0),p(length),p(length,8.0),p(0.0,8.0),p(0.0,5.0),p(length,5.0)),emptyList())
        fun heading(at:Long)=RouteFacingObservation(90.0,3.0,at)
        fun fix(x:Double,y:Double,a:Double,at:Long)=p(x,y).let {TrustedLocation(it.latitude,it.longitude,a.toFloat(),at)}
        fun position(x:Double,y:Double,a:Double,at:Long)=FilteredRoutePosition(p(x,y),a,at)
        fun update(x:Double,y:Double,a:Double,at:Long):RouteNavigatorUpdate {now=at;return nav.update(fix(x,y,a,at),at,false,
            filteredPosition=position(x,y,a,at),facingObservation=heading(at))}
        fun progress()=(nav.javaClass.getDeclaredField("progressDistanceM").apply{isAccessible=true}.get(nav) as Double)
    }
}
