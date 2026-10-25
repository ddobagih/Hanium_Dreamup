package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.depth.*
import kr.co.hanium.dreamup.walksafe.feedback.WalkSafeFeedbackPolicy
import kr.co.hanium.dreamup.walksafe.inference.*
import org.junit.Assert.*
import org.junit.Test

class TactileSurfaceConsumerTest {
    @Test fun actualSelectorAndDepthProducerReachFrameCoordinatorAndSelectedObservation() {
        val f=TactileSurfaceFixture()
        val result=evaluate(f)
        val observed=requireNotNull(result.observation?.surfaceEvidence)
        assertSame(f.candidate,observed.surface.candidate)
        assertEquals(TactileObservedRouteSupport.AXIS_AND_MULTIPOINT_OVERLAP,observed.observedRouteSupport)
        assertTrue(observed.components.single().axis!!.routeAngleDeg<5)
        assertTrue(observed.components.single().tangents.isNotEmpty())
        assertTrue(observed.overlaps.size>1)
        assertTrue(result.surfaceEvaluations[0]!!.rejection==null)
        assertEquals(LocalRouteMode.TMAP,result.decision.mode)
        assertNull(result.decision.instruction)
        assertTrue(result.outputs.all { it.userFacing.messageLevel==MessageLevel.NONE })
    }

    @Test fun typedSurfaceWithoutRealRouteOverlapRejectsAnOtherwiseAcceptedScalarContact() {
        val f=TactileSurfaceFixture { x,y -> x in 34..51 && y in 30..45 }
        val surface=f.project()
        assertNotNull(surface.surface!!.measuredContact)
        assertNotNull(AndroidTactileRouteObservationSupplier().observationFor(output(f),surfaceContext(f)))
        val result=evaluate(f,surfaces=listOf(surface))
        assertNull(result.observation)
        assertEquals(TactileObservationRejection.SURFACE_NO_MULTIPOINT_ROUTE_OVERLAP,result.observationRejections["surface-track"])
        assertEquals(TactileObservedRouteSupport.NO_MULTIPOINT_OVERLAP,result.surfaceEvaluations[0]!!.evidence!!.observedRouteSupport)
    }

    @Test fun suppliedForeignCaptureCannotDecorateTheCurrentObservation() {
        val f=TactileSurfaceFixture()
        val context=surfaceContext(f).copy(captureFrameId=8,cameraFrame=surfaceContext(f).cameraFrame!!.copy(frameId=8))
        val result=AndroidTactileRouteGuidance().apply(listOf(output(f).copy(frameId=8)),context,true,true,
            tactileSurfaces=listOf(f.project()),decisionElapsedRealtimeNs=f.decisionNs)
        assertNull(result.observation)
        assertEquals(TactileSurfaceRouteRejection.CAPTURE_MISMATCH,result.surfaceEvaluations[0]!!.rejection)
    }

    @Test fun noTypedSurfaceKeepsExistingCompatibilityAndDamageStillHasPriority() {
        val f=TactileSurfaceFixture()
        val normal=output(f)
        assertNotNull(AndroidTactileRouteGuidance().apply(listOf(normal),surfaceContext(f),true,true).observation)
        val damaged=normal.copy(trackId="damage",className="damaged_linear_tactile_paving",sourceDetectionIndex=1)
        val result=AndroidTactileRouteGuidance().apply(listOf(normal,damaged),surfaceContext(f),true,true,
            tactileSurfaces=listOf(f.project()),decisionElapsedRealtimeNs=f.decisionNs)
        assertEquals("damage",result.observation?.candidateId)
        assertEquals(LocalRouteMode.TMAP,result.decision.mode)
    }

