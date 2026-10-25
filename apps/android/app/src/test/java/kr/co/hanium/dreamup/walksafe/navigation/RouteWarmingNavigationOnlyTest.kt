package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.FilteredRoutePosition
import kr.co.hanium.dreamup.walksafe.navigation.positioning.HorizontalUncertaintyKind
import org.junit.Assert.*
import org.junit.Test

/** Raw warm-up references support a first cue, never protected passage or automatic arrival. */
class RouteWarmingNavigationOnlyTest {
    @Test fun firstRawReferenceOffersClockAndMetresWithoutConfirmingRouteProgress() {
        val n = nav(); val raw = fix(50.0, 1000L, true, 15f)
        val u = n.update(raw,1000L,false,facingObservation = facing(1000L))
        assertTrue(u.instruction.orEmpty(),u.instruction.orEmpty().contains("약 12시 방향"))
        assertTrue(u.instruction.orEmpty(),u.instruction.orEmpty().contains("약 50미터"))
        assertNull(field(n,"progressDistanceM")); assertNull(field(n,"lastAcceptedProgress"))
        assertFalse(u.arrived); assertNull(u.pendingUserDecision)
        assertTrue(n.hasCurrentNavigationOnlyReference(1000L))
        assertNull(n.currentAcceptedRouteMatchFor(1000L));assertNull(n.currentTactileRouteProgress().segmentIndex)
        assertNull(n.currentProjection(raw))
        assertTrue(n.reserveInstruction(u)); assertTrue(n.isInstructionCurrent(u,1000L,facing(1000L)))
    }

    @Test fun rawReferencesCannotPassAGuideButNormalFilteredEvidenceCanRecover() {
        val n=nav()
        for ((t,m) in listOf(1000L to 110.0,2000L to 111.0,3000L to 112.0)) {
            val u=n.update(fix(m,t,true),t,false,facingObservation=facing(t))
            assertEquals(0,field(n,"nextGuideIndex"));assertNull(field(n,"progressDistanceM"))
            assertFalse(u.arrived)
        }
        n.update(fix(112.0,4000L,false),4000L,false,facingObservation=facing(4000L))
        assertNotNull(field(n,"progressDistanceM"))
        assertFalse(n.isWaitingForInitialRouteJoin())
    }

    @Test fun repeatedWarmReferencesAtTheDestinationNeverCreateAutomaticArrivalEvidence() {
        val n=RouteNavigator().apply {setRoute(WalkingRoute("TEST",WalkingRouteSummary(50,50),
            listOf(point(0.0),point(50.0)),emptyList()))}
        for (t in listOf(1000L,2000L,3000L,4000L,5000L)) {
            val u=n.update(fix(49.0,t,true,1f),t,false,facingObservation=facing(t))
            assertFalse(u.arrived);assertNull(u.pendingUserDecision)
            assertEquals(0,field(n,"arrivalSampleCount")); assertNull(field(n,"progressDistanceM"))
        }
    }

    @Test fun firstClockPlayingSurvivesNormalFilterRecoveryAtTheSamePosition() {
        val n=nav();val first=n.update(fix(50.0,1000L,true,15f),1000L,false,facingObservation=facing(1000L))
        assertTrue(n.reserveInstruction(first))
        val raw=fix(50.0,1200L,false,15f)
        val filtered=FilteredRoutePosition(point(50.0),12.8,1200L,
            uncertaintyKind=HorizontalUncertaintyKind.FILTER_MAX_AXIS_SIGMA)
        val recovered=n.update(raw,1200L,false,filteredPosition=filtered,facingObservation=facing(1200L))
        assertEquals("guidance_in_flight",recovered.reason)
        assertFalse(recovered.cancelStaleNavigationSpeech)
        assertTrue(n.isInstructionCurrent(first,1200L,facing(1200L)))
        assertNotNull(field(n,"progressDistanceM"))
    }

    private fun nav()=RouteNavigator().apply {setRoute(WalkingRoute("TEST",WalkingRouteSummary(200,200),
        listOf(point(0.0),point(200.0)),listOf(WalkingRouteGuidePoint(0,point(100.0),null,100,100,turnType=11))))}
    private fun point(east:Double)=RoutePoint(0.0,east/111194.92664455874)
    private fun fix(east:Double,time:Long,only:Boolean,accuracy:Float=2f)=point(east).let {
        TrustedLocation(it.latitude,it.longitude,accuracy,time,
            uncertaintyKind=HorizontalUncertaintyKind.ANDROID_RADIUS_68,navigationOnly=only)}
    private fun facing(time:Long)=RouteFacingObservation(90.0,3.0,time)
    private fun field(n:RouteNavigator,name:String):Any?=RouteNavigator::class.java.getDeclaredField(name)
        .apply {isAccessible=true}.get(n)
}
