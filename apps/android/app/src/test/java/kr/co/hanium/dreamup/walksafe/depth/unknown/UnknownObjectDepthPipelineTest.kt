package kr.co.hanium.dreamup.walksafe.depth.unknown

import java.util.BitSet
import kr.co.hanium.dreamup.walksafe.depth.*
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamFrameToken
import kr.co.hanium.dreamup.walksafe.inference.unknown.segmentation.InstanceMask
import org.junit.Assert.*
import org.junit.Test

class UnknownObjectDepthPipelineTest {
    private val width = 40
    private val height = 32
    private var clock = 100_050L
    private fun pipeline() = UnknownObjectDepthPipeline(elapsedRealtimeMs = { clock })
    private fun token(i: Int, epoch: Long = 1L, geometry: String = "geometry-1") = FastSamFrameToken(
        FastSamFrameToken.Source.LIVE_CAMERA, epoch, 10_000_000_000L + i * 200_000_000L,
        10_000_000_000L + i * 200_000_000L, 8_000_000_000L + i * 200_000_000L,
        100_000_000_000L + i * 200_000_000L, geometry, width, height,
    )
    private fun snapshot(t: FastSamFrameToken, mm: Int = 2_000, rawTimestamp: Long = t.cpuImageTimestampNs,
                         confidenceTimestamp: Long? = rawTimestamp) = DepthFrameSnapshot(
        frameTimestampNs = t.cameraTimestampNs,
        rawDepth = DepthImage16(width, height, IntArray(width * height) { mm }),
        rawConfidence = ConfidenceImage8(width, height, ByteArray(width * height) { 255.toByte() }),
        fullDepth = DepthImage16(width, height, IntArray(width * height) { 1000 }),
        rawDepthTimestampNs = rawTimestamp,
        cameraImageTimestampNs = t.cpuImageTimestampNs,
        cameraPoseEvidence = CameraPoseEvidence(1L, t.cameraTimestampNs / 1_000_000L, 0f, 0f, 0f, 0f, 0f, -1f,
            CameraImageProjection(width, height, 40f, 40f, width / 2f, height / 2f, 1f, 0f, 0f, 0f, 1f, 0f)),
        fullDepthTimestampNs = null,
        rawConfidenceTimestampNs = confidenceTimestamp,
    )
    private fun h() = doubleArrayOf(1.0 / width, 0.0, 0.0, 0.0, 1.0 / height, 0.0, 0.0, 0.0, 1.0)
    private fun capture(t: FastSamFrameToken, snapshot: DepthFrameSnapshot = snapshot(t), matrix: DoubleArray? = h()) =
        requireNotNull(FrozenUnknownDepthCapture.freeze(t, snapshot, matrix, t.frameId))
    private fun mask(vararg rectangles: IntArray): InstanceMask {
        val bits = BitSet(width * height)
        for (r in rectangles) for (y in r[1] until r[3]) for (x in r[0] until r[2]) bits.set(y * width + x)
        val constructor = InstanceMask::class.java.declaredConstructors.single().also { it.isAccessible = true }
        return constructor.newInstance(width, height, 0, 0, width, height, bits.cardinality(), 17,
            0f, 0f, width.toFloat(), height.toFloat(), .99f, bits) as InstanceMask
    }
    private fun regular() = mask(intArrayOf(5, 5, 29, 27))
    private fun tightRegular(): InstanceMask {
        val bits = BitSet(24 * 20).also { it.set(0, 24 * 20) }
        val constructor = InstanceMask::class.java.declaredConstructors.single().also { it.isAccessible = true }
        return constructor.newInstance(width, height, 8, 6, 32, 26, 480, 17,
            8f, 6f, 32f, 26f, .99f, bits) as InstanceMask
    }
    private fun process(p: UnknownObjectDepthPipeline, t: FastSamFrameToken, c: FrozenUnknownDepthCapture = capture(t),
                        masks: List<InstanceMask> = listOf(regular()), named: List<NamedMaskDepthEvidence> = emptyList()): UnknownDepthFrameResult {
        clock = t.capturedElapsedNs / 1_000_000L + 50L
        return p.process(c, t, masks, clock, named)
    }

