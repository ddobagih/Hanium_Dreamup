package android.media

import java.nio.ByteBuffer
import kr.co.hanium.dreamup.walksafe.depth.*
import kr.co.hanium.dreamup.walksafe.inference.tracking.*
import kr.co.hanium.dreamup.walksafe.navigation.FrozenImageToDepthTransform
import org.junit.Assert.*
import org.junit.Test

/** Actual packet -> unchanged runtime -> own material-point pixel -> accepted Body producer. */
class TrackedMeasuredBodyDepthTest {
    @Test fun rawAndFullBindOwnPixelRatherThanOutputMedianAndKeepOriginalPublication() {
        for (full in listOf(false,true)) {
            val f=Fixture(full=full); val joined=f.run().single(); val clearance=requireNotNull(joined.clearance)
            assertEquals(if(full) DepthSource.ARCORE_FULL_DEPTH else DepthSource.ARCORE_RAW_DEPTH,joined.originalOutput.source)
            assertEquals(2.12f,(-clearance.cameraPoint.z).toFloat(),0f)
            assertEquals(2.10f,requireNotNull(joined.originalOutput.depthMedianM),0f)
            assertNotEquals(joined.originalOutput.depthMedianM!!,(-clearance.cameraPoint.z).toFloat(),0f)
            assertEquals(20,clearance.depthX); assertEquals(20,clearance.depthY)
            assertEquals(f.target.cameraTimestampNs,clearance.depthTimestampNs)
            assertEquals(if(full) null else 245,clearance.depthPixelConfidence)
            assertSame(f.packet.observations.single().materialPoint,joined.originalVisualMaterialPoint)
            assertSame(f.old().single(),joined.originalOutput)
            assertEquals(CollisionAssessment.UNKNOWN,joined.originalOutput.collisionAssessment)
            assertEquals(6_000_000_000L,clearance.semanticPixel?.labelTimestampNs)
            assertEquals(6_100_000_000L,clearance.semanticPixel?.confidenceTimestampNs)
        }
    }

    @Test fun detectorPastSourceIsPreservedWhileBodyUsesCurrentTargetWithoutEpochConversion() {
        val f=Fixture(); val joined=f.run().single()
        assertNotEquals(f.packet.sourceKey.frameId,f.target.frameId)
        assertNotEquals(f.target.epoch,f.key.owner.walkEpoch)
        assertEquals(f.target.frameId,joined.clearance?.captureKey?.frameTimestampNs)
        assertEquals(f.target.cameraTimestampNs,joined.clearance?.captureKey?.cameraImageTimestampNs)
        assertEquals(f.packet.sourceKey,f.packet.observations.single().detectorSourceKey)
        assertEquals(0,joined.originalOutput.sourceDetectionIndex)
        assertTrue(joined.originalOutput.sourceObservedAtElapsedRealtimeMs!! <= f.packet.sourceKey.capturedAtElapsedRealtimeMs)
    }

    @Test fun repeatedPacketRetainsSameOriginalOutputAndOpaquePointWithoutAdvancingTrack() {
        val f=Fixture(); val first=f.run().single(); val again=f.run().single()
        assertNotNull(first.clearance); assertNotNull(again.clearance)
        assertSame(first.originalOutput,again.originalOutput)
        assertSame(first.originalVisualMaterialPoint?.id,again.originalVisualMaterialPoint?.id)
        assertEquals(first.originalOutput.trackAgeFrames,again.originalOutput.trackAgeFrames)
        assertEquals(first.clearance?.bodyPoint,again.clearance?.bodyPoint)
    }

    @Test fun absentOrInvalidErrorPreservesActualOutputWithoutClaimingMeasuredBody() {
        val f=Fixture(); val original=f.old().single()
        for (error in listOf(null,ERROR.copy(metricPositionErrorM=null),ERROR.copy(axialQuantizationErrorBoundM=null),
            ERROR.copy(metricPositionErrorM=Double.NaN),ERROR.copy(axialQuantizationErrorBoundM=-.1),ERROR.copy(basis=""))) {
            val binding=f.binding(error=error); assertNull(binding)
            val output=f.run(binding=binding).single()
            assertSame(original,output.originalOutput); assertNull(output.clearance)
        }
    }

