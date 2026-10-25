package kr.co.hanium.dreamup.walksafe.depth

import kr.co.hanium.dreamup.walksafe.inference.tracking.*
import org.junit.Assert.*
import org.junit.Test

/** Real InterFrame/admission/pipeline with deterministic measured correspondences, not native LK. */
class QualifiedSourceScaleHandoffRegressionTest {
    @Test fun sourceBoundMeasuredExpansionKeepsIdsAtThirtyMs() = verifyExpansion(30L)
    @Test fun sourceBoundMeasuredExpansionKeepsIdsAtFiveHundredMs() = verifyExpansion(500L)

    private fun verifyExpansion(gap: Long) {
        val scene = Scene(gap)
        val first = scene.first()
        val common = scene.common()
        assertEquals(2, first.outputs.size)
        assertEquals(2, common.observations.size)
        assertEquals(ids(first.result), ids(common))
        for (row in common.observations) {
            val old = first.result.observations.single { it.materialPoint!!.id === row.materialPoint!!.id }
            assertEquals(VisualTrackingStatus.TRACKED, row.status)
            assertTrue(row.trackingQuality >= .65f)
            assertTrue(row.originalFeatureCount >= 6 && row.survivingFeatureCount >= 6)
            assertTrue(row.transformNorm!!.a in .5f..2f)
            assertTrue(row.transformNorm.areaScale in .25f..4f)
            assertTrue(bboxIoU(old.geometry!!.bboxNorm, row.geometry!!.bboxNorm) >= .65f)
        }
        // Actual source-time boxes have four center-only candidates, no .20 IoU anchor.
        for (old in first.detections) for (new in scene.nextDetections) {
            assertEquals(0f, bboxIoU(old.bboxNorm, new.bboxNorm), 0f)
            assertTrue(centerDistance(old.bboxNorm.center, new.bboxNorm.center) <= .25f)
        }
        val after = scene.next()
        assertEquals(ids(first.result), ids(after.result))
        assertEquals(2, after.outputs.size)
        val expected = assignments(first)
        for (row in after.result.observations) {
            val old = first.result.observations.single { it.materialPoint!!.id === row.materialPoint!!.id }
            assertTrue(row.transformNorm!!.a in .5f..2f)
            assertTrue(row.transformNorm.areaScale in .25f..4f)
            val ratio = row.geometry!!.bboxNorm.area / old.geometry!!.bboxNorm.area
            assertTrue("actual target area ratio $ratio", ratio > 4f)
        }
        println("QUALIFIED_SCALE gap=$gap old=${first.outputs.map { it.trackId }} new=${after.outputs.map { it.trackId }} " +
            "oldSource=${first.result.detectorSourceKey} common=${common.trackedTargetKey} newSource=${after.result.detectorSourceKey} target=${after.result.trackedTargetKey}")
        assertEquals("Qualified common-image ownership must survive source-scale handoff", expected, assignments(after))
        assertTrue(after.outputs.all { it.trackAgeFrames == 2 })
        for (row in after.result.observations) {
            val output = after.outputs.single { it.sourceDetectionIndex == row.sourceIndex }
            assertEquals(after.result.trackedTargetKey.frameId, output.frameId)
            assertEquals(after.result.trackedTargetKey.frameId / 1_000_000L, output.timestampMs)
            assertEquals(row.geometry!!.bboxNorm, output.bboxNorm)
            assertEquals(DepthAvailability.MEASURED, output.depthAvailability)
            assertEquals(3f, output.riskDistanceM!!, .001f)
            val track = scene.tracker.activeTracks().single { it.trackId == output.trackId }
            assertEquals(output.timestampMs, track.lastSeenAtMs)
            assertEquals(row.geometry.bboxNorm, track.latestGeometry!!.bboxNorm)
        }
    }

