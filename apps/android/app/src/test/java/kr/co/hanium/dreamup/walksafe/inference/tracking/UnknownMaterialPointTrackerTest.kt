package kr.co.hanium.dreamup.walksafe.inference.tracking

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class UnknownMaterialPointTrackerTest {
    @Test fun delayedResultTracksOnlyThroughItsOriginalCpuCaptureAndHandsOffMeasuredPoint() {
        val scene = UnknownMaterialPointTestScene()
        scene.offerThrough(0L, 300L)
        assertTrue(scene.consume(0L).materialPointsByMaskIndex.isEmpty())
        val result = scene.consume(200L)
        val point = result.materialPointsByMaskIndex.getValue(0)
        assertEquals(scene.token(200L).cpuImageTimestampNs, point.point.targetKey.cameraTimestampNs)
        assertEquals(scene.token(200L).sessionEpoch, point.point.targetKey.epoch)
        assertEquals(100_200L, point.point.targetKey.capturedAtElapsedRealtimeMs)
        assertEquals(.45f, point.point.imagePointNorm.x, .0001f)
        assertEquals(15f / 32, point.point.imagePointNorm.y, .0001f)
        assertTrue(scene.flow.targets.all { it.frameId <= scene.token(200L).frameId })
        assertEquals(4, scene.flow.targets.size)
        assertEquals(2, result.trackingMetrics.size)
        scene.offerThrough(350L, 500L)
        assertSame(point.point.id, scene.consume(400L).materialPointsByMaskIndex.getValue(0).point.id)
    }

    @Test fun pointIsNotCreatedUntilActualFlowAndDiagnosticEstimatorCannotForgeOne() {
        val scene = UnknownMaterialPointTestScene()
        scene.flow.supportsPoint = false
        scene.offerThrough(0L, 300L)
        assertTrue(scene.consume(0L).materialPointsByMaskIndex.isEmpty())
        assertEquals(0, scene.flow.seeds)
        assertTrue(scene.consume(200L).materialPointsByMaskIndex.isEmpty())
        assertTrue(scene.flow.targets.isNotEmpty())
        assertTrue(scene.flow.pointsPassedToFlow.all { it == null })
    }

    @Test fun originalMaskHoleCannotSupplyAnUnownedSeedEvenIfNextMaskFillsTheHole() {
        val scene = UnknownMaterialPointTestScene()
        scene.offerThrough(0L, 300L)
        scene.consume(0L, listOf(scene.mask(hole = 18 to 15)))
        val point = scene.consume(200L).materialPointsByMaskIndex.getValue(0).point
        assertFalse(point.imagePointNorm.x == .45f && point.imagePointNorm.y == 15f / 32)
        assertTrue(scene.flow.pointsPassedToFlow.all { it == null || it.x != 18f || it.y != 15f })
    }

    @Test fun currentMaskHoleRetiresIdentityAndRecoveryCannotReturnOldId() {
        val scene = warm()
        val old = scene.consume(200L).materialPointsByMaskIndex.getValue(0).point.id
        scene.offerThrough(350L, 700L)
        val hole = scene.consume(400L, listOf(scene.mask(hole = 18 to 15)))
        assertTrue(hole.materialPointsByMaskIndex.isEmpty())
        assertEquals(VisualTrackingFailure.OBJECT_COMPETITION, hole.failure)
        assertTrue(scene.consume(500L).materialPointsByMaskIndex.isEmpty())
        assertNotSame(old, scene.consume(600L).materialPointsByMaskIndex.getValue(0).point.id)
    }

    @Test fun pointLossAndFailedLkCannotResumeOldPointAfterRecovery() {
        for (failure in listOf("point", "flow", "outside")) {
            val scene = warm()
            val old = scene.consume(200L).materialPointsByMaskIndex.getValue(0).point.id
            when (failure) {
                "point" -> scene.flow.pointLostAt = 350L
                "flow" -> scene.flow.failureAt = 350L
                else -> scene.flow.pointOutsideAt = 350L
            }
            scene.offerThrough(350L, 700L)
            assertTrue(failure, scene.consume(400L).materialPointsByMaskIndex.isEmpty())
            assertNotSame(failure, old, scene.consume(600L).materialPointsByMaskIndex.getValue(0).point.id)
        }
    }

    @Test fun multipleUnknownMasksCannotInventPerMaskClassesForHandoff() {
        val scene = warm()
        scene.consume(200L)
        scene.offerThrough(350L, 700L)
        val masks = listOf(scene.mask(2, 8, 18, 24), scene.mask(22, 8, 38, 24))
        assertTrue(scene.consume(400L, masks).materialPointsByMaskIndex.isEmpty())
        assertTrue(scene.consume(600L, masks).materialPointsByMaskIndex.isEmpty())
    }

    @Test fun competingActualMasksAndLateResultCannotRestorePreviousId() {
        val scene = warm()
        val old = scene.consume(200L).materialPointsByMaskIndex.getValue(0).point.id
        scene.offerThrough(350L, 800L)
        assertTrue(scene.consume(400L, listOf(scene.mask(), scene.mask(17, 14, 20, 17))).materialPointsByMaskIndex.isEmpty())
        assertTrue(scene.consume(200L).materialPointsByMaskIndex.isEmpty())
        assertTrue(scene.consume(600L).materialPointsByMaskIndex.isEmpty())
        val fresh = scene.consume(800L).materialPointsByMaskIndex.getValue(0).point.id
        assertNotSame(old, fresh)
        scene.offerThrough(850L, 1_100L)
        assertSame(fresh, scene.consume(1_000L).materialPointsByMaskIndex.getValue(0).point.id)
    }

    @Test fun missingImageGapAndEvictedOriginalCaptureFailClosedWithoutNative() {
        val gap = UnknownMaterialPointTestScene()
        gap.offer(0L); gap.consume(0L); gap.offer(200L)
        assertTrue(gap.consume(200L).materialPointsByMaskIndex.isEmpty())
        assertTrue(gap.flow.targets.isEmpty())
        val evicted = UnknownMaterialPointTestScene(VisualTrackingConfig(maxHistoryFrames = 4))
        evicted.offerThrough(0L, 300L)
        val result = evicted.consume(0L)
        assertEquals(VisualTrackingFailure.SOURCE_NOT_IN_HISTORY, result.failure)
        assertTrue(evicted.flow.targets.isEmpty())
        assertEquals(4, result.retainedFrames)
        assertEquals(4 * 40 * 32, result.retainedImageBytes)
    }

    @Test fun remainingBudgetIsSharedAndCompletedNativeOverrunDoesNotStartAnotherEdgeOrHandoff() {
        val scene = warm()
        scene.flow.advanceDurationNs = 700L
        val result = scene.consume(200L, budgetNs = 500L)
        assertTrue(result.materialPointsByMaskIndex.isEmpty())
        assertEquals(VisualTrackingFailure.TIME_BUDGET_EXCEEDED, result.failure)
        assertEquals(1, scene.flow.targets.size)
        assertEquals(1, result.trackingMetrics.size)
        assertEquals(500L, result.trackingMetrics.single().executionBudgetNs)
        assertTrue(result.trackingMetrics.single().executionBudgetOverrun)
        assertTrue(result.executionBudgetOverrun)
        assertEquals(500L, result.executionBudgetNs)
        val zero = scene.consume(300L, budgetNs = 0L)
        assertTrue(zero.materialPointsByMaskIndex.isEmpty())
        assertTrue(zero.trackingMetrics.isEmpty())
    }

    @Test fun theSecondTrackerCallReceivesOnlyTheAllowanceLeftByActualFlow() {
        val scene = warm()
        scene.flow.advanceDurationNs = 100L
        val result = scene.consume(200L, budgetNs = 500L)
        assertNotNull(result.materialPointsByMaskIndex[0])
        assertEquals(listOf(500L, 100L), result.trackingMetrics.map { it.executionBudgetNs })
        assertEquals(400L, result.durationNs)
        assertFalse(result.executionBudgetOverrun)
    }

    @Test fun oversizedAndExpiredMasksAreRejectedBeforeNativeEntry() {
        val scene = warm()
        val oversizedMasks = object : AbstractList<kr.co.hanium.dreamup.walksafe.inference.unknown.segmentation.InstanceMask>() {
            override val size = 101
            override fun get(index: Int): kr.co.hanium.dreamup.walksafe.inference.unknown.segmentation.InstanceMask =
                error("Oversized input must be rejected without reading masks")
        }
        val oversized = scene.consume(200L, oversizedMasks)
        assertEquals(VisualTrackingFailure.INPUT_BUDGET_EXCEEDED, oversized.failure)
        assertTrue(scene.flow.targets.isEmpty())
        assertEquals(VisualTrackingFailure.SOURCE_EXPIRED, scene.consume(200L, delayMs = 801L).failure)
        assertTrue(scene.flow.targets.isEmpty())
    }

    @Test fun lumaProvenanceAndBufferBoundsAreCheckedBeforeAdmission() {
        val scene = UnknownMaterialPointTestScene()
        val token = scene.token(0L)
        for (invalid in listOf("cpu", "dimensions", "buffer", "age")) {
            val admission = scene.producer.offerLuma(token, 0,
                token.capturedElapsedNs / 1_000_000L + if (invalid == "age") 801L else 0L,
                token.cpuImageTimestampNs + if (invalid == "cpu") 1L else 0L,
                if (invalid == "dimensions") 41 else 40, 32,
                ByteBuffer.wrap(ByteArray(if (invalid == "buffer") 1 else 1280)), 40, 1)
            assertFalse(invalid, admission.accepted)
        }
        assertEquals(VisualTrackingFailure.EPOCH_OR_GEOMETRY_CHANGED, scene.consume(0L).failure)
        assertTrue(scene.offer(0L).accepted)
    }

    @Test fun scopeChangesResetPointsAndRejectOldEpochGeometryAndRotationResults() {
        val scene = warm()
        scene.consume(200L)
        assertTrue(scene.offer(350L, epoch = 10L, geometry = "new-geometry", turns = 1).reset)
        assertEquals(VisualTrackingFailure.EPOCH_OR_GEOMETRY_CHANGED, scene.consume(200L).failure)
        assertFalse(scene.offer(400L, epoch = 9L).accepted)
        assertTrue(scene.consume(350L, epoch = 10L, geometry = "new-geometry", turns = 1).materialPointsByMaskIndex.isEmpty())
        for (at in 400L..550L step 50L) assertTrue(scene.offer(at, epoch = 10L, geometry = "new-geometry", turns = 1).accepted)
        val evidence = scene.consume(500L, epoch = 10L, geometry = "new-geometry", turns = 1).materialPointsByMaskIndex.getValue(0)
        assertEquals(1, evidence.imageQuarterTurns)
        assertEquals(10L, evidence.point.targetKey.epoch)
        assertEquals(2L, evidence.point.targetKey.geometryVersion)
    }

    @Test fun oversizedOldResultCannotClearTheCurrentMeasuredPointOwner() {
        val scene = warm()
        val point = scene.consume(200L).materialPointsByMaskIndex.getValue(0).point.id
        scene.offerThrough(350L, 500L)
        assertEquals(VisualTrackingFailure.DETECTOR_SOURCE_OUT_OF_ORDER,
            scene.consume(0L, List(101) { scene.mask() }).failure)
        assertSame(point, scene.consume(400L).materialPointsByMaskIndex.getValue(0).point.id)
    }

    @Test fun admittedLumaIsAnOwnedCopyAndNeverRetainsTheCallerBuffer() {
        val scene = UnknownMaterialPointTestScene()
        val token = scene.token(0L)
        val pixels = ByteArray(1280) { 77 }
        fun offer(buffer: ByteArray) = scene.producer.offerLuma(token, 0, 100_000L,
            token.cpuImageTimestampNs, 40, 32, ByteBuffer.wrap(buffer), 40, 1)
        assertTrue(offer(pixels).accepted)
        pixels.fill(12)
        assertTrue(offer(ByteArray(1280) { 77 }).accepted)
        assertEquals(VisualTrackingFailure.FRAME_IDENTITY_CONFLICT, offer(pixels).failure)
        assertTrue(scene.consume(0L).materialPointsByMaskIndex.isEmpty())
    }

    @Test fun exactCaptureCancellationAndResetDoNotLetLateOwnershipReleaseNewBatch() {
        val scene = warm()
        scene.consume(200L)
        assertFalse(scene.producer.cancelCapture(scene.token(0L)))
        assertTrue(scene.producer.cancelCapture(scene.token(300L)))
        assertTrue(scene.consume(300L).materialPointsByMaskIndex.isEmpty())
        scene.offerThrough(350L, 650L)
        assertTrue(scene.consume(400L).materialPointsByMaskIndex.isEmpty())
        assertNotNull(scene.consume(600L).materialPointsByMaskIndex[0])
        scene.producer.reset()
        assertFalse(scene.offer(0L).accepted)
        assertTrue(scene.consume(600L).materialPointsByMaskIndex.isEmpty())
    }

    @Test fun aForeignImageMaskCannotSupplyAPointAtTheCurrentCapture() {
        val scene = warm()
        assertEquals(VisualTrackingFailure.INVALID_GEOMETRY,
            scene.consume(200L, listOf(scene.mask(imageWidth = 80))).failure)
        assertTrue(scene.flow.targets.isEmpty())
    }

    @Test fun scaledNonSquareLumaReturnsItsActualSampleCenterAndKeepsTheMeasuredId() {
        val scene = scaledScene()
        scene.offerThrough(0L, 300L)
        val mask = scene.mask(100, 100, 270, 210)
        assertTrue(scene.consume(0L, listOf(mask)).materialPointsByMaskIndex.isEmpty())
        val first = scene.consume(200L, listOf(mask)).materialPointsByMaskIndex.getValue(0).point
        assertEquals(184.5f / 1920, first.imagePointNorm.x, .000001f)
        assertEquals(154.5f / 1080, first.imagePointNorm.y, .000001f)
        assertFalse(first.imagePointNorm.x == 180f / 1920)
        scene.offerThrough(350L, 500L)
        assertSame(first.id, scene.consume(400L, listOf(mask)).materialPointsByMaskIndex.getValue(0).point.id)
    }

    @Test fun scaledOriginalMaskHoleCannotBorrowTheIncorrectCellEdgeOrAnUnownedSampleCenter() {
        val scene = scaledScene()
        scene.offerThrough(0L, 300L)
        val holes = setOf(182 to 152, 187 to 152, 182 to 157, 187 to 157, 184 to 154)
        val mask = scene.mask(100, 100, 270, 210, holes = holes)
        assertTrue(mask.contains(180, 150))
        assertFalse(mask.contains(184, 154))
        scene.consume(0L, listOf(mask))
        val actual = scene.consume(200L, listOf(scene.mask(100, 100, 270, 210))).materialPointsByMaskIndex.getValue(0).point
        assertFalse(scene.flow.pointsPassedToFlow.any { it?.x == 18f && it.y == 15f })
        assertFalse(actual.imagePointNorm.x == 184.5f / 1920 && actual.imagePointNorm.y == 154.5f / 1080)
    }

    @Test fun aScaledCurrentCenterHoleRetiresTheOldIdEvenWhenTheOldCellEdgeRemainsInside() {
        val scene = scaledScene()
        scene.offerThrough(0L, 300L)
        val mask = scene.mask(100, 100, 270, 210)
        scene.consume(0L, listOf(mask))
        val old = scene.consume(200L, listOf(mask)).materialPointsByMaskIndex.getValue(0).point.id
        scene.offerThrough(350L, 700L)
        val hole = scene.mask(100, 100, 270, 210, hole = 184 to 154)
        assertTrue(hole.contains(180, 150))
        assertTrue(scene.consume(400L, listOf(hole)).materialPointsByMaskIndex.isEmpty())
        assertTrue(scene.consume(500L, listOf(mask)).materialPointsByMaskIndex.isEmpty())
        assertNotSame(old, scene.consume(600L, listOf(mask)).materialPointsByMaskIndex.getValue(0).point.id)
    }

    @Test fun scaledIntermediatePointOutsideActualGeometryCannotReturnWithTheOldId() {
        val scene = scaledScene()
        scene.offerThrough(0L, 300L)
        val mask = scene.mask(100, 100, 270, 210)
        scene.consume(0L, listOf(mask))
        val old = scene.consume(200L, listOf(mask)).materialPointsByMaskIndex.getValue(0).point.id
        scene.flow.pointCoordinatesAt[350L] = 26.8f to 15f
        scene.flow.pointCoordinatesAt[400L] = 18f to 15f
        assertTrue(mask.contains(268, 150))
        assertFalse(mask.contains(272, 154))
        scene.offerThrough(350L, 700L)
        assertTrue(scene.consume(400L, listOf(mask)).materialPointsByMaskIndex.isEmpty())
        assertNotSame(old, scene.consume(600L, listOf(mask)).materialPointsByMaskIndex.getValue(0).point.id)
    }

    @Test fun scaledIntermediatePointOnTheExclusiveRoiEdgeCannotReturnWithTheOldId() {
        val scene = scaledScene()
        scene.offerThrough(0L, 300L)
        val mask = scene.mask(100, 100, 270, 210)
        scene.consume(0L, listOf(mask))
        val old = scene.consume(200L, listOf(mask)).materialPointsByMaskIndex.getValue(0).point.id
        scene.flow.pointCoordinatesAt[350L] = 26.55f to 15f
        scene.flow.pointCoordinatesAt[400L] = 18f to 15f
        assertFalse(mask.contains(270, 154))
        scene.offerThrough(350L, 700L)
        assertTrue(scene.consume(400L, listOf(mask)).materialPointsByMaskIndex.isEmpty())
        assertNotSame(old, scene.consume(600L, listOf(mask)).materialPointsByMaskIndex.getValue(0).point.id)
    }

    @Test fun scaledIntermediatePointOnTheExclusiveBottomEdgeCannotReturnWithTheOldId() {
        val scene = scaledScene()
        scene.offerThrough(0L, 300L)
        val mask = scene.mask(100, 100, 270, 210)
        scene.consume(0L, listOf(mask))
        val old = scene.consume(200L, listOf(mask)).materialPointsByMaskIndex.getValue(0).point.id
        scene.flow.pointCoordinatesAt[350L] = 18f to 20.55f
        scene.flow.pointCoordinatesAt[400L] = 18f to 15f
        assertFalse(mask.contains(184, 210))
        scene.offerThrough(350L, 700L)
        assertTrue(scene.consume(400L, listOf(mask)).materialPointsByMaskIndex.isEmpty())
        assertNotSame(old, scene.consume(600L, listOf(mask)).materialPointsByMaskIndex.getValue(0).point.id)
    }

    @Test fun scaledIntermediatePointUsesTheAccumulatedTransformedGeometryRatherThanTheSourceBox() {
        val scene = scaledScene()
        scene.offerThrough(0L, 300L)
        val source = scene.mask(100, 100, 270, 210)
        scene.consume(0L, listOf(source))
        for (at in 50L..200L step 50L) {
            scene.flow.pointCoordinatesAt[at] = 28f to 15f
            scene.flow.transformsAt[at] = VisualAffineTransform(tx = 100f / 1920)
        }
        val shifted = scene.mask(200, 100, 370, 210)
        assertFalse(source.contains(284, 154))
        assertTrue(shifted.contains(284, 154))
        val actual = scene.consume(200L, listOf(shifted)).materialPointsByMaskIndex.getValue(0).point
        assertEquals(284.5f / 1920, actual.imagePointNorm.x, .000001f)
    }

    @Test fun ambiguousScaledPeerOwnershipRestartsBeforeTheNextSingleMaskCanInheritAPoint() {
        val scene = scaledScene()
        scene.offerThrough(0L, 300L)
        val main = scene.mask(100, 100, 270, 210)
        val peer = scene.mask(269, 140, 290, 180)
        scene.consume(0L, listOf(main))
        val old = scene.consume(100L, listOf(main)).materialPointsByMaskIndex.getValue(0).point.id
        assertTrue(scene.consume(200L, listOf(main, peer)).materialPointsByMaskIndex.isEmpty())
        scene.flow.pointCoordinatesAt[350L] = 26.5f to 15f
        scene.flow.pointCoordinatesAt[400L] = 18f to 15f
        assertFalse(peer.contains(265, 150))
        assertTrue(peer.contains(269, 154))
        scene.offerThrough(350L, 900L)
        assertTrue(scene.consume(400L, listOf(main)).materialPointsByMaskIndex.isEmpty())
        val fresh = scene.consume(600L, listOf(main)).materialPointsByMaskIndex.getValue(0).point.id
        assertNotSame(old, fresh)
        assertSame(fresh, scene.consume(800L, listOf(main)).materialPointsByMaskIndex.getValue(0).point.id)
    }

    private fun scaledScene() = UnknownMaterialPointTestScene(width = 1920, height = 1080).also {
        it.flow.seedCoordinates = listOf(18 to 15, 12 to 10, 14 to 10, 24 to 10, 26 to 10,
            12 to 20, 14 to 20, 24 to 20, 26 to 20)
    }

    private fun warm() = UnknownMaterialPointTestScene().also {
        it.offerThrough(0L, 300L)
        assertTrue(it.consume(0L).materialPointsByMaskIndex.isEmpty())
    }
}