    @Test fun originalOwnerReferenceClockGeometryAndLeaseCannotBeReplaced() {
        val f=Fixture(); assertNotNull(f.run().single().clearance)
        val changed=listOf(f.key.copy(owner=OWNER.copy(walkEpoch=4)),f.key.copy(owner=OWNER.copy(mountGeneration=2)),
            f.key.copy(poseReferenceId=8),f.key.copy(frameTimestampNs=f.key.frameTimestampNs+1),
            f.key.copy(cameraImageTimestampNs=f.key.cameraImageTimestampNs+1),f.key.copy(geometryVersion=2),
            f.key.copy(capturedAtElapsedNs=f.key.capturedAtElapsedNs+1),f.key.copy(validUntilElapsedNs=f.key.validUntilElapsedNs+1),
            f.key.copy(leaseIdentity="renewed"))
        changed.forEach { assertNull(f.run(key=it).single().clearance); assertNull(f.binding(key=it)) }
        assertNull(f.run(nowNs=f.key.capturedAtElapsedNs-1).single().clearance)
        assertNull(f.run(nowNs=f.key.validUntilElapsedNs+1).single().clearance)
        val foreignProducer=MeasuredBodySpace()
        assertNull(TrackedMeasuredBodyBinding.freeze(foreignProducer,f.capture,f.frozen,f.key,ERROR))
    }

    @Test fun bodyRetirementAndReplacingActiveCaptureAreConsumedByActualProducer() {
        val f=Fixture(); assertNotNull(f.run().single().clearance)
        f.body.retire(); assertNull(f.run().single().clearance)
        val replacement=Fixture(); assertNotNull(replacement.run().single().clearance)
        assertNotNull(replacement.body.capture(replacement.registration,replacement.key.copy(leaseIdentity="replacement"),
            replacement.snapshot,replacement.frozen,replacement.nowNs))
        assertNull(replacement.run().single().clearance)
    }

    @Test fun originalTargetElapsedResolutionAllowsNanosecondsButDoesNotRetagCapture() {
        val f=Fixture(captureRemainderNs=123_456L)
        assertEquals(f.target.capturedAtElapsedRealtimeMs,f.key.capturedAtElapsedNs/1_000_000)
        assertEquals(f.key,f.run().single().clearance?.captureKey)
        val shifted=f.target.copy(capturedAtElapsedRealtimeMs=f.target.capturedAtElapsedRealtimeMs+1)
        val packet=f.packet(target=shifted)
        assertTrue(f.run(packet=packet).isEmpty()) // existing pipeline rejects changed metadata at an already published frame
    }

    @Test fun sameMetadataWithDifferentPositionForwardIntrinsicsOrSemanticPayloadIsUnknown() {
        val f=Fixture(); assertNotNull(f.run().single().clearance)
        for (pose in listOf(f.pose.copy(positionX=.1f),f.pose.copy(forwardZ=1f),f.pose.copy(referenceId=8),
            f.pose.copy(imageProjection=f.pose.imageProjection!!.copy(fx=99f)))) {
            assertNull(f.run(snapshot=f.snapshot.copy(cameraPoseEvidence=pose)).single().clearance)
        }
        val foreign=semantic(f.target,20)
        assertNull(f.run(snapshot=f.snapshot.copy(semanticFrame=foreign)).single().clearance)
        assertNull(f.run(snapshot=f.snapshot.copy(semanticFrame=null)).single().clearance)
        assertNotNull(Fixture(includeSemantic=false).run().single().clearance)
    }

    @Test fun foreignSourceClockPairAndStaleFullCannotJoinSameCachedPublication() {
        val raw=Fixture(); assertNotNull(raw.run().single().clearance)
        for (snapshot in listOf(raw.snapshot.copy(rawDepthTimestampNs=raw.target.cameraTimestampNs-1),
            raw.snapshot.copy(rawDepthTimestampNs=raw.target.cameraTimestampNs-1,rawConfidenceTimestampNs=raw.target.cameraTimestampNs-1),
            raw.snapshot.copy(rawConfidenceTimestampNs=raw.target.cameraTimestampNs-1),raw.snapshot.copy(rawConfidence=null))) {
            assertNull(raw.run(snapshot=snapshot).single().clearance)
        }
        val full=Fixture(full=true); assertNotNull(full.run().single().clearance)
        assertNull(full.run(snapshot=full.snapshot.copy(fullDepthTimestampNs=full.target.cameraTimestampNs-1)).single().clearance)
    }