    @Test fun laterProducerCommonFrameKeepsThePreviouslyConsumedIdentity() {
        val scene = Scene(500L)
        val first = scene.first()
        val oldCommon = scene.advanceOldOnly(1_150L)
        val newCommon = scene.common(1_150L)
        assertEquals(ids(first.result), ids(oldCommon))
        assertEquals(ids(first.result), ids(newCommon))
        assertTrue(oldCommon.trackedTargetKey.frameId > first.result.trackedTargetKey.frameId)
        for (row in newCommon.observations) {
            val old = oldCommon.observations.single { it.materialPoint!!.id === row.materialPoint!!.id }
            assertTrue(row.trackingQuality >= .65f && row.originalFeatureCount >= 6 && row.survivingFeatureCount >= 6)
            assertTrue(bboxIoU(old.geometry!!.bboxNorm, row.geometry!!.bboxNorm) >= .65f)
        }
        for (old in first.detections) for (new in scene.nextDetections) {
            assertEquals(0f, bboxIoU(old.bboxNorm, new.bboxNorm), 0f)
            assertTrue(centerDistance(old.bboxNorm.center, new.bboxNorm.center) <= .25f)
        }
        val after = scene.next()
        assertEquals(ids(first.result), ids(after.result))
        for (row in after.result.observations) {
            val old = first.result.observations.single { it.materialPoint!!.id === row.materialPoint!!.id }
            assertTrue(row.geometry!!.bboxNorm.area / old.geometry!!.bboxNorm.area > 4f)
        }
        println("SPARSE_SCALE oldDepth=${first.result.trackedTargetKey} common=${newCommon.trackedTargetKey} current=${after.result.trackedTargetKey} oldIDs=${first.outputs.map { it.trackId }} newIDs=${after.outputs.map { it.trackId }}")
        assertEquals(assignments(first), assignments(after))
        assertEquals(2, after.outputs.size)
        assertTrue(after.outputs.all { it.trackAgeFrames == 2 })
        for (row in after.result.observations) {
            val output = after.outputs.single { it.sourceDetectionIndex == row.sourceIndex }
            assertEquals(row.geometry!!.bboxNorm, output.bboxNorm)
            assertEquals(after.result.trackedTargetKey.frameId, output.frameId)
        }
    }

    @Test fun qualifiedMeasuredLargeDisplacementKeepsTheSourceBoundIdentity() = verifyDisplacement(.4f, .35f, null)
    @Test fun qualifiedMeasuredDisplacementAboveWeakIouLimitKeepsIdentity() = verifyDisplacement(.3f, .25f, .35f)

    private fun verifyDisplacement(shift: Float, minDistance: Float, maxDistance: Float?) {
        val scene = Scene(500L, finalShift = shift)
        val first = scene.first()
        assertEquals(ids(first.result), ids(scene.common()))
        val after = scene.next()
        assertEquals(ids(first.result), ids(after.result))
        for (row in after.result.observations) {
            val old = first.result.observations.single { it.materialPoint!!.id === row.materialPoint!!.id }
            val distance = centerDistance(old.geometry!!.bboxNorm.center, row.geometry!!.bboxNorm.center)
            assertTrue("measured center displacement $distance", distance > minDistance)
            if (maxDistance != null) assertTrue(distance < maxDistance)
            assertEquals(0f, bboxIoU(old.geometry.bboxNorm, row.geometry!!.bboxNorm), 0f)
            assertNotNull(row.materialPointHandoff)
        }
        println("MEASURED_TRANSLATION oldIDs=${first.outputs.map { it.trackId }} newIDs=${after.outputs.map { it.trackId }}")
        assertEquals(assignments(first), assignments(after))
        assertEquals(2, after.outputs.size)
        assertTrue(after.outputs.all { it.trackAgeFrames == 2 })
        for (row in after.result.observations) {
            val output = after.outputs.single { it.sourceDetectionIndex == row.sourceIndex }
            assertEquals(row.geometry!!.bboxNorm, output.bboxNorm)
            assertEquals(after.result.trackedTargetKey.frameId, output.frameId)
        }
    }

