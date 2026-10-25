package kr.co.hanium.dreamup.walksafe.inference.tracking

import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamFrameToken
import org.junit.Assert.*
import org.junit.Test

class UnknownMaterialPointOwnedLumaAdmissionTest {
    @Test fun copyOfOriginalCpuLumaCompletesWhileAnotherOwnerIsInNativeConsume() {
        val blocked = BlockingConsume()
        try {
            blocked.start()
            val copyEntered = CountDownLatch(1)
            val copyCompleted = CountDownLatch(1)
            blocked.threads.submit {
                copyEntered.countDown()
                val token = blocked.scene.token(100)
                assertNotNull(UnknownMaterialPointLumaCapture.copyFromLuma(token, 0, token.cpuImageTimestampNs,
                    40, 32, ByteBuffer.wrap(ByteArray(40 * 32) { 77 }), 40, 1))
                copyCompleted.countDown()
            }
            assertTrue(copyEntered.await(2, TimeUnit.SECONDS))
            assertTrue("Same Image luma copy must not wait for native consume monitor",
                copyCompleted.await(200, TimeUnit.MILLISECONDS))
        } finally {
            blocked.close()
        }
    }

    @Test fun ownedAdmissionWaitsOnlyOnItsWorkerAfterTheImageBufferIsReleased() {
        val blocked = BlockingConsume()
        try {
            blocked.start()
            val token = blocked.scene.token(100)
            val original = ByteBuffer.wrap(ByteArray(40 * 32) { 77 })
            val copied = blocked.threads.submit<UnknownMaterialPointLumaCapture> {
                requireNotNull(UnknownMaterialPointLumaCapture.copyFromLuma(token, 0,
                    token.cpuImageTimestampNs, 40, 32, original, 40, 1))
            }.get(1, TimeUnit.SECONDS)
            original.array().fill(0)
            original.limit(0) // Original source is no longer usable; the packet has no buffer access.
            val offerEntered = CountDownLatch(1)
            val offerCompleted = CountDownLatch(1)
            val offered = blocked.threads.submit<TrackingFrameAdmission> {
                offerEntered.countDown()
                blocked.producer.offerOwnedLuma(copied, 100_100).also { offerCompleted.countDown() }
            }
            assertTrue(offerEntered.await(2, TimeUnit.SECONDS))
            assertFalse(offerCompleted.await(150, TimeUnit.MILLISECONDS))
            blocked.proceed.countDown()
            assertTrue(offered.get(2, TimeUnit.SECONDS).accepted)
            // The duplicate legacy path verifies that the owned source still contains original 77.
            assertTrue(blocked.producer.offerLuma(token, 0, 100_100, token.cpuImageTimestampNs,
                40, 32, ByteBuffer.wrap(ByteArray(40 * 32) { 77 }), 40, 1).accepted)
            val point = blocked.producer.consume(token, 0, listOf(blocked.scene.mask()),
                100_200, 5_000_000).materialPointsByMaskIndex.getValue(0).point
            assertEquals(token.frameId, point.targetKey.frameId)
            assertEquals(token.cpuImageTimestampNs, point.targetKey.cameraTimestampNs)
            assertEquals(token.capturedElapsedNs / 1_000_000, point.targetKey.capturedAtElapsedRealtimeMs)
        } finally {
            blocked.close()
        }
    }

    @Test fun paddedStridedAndNonSquareCopiesUseTheExactExistingFourSourceSamples() {
        for ((width, height) in listOf(40 to 32, 1920 to 1080, 1080 to 1920, 321 to 123)) {
            val scene = UnknownMaterialPointTestScene(width = width, height = height)
            val token = scene.token(0)
            val stride = width * 2 + 7
            val plane = ByteBuffer.wrap(ByteArray(9 + stride * height) { 250.toByte() })
            for (y in 0 until height) for (x in 0 until width) {
                plane.put(9 + y * stride + x * 2, ((x * 11 + y * 29) % 256).toByte())
            }
            plane.position(9)
            val packet = requireNotNull(UnknownMaterialPointLumaCapture.copyFromLuma(token, 3,
                token.cpuImageTimestampNs, width, height, plane.asReadOnlyBuffer(), stride, 2))
            assertEquals(9, plane.position())
            assertEquals(stride, packet.rowStride)
            assertEquals(2, packet.pixelStride)
            assertEquals(3, packet.imageQuarterTurns)
            assertTrue(packet.sampledWidth <= 192 && packet.sampledHeight <= 192)
            assertEquals(packet.sampledWidth * packet.sampledHeight, packet.retainedImageBytes)
            val actual = packet.toFrame(17)
            val reference = GrayTrackingFrame.copyFromLuma(VisualFrameKey(token.sessionEpoch, token.frameId,
                token.cpuImageTimestampNs, token.capturedElapsedNs / 1_000_000, 17), width, height, plane, stride, 2)
            assertArrayEquals(reference.copyPixels(), actual.copyPixels())
            assertEquals(width, actual.sourceWidth)
            assertEquals(height, actual.sourceHeight)
            if (width == 1920) {
                assertEquals(192, actual.width); assertEquals(108, actual.height)
                // Source (182,152),(187,152),(182,157),(187,157), NOT naive (180,150).
                assertEquals(110, actual.intensity(18, 15))
            } else if (width == 40) assertEquals((18 * 11 + 15 * 29) % 256, actual.intensity(18, 15))
        }
    }