    @Test fun laterCompletionConsumesOriginalSourceBundleWithoutRegisteringANewTrackerFrame() {
        val f=TactileSurfaceFixture()
        val decisionNs=f.token.capturedElapsedNs+80_000_000L
        val context=surfaceContext(f).copy(decisionElapsedRealtimeMs=decisionNs/1_000_000L)
        // The render frame may already differ. The input deliberately retains source frame 7.
        val sourceInput=AndroidTactileFrameComposition.input(listOf(output(f)),
            AndroidDetectionSnapshotFrameIdentity(f.token.frameId,f.token.cameraTimestampNs/1_000_000L),
            AndroidTactileEvidenceFrameIdentity(f.token.frameId,f.token.cameraTimestampNs/1_000_000L,f.token.frameId,f.token.frameId),
            context,80,true,true,true,true,context.expectedRouteId,TactileActiveRouteProgress(1,0),
            decisionNs/1_000_000L,listOf(f.project()),decisionNs)
        val result=AndroidTactileFrameCoordinator(AndroidTactileRouteGuidance(),WalkSafeFeedbackPolicy(),TactileFrameFeedbackActuator{})
            .evaluate(sourceInput)
        assertEquals(f.token.frameId,result.observation!!.surfaceEvidence!!.surface.candidate.capture.token.frameId)
        assertNotNull(result.observation!!.surfaceEvidence)
    }

    @Test fun fullSurfaceRemainsFullAtObservationWithoutMotionOrFootAuthority() {
        val f=TactileSurfaceFixture()
        val full=output(f).copy(source=DepthSource.ARCORE_FULL_DEPTH,
            confidence=output(f).confidence.copy(sourceQuality=DepthSource.ARCORE_FULL_DEPTH.sourceQuality))
        val result=AndroidTactileRouteGuidance().apply(listOf(full),surfaceContext(f),true,true,
            tactileSurfaces=listOf(f.project(full=true)),decisionElapsedRealtimeNs=f.decisionNs)
        assertEquals(DepthSource.ARCORE_FULL_DEPTH,result.observation!!.surfaceEvidence!!.surface.source)
        assertTrue(result.observation!!.surfaceEvidence!!.surface.samples.all{it.pixelConfidence==null})
        assertEquals(LocalRouteMode.TMAP,result.decision.mode)
        assertNull(result.decision.instruction)
    }

    @Test fun typedPerpendicularBlockRejectsDespiteCameraAlignedToRoute() {
        val f=TactileSurfaceFixture { x,y -> x in 12..51 && y in 30..45 }
        val surface=f.project()
        assertNotNull(surface.surface!!.measuredContact)
        val result=evaluate(f,surfaces=listOf(surface))
        val evidence=result.surfaceEvaluations[0]!!.evidence!!
        assertEquals(TactileObservedRouteSupport.AXIS_AND_MULTIPOINT_OVERLAP,evidence.observedRouteSupport)
        assertTrue(evidence.components.single().axis!!.routeAngleDeg>85)
        assertEquals("route_heading_mismatch",result.decision.reason)
        assertEquals(LocalRouteMode.TMAP,result.decision.mode)
        assertNull(result.decision.instruction)
    }

    @Test fun typedParallelBlockPassesHeadingEligibilityDespitePerpendicularCamera() {
        val f=TactileSurfaceFixture { x,y -> x in 12..51 && y in 30..45 }
        val base=surfaceContext(f)
        // Same owned sensor cells, camera bearing East; their horizontal block axis is North/South.
        // Move only the captured route segment to its observed East offset so its real footprint
        // intersects the route. Location stays the original camera/GPS origin.
        val eastAtBlock=f.s*(1f-((37.5-32)/96).toFloat())*f.ownZ(37.5)
        val longitude=127.0+eastAtBlock/kr.co.hanium.dreamup.walksafe.navigation.positioning.LocalGeodesy.metersPerLongitudeDegree(37.0)
        val context=base.copy(
            orientation=base.orientation!!.copy(deviceToMagneticEnu=RotationMatrix3(0f,1f,0f,-1f,0f,0f,0f,0f,1f)),
            routeProjection=base.routeProjection!!.copy(segmentStart=RoutePoint(36.9999,longitude),
                segmentEnd=RoutePoint(37.001,longitude),distanceToRouteM=eastAtBlock.toDouble()),
        )
        assertEquals(90f,AndroidTactileRouteObservationSupplier().observationFor(output(f),context)!!.routeHeadingDeltaDeg!!,.001f)
        val surface=TactileMaskSurfaceProjector().project(f.candidate,f.frozen(),f.decisionNs,context)
        assertNotNull(surface.surface!!.measuredContact)
        val result=evaluate(f,context,surfaces=listOf(surface))
        val evidence=result.surfaceEvaluations[0]!!.evidence!!
        assertEquals(TactileObservedRouteSupport.AXIS_AND_MULTIPOINT_OVERLAP,evidence.observedRouteSupport)
        assertTrue(evidence.components.single().axis!!.routeAngleDeg<5)
        assertEquals("tactile_motion_evidence_unavailable",result.decision.reason)
        assertEquals(LocalRouteMode.TMAP,result.decision.mode)
        assertNull(result.decision.instruction)
    }