    @Test fun frozenCaptureSeparatesCameraClocksAndCopiesMutableInputs() {
        val t = token(0); val s = snapshot(t); val matrix = h(); val c = capture(t, s, matrix)
        s.rawDepth!!.millimeters.fill(0); s.rawConfidence!!.values.fill(0); matrix.fill(0.0)
        val result = process(pipeline(), t, c)
        assertEquals(MaskDepthEstimator.Status.KNOWN, result.observations.single().depth.status)
        assertEquals(2f, result.objects.single().zDistanceM!!, 0f)
        assertNull(FrozenUnknownDepthCapture.freeze(t, snapshot(t).copy(frameTimestampNs = t.cameraTimestampNs + 1), h(), t.frameId))
        assertNull(FrozenUnknownDepthCapture.freeze(t, snapshot(t).copy(cameraImageTimestampNs = t.cpuImageTimestampNs + 1), h(), t.frameId))
        assertNull(FrozenUnknownDepthCapture.freeze(t, snapshot(t), h(), t.frameId + 1))
        assertNull(FrozenUnknownDepthCapture.freeze(t, snapshot(t).copy(cameraPoseEvidence = snapshot(t).cameraPoseEvidence!!.copy(timestampMs = 7)), h(), t.frameId))
    }

    @Test fun extentNeedsItsOwnContinuousStableObservationsAndResetsAfterMissingFrame() {
        val p = pipeline()
        val results = (0..4).map { i -> process(p, token(i), masks = listOf(tightRegular())) }
        assertTrue(results.take(4).all { it.observations.single().metricExtent?.canRejectSmall == false })
        assertTrue(requireNotNull(results.last().observations.single().metricExtent).canRejectSmall)
        process(p, token(5), masks = emptyList())
        val afterGap = process(p, token(6), masks = listOf(tightRegular()))
        assertFalse(requireNotNull(afterGap.observations.single().metricExtent).canRejectSmall)
        assertEquals("extent_temporal_support_pending", afterGap.observations.single().metricExtent?.reason)
    }

    @Test fun changedExtentAndFullFallbackCannotReusePriorExtentConfirmation() {
        val p = pipeline()
        (0..4).forEach { i -> process(p, token(i), masks = listOf(tightRegular())) }
        val t = token(5)
        val smaller = process(p, t, capture(t, snapshot(t, 1000)), listOf(tightRegular()))
        assertFalse(requireNotNull(smaller.observations.single().metricExtent).canRejectSmall)
        val fullToken = token(6)
        val s = snapshot(fullToken, 0).copy(fullDepthTimestampNs = fullToken.cpuImageTimestampNs)
        val full = process(p, fullToken, capture(fullToken, s), listOf(tightRegular()))
        assertFalse(requireNotNull(full.observations.single().metricExtent).canRejectSmall)
        assertEquals("smoothed_depth_extent_is_diagnostic", full.observations.single().metricExtent?.reason)
        val raw = process(p, token(7), masks = listOf(tightRegular()))
        assertFalse(requireNotNull(raw.observations.single().metricExtent).canRejectSmall)
    }

    @Test fun partialAndUnknownNeverBecomeWholeDistanceOrMessage() {
        val t = token(0)
        val partial = mask(intArrayOf(3, 5, 28, 27), intArrayOf(35, 2, 37, 4))
        val result = process(pipeline(), t, masks = listOf(partial))
        val evidence = result.observations.single().depth
        assertEquals(MaskDepthEstimator.Status.PARTIAL, evidence.status)
        assertEquals(2, evidence.components.size)
        assertNotNull(evidence.dominantComponentDistance)
        assertNull(evidence.axialDepthM)
        val obj = result.objects.single()
        assertEquals(DepthSource.UNKNOWN, obj.source)
        assertNull(obj.zDistanceM); assertNull(obj.riskDistanceM); assertNull(obj.approachSpeedMps)
        assertNull(obj.timeToCollisionMs); assertNull(obj.userFacing.message); assertNull(obj.userFacing.stepsAhead)
        val noDepth = process(pipeline(), t, capture(t, snapshot(t, mm = 0))).objects.single()
        assertNull(noDepth.riskDistanceM)
        assertNull(noDepth.userFacing.message)
    }

    @Test fun missingOrMismatchedRawConfidenceWithoutFreshFullDepthStaysUnknown() {
        val t = token(0)
        for (s in listOf(snapshot(t, confidenceTimestamp = null), snapshot(t, confidenceTimestamp = 1L),
            snapshot(t).copy(rawDepth = null, rawConfidence = null))) {
            val r = process(pipeline(), t, capture(t, s))
            assertEquals(MaskDepthEstimator.Status.UNKNOWN, r.observations.single().depth.status)
            assertNull(r.objects.single().riskDistanceM)
        }
    }