    @Test fun invalidClockDimensionsRotationStrideOrUnavailableBufferCannotCreateACapture() {
        val scene = UnknownMaterialPointTestScene()
        val token = scene.token(0)
        val plane = ByteBuffer.wrap(ByteArray(40 * 32))
        fun copy(turns: Int = 0, cpu: Long = token.cpuImageTimestampNs, width: Int = 40,
            height: Int = 32, row: Int = 40, pixel: Int = 1, buffer: ByteBuffer = plane) =
            UnknownMaterialPointLumaCapture.copyFromLuma(token, turns, cpu, width, height, buffer, row, pixel)
        assertNull(copy(cpu = token.cameraTimestampNs)) // AR camera clock is distinct from actual CPU clock.
        assertNull(copy(cpu = token.cpuImageTimestampNs + 1))
        assertNull(copy(width = 41)); assertNull(copy(height = 31))
        assertNull(copy(turns = -1)); assertNull(copy(turns = 4))
        assertNull(copy(row = 39)); assertNull(copy(pixel = 0))
        plane.limit(0)
        assertNull(copy()) // JVM unavailable-plane boundary; not a real Android Image.close claim.
        val fixture = FastSamFrameToken(FastSamFrameToken.Source.CALIBRATION_FIXTURE, 9,
            token.frameId, 0, 0, token.capturedElapsedNs, token.geometryId, 40, 32)
        assertNull(UnknownMaterialPointLumaCapture.copyFromLuma(fixture, 0, 0, 40, 32,
            ByteBuffer.wrap(ByteArray(40 * 32)), 40, 1))
    }

    @Test fun delayedAdmissionKeepsOriginalDeadlineAndCannotRebindAnOldPacketToNewEpoch() {
        val scene = UnknownMaterialPointTestScene()
        val packet = copy(scene, 0)
        assertEquals(VisualTrackingFailure.SOURCE_EXPIRED, scene.producer.offerOwnedLuma(packet, 99_999).failure)
        assertEquals(VisualTrackingFailure.SOURCE_EXPIRED, scene.producer.offerOwnedLuma(packet, 100_801).failure)
        assertTrue(scene.producer.offerOwnedLuma(packet, 100_800).accepted)
        assertTrue(scene.offer(17, epoch = 10, geometry = "next", turns = 1).reset)
        assertEquals(VisualTrackingFailure.EPOCH_OR_GEOMETRY_CHANGED,
            scene.producer.offerOwnedLuma(packet, 100_100).failure)
        assertEquals(9L, packet.token.sessionEpoch)
        assertEquals(scene.token(0).capturedElapsedNs, packet.token.capturedElapsedNs)
        // New geometry gets only a local version; none of its original clocks are rewritten.
        val next = copy(scene, 34, epoch = 10, geometry = "next", turns = 1)
        assertTrue(scene.producer.offerOwnedLuma(next, 100_034).accepted)
        val nextFrame = next.toFrame(8)
        assertEquals(next.token.sessionEpoch, nextFrame.key.epoch)
        assertEquals(next.token.frameId, nextFrame.key.frameId)
        assertEquals(next.token.cpuImageTimestampNs, nextFrame.key.cameraTimestampNs)
        assertEquals(100_034L, nextFrame.key.capturedAtElapsedRealtimeMs)
        assertEquals(8L, nextFrame.key.geometryVersion)
    }

    @Test fun duplicateOwnedPixelsUseTheOriginalConflictAndLeaseResetRules() {
        val scene = UnknownMaterialPointTestScene()
        val original = copy(scene, 0)
        assertTrue(scene.producer.offerOwnedLuma(original, 100_000).accepted)
        val lease = requireNotNull(scene.producer.reserveCapture(original.token, 0, 100_000).lease)
        assertTrue(scene.producer.offerOwnedLuma(copy(scene, 0), 100_000).accepted)
        assertFalse(lease.isClosed)
        val conflict = copy(scene, 0, value = 78)
        val rejected = scene.producer.offerOwnedLuma(conflict, 100_000)
        assertEquals(VisualTrackingFailure.FRAME_IDENTITY_CONFLICT, rejected.failure)
        assertTrue(rejected.reset)
        assertTrue(lease.isClosed)
        assertEquals(VisualTrackingFailure.FRAME_ORDER_INVALID,
            scene.producer.offerOwnedLuma(original, 100_000).failure)
    }