    @Test fun explicitTypedNullIndexCannotFallBackToScalarObservation() {
        val f=TactileSurfaceFixture(); val noIndex=output(f).copy(sourceDetectionIndex=null)
        assertNotNull(AndroidTactileRouteObservationSupplier().observationFor(noIndex,surfaceContext(f)))
        val result=evaluate(f,outputs=listOf(noIndex))
        assertEquals("tactile_not_visible",result.decision.reason)
        assertNull(result.observation)
        assertEquals(TactileObservationRejection.SURFACE_UNAVAILABLE,result.observationRejections[noIndex.trackId])
    }

    @Test fun explicitTypedWrongIndexCannotFallBackToScalarObservation() {
        val f=TactileSurfaceFixture(); val wrongIndex=output(f).copy(sourceDetectionIndex=1)
        assertNotNull(AndroidTactileRouteObservationSupplier().observationFor(wrongIndex,surfaceContext(f)))
        val result=evaluate(f,outputs=listOf(wrongIndex))
        assertEquals("tactile_not_visible",result.decision.reason)
        assertNull(result.observation)
        assertEquals(TactileObservationRejection.SURFACE_UNAVAILABLE,result.observationRejections[wrongIndex.trackId])
    }

    @Test fun unrelatedTypedSemanticDoesNotBlockCoarseObservationOrBlockingClass() {
        val f=TactileSurfaceFixture()
        val coarse=output(f).copy(className="normal_tactile_block",sourceDetectionIndex=2)
        val normal=evaluate(f,outputs=listOf(coarse))
        assertEquals("tactile_motion_evidence_unavailable",normal.decision.reason)
        assertEquals(coarse.trackId,normal.observation!!.candidateId)
        assertNull(normal.observation!!.surfaceEvidence)
        val dot=output(f).copy(className="dot_tactile_paving",trackId="dot-blocker",sourceDetectionIndex=3)
        val blocked=evaluate(f,outputs=listOf(dot))
        assertEquals(dot.trackId,blocked.observation!!.candidateId)
        assertEquals("tactile_not_traversable",blocked.decision.reason)
        assertEquals(LocalRouteMode.TMAP,blocked.decision.mode)
    }

    @Test fun secondLineWithDifferentOriginalBoxKeepsLegacyEligibilityWithoutBorrowingFirstSurface() {
        val f=TactileSurfaceFixture(); val context=surfaceContext(f)
        val first=f.candidate.detection.copy(bboxNorm=RectNorm(26f/64,24f/64,12f/64,24f/64))
        val second=first.copy(detectionConfidence=.95f,bboxNorm=RectNorm(.02f,.375f,.2f,.375f))
        val classes=TactileSemanticKind.values().map{it.className}
        val model=ModelRuntimeConfig(TwoModelRuntimeConfig.UNIFIED_MODEL_KEY,"model/existing.tflite",768,
            classes,classes.toSet(),classes.associateWith{.25f},artifactSha256=f.candidate.modelArtifactSha256)
        val config=TwoModelRuntimeConfig("surface-fixture",null,model.key,null,model,null,null)
        val named=TactileNamedSemanticEvidence.fromResult(f.capture,AndroidDetectionResult(listOf(first,second),
            timing=AndroidDetectorTiming(modelKey=model.key)),TactileSemanticCoordinates.SENSOR_ORIGINAL)
        val selected=TactileSemanticMaskSelector(config).select(named,TactileInstanceMaskEvidence(f.capture,f.token,
            listOf(f.candidate.instanceMask),TactileSemanticCoordinates.SENSOR_ORIGINAL),f.decisionNs)
        assertEquals(0,selected.candidates.single().namedDetectionIndex)
        assertNull(selected.decisions.single{it.namedDetectionIndex==1}.candidate)
        val surface=TactileMaskSurfaceProjector().project(selected.candidates.single(),f.frozen(),f.decisionNs,context)
        val firstOutput=output(f).copy(bboxNorm=first.bboxNorm)
        val secondOutput=output(f).copy(trackId="second-line",sourceDetectionIndex=1,detectionConfidence=second.detectionConfidence,
            bboxNorm=second.bboxNorm,centerNorm=Point2(.12f,.6f),bottomContactNorm=Point2(.12f,.6f))
        val result=evaluate(f,outputs=listOf(firstOutput,secondOutput),surfaces=listOf(surface))
        assertEquals("second-line",result.observation!!.candidateId)
        assertEquals("tactile_motion_evidence_unavailable",result.decision.reason)
        assertNull(result.observation!!.surfaceEvidence)
        assertFalse(result.observationRejections.containsKey("second-line"))
    }