    @Test fun sparseRawUsesFrozenFullMaskDepthWithExplicitSourceAndNoInventedMotion() {
        val p = pipeline()
        val outputs = (0..8).map { i ->
            val t = token(i)
            val s = snapshot(t, mm = 0).copy(
                fullDepth = DepthImage16(width, height, IntArray(width * height) { 2200 - i * 100 }),
                fullDepthTimestampNs = t.cpuImageTimestampNs,
            )
            val frozen = capture(t, s)
            s.fullDepth!!.millimeters.fill(0)
            val r = process(p, t, frozen)
            assertEquals("ARCORE_FULL_DEPTH", r.observations.single().depth.source)
            assertNull(r.observations.single().depth.components.single().rawConfidenceMedian)
            r.objects.single().also {
                assertEquals(DepthSource.ARCORE_FULL_DEPTH, it.source)
                assertEquals((2200 - i * 100) / 1000f, it.zDistanceM!!, 0.0001f)
                assertNull(it.approachSpeedMps)
                assertNull(it.timeToCollisionMs)
                assertEquals(ObjectMotion.UNKNOWN, it.objectMotion)
            }
        }
        assertTrue(outputs.any { it.walkingObstacleCandidate && it.userFacing.message != null })
    }

    @Test fun freshFullWorksWithoutRawButStaleFullCannotFillMissingDepth() {
        val t = token(0)
        val s = snapshot(t).copy(rawDepth = null, rawConfidence = null, fullDepthTimestampNs = t.cpuImageTimestampNs)
        assertEquals(1f, process(pipeline(), t, capture(t, s)).objects.single().zDistanceM!!, 0f)
        val stale = s.copy(fullDepthTimestampNs = t.cpuImageTimestampNs - 1)
        assertNull(process(pipeline(), t, capture(t, stale)).objects.single().riskDistanceM)
        val future = s.copy(fullDepthTimestampNs = t.cpuImageTimestampNs + 1)
        assertNull(process(pipeline(), t, capture(t, future)).objects.single().riskDistanceM)
        // A supported raw mask always wins even when smoothed depth disagrees.
        val both = snapshot(t).copy(fullDepthTimestampNs = t.cpuImageTimestampNs)
        assertEquals(DepthSource.ARCORE_RAW_DEPTH, process(pipeline(), t, capture(t, both)).objects.single().source)
    }

    @Test fun fullProximityBetweenFreshRawMasksDoesNotEraseRawMotionSupport() {
        val p = pipeline()
        val outputs = (0..12).map { i ->
            val t = token(i)
            val s = if (i % 2 == 0) snapshot(t, 5000 - i * 150) else snapshot(t, 0).copy(
                fullDepth = DepthImage16(width, height, IntArray(width * height) { 5000 - i * 150 }),
                fullDepthTimestampNs = t.cpuImageTimestampNs)
            process(p, t, capture(t, s)).objects.single()
        }
        assertEquals(1, outputs.map { it.trackId }.distinct().size)
        assertTrue(outputs.filterIndexed { i, _ -> i % 2 == 1 }.all {
            it.source == DepthSource.ARCORE_FULL_DEPTH && it.trend == Trend.UNKNOWN &&
                it.approachSpeedMps == null && it.timeToCollisionMs == null
        })
        assertNotNull(outputs.last().approachSpeedMps)
        assertNotNull(outputs.last().timeToCollisionMs)
        assertEquals(Trend.APPROACHING, outputs.last().trend)
    }

    @Test fun fullSmoothingCannotOverwriteAmbiguousRawLayers() {
        val t = token(0)
        val s = snapshot(t).copy(fullDepthTimestampNs = t.cpuImageTimestampNs)
        for (y in 0 until height) for (x in 0 until width) {
            s.rawDepth!!.millimeters[y * width + x] = if (x < 17) 1000 else 4000
        }
        val r = process(pipeline(), t, capture(t, s))
        assertEquals("ARCORE_RAW_DEPTH", r.observations.single().depth.source)
        assertNull(r.objects.single().riskDistanceM)
    }

    @Test fun sideAndFarMasksStayDiagnosticAndCannotCreateWarnings() {
        val side = mask(intArrayOf(0, 6, 8, 27))
        val p = pipeline()
        val outputs = (0..5).map { i -> process(p, token(i), capture(token(i), snapshot(token(i), 1000)), listOf(side)) }
        assertTrue(outputs.all { !it.objects.single().walkingObstacleCandidate && it.objects.single().userFacing.message == null })
        assertTrue(outputs.all { it.observations.single().walkingSelection?.reason == "outside_forward_corridor" })
        val far = process(pipeline(), token(0), capture(token(0), snapshot(token(0), 8000)))
        assertFalse(far.objects.single().walkingObstacleCandidate)
        assertEquals("outside_near_obstacle_range", far.observations.single().walkingSelection?.reason)
    }