    @Test fun sameClocksWithDifferentOwnDepthConfidenceOrGridAreRejected() {
        for (full in listOf(false,true)) {
            val f=Fixture(full=full); assertNotNull(f.run().single().clearance)
            val depth=if(full) f.snapshot.fullDepth!! else f.snapshot.rawDepth!!
            val changed=DepthImage16(40,40,depth.millimeters.copyOf().also { it[820]=2121 })
            val snapshot=if(full) f.snapshot.copy(fullDepth=changed) else f.snapshot.copy(rawDepth=changed)
            assertNull(f.run(snapshot=snapshot).single().clearance)
        }
        val f=Fixture(); assertNotNull(f.run().single().clearance)
        val altered=f.snapshot.rawConfidence!!.values.copyOf().also { it[820]=240.toByte() }
        assertNull(f.run(snapshot=f.snapshot.copy(rawConfidence=ConfidenceImage8(40,40,altered))).single().clearance)
        assertNull(f.run(snapshot=f.snapshot.copy(rawDepth=DepthImage16(20,20,IntArray(400){2100}),
            rawConfidence=ConfidenceImage8(20,20,ByteArray(400){245.toByte()}))).single().clearance)
    }

    @Test fun originalFrozenTransformAndActualPipelinePointAndPolygonMappingMustMatch() {
        val f=Fixture(); assertNotNull(f.run().single().clearance)
        assertNull(f.binding(mapper=frozen(f.target.frameId))) // equal values are not the original object
        assertTrue(f.run(mapperFrameId=f.target.frameId+1).isEmpty())
        for (pointChanged in listOf(true,false)) {
            val mapper=object: CoordinateMapper by MAPPER {
                override fun imageToDepth(point: Point2)=if(pointChanged) Point2(point.x+.01f,point.y) else point
                override fun imagePolygonToDepthPolygon(polygon: List<Point2>)=
                    if(pointChanged) polygon else polygon.map { Point2(it.x+.01f,it.y) }
            }
            assertNull(f.run(mapper=mapper).single().clearance)
        }
    }

    @Test fun missingPointOrIndexAndLimitedPredictedPseudoPublicationsPreserveUnknownJoins() {
        val missing=Fixture(includePoint=false); val result=missing.run().single()
        assertNull(result.originalVisualMaterialPoint); assertNull(result.clearance); assertTrue(result.originalOutput.source.metric)
        val pseudo=Fixture(allDepthMm=0).run().single()
        assertEquals(DepthSource.POLYGON_TREND_PSEUDO_DEPTH,pseudo.originalOutput.source); assertNull(pseudo.clearance)
        val f=Fixture(); val original=f.run().single().originalOutput
        for (output in listOf(original.copy(sourceDetectionIndex=null),original.copy(sourceDetectionIndex=8),
            original.copy(spatialCoverageLimited=true),original.copy(depthAvailability=DepthAvailability.PREDICTED),
            original.copy(source=DepthSource.POLYGON_TREND_PSEUDO_DEPTH))) {
            val joined=joinTrackedMeasuredBodyDepth(output,f.snapshot,f.packet,MAPPER,f.target.frameId,MotionContext(),0,
                f.bound,f.key,f.nowNs)
            assertSame(output,joined.originalOutput); assertNull(joined.clearance)
        }
    }

    @Test fun holeOutsideAndRoundVersusFloorCellMismatchAreNotFilledOrRepaired() {
        assertNull(Fixture(point=Point2(.22f,.5f)).run().single().clearance) // round=9, floor=8
        assertNull(Fixture(point=Point2(.95f,.5f)).run().single().clearance) // outside own polygon
        assertNull(Fixture(pointDepthMm=0).run().single().clearance)
        assertNull(Fixture(pointDepthMm=2600).run().single().clearance) // own point outside foreground population
    }