    private fun evaluate(f:TactileSurfaceFixture,context:TactileProjectionContext=surfaceContext(f),
                         outputs:List<TrackedObjectDepth> = listOf(output(f)),
                         surfaces:List<TactileMaskSurfaceResult> = listOf(TactileMaskSurfaceProjector()
                             .project(f.candidate,f.frozen(),f.decisionNs,context))):TactileRouteGuidanceResult {
        val input=AndroidTactileFrameComposition.input(outputs,
            AndroidDetectionSnapshotFrameIdentity(f.token.frameId,f.token.cameraTimestampNs/1_000_000L),
            AndroidTactileEvidenceFrameIdentity(f.token.frameId,f.token.cameraTimestampNs/1_000_000L,f.token.frameId,f.token.frameId),
            context,50,true,true,true,true,context.expectedRouteId,TactileActiveRouteProgress(1,0),
            f.decisionNs/1_000_000L,surfaces,f.decisionNs)
        return AndroidTactileFrameCoordinator(AndroidTactileRouteGuidance(),WalkSafeFeedbackPolicy(),TactileFrameFeedbackActuator{})
            .evaluate(input)
    }

    private fun output(f:TactileSurfaceFixture):TrackedObjectDepth = TrackedObjectDepth(
        frameId=f.token.frameId,timestampMs=f.token.cameraTimestampNs/1_000_000L,trackId="surface-track",
        className=f.candidate.semantic.className,detectionConfidence=.9f,bboxNorm=RectNorm(0f,0f,1f,1f),
        polygonNorm=emptyList(),maskAreaNorm=f.candidate.sensorMask.area/4096f,centerNorm=Point2(.5f,.6f),
        bottomContactNorm=Point2(.5f,.6f),source=DepthSource.ARCORE_RAW_DEPTH,zDistanceM=f.ownZ(.6*64),
        rayDistanceM=null,groundDistanceM=null,riskDistanceM=f.ownZ(.6*64),validSampleCount=80,
        validSampleRatio=.6f,depthMedianM=2f,depthP20M=1.9f,depthIqrM=.2f,trend=Trend.STABLE,
        approachScore=0f,approachSpeedMps=null,timeToCollisionMs=null,
        confidence=DepthConfidenceBreakdown(.95f,.9f,.9f,.9f,1f,1f,1f,1f),
        userFacing=UserFacingDepth(null,MessageLevel.NONE,null),trackAgeFrames=4,trackStableMs=900,
        imageQuarterTurns=0,sourceValidUntilElapsedRealtimeMs=f.capture.originalDeadlineElapsedNs/1_000_000L,
        contactDepth=ContactDepthEstimate(f.ownZ(.6*64),.05f,25),sourceDetectionIndex=0,
        depthObservedAtMs=f.token.cameraTimestampNs/1_000_000L,depthReprojected=false,
        sourceObservedAtElapsedRealtimeMs=f.token.capturedElapsedNs/1_000_000L)
}