    @Test fun rawConfidenceIsNormalizedAndIqrIsMeasured() {
        val t = token(0)
        val s = snapshot(t)
        s.rawConfidence!!.values.fill(128.toByte())
        val result = process(pipeline(), t, capture(t, s)).objects.single()
        assertEquals(0.0f, result.depthIqrM!!, 0f)
        assertTrue(result.confidence.depthQuality in 0.75f..0.80f)
    }

    @Test fun noDepthKeepsDiagnosticObservationsButCannotEstablishThreeMeterDisplayOrWarning() {
        val p = pipeline()
        val results = (0..4).map { i ->
            val t = token(i)
            process(p, t, capture(t, snapshot(t).copy(rawDepth = null, rawConfidence = null, fullDepth = null)))
        }
        val last = results.last()
        // Repetition used to permit display without range; it cannot prove the v6 <=3 m boundary.
        assertTrue(results.all { it.observations.size == 1 })
        assertTrue(results.all { it.observations.single().walkingSelection?.show == false })
        assertFalse(last.observations.single().walkingSelection!!.warningCandidate)
        assertEquals("depth_unconfirmed", last.observations.single().walkingSelection!!.reason)
        assertFalse(last.objects.single().walkingObstacleCandidate)
        assertNull(last.objects.single().riskDistanceM)
        assertNull(last.objects.single().userFacing.message)
    }

    @Test fun actualMessagePolicyPathProducesUnnamedWarningWithSupportedDepth() {
        val p = pipeline()
        val outputs = (0..5).map { i -> process(p, token(i), capture(token(i), snapshot(token(i), 1200))).objects.single() }
        assertEquals(UNNAMED_OBSTACLE_CLASS, outputs.last().className)
        assertTrue(outputs.any { it.userFacing.message?.contains("물체") == true })
        assertTrue(outputs.any { it.userFacing.messageLevel in setOf(MessageLevel.WARNING, MessageLevel.STOP) })
        assertTrue(outputs.map { it.trackId }.distinct().size == 1)
    }

    @Test fun duplicateSourcesAgeGatesAndFrozenTokenMismatchRejectOutputs() {
        val p = pipeline(); val t = token(0); val c = capture(t)
        assertNotNull(process(p, t, c).objects.single())
        assertEquals("duplicate_or_out_of_order_source", process(p, t, c).rejectionReason)
        val newer = token(1)
        assertEquals("capture_token_mismatch", p.process(c, newer, listOf(regular()), clock).rejectionReason)
        val expired = p.process(capture(newer), newer, listOf(regular()), newer.capturedElapsedNs / 1_000_000L + 1001)
        assertEquals("source_frame_expired", expired.rejectionReason)
        val slow = pipeline(); clock = newer.capturedElapsedNs / 1_000_000L + 1001
        val crossed = slow.process(capture(newer), newer, listOf(regular()), newer.capturedElapsedNs / 1_000_000L + 10)
        assertEquals("source_frame_expired_during_depth", crossed.rejectionReason)
        assertTrue(crossed.objects.isEmpty()); assertEquals(1, crossed.observations.size)
    }

    @Test fun partialMissAndEpochBoundariesCannotInheritApproachHistory() {
        val p = pipeline()
        val approaching = (0..10).map { i -> process(p, token(i), capture(token(i), snapshot(token(i), 5000 - i * 250))).objects.single() }
        assertTrue(approaching.any { it.trend == Trend.APPROACHING })
        val oldId = approaching.last().trackId
        val partial = mask(intArrayOf(3, 5, 28, 27), intArrayOf(35, 2, 37, 4))
        process(p, token(11), masks = listOf(partial))
        val recovered = process(p, token(12)).objects.single()
        assertEquals(oldId, recovered.trackId)
        assertNull(recovered.approachSpeedMps); assertNull(recovered.timeToCollisionMs)
        p.missed()
        val afterMiss = process(p, token(13)).objects.single()
        assertNull(afterMiss.approachSpeedMps)
        val afterEpoch = process(p, token(14, epoch = 2)).objects.single()
        assertNotEquals(oldId, afterEpoch.trackId)
        assertNull(afterEpoch.approachSpeedMps)
        assertEquals("old_session_epoch", process(p, token(15, epoch = 1)).rejectionReason)
    }