    @Test fun unprovenShrinkAndLargeDisplacementRetainTheExistingGeometryVeto() {
        for (scene in listOf(Scene(30L, commonScale = 1.1f, finalScale = .5f), Scene(500L, finalShift = .4f))) {
            val first = scene.first()
            assertEquals(ids(first.result), ids(scene.common()))
            val after = scene.next { it.copy(observations = it.observations.map { row -> row.copy() }) }
            assertEquals(ids(first.result), ids(after.result))
            assertEquals(2, after.outputs.size)
            assertTrue(after.outputs.none { output -> first.outputs.any { it.trackId == output.trackId } })
            assertTrue(after.outputs.all { it.trackAgeFrames == 1 })
        }
    }

    @Test fun copiesAndUnqualifiedMeasurementsCannotInheritExpandedIdentity() {
        val invalidRows: List<(VisualTrackingObservation) -> VisualTrackingObservation> = listOf(
            { it.copy() }, // Preserved ABI copy is deliberately not a producer witness.
            { it.copy(trackingQuality = .64f) },
            { it.copy(transformNorm = null) },
            { it.copy(originalFeatureCount = 5, survivingFeatureCount = 5) },
            { it.copy(materialPoint = null) },
        )
        for (invalidate in invalidRows) {
            val scene = Scene(30L)
            val old = scene.first().outputs.map { it.trackId }.toSet()
            scene.common()
            val after = scene.next { it.copy(observations = it.observations.map(invalidate)) }
            assertTrue(after.outputs.none { it.trackId in old })
            assertTrue(after.outputs.all { it.trackAgeFrames == 1 })
        }
    }

    @Test fun commonImageWithoutStrongGeometricHandoffDoesNotInheritIdentity() {
        val scene = Scene(30L, sourceOffset = .03f)
        val first = scene.first()
        val common = scene.common()
        for (row in common.observations) {
            assertTrue(first.result.observations.all { bboxIoU(it.geometry!!.bboxNorm, row.geometry!!.bboxNorm) < .65f })
        }
        assertTrue(ids(first.result).intersect(ids(common)).isEmpty())
        val after = scene.next()
        assertTrue(after.outputs.none { output -> first.outputs.any { it.trackId == output.trackId } })
    }

    @Test fun qualifiedCommonPointKeepsIdentityAcrossMeasuredShrink() {
        val scene = Scene(30L, commonScale = 1.1f, finalScale = .5f)
        val first = scene.first()
        val common = scene.common()
        assertEquals(ids(first.result), ids(common))
        val after = scene.next()
        assertEquals(ids(first.result), ids(after.result))
        for (row in after.result.observations) {
            val old = first.result.observations.single { it.materialPoint!!.id === row.materialPoint!!.id }
            assertTrue(old.geometry!!.bboxNorm.area / row.geometry!!.bboxNorm.area > 4f)
        }
        assertEquals(2, after.outputs.size)
        assertEquals(assignments(first), assignments(after))
        assertTrue(after.outputs.all { it.trackAgeFrames == 2 })
        for (row in after.result.observations) {
            val output = after.outputs.single { it.sourceDetectionIndex == row.sourceIndex }
            assertEquals(row.geometry!!.bboxNorm, output.bboxNorm)
            assertEquals(after.result.trackedTargetKey.frameId, output.frameId)
        }
    }

