package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.navigation.*
import org.junit.Assert.*
import org.junit.Test

/** Original accepted fix and geometry only; no Android/context/native/clock fixture exists. */
class RouteResponseInstallLocationPolicyTest {
    private val route=route(listOf(RoutePoint(0.0,0.0),RoutePoint(0.0,.001)))
    private val origin=fix(0.0,0.0)
    @Test fun missingCurrentFixIsUnavailable() { assertEquals(unavailable,status(null)) }
    @Test fun freshnessIncludesMaximumAgeAndRejectsOneMsPastIt() {
        assertEquals(connected,status(origin,11_000));assertEquals(unavailable,status(origin,11_001))
    }
    @Test fun futureOriginalMeasurementCannotUseAReceiptAsItsClock() {
        assertEquals(unavailable,status(origin,999));assertEquals(connected,status(origin,1000))
    }
    @Test fun acceptedOriginalLocationAndRequestOriginAreNotMutated() {
        val oldRoute=route.copy(polyline=route.polyline.toList());val oldOrigin=origin.copy();val current=fix(.00001,.0005,time=1500);val old=current.copy()
        assertEquals(connected,status(current));assertEquals(old,current);assertEquals(oldOrigin,origin);assertEquals(oldRoute,route)
        assertEquals(1500L,current.elapsedRealtimeMs)
    }
    @Test fun mockRejectionRemainsAtOriginalFixAcceptanceBoundary() {
        val mock=LocationTrustPolicy.trustedOrNull(0.0,.0005,3f,1000,mock=true)
        assertNull(mock);assertEquals(unavailable,status(mock))
        val accepted=LocationTrustPolicy.trustedOrNull(0.0,.0005,3f,1000,mock=false)
        assertNotNull(accepted);assertEquals(connected,status(accepted))
    }
    @Test fun currentAccuracyBoundsKeepZeroAndOneHundredInclusive() {
        listOf(0f,100f).forEach{assertEquals(connected,status(origin.copy(accuracyM=it)))}
        listOf(-.001f,100.001f,Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY).forEach{
            assertEquals(disconnected,status(origin.copy(accuracyM=it)))
        }
    }
    @Test fun movedAlongReturnedRouteRemainsConnectedWithDirectProductMode() {
        listOf(.0001,.0008,.001).forEach{assertEquals(connected,status(fix(0.0,it),allow=false))}
    }
    @Test fun eightMeterLocalSearchSeparatesConnectedGuidanceFromClosestApproach() {
        assertEquals(connected,status(northOfRoute(7.999),allow=false));assertEquals(approach,status(northOfRoute(8.001),allow=false))
    }
    @Test fun accuracyExpandsLocalRadiusButNotGlobalCorridorBound() {
        assertEquals(connected,status(northOfRoute(19.999,accuracy=10f),allow=false))
        assertEquals(approach,status(northOfRoute(20.001,accuracy=10f),allow=false))
        assertEquals(disconnected,status(northOfRoute(35.001,accuracy=100f),allow=false))
    }
    @Test fun corridorThirtyFiveMetersStillCapsAnAccurateOrUncertainFix() {
        assertEquals(connected,status(northOfRoute(34.999,accuracy=20f),allow=false))
        assertEquals(disconnected,status(northOfRoute(35.001,accuracy=20f),allow=false))
    }
    @Test fun delayedSidewaysMoveUsesTheCurrentClosestApproach() {
        val atRequest=fix(.000135,0.0)
        assertEquals(approach,routeResponseInstallLocationStatus(route,atRequest,atRequest,1500))
        assertEquals(approach,routeResponseInstallLocationStatus(route,atRequest,fix(.000297,0.0,time=1500),2000))
    }
    @Test fun neitherLegacyConnectorArgumentRestoresAFirstPointConnector() {
        val unchanged=fix(.000135,0.0)
        assertEquals(approach,routeResponseInstallLocationStatus(route,unchanged,unchanged,1500))
        assertEquals(approach,routeResponseInstallLocationStatus(route,unchanged,unchanged,1500,allowInitialStartConnector=false))
    }
    @Test fun closestApproachCanJoinTheInteriorOfTheFirstLeg() {
        val pastFirstStart=fix(.000135,.0005)
        assertEquals(approach,routeResponseInstallLocationStatus(route,pastFirstStart,pastFirstStart,1500))
    }
    @Test fun connectorCannotUseANewerNegativeOrInvalidOriginClock() {
        val current=fix(.000135,0.0,time=1500)
        listOf(-1L,1501L).forEach{assertEquals(disconnected,routeResponseInstallLocationStatus(route,current.copy(elapsedRealtimeMs=it),current,2000))}
        assertEquals(approach,routeResponseInstallLocationStatus(route,current.copy(elapsedRealtimeMs=0),current,2000))
        assertEquals(approach,routeResponseInstallLocationStatus(route,current,current,2000))
    }
    @Test fun connectorRequiresFiniteBoundedOriginAccuracy() {
        val current=fix(.000135,0.0,time=1500)
        listOf(-.001f,100.001f,Float.NaN,Float.POSITIVE_INFINITY).forEach{
            assertEquals(disconnected,routeResponseInstallLocationStatus(route,current.copy(accuracyM=it),current,2000))
        }
    }
    @Test fun originIsConsultedOnlyForTheConnectorNotAnAlreadyLocalFix() {
        val invalidOrigin=fix(Double.NaN,Double.NaN,Float.NaN,Long.MAX_VALUE)
        assertEquals(connected,routeResponseInstallLocationStatus(route,invalidOrigin,origin,1500,false))
    }
    @Test fun emptyAndNonFinitePolylineGeometryCannotCreateConnectivity() {
        assertEquals(disconnected,routeResponseInstallLocationStatus(route(emptyList()),origin,origin,1500))
        val invalid=route(listOf(RoutePoint(Double.NaN,0.0),RoutePoint(0.0,.001)))
        assertEquals(disconnected,routeResponseInstallLocationStatus(invalid,origin,origin,1500))
    }
    @Test fun repeatedFirstPointsAreSkippedWithoutChangingTheOriginalGeometry() {
        val duplicate=route(listOf(RoutePoint(0.0,0.0),RoutePoint(0.0,0.0),RoutePoint(0.0,.001)))
        val offset=fix(.000135,0.0)
        assertEquals(approach,routeResponseInstallLocationStatus(duplicate,offset,offset,1500))
    }
    @Test fun degeneratePolylineCanMatchLocallyButCannotInventAFirstLegConnector() {
        val degenerate=route(listOf(RoutePoint(0.0,0.0),RoutePoint(0.0,0.0)))
        assertEquals(connected,routeResponseInstallLocationStatus(degenerate,origin,origin,1500))
        val offset=fix(.000135,0.0)
        assertEquals(disconnected,routeResponseInstallLocationStatus(degenerate,offset,offset,1500))
    }
    @Test fun originalEnumOrdinalsStayStableAndClosestApproachIsAppended() {
        assertEquals(listOf("CONNECTED","LOCATION_UNAVAILABLE","ROUTE_DISCONNECTED","APPROACH_REQUIRED"),RouteResponseInstallLocationStatus.entries.map{it.name})
        assertEquals(listOf(0,1,2,3),RouteResponseInstallLocationStatus.entries.map{it.ordinal})
    }
    @Test fun recoveryDistinguishesCurrentConnectionMissWithoutRaisingTheInstallBound() {
        val current = northOfRoute(101.0).copy(elapsedRealtimeMs = 1500)
        assertEquals(disconnected, routeResponseInstallLocationStatus(route, origin, current, 2000))
        assertTrue(routeResponseHasRecoverableCurrentConnection(route, origin, current, 2000))
        assertFalse(routeResponseHasRecoverableCurrentConnection(route, origin, northOfRoute(99.0), 2000))
    }
    @Test fun recoveryCannotHideInvalidProviderOriginOrPolyline() {
        val current = northOfRoute(120.0).copy(elapsedRealtimeMs = 1500)
        listOf(origin.copy(latitude = Double.NaN), origin.copy(longitude = 181.0),
            origin.copy(accuracyM = 15.001f), origin.copy(elapsedRealtimeMs = -1),
            origin.copy(elapsedRealtimeMs = 2001), origin.copy(elapsedRealtimeMs = 1501),
            northOfRoute(101.0)).forEach {
            assertFalse(routeResponseHasRecoverableCurrentConnection(route, it, current, 2000))
        }
        listOf(emptyList(), listOf(RoutePoint(0.0, 0.0)),
            listOf(RoutePoint(0.0, 0.0), RoutePoint(0.0, 0.0)),
            listOf(RoutePoint(0.0, 0.0), RoutePoint(Double.NaN, 0.0))).forEach {
            assertFalse(routeResponseHasRecoverableCurrentConnection(route(it), origin, current, 2000))
        }
    }
    @Test fun recoveryRequiresCurrentPrecisionAndOriginalObservationTime() {
        val current = northOfRoute(120.0).copy(elapsedRealtimeMs = 1500)
        listOf(null, current.copy(accuracyM = 15.001f), current.copy(accuracyM = Float.NaN),
            current.copy(elapsedRealtimeMs = 2001), current.copy(elapsedRealtimeMs = 999),
            current.copy(latitude = Double.POSITIVE_INFINITY)).forEach {
            assertFalse(routeResponseHasRecoverableCurrentConnection(route, origin, it, 2000))
        }
        assertFalse(routeResponseHasRecoverableCurrentConnection(route, origin, current, 11_501))
    }
    private fun status(location:TrustedLocation?,now:Long=1500,allow:Boolean=true)=routeResponseInstallLocationStatus(route,origin,location,now,allow)
    private fun northOfRoute(meters:Double,accuracy:Float=3f)=fix(Math.toDegrees(meters/6_371_000.0),.0005,accuracy)
    private fun fix(lat:Double,lon:Double,accuracy:Float=3f,time:Long=1000)=TrustedLocation(lat,lon,accuracy,time)
    private fun route(points:List<RoutePoint>)=WalkingRoute("STAIR_AVOID",WalkingRouteSummary(111,90),points,emptyList())
    companion object {
        private val connected=RouteResponseInstallLocationStatus.CONNECTED
        private val unavailable=RouteResponseInstallLocationStatus.LOCATION_UNAVAILABLE
        private val disconnected=RouteResponseInstallLocationStatus.ROUTE_DISCONNECTED
        private val approach=RouteResponseInstallLocationStatus.APPROACH_REQUIRED
    }
}
