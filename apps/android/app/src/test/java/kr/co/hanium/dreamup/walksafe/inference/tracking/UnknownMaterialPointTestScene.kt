package kr.co.hanium.dreamup.walksafe.inference.tracking

import java.nio.ByteBuffer
import java.util.BitSet
import kr.co.hanium.dreamup.walksafe.depth.*
import kr.co.hanium.dreamup.walksafe.depth.unknown.FrozenUnknownDepthCapture
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamFrameToken
import kr.co.hanium.dreamup.walksafe.inference.unknown.segmentation.InstanceMask

/** Real producer/tracker with deterministic measured LK correspondences, never a native claim. */
internal class UnknownMaterialPointTestScene(config: VisualTrackingConfig = VisualTrackingConfig(),
    val width: Int = 40, val height: Int = 32) {
    var nanoTime = 0L
    val flow = Flow()
    val producer = UnknownMaterialPointTracker(config, { nanoTime }, flow)
    fun token(at: Long, epoch: Long = 9L, geometry: String = "unknown-image-geometry") = FastSamFrameToken(
        FastSamFrameToken.Source.LIVE_CAMERA, epoch, (10_000L + at) * 1_000_000L,
        (10_000L + at) * 1_000_000L, (9_980L + at) * 1_000_000L,
        (100_000L + at) * 1_000_000L, geometry, width, height)
    fun offer(at: Long, epoch: Long = 9L, geometry: String = "unknown-image-geometry", turns: Int = 0): TrackingFrameAdmission {
        val token = token(at, epoch, geometry)
        return producer.offerLuma(token, turns, token.capturedElapsedNs / 1_000_000L,
            token.cpuImageTimestampNs, width, height, ByteBuffer.wrap(ByteArray(width * height) { 77 }), width, 1)
    }
    fun consume(at: Long, masks: List<InstanceMask> = listOf(mask()), budgetNs: Long = 5_000_000L,
        delayMs: Long = 100L, epoch: Long = 9L, geometry: String = "unknown-image-geometry", turns: Int = 0) =
        token(at, epoch, geometry).let { producer.consume(it, turns, masks,
            it.capturedElapsedNs / 1_000_000L + delayMs, budgetNs) }
    fun offerThrough(first: Long, last: Long, step: Long = 50L) {
        for (at in first..last step step) check(offer(at).accepted)
    }
    fun mask(left: Int = 10, top: Int = 8, right: Int = 30, bottom: Int = 24,
        hole: Pair<Int, Int>? = null, imageWidth: Int = width, imageHeight: Int = height,
        holes: Set<Pair<Int, Int>> = emptySet()): InstanceMask {
        val bits = BitSet((right - left) * (bottom - top)).also {
            it.set(0, (right - left) * (bottom - top))
            if (hole != null) it.clear((hole.second - top) * (right - left) + hole.first - left)
            for (pixel in holes) it.clear((pixel.second - top) * (right - left) + pixel.first - left)
        }
        return InstanceMask::class.java.declaredConstructors.single().also { it.isAccessible = true }
            .newInstance(imageWidth, imageHeight, left, top, right, bottom, bits.cardinality(), 17,
                left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), .99f, bits) as InstanceMask
    }
    fun capture(at: Long, millimeters: Int? = 2_000): FrozenUnknownDepthCapture {
        val token = token(at)
        val snapshot = DepthFrameSnapshot(token.cameraTimestampNs,
            millimeters?.let { mm -> DepthImage16(width, height, IntArray(width * height) { mm }) },
            millimeters?.let { ConfidenceImage8(width, height, ByteArray(width * height) { 255.toByte() }) },
            null, rawDepthTimestampNs = token.cpuImageTimestampNs.takeIf { millimeters != null },
            rawConfidenceTimestampNs = token.cpuImageTimestampNs.takeIf { millimeters != null },
            cameraImageTimestampNs = token.cpuImageTimestampNs,
            cameraPoseEvidence = CameraPoseEvidence(7L, token.cameraTimestampNs / 1_000_000L,
                0f, 1.5f, 0f, 0f, 0f, -1f, CameraImageProjection(width, height, width * .8f, height.toFloat(),
                    width / 2f, height / 2f, 1f, 0f, 0f, 0f, 1f, 0f), gravityUpInAnchor = Vec3(0f, 1f, 0f)))
        return requireNotNull(FrozenUnknownDepthCapture.freeze(token, snapshot,
            doubleArrayOf(1.0 / width, 0.0, 0.0, 0.0, 1.0 / height, 0.0, 0.0, 0.0, 1.0), token.frameId))
    }
    inner class Flow : VisualMotionEstimator {
        override val supportsMaterialPointTracking: Boolean get() = supportsPoint
        var supportsPoint = true
        var failureAt: Long? = null
        var pointLostAt: Long? = null
        var pointOutsideAt: Long? = null
        var advanceDurationNs = 0L
        val pointCoordinatesAt = mutableMapOf<Long, Pair<Float, Float>>()
        val transformsAt = mutableMapOf<Long, VisualAffineTransform>()
        var seedCoordinates = listOf(18 to 15, 12 to 10, 16 to 10, 24 to 10, 28 to 10, 12 to 22, 16 to 22, 24 to 22, 28 to 22)
        val targets = mutableListOf<VisualFrameKey>()
        val pointsPassedToFlow = mutableListOf<VisualMotionPoint?>()
        var seeds = 0
        override fun seed(frame: GrayTrackingFrame, detection: DetectionCandidate, budget: TrackingWorkBudget): List<VisualMotionFeature> {
            seeds++
            return seedCoordinates.map { VisualMotionFeature(it.first, it.second) }
        }
        override fun advance(source: GrayTrackingFrame, previous: GrayTrackingFrame, target: GrayTrackingFrame,
            detection: DetectionCandidate, features: List<VisualMotionFeature>, originalFeatureCount: Int,
            budget: TrackingWorkBudget, materialPoint: VisualMotionPoint?): VisualMotionEstimate {
            targets += target.key
            pointsPassedToFlow += materialPoint
            nanoTime += advanceDurationNs
            val at = target.key.frameId / 1_000_000L - 10_000L
            return VisualMotionEstimate(features, transformsAt[at] ?: VisualAffineTransform(), .95f, .01f,
                failure = VisualTrackingFailure.FORWARD_BACKWARD_MISMATCH.takeIf { at == failureAt },
                materialPoint = materialPoint?.takeUnless { at == pointLostAt }?.let {
                    pointCoordinatesAt[at]?.let { measured -> it.copy(x = measured.first, y = measured.second) }
                        ?: if (at == pointOutsideAt) it.copy(x = 35f) else it
                })
        }
    }
}