    @Test fun wrongCpuOrExpiredCaptureCannotPublishQualifiedExpansion() {
        val scene = Scene(30L)
        scene.first()
        scene.common()
        val result = scene.nextResult()
        val target = result.trackedTargetKey
        val input = requireNotNull(CurrentTrackedFrameObservation.fromTrackingResult(result,
            scene.nextDetections, true, target.capturedAtElapsedRealtimeMs))
        val before = scene.ledger()
        assertTrue(scene.pipeline.processTrackedObservation(snapshot(target).copy(
            cameraImageTimestampNs = target.cameraTimestampNs + 1L), input, MAPPER,
            target.frameId, target.capturedAtElapsedRealtimeMs).isEmpty())
        assertEquals(before, scene.ledger())
        assertTrue(scene.pipeline.processTrackedObservation(snapshot(target), input, MAPPER,
            target.frameId, target.capturedAtElapsedRealtimeMs + 801L).isEmpty())
        assertEquals(before, scene.ledger())
    }

    private data class Seen(val detections: List<DetectionCandidate>, val result: VisualTrackingResult,
        val outputs: List<TrackedObjectDepth>)
    private class Scene(val gap: Long, val commonScale: Float = .9f, val finalScale: Float = 1.98f,
        sourceOffset: Float = 0f, val finalShift: Float = 0f) {
        val visual = InterFrameDetectionTracker(VisualTrackingConfig(allowSimilarityTransform = true), { 0L }, Flow(gap, commonScale, finalScale, finalShift))
        val tracker = ObjectTracker()
        val pipeline = ObjectDepthRuntimePipeline(tracker = tracker, rawDepthMotionOnly = true)
        val nextDetections = boxes(1_050).map { it.copy(bboxNorm = it.bboxNorm.copy(x = it.bboxNorm.x + sourceOffset)) }
        private var at = 950L
        private fun offer(until: Long) { while (at < until) { at = minOf(at + 50L, until); assertTrue(visual.offerFrame(frame(key(at))).accepted) } }
        fun first(): Seen { offer(1_100); return publish(boxes(1_000),visual.trackFrom(key(1_000),boxes(1_000),key(1_100))) }
        fun common(at: Long = 1_100L): VisualTrackingResult = visual.trackFrom(key(1_050),nextDetections,key(at))
        fun advanceOldOnly(at: Long): VisualTrackingResult { offer(at); return visual.trackFrom(key(1_000),boxes(1_000),key(at)) }
        fun nextResult(): VisualTrackingResult { offer(1_100 + gap); return visual.trackFrom(key(1_050),nextDetections,key(1_100 + gap)) }
        fun next(change: (VisualTrackingResult) -> VisualTrackingResult = { it }): Seen = publish(nextDetections, change(nextResult()))
        fun ledger() = tracker.activeTracks().associate { it.trackId to Triple(it.ageFrames, it.lastSeenAtMs, it.distanceHistory.toList()) }
        private fun publish(detections: List<DetectionCandidate>, result: VisualTrackingResult): Seen {
            assertNull(result.batchFailure)
            val target = result.trackedTargetKey
            val observation = requireNotNull(CurrentTrackedFrameObservation.fromTrackingResult(result,detections,true,target.capturedAtElapsedRealtimeMs))
            return Seen(detections,result,pipeline.processTrackedObservation(snapshot(target),observation,MAPPER,target.frameId,target.capturedAtElapsedRealtimeMs))
        }
    }