    @Test fun immutableWrappersAndCapturedInputsCannotRewritePreviouslyReturnedEvidence() {
        val f=Fixture(); val list=f.run(); val first=list.single(); val point=requireNotNull(first.clearance).bodyPoint
        f.measured.cameraToBodyTranslationM!!.fill(100.0)
        f.snapshot.rawDepth!!.millimeters.fill(0); f.snapshot.rawConfidence!!.values.fill(0)
        assertEquals(point,first.clearance?.bodyPoint)
        assertSame(first.originalOutput,f.run().single().originalOutput)
        assertNull(f.run().single().clearance) // changed observations are not silently copied back into the body capture
        try { (list as MutableList).clear(); fail("wrapper list is owned and immutable") }
        catch (_: UnsupportedOperationException) { }
        assertEquals(1,list.size)
    }

    private class Fixture(val full: Boolean=false,point: Point2=Point2(.5f,.5f),includePoint: Boolean=true,
        includeSemantic: Boolean=true,pointDepthMm: Int=2120,captureRemainderNs: Long=0,allDepthMm: Int?=null) {
        val pipeline=ObjectDepthRuntimePipeline()
        val id=VisualMaterialPointId()
        val target=target(3)
        val pose=pose(target)
        val packet=packet(target,point,includePoint)
        val snapshot=snapshot(target,full,point,pointDepthMm,includeSemantic,allDepthMm)
        val frozen=frozen(target.frameId)
        val measured=MeasuredBodySpaceInput(BodyMeasurementProvenance("explicit fixture measurement",
            BodyMeasurementClock.UNIX_EPOCH_NANOSECONDS,1,BodySpaceAxes.RIGHT_UP_BACK,"same measured body origin mark",
            "Euclidean bounds including mount lifetime variation"),doubleArrayOf(0.0,0.0,0.0,1.0),doubleArrayOf(0.0,1.0,0.0),
            doubleArrayOf(-.4,0.0,-.4),doubleArrayOf(.4,1.7,.2),doubleArrayOf(-.4,.4,0.0,1.0,0.0),.01,0.0,.02,.03)
        val body=MeasuredBodySpace().also { it.bindOwner(OWNER) }
        val registration=requireNotNull(body.register(measured))
        val key=MeasuredBodyCaptureKey(OWNER,"original target lease",target.frameId,target.cameraTimestampNs,7,
            target.capturedAtElapsedRealtimeMs*1_000_000+captureRemainderNs,
            (target.capturedAtElapsedRealtimeMs+700)*1_000_000,1)
        val nowNs=key.capturedAtElapsedNs+50_000_000
        val capture=requireNotNull(body.capture(registration,key,snapshot,frozen,nowNs))
        val bound=requireNotNull(binding())
        init {
            for (i in 0 until 3) {
                val t=target(i)
                pipeline.processTrackedObservation(snapshot(t,full,point,pointDepthMm,includeSemantic,allDepthMm),
                    packet(t,point,includePoint),MAPPER,t.frameId,t.capturedAtElapsedRealtimeMs)
            }
        }
        fun packet(target: VisualFrameKey=this.target,point: Point2=Point2(.5f,.5f),includePoint: Boolean=true): CurrentTrackedFrameObservation {
            val source=target.copy(frameId=target.frameId-100_000_000,cameraTimestampNs=target.cameraTimestampNs-100_000_000,
                capturedAtElapsedRealtimeMs=target.capturedAtElapsedRealtimeMs-100)
            return requireNotNull(CurrentTrackedFrameObservation.fromTrackingResult(VisualTrackingResult(source,target,
                listOf(VisualTrackingObservation(0,source,target,VisualTrackingStatus.TRACKED,CANDIDATE,.95f,null,
                    Point2(0f,0f),39,39,0f,false,VisualAffineTransform(),
                    if(includePoint) VisualMaterialPoint(id,target,point) else null)),VisualTrackingMetrics(0,1,0,false,2,0)),
                listOf(CANDIDATE),true,target.capturedAtElapsedRealtimeMs))
        }
        fun binding(key: MeasuredBodyCaptureKey=this.key,error: BodyDepthObservationError?=ERROR,
            mapper: FrozenImageToDepthTransform=frozen)=TrackedMeasuredBodyBinding.freeze(body,capture,mapper,key,error)
        fun old()=pipeline.processTrackedObservation(snapshot,packet,MAPPER,target.frameId,target.capturedAtElapsedRealtimeMs+50)
        fun run(snapshot: DepthFrameSnapshot=this.snapshot,packet: CurrentTrackedFrameObservation=this.packet,
            binding: TrackedMeasuredBodyBinding?=bound,key: MeasuredBodyCaptureKey=this.key,nowNs: Long=this.nowNs,
            mapper: CoordinateMapper=MAPPER,mapperFrameId: Long=target.frameId)=pipeline.processTrackedObservationWithMeasuredBody(
            snapshot,packet,mapper,mapperFrameId,target.capturedAtElapsedRealtimeMs+50,binding=binding,
            currentOriginalBodyKey=key,nowElapsedNs=nowNs)
    }

