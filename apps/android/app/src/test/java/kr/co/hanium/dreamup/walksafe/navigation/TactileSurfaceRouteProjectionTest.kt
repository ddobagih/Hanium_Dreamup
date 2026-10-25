package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.depth.*
import org.junit.Assert.*
import org.junit.Test

class TactileSurfaceRouteProjectionTest {
    @Test fun parallelAndPerpendicularMasksHaveDifferentActualAxesWithTheSameCamera() {
        val parallel=TactileSurfaceFixture()
        val perpendicular=TactileSurfaceFixture { x,y -> x in 12..51 && y in 34..41 }
        val p=project(parallel); val o=project(perpendicular)
        assertEquals(surfaceContext(parallel).cameraFrame,surfaceContext(perpendicular).cameraFrame)
        assertTrue(p.components.single().axis!!.routeAngleDeg<5)
        assertTrue(o.components.single().axis!!.routeAngleDeg>85)
        assertTrue(p.components.single().tangents.isNotEmpty())
        assertTrue(p.overlaps.size>1)
        assertEquals(TactileObservedRouteSupport.AXIS_AND_MULTIPOINT_OVERLAP,p.observedRouteSupport)
        assertEquals(1L,p.routeRevision)
        assertEquals(0,p.routeSegmentIndex)
    }

    @Test fun contactOnlyAtTheRouteCannotStandInForMultipointSurfaceOverlap() {
        val f=TactileSurfaceFixture { x,y -> x in 34..51 && y in 34..41 }
        // Legacy contact can be x=.5 (the route ray); all actual foreground is east of it.
        val evidence=project(f)
        assertTrue(evidence.components.flatMap{it.points}.all{it.eastM>0})
        assertTrue(evidence.overlaps.isEmpty())
        assertEquals(TactileObservedRouteSupport.NO_MULTIPOINT_OVERLAP,evidence.observedRouteSupport)
    }

    @Test fun holeAndDisconnectedComponentsAreNotFilledOrJoinedByRouteHull() {
        val broadHole=TactileSurfaceFixture { x,y -> x in 20..43 && y in 22..47 && !(x in 28..35 && y in 30..39) }
        // Its own-z spread exceeds the existing sampler limit; keep that original rejection.
        assertEquals(TactileSurfaceRejection.MEASURED_SURFACE_UNAVAILABLE,broadHole.project().rejection)
        // A compact measured ring keeps the exact inner hole without relaxing that limit.
        val hole=TactileSurfaceFixture { x,y -> x in 20..43 && y in 26..45 && !(x in 28..35 && y in 30..39) }
        val h=project(hole)
        assertEquals(1,h.originalMaskHoleCount)
        assertTrue(h.components.all{it.axis==null})
        h.overlaps.forEach { patch -> assertTrue(patch.depthCells.none { it%64 in 28..35 && it/64 in 30..39 }) }
        val separated=TactileSurfaceFixture { x,y -> x in 26..37 && (y in 20..29 || y in 38..47) }
        val d=project(separated)
        assertEquals(2,d.originalMaskComponentCount)
        assertEquals(2,d.components.size)
        d.overlaps.forEach { patch -> assertTrue(patch.depthCells.map{it/64}.let { rows ->
            rows.all{it<=29} || rows.all{it>=38} }) }
        d.components.forEach { c -> assertTrue(c.tangents.all { t ->
            kotlin.math.abs(t.fromDepthCell/64-t.toDepthCell/64)<=1 }) }
    }

    @Test fun branchingMaskRetainsItsTopologyWithoutOneGlobalProgressAxis() {
        val f=TactileSurfaceFixture { x,y -> (x in 28..35 && y in 26..49) || (x in 14..49 && y in 22..29) }
        val evidence=project(f)
        assertTrue(evidence.originalMaskBranching)
        assertTrue(evidence.components.all{it.axis==null})
        assertEquals(TactileObservedRouteSupport.TOPOLOGY_UNRESOLVED,evidence.observedRouteSupport)
    }