    @Test fun ownedOfferPreservesAll32LeasePressureAndRejectsBeforeAdmittingNewSource() {
        val scene = UnknownMaterialPointTestScene()
        val leases = (0L..527L step 17L).map { at ->
            val packet = copy(scene, at)
            assertTrue(scene.producer.offerOwnedLuma(packet, 100_000 + at).accepted)
            requireNotNull(scene.producer.reserveCapture(packet.token, 0, 100_000 + at).lease)
        }
        // Static copy deliberately cannot inspect producer pressure; caller bounds its queue.
        val next = copy(scene, 544)
        assertEquals(40 * 32, next.retainedImageBytes)
        assertEquals(VisualTrackingFailure.INPUT_BUDGET_EXCEEDED,
            scene.producer.offerOwnedLuma(next, 100_544).failure)
        assertTrue(leases.none { it.isClosed })
        assertEquals(VisualTrackingFailure.SOURCE_NOT_IN_HISTORY,
            scene.producer.reserveCapture(next.token, 0, 100_544).failure)
        leases.first().close()
        assertTrue(scene.producer.offerOwnedLuma(next, 100_544).accepted)
        val lease = requireNotNull(scene.producer.reserveCapture(next.token, 0, 100_544).lease)
        val result = scene.producer.consume(next.token, 0, listOf(scene.mask()), 100_644, 5_000_000, lease)
        assertNull(result.failure)
        assertEquals(32, result.retainedFrames)
        assertEquals(32 * 40 * 32, result.retainedImageBytes)
    }

    @Test fun ownedScaledFramesKeepActualSampleCentersAndRetireTheIdAtAnIntermediateEdge() {
        val scene = UnknownMaterialPointTestScene(width = 1920, height = 1080)
        scene.flow.seedCoordinates = listOf(18 to 15, 12 to 10, 14 to 10, 24 to 10, 26 to 10,
            12 to 20, 14 to 20, 24 to 20, 26 to 20)
        fun offerThrough(first: Long, last: Long) {
            for (at in first..last step 50L) assertTrue(scene.producer.offerOwnedLuma(copy(scene, at), 100_000 + at).accepted)
        }
        offerThrough(0, 300)
        val mask = scene.mask(100, 100, 270, 210)
        scene.consume(0, listOf(mask))
        val first = scene.consume(200, listOf(mask)).materialPointsByMaskIndex.getValue(0).point
        assertEquals(184.5f / 1920, first.imagePointNorm.x, .000001f)
        assertEquals(154.5f / 1080, first.imagePointNorm.y, .000001f)
        scene.flow.pointCoordinatesAt[350] = 26.8f to 15f
        scene.flow.pointCoordinatesAt[400] = 18f to 15f
        offerThrough(350, 700)
        assertTrue(scene.consume(400, listOf(mask)).materialPointsByMaskIndex.isEmpty())
        assertNotSame(first.id, scene.consume(600, listOf(mask)).materialPointsByMaskIndex.getValue(0).point.id)
    }

    private fun copy(scene: UnknownMaterialPointTestScene, at: Long, value: Int = 77,
        epoch: Long = 9, geometry: String = "unknown-image-geometry", turns: Int = 0): UnknownMaterialPointLumaCapture {
        val token = scene.token(at, epoch, geometry)
        return requireNotNull(UnknownMaterialPointLumaCapture.copyFromLuma(token, turns, token.cpuImageTimestampNs,
            scene.width, scene.height, ByteBuffer.wrap(ByteArray(scene.width * scene.height) { value.toByte() }), scene.width, 1))
    }

    private class BlockingConsume : AutoCloseable {
        val scene = UnknownMaterialPointTestScene()
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val threads = Executors.newFixedThreadPool(2)
        val flow = object : VisualMotionEstimator by scene.flow {
            override fun advance(source: GrayTrackingFrame, previous: GrayTrackingFrame, target: GrayTrackingFrame,
                detection: DetectionCandidate, features: List<VisualMotionFeature>, originalFeatureCount: Int,
                budget: TrackingWorkBudget, materialPoint: VisualMotionPoint?): VisualMotionEstimate {
                entered.countDown()
                check(proceed.await(10, TimeUnit.SECONDS))
                return scene.flow.advance(source, previous, target, detection, features,
                    originalFeatureCount, budget, materialPoint)
            }
        }
        val producer = UnknownMaterialPointTracker(VisualTrackingConfig(), { scene.nanoTime }, flow)
        fun offer(at: Long) {
            val token = scene.token(at)
            check(producer.offerLuma(token, 0, 100_000 + at, token.cpuImageTimestampNs,
                40, 32, ByteBuffer.wrap(ByteArray(40 * 32) { 77 }), 40, 1).accepted)
        }
        fun start() {
            offer(0)
            check(producer.consume(scene.token(0), 0, listOf(scene.mask()), 100_100, 5_000_000).failure == null)
            offer(50)
            threads.submit { producer.consume(scene.token(50), 0, listOf(scene.mask()), 100_150, 5_000_000) }
            check(entered.await(2, TimeUnit.SECONDS))
        }
        override fun close() {
            proceed.countDown()
            threads.shutdown()
            check(threads.awaitTermination(3, TimeUnit.SECONDS))
        }
    }
}