    private class SemanticImage(private val time: Long,first: Int): Image() {
        private val bytes=ByteArray(8){(first+it).toByte()}
        override fun getWidth()=2
        override fun getHeight()=4
        override fun getFormat()=35
        override fun getTimestamp()=time
        override fun close()=Unit
        override fun getPlanes(): Array<Plane> = arrayOf(object: Plane() {
            override fun getBuffer(): ByteBuffer=ByteBuffer.wrap(bytes)
            override fun getRowStride()=2
            override fun getPixelStride()=1
        })
    }

    private companion object {
        val OWNER=BodySpaceOwner(3,"original measured mount",1)
        val ERROR=BodyDepthObservationError(.04,.001,"depth/K/frozen mapper metric and original axial bounds")
        val CANDIDATE=DetectionCandidate("car",.9f,RectNorm(.1f,.1f,.8f,.8f))
        val MAPPER=LetterboxCoordinateMapper(ModelInputTransform(40,40,40,40,1f,0f,0f),ImageSize(40,40))
        fun target(i: Int)=VisualFrameKey(41,(1_000L+i*500)*1_000_000,(900L+i*500)*1_000_000,50_000L+i*500,1)
        fun pose(t: VisualFrameKey)=CameraPoseEvidence(7,t.frameId/1_000_000,0f,1.5f,0f,0f,0f,-1f,
            CameraImageProjection(40,40,60f,60f,20f,20f,1f,0f,0f,0f,1f,0f))
        fun frozen(frame: Long)=requireNotNull(FrozenImageToDepthTransform.create(frame,
            listOf(Point2(0f,0f),Point2(1f,0f),Point2(0f,1f),Point2(1f,1f)),Point2(.5f,.5f)))
        fun semantic(t: VisualFrameKey,first: Int=10)=SemanticFrameSnapshot(t.frameId,t.cameraTimestampNs,7,
            SemanticImage8Snapshot.copyOf(SemanticImage(6_000_000_000,first)),
            SemanticImage8Snapshot.copyOf(SemanticImage(6_100_000_000,110)))
        fun snapshot(t: VisualFrameKey,full: Boolean,point: Point2,pointDepth: Int,includeSemantic: Boolean,allDepthMm: Int?): DepthFrameSnapshot {
            val x=kotlin.math.round(point.x*39).toInt(); val y=kotlin.math.round(point.y*39).toInt()
            val depth=DepthImage16(40,40,IntArray(1600){allDepthMm ?: if(it==y*40+x) pointDepth else 2100})
            return DepthFrameSnapshot(t.frameId,if(full) null else depth,
                if(full) null else ConfidenceImage8(40,40,ByteArray(1600){245.toByte()}),if(full) depth else null,
                rawDepthTimestampNs=if(full) null else t.cameraTimestampNs,rawConfidenceTimestampNs=if(full) null else t.cameraTimestampNs,
                fullDepthTimestampNs=if(full) t.cameraTimestampNs else null,cameraImageTimestampNs=t.cameraTimestampNs,
                cameraPoseEvidence=pose(t),semanticFrame=if(includeSemantic) semantic(t) else null)
        }
    }
}