    private class Flow(val gap: Long, val commonScale: Float, val finalScale: Float, val finalShift: Float) : VisualMotionEstimator {
        override val supportsMaterialPointTracking: Boolean get() = true
        override fun seed(frame: GrayTrackingFrame,detection: DetectionCandidate,budget: TrackingWorkBudget): List<VisualMotionFeature> =
            (0 until 40).map { i -> val b=detection.bboxNorm
                VisualMotionFeature(((b.x+b.width*(.1f+(i%8)*.8f/7f))*frame.width).toInt(),
                    ((b.y+b.height*(.1f+(i/8)*.8f/4f))*frame.height).toInt()) }
        override fun advance(source: GrayTrackingFrame, previous: GrayTrackingFrame,target: GrayTrackingFrame,
            detection: DetectionCandidate,features: List<VisualMotionFeature>,originalFeatureCount: Int,
            budget: TrackingWorkBudget,materialPoint: VisualMotionPoint?): VisualMotionEstimate {
            val before=absolute(previous.key.frameId/1_000_000L,gap,commonScale,finalScale,finalShift); val after=absolute(target.key.frameId/1_000_000L,gap,commonScale,finalScale,finalShift)
            val origin=absolute(source.key.frameId/1_000_000L,gap,commonScale,finalScale,finalShift)
            fun relative(a:VisualAffineTransform,b:VisualAffineTransform)=VisualAffineTransform(a=a.a/b.a,d=a.d/b.d,
                tx=a.tx-a.a/b.a*b.tx,ty=a.ty-a.d/b.d*b.ty)
            val mapping=relative(after,origin); val edge=relative(after,before)
            // Native estimator's qualification is source-to-target, not an edge ratio.
            assertTrue("source cumulative scale ${mapping.a}", mapping.a in .5f..2f)
            assertTrue("source cumulative area ${mapping.areaScale}", mapping.areaScale in .25f..4f)
            return VisualMotionEstimate(features.map { f -> val p=mapping.map(Point2(f.sourceX/source.width.toFloat(),f.sourceY/source.height.toFloat()))
                assertTrue("measured feature stays in image: $p", p.x in 0f..1f && p.y in 0f..1f)
                f.copy(currentX=p.x*target.width,currentY=p.y*target.height) },mapping,.95f,0f,
                materialPoint=materialPoint?.let { p -> val q=edge.map(Point2(p.x/previous.width,p.y/previous.height))
                    assertTrue("measured point stays in image: $q", q.x in 0f..1f && q.y in 0f..1f)
                    p.copy(x=q.x*target.width,y=q.y*target.height) })
        }
    }
    private companion object {
        fun absolute(at:Long,gap:Long,commonScale:Float=.9f,finalScale:Float=1.98f,finalShift:Float=0f):VisualAffineTransform {
            val scale=when { at<1_100 -> 1f; at==1_100L -> commonScale; else -> commonScale+(finalScale-commonScale)*(at-1_100)/gap.toFloat() }
            val shift=if(at>=1_050) .16f else 0f
            return VisualAffineTransform(a=scale,d=scale,tx=scale*shift+(1f-scale)*.365f+finalShift*((at-1_100)/gap.toFloat()).coerceIn(0f,1f),ty=(1f-scale)*.43f)
        }
        fun boxes(at:Long)=listOf(.15f,.20f).map { x -> val b=RectNorm(x,.4f,.06f,.06f); val m=absolute(at,30)
            val p=m.map(Point2(b.x,b.y)); DetectionCandidate("person",.95f,RectNorm(p.x,p.y,b.width*m.a,b.height*m.d)) }
        fun key(at:Long)=VisualFrameKey(7L,at*1_000_000L,10_000_000_000_000L+at*1_000_000L,at+50_000L,3L)
        fun frame(k:VisualFrameKey)=GrayTrackingFrame.copyOf(k,192,192,ByteArray(192*192),1000,1000)
        val MAPPER=LetterboxCoordinateMapper(ModelInputTransform(1000,1000,1000,1000,1f,0f,0f),ImageSize(100,100))
        fun snapshot(k:VisualFrameKey)=DepthFrameSnapshot(k.frameId,DepthImage16(100,100,IntArray(10_000){3000}),
            ConfidenceImage8(100,100,ByteArray(10_000){255.toByte()}),null,rawDepthTimestampNs=k.cameraTimestampNs,
            rawConfidenceTimestampNs=k.cameraTimestampNs,cameraImageTimestampNs=k.cameraTimestampNs)
        fun ids(r:VisualTrackingResult)=r.observations.map { requireNotNull(it.materialPoint).id }.toSet()
        fun assignments(s:Seen)=s.result.observations.associate { row -> requireNotNull(row.materialPoint).id to
            s.outputs.single { it.sourceDetectionIndex==row.sourceIndex }.trackId }
    }
}