    @Test fun foreignFrameAndPlaneReferenceAndExpiredClockAreRejected() {
        val f=TactileSurfaceFixture(); val surface=f.project().surface!!; val context=surfaceContext(f)
        val projector=TactileSurfaceRouteProjector()
        assertEquals(TactileSurfaceRouteRejection.CAPTURE_MISMATCH,
            projector.project(surface,context.copy(captureFrameId=8),f.decisionNs).rejection)
        val wrongPlane=surface.pose.copy(horizontalPlaneCandidates=surface.pose.horizontalPlaneCandidates.map { it.copy(referenceId=99) })
        val foreign=TactileMaskSurface(surface.candidate,surface.source,surface.depthTimestampNs,
            surface.depthMatchesCameraImage,wrongPlane,surface.samplingStatus,surface.samplingReason,
            surface.depthWidth,surface.depthHeight,surface.captureContext,surface.samples)
        assertEquals(TactileSurfaceRouteRejection.PLANE_UNAVAILABLE,projector.project(foreign,context,f.decisionNs).rejection)
        val expired=f.capture.originalDeadlineElapsedNs+1
        assertEquals(TactileSurfaceRouteRejection.EXPIRED_CAPTURE,projector.project(surface,
            context.copy(decisionElapsedRealtimeMs=expired/1_000_000L),expired).rejection)
    }

    @Test fun completionCannotRetagOriginalCaptureWithANewRouteRevisionOrLocation() {
        val f=TactileSurfaceFixture(); val surface=f.project().surface!!; val context=surfaceContext(f)
        val projector=TactileSurfaceRouteProjector()
        assertEquals(TactileSurfaceRouteRejection.CAPTURE_MISMATCH,projector.project(surface,
            context.copy(routeProjection=context.routeProjection!!.copy(routeRevision=2)),f.decisionNs).rejection)
        assertEquals(TactileSurfaceRouteRejection.CAPTURE_MISMATCH,projector.project(surface,
            context.copy(trustedLocation=context.trustedLocation!!.copy(longitude=127.0001)),f.decisionNs).rejection)
        val withoutContext=TactileMaskSurfaceProjector().project(f.candidate,f.frozen(),f.decisionNs).surface!!
        assertEquals(TactileSurfaceRouteRejection.CAPTURE_MISMATCH,
            projector.project(withoutContext,context,f.decisionNs).rejection)
    }

    private fun project(f:TactileSurfaceFixture):TactileSurfaceRouteEvidence {
        val measured=f.project()
        val surface=requireNotNull(measured.surface) { "depth surface rejected: ${measured.rejection}" }
        val projected=TactileSurfaceRouteProjector().project(surface,surfaceContext(f),f.decisionNs)
        return requireNotNull(projected.evidence) { "route surface rejected: ${projected.rejection}" }
    }
}

internal fun surfaceContext(f:TactileSurfaceFixture):TactileProjectionContext {
    val captureMs=f.token.capturedElapsedNs/1_000_000L
    val identity=RotationMatrix3(1f,0f,0f,0f,1f,0f,0f,0f,1f)
    return TactileProjectionContext(f.token.frameId,"surface-route",
        ActiveRouteProjection("surface-route",0,RoutePoint(36.9999,127.0),RoutePoint(37.001,127.0),0.0,0f,1),
        TrustedLocation(37.0,127.0,.5f,captureMs),
        DeviceEarthOrientation(identity,captureMs,1f,EarthOrientationAccuracy.HIGH),0f,
        TactileCameraFrame(f.token.frameId,RotationMatrix3(1f,0f,0f,0f,f.s,-f.s,0f,f.s,f.s),
            TactileCameraIntrinsics(64,64,96f,96f,32f,32f),captureMs,true),
        50,captureMs,f.decisionNs/1_000_000L)
}