    @Test fun repeatedDepthAndMultipleComponentsCannotManufactureVelocity() {
        val repeated = pipeline()
        val outputs = (0..6).map { i -> process(repeated, token(i), capture(token(i), snapshot(token(i),
            rawTimestamp = token(0).cpuImageTimestampNs))).objects.single() }
        assertTrue(outputs.all { it.timeToCollisionMs == null && it.approachSpeedMps == null })
        val multiple = mask(intArrayOf(2, 5, 16, 27), intArrayOf(23, 5, 37, 27))
        val p = pipeline()
        (0..8).forEach { i ->
            val t = token(i); val r = process(p, t, capture(t, snapshot(t, 5000 - i * 200)), listOf(multiple))
            assertEquals(MaskDepthEstimator.Status.KNOWN, r.observations.single().depth.status)
            assertEquals(2, r.observations.single().depth.components.size)
            assertNotNull(r.objects.single().zDistanceM)
            assertNull(r.objects.single().approachSpeedMps)
        }
    }

    @Test fun selectedDepthLayerSwitchWithinOneKnownComponentResetsMotion() {
        val p = pipeline()
        fun layerSnapshot(t: FastSamFrameToken, z: Int, right: Boolean): DepthFrameSnapshot {
            val s = snapshot(t)
            for (y in 0 until height) for (x in 0 until width) {
                val selected = if (right) x >= 13 else x < 21
                s.rawDepth!!.millimeters[y * width + x] = if (selected) z else 10_000
            }
            return s
        }
        val before = (0..8).map { i ->
            val t = token(i)
            process(p, t, capture(t, layerSnapshot(t, 5000 - i * 150, false)))
        }
        assertTrue(before.any { it.objects.single().trend == Trend.APPROACHING })
        val t = token(9)
        val switched = process(p, t, capture(t, layerSnapshot(t, 3400, true)))
        assertEquals(MaskDepthEstimator.Status.KNOWN, switched.observations.single().depth.status)
        assertEquals(1, switched.observations.single().depth.components.size)
        assertEquals(before.last().objects.single().trackId, switched.objects.single().trackId)
        assertNull(switched.objects.single().approachSpeedMps)
        assertNull(switched.objects.single().timeToCollisionMs)
        val oldComponent = before.last().observations.single().depth.components.single()
        val newComponent = switched.observations.single().depth.components.single()
        assertTrue(oldComponent.inlierImageCentroid()[0] < newComponent.inlierImageCentroid()[0])
        val first = newComponent.inlierDepthPixelIndices()[0]
        newComponent.inlierDepthPixelIndices()[0] = -1
        assertEquals(first, newComponent.inlierDepthPixelIndices()[0])
    }

    @Test fun namedSuppressionRequiresSameCaptureRealMaskAndAgreeingDepth() {
        val t = token(0); val base = process(pipeline(), t)
        val obj = base.objects.single().copy(className = "person", confidence = DepthConfidenceBreakdown(1f,1f,1f,1f,1f,1f,1f,1f))
        val evidence = NamedMaskDepthEvidence(t, base.observations.single().mask, obj)
        val suppressed = process(pipeline(), t, named = listOf(evidence))
        assertTrue(suppressed.objects.isEmpty())
        assertEquals("same_capture_named_mask_and_depth", suppressed.observations.single().suppressionReason)
        val wrongDepth = evidence.copy(depth = obj.copy(zDistanceM = 9f))
        assertEquals(1, process(pipeline(), t, named = listOf(wrongDepth)).objects.size)
        val wrongFrame = evidence.copy(token = token(1))
        assertEquals(1, process(pipeline(), t, named = listOf(wrongFrame)).objects.size)
    }

    @Test fun cropCoverageAndEmptyFramesAreExplicitBoundaries() {
        val t = token(0); val matrix = h().also { it[2] = .6 }
        val cropped = process(pipeline(), t, capture(t, matrix = matrix))
        assertEquals(MaskDepthEstimator.Status.KNOWN, cropped.observations.single().depth.status)
        assertEquals("mask_outside_calibrated_depth_coverage", cropped.observations.single().suppressionReason)
        assertNull(cropped.objects.single().riskDistanceM)
        val p = pipeline(); process(p, t)
        val empty = process(p, token(1), masks = emptyList())
        assertNull(empty.rejectionReason); assertTrue(empty.objects.isEmpty()); assertTrue(empty.observations.isEmpty())
        val next = process(p, token(2)).objects.single()
        assertNull(next.approachSpeedMps)
    }
}
