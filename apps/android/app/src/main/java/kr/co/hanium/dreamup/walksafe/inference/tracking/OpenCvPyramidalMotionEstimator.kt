package kr.co.hanium.dreamup.walksafe.inference.tracking

import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import kr.co.hanium.dreamup.walksafe.depth.Point2
import org.opencv.android.OpenCVLoader
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.core.TermCriteria
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * CPU pyramidal LK over owned gray copies. Features are seeded only by a detector source frame.
 * The seed mask constrains feature centers; LK pyramid windows can sample outside that mask.
 * Native calls cannot be preempted. The caller checks the deadline after caching a validated edge.
 */
internal class OpenCvPyramidalMotionEstimator(
    private val maxFeatures: Int,
    private val allowSimilarityTransform: Boolean = false,
    private val stageObserver: ((VisualFrameKey, VisualFrameKey, String, () -> StageSnapshot) -> Unit)? = null,
) : VisualMotionEstimator {
    /** Numeric copies only; absent status-dependent fields mean unavailable, never zero. */
    internal data class StageSnapshot(
        val coordinateSystem: String,
        val slots: List<Map<String, Number?>>,
        val scalarValues: Map<String, Number?>,
    )

    init {
        require(maxFeatures in MIN_SURVIVORS..MAX_FEATURES)
    }

    override val supportsMaterialPointTracking: Boolean get() = true

    override fun initialize(): Boolean = synchronized(initializationLock) {
        initializationSucceeded?.let { return@synchronized it }
        val succeeded = try {
            OpenCVLoader.initLocal().also { loaded -> if (loaded) Core.setNumThreads(1) }
        } catch (_: RuntimeException) {
            false
        } catch (_: LinkageError) {
            false
        }
        initializationSucceeded = succeeded
        succeeded
    }

    override fun seed(
        frame: GrayTrackingFrame,
        detection: DetectionCandidate,
        budget: TrackingWorkBudget,
    ): List<VisualMotionFeature> {
        budget.charge()
        if (!initialize()) throw TrackingBudgetExceeded(VisualTrackingFailure.NATIVE_INITIALIZATION_FAILED)
        val mats = mutableListOf<Mat>()
        try {
            val gray = own(mats, Mat(frame.height, frame.width, CvType.CV_8UC1))
            gray.put(0, 0, frame.copyPixels())
            val mask = own(mats, Mat(frame.height, frame.width, CvType.CV_8UC1))
            val maskPixels = ByteArray(frame.width * frame.height)
            val box = detection.bboxNorm
            val left = ceil(box.x * frame.width).toInt().coerceIn(0, frame.width)
            val top = ceil(box.y * frame.height).toInt().coerceIn(0, frame.height)
            val right = floor((box.x + box.width) * frame.width).toInt().coerceIn(0, frame.width)
            val bottom = floor((box.y + box.height) * frame.height).toInt().coerceIn(0, frame.height)
            for (y in top until bottom) for (x in left until right) {
                if (detection.polygonNorm.isEmpty() || pointInside(
                        x.toFloat() / frame.width, y.toFloat() / frame.height, detection.polygonNorm,
                    )
                ) maskPixels[y * frame.width + x] = 0xff.toByte()
            }
            mask.put(0, 0, maskPixels)
            val corners = own(mats, MatOfPoint())
            Imgproc.goodFeaturesToTrack(gray, corners, maxFeatures, 0.01, 3.0, mask, 3, false, 0.04)
            // Java's GFTT binding returns integer MatOfPoint; LK inputs below are MatOfPoint2f.
            return corners.toArray().filter { inFrame(it, frame) }.map {
                VisualMotionFeature(sourceX = it.x.toInt(), sourceY = it.y.toInt())
            }
        } catch (exceeded: TrackingBudgetExceeded) {
            throw exceeded
        } catch (_: RuntimeException) {
            throw TrackingBudgetExceeded(VisualTrackingFailure.NATIVE_TRACKING_FAILED)
        } catch (_: LinkageError) {
            throw TrackingBudgetExceeded(VisualTrackingFailure.NATIVE_TRACKING_FAILED)
        } finally {
            release(mats)
        }
    }

    override fun advance(
        source: GrayTrackingFrame,
        previous: GrayTrackingFrame,
        target: GrayTrackingFrame,
        detection: DetectionCandidate,
        features: List<VisualMotionFeature>,
        originalFeatureCount: Int,
        budget: TrackingWorkBudget,
        materialPoint: VisualMotionPoint?,
    ): VisualMotionEstimate {
        budget.charge()
        if (!initialize()) return failed(VisualTrackingFailure.NATIVE_INITIALIZATION_FAILED)
        val minimumSurvivors = maxOf(MIN_SURVIVORS, ceil(originalFeatureCount * 0.20f).toInt())
        if (originalFeatureCount !in MIN_SURVIVORS..maxFeatures || features.size < minimumSurvivors ||
            features.size > originalFeatureCount
        ) return failed(VisualTrackingFailure.TOO_FEW_FEATURES)
        if (source.width != previous.width || source.height != previous.height ||
            source.width != target.width || source.height != target.height
        ) return failed(VisualTrackingFailure.INVALID_GEOMETRY)

        val inputFeatures = features.filter {
            it.sourceX in 0 until source.width && it.sourceY in 0 until source.height &&
                inFrame(Point(it.currentX.toDouble(), it.currentY.toDouble()), previous)
        }
        if (inputFeatures.size < minimumSurvivors) return failed(VisualTrackingFailure.TOO_FEW_FEATURES)
        val point = materialPoint?.takeIf {
            it.x.isFinite() && it.y.isFinite() && inFrame(Point(it.x.toDouble(), it.y.toDouble()), previous)
        }
        // Geometry retains its own 6..40 support contract; the auxiliary point uses a reserved slot.
        if (inputFeatures.size + (if (point == null) 0 else 1) > maxFeatures) {
            return failed(VisualTrackingFailure.INPUT_BUDGET_EXCEEDED)
        }
        val inputPoints = inputFeatures.map { Point(it.currentX.toDouble(), it.currentY.toDouble()) } +
            listOfNotNull(point?.let { Point(it.x.toDouble(), it.y.toDouble()) })
        val mats = mutableListOf<Mat>()
        try {
            val before = own(mats, Mat(previous.height, previous.width, CvType.CV_8UC1))
            before.put(0, 0, previous.copyPixels())
            val after = own(mats, Mat(target.height, target.width, CvType.CV_8UC1))
            after.put(0, 0, target.copyPixels())
            var trackingBefore = before
            var trackingPoints = inputPoints
            var trackingIndices: List<Int>? = null
            var inverseAffine: DoubleArray? = null
            var directNext: Array<Point>? = null
            var directFlags: ByteArray? = null
            var directErrors: FloatArray? = null
            var directIndices: List<Int>? = null
            if (allowSimilarityTransform) {
                // A translation-only LK patch biases a changing scale. Estimate the incremental
                // similarity from geometry, then measure only its remaining local displacement.
                val coarseInput = own(mats, MatOfPoint2f())
                coarseInput.fromArray(*inputPoints.toTypedArray())
                val coarsePoints = own(mats, MatOfPoint2f())
                val coarseStatus = own(mats, MatOfByte())
                val coarseError = own(mats, MatOfFloat())
                budget.charge()
                flow(before, after, coarseInput, coarsePoints, coarseStatus, coarseError)
                val coarseNext = coarsePoints.toArray()
                val coarseFlags = coarseStatus.toArray()
                val coarseErrors = coarseError.toArray()
                if (coarseNext.size != inputPoints.size || coarseFlags.size != coarseNext.size ||
                    coarseErrors.size != coarseNext.size
                ) return failed(VisualTrackingFailure.NATIVE_TRACKING_FAILED)
                val validCoarseIndices = coarseNext.indices.filter {
                    coarseFlags[it].toInt() == 1 && inFrame(coarseNext[it], target) &&
                        coarseErrors[it].isFinite() && coarseErrors[it] >= 0f && coarseErrors[it] <= MAX_LK_ERROR
                }
                directNext = coarseNext
                directFlags = coarseFlags
                directErrors = coarseErrors
                directIndices = validCoarseIndices
                val geometryIndices = validCoarseIndices.filter { it < inputFeatures.size }
                stageObserver?.invoke(source.key, target.key, "COARSE_VALID") {
                    captureStage(source, detection, inputFeatures, inputPoints, validCoarseIndices,
                        "source/previous/target gray pixels; stage=coarse target") { slot, row ->
                        row["lk_status"] = coarseFlags[slot].toInt()
                        if (coarseFlags[slot].toInt() == 1) {
                            if (coarseErrors[slot].isFinite()) row["lk_error"] = coarseErrors[slot]
                            coarseNext[slot]
                        } else null
                    }
                }
                if (geometryIndices.size < minimumSurvivors) return failed(VisualTrackingFailure.PHOTOMETRIC_MISMATCH)
                val from = own(mats, MatOfPoint2f())
                from.fromArray(*geometryIndices.map { inputPoints[it] }.toTypedArray())
                val to = own(mats, MatOfPoint2f())
                to.fromArray(*geometryIndices.map { coarseNext[it] }.toTypedArray())
                val inliers = own(mats, Mat())
                budget.charge()
                val matrix = own(mats, Calib3d.estimateAffinePartial2D(from, to, inliers,
                    Calib3d.RANSAC, 2.0, 2000L, 0.99, 10L))
                val values = affineValues(matrix) ?: return failed(VisualTrackingFailure.INCONSISTENT_MOTION)
                if (inliers.total() != geometryIndices.size.toLong() || inliers.type() != CvType.CV_8UC1) {
                    return failed(VisualTrackingFailure.INCONSISTENT_MOTION)
                }
                val inlierFlags = ByteArray(geometryIndices.size)
                if (inliers.get(0, 0, inlierFlags) != inlierFlags.size ||
                    inlierFlags.any { it.toInt() !in 0..1 }
                ) return failed(VisualTrackingFailure.INCONSISTENT_MOTION)
                stageObserver?.invoke(source.key, target.key, "COARSE_INLIERS") {
                    val keptSlots = geometryIndices.filterIndexed { index, _ -> inlierFlags[index].toInt() == 1 } +
                        validCoarseIndices.filter { it == inputFeatures.size }
                    captureStage(source, detection, inputFeatures, inputPoints, keptSlots,
                        "source/previous/target gray pixels; stage=coarse target") { slot, row ->
                        val index = geometryIndices.indexOf(slot)
                        if (index >= 0) row["ransac_inlier"] = inlierFlags[index].toInt()
                        row["lk_status"] = coarseFlags[slot].toInt()
                        if (coarseFlags[slot].toInt() == 1) {
                            if (coarseErrors[slot].isFinite()) row["lk_error"] = coarseErrors[slot]
                            coarseNext[slot]
                        } else null
                    }
                }
                val scale = sqrt(values[0] * values[0] + values[3] * values[3])
                val angle = abs(atan2(values[3], values[0]))
                if (scale !in 0.5..2.0 || angle > PI / 4) {
                    return failed(VisualTrackingFailure.UNSUPPORTED_SCALE_OR_ROTATION)
                }
                val geometryInliers = geometryIndices.filterIndexed { index, _ -> inlierFlags[index].toInt() == 1 }
                if (geometryInliers.size < minimumSurvivors) return failed(VisualTrackingFailure.INCONSISTENT_MOTION)
                val inverse = own(mats, Mat())
                budget.charge()
                Imgproc.invertAffineTransform(matrix, inverse)
                inverseAffine = affineValues(inverse) ?: return failed(VisualTrackingFailure.INCONSISTENT_MOTION)
                // The auxiliary correspondence never participates in either geometry fit.
                val activeIndices = geometryInliers + validCoarseIndices.filter { it == inputFeatures.size }
                val warpedInputs = inputPoints.map { mapAffine(it, values) }
                val warpedIndices = activeIndices.filter { inFrame(warpedInputs[it], previous) }
                stageObserver?.invoke(source.key, target.key, "WARP_BOUNDS") {
                    captureStage(source, detection, inputFeatures, inputPoints, warpedIndices,
                        "source/previous gray pixels; stage=projected previous input on warped canvas (also defined for inactive slots)") { slot, row ->
                        row["coarse_active"] = if (slot in activeIndices) 1 else 0
                        row["warped_in_frame"] = if (inFrame(warpedInputs[slot], previous)) 1 else 0
                        warpedInputs[slot]
                    }
                }
                if (warpedIndices.count { it < inputFeatures.size } < minimumSurvivors) {
                    return failed(VisualTrackingFailure.TOO_FEW_FEATURES)
                }
                val warped = own(mats, Mat())
                budget.charge()
                Imgproc.warpAffine(before, warped, matrix, Size(previous.width.toDouble(), previous.height.toDouble()),
                    Imgproc.INTER_LINEAR, Core.BORDER_REFLECT_101)
                trackingBefore = warped
                trackingPoints = warpedIndices.map { warpedInputs[it] }
                trackingIndices = warpedIndices
            }
            val currentPoints = own(mats, MatOfPoint2f())
            currentPoints.fromArray(*trackingPoints.toTypedArray())
            val forwardPoints = own(mats, MatOfPoint2f())
            val forwardStatus = own(mats, MatOfByte())
            val forwardError = own(mats, MatOfFloat())
            if (allowSimilarityTransform) budget.charge()
            flow(trackingBefore, after, currentPoints, forwardPoints, forwardStatus, forwardError)
            val next = forwardPoints.toArray()
            val status = forwardStatus.toArray()
            val error = forwardError.toArray()
            if (next.size != trackingPoints.size || status.size != next.size || error.size != next.size) {
                return failed(VisualTrackingFailure.NATIVE_TRACKING_FAILED)
            }
            // status==0 errors are undefined; never feed invalid forward points into reverse LK.
            val forwardIndices = next.indices.filter {
                status[it].toInt() == 1 && inFrame(next[it], target) &&
                    error[it].isFinite() && error[it] >= 0f && error[it] <= MAX_LK_ERROR
            }
            stageObserver?.invoke(source.key, target.key, "REFINED_VALID") {
                val keptSlots = forwardIndices.map { trackingIndices?.get(it) ?: it }
                captureStage(source, detection, inputFeatures, inputPoints, keptSlots,
                    "source/previous/target gray pixels; stage=refined target") { slot, row ->
                    val index = trackingIndices?.indexOf(slot) ?: slot
                    if (index in next.indices) {
                        row["lk_status"] = status[index].toInt()
                        if (status[index].toInt() == 1) {
                            if (error[index].isFinite()) row["lk_error"] = error[index]
                            next[index]
                        } else null
                    } else null
                }
            }
            if (forwardIndices.count { (trackingIndices?.get(it) ?: it) < inputFeatures.size } < minimumSurvivors) {
                return failed(VisualTrackingFailure.PHOTOMETRIC_MISMATCH)
            }
            val reverseInput = own(mats, MatOfPoint2f())
            reverseInput.fromArray(*forwardIndices.map { next[it] }.toTypedArray())
            val reversePoints = own(mats, MatOfPoint2f())
            val reverseStatus = own(mats, MatOfByte())
            val reverseError = own(mats, MatOfFloat())
            if (allowSimilarityTransform) budget.charge()
            flow(after, trackingBefore, reverseInput, reversePoints, reverseStatus, reverseError)
            val back = reversePoints.toArray()
            val backStatus = reverseStatus.toArray()
            val backError = reverseError.toArray()
            if (back.size != forwardIndices.size || backStatus.size != back.size || backError.size != back.size) {
                return failed(VisualTrackingFailure.NATIVE_TRACKING_FAILED)
            }
            var advancedPoint: VisualMotionPoint? = null
            val reverseMapping = inverseAffine
            val matches = forwardIndices.mapIndexedNotNull { reverseIndex, forwardIndex ->
                if (backStatus[reverseIndex].toInt() != 1 || !inFrame(back[reverseIndex], previous) ||
                    !backError[reverseIndex].isFinite() || backError[reverseIndex] < 0f ||
                    backError[reverseIndex] > MAX_LK_ERROR
                ) return@mapIndexedNotNull null
                val originalIndex = trackingIndices?.get(forwardIndex) ?: forwardIndex
                val originalPoint = inputPoints[originalIndex]
                val returnedPoint = reverseMapping?.let { mapAffine(back[reverseIndex], it) } ?: back[reverseIndex]
                if (reverseMapping != null && !inFrame(returnedPoint, previous)) return@mapIndexedNotNull null
                val fbError = maxOf(
                    abs(returnedPoint.x - originalPoint.x),
                    abs(returnedPoint.y - originalPoint.y),
                ).toFloat()
                if (fbError >= MAX_FB_ERROR) return@mapIndexedNotNull null
                if (originalIndex == inputFeatures.size) {
                    advancedPoint = point?.copy(x = next[forwardIndex].x.toFloat(), y = next[forwardIndex].y.toFloat())
                    return@mapIndexedNotNull null
                }
                val original = inputFeatures[originalIndex]
                Match(
                    original.copy(currentX = next[forwardIndex].x.toFloat(), currentY = next[forwardIndex].y.toFloat()),
                    fbError,
                    maxOf(error[forwardIndex], backError[reverseIndex]),
                )
            }
            stageObserver?.invoke(source.key, target.key, "REVERSE_FB") {
                val keptSlots = inputFeatures.indices.filter { slot -> matches.any {
                    it.feature.sourceX == inputFeatures[slot].sourceX && it.feature.sourceY == inputFeatures[slot].sourceY
                } } + listOfNotNull(advancedPoint?.let { inputFeatures.size })
                captureStage(source, detection, inputFeatures, inputPoints, keptSlots,
                    "stage=target pixels; reverse=warped previous canvas (or previous); returned=previous pixels") { slot, row ->
                    val forwardIndex = trackingIndices?.indexOf(slot) ?: slot
                    val reverseIndex = forwardIndices.indexOf(forwardIndex)
                    if (reverseIndex >= 0) {
                        row["reverse_status"] = backStatus[reverseIndex].toInt()
                        if (backStatus[reverseIndex].toInt() == 1) {
                            val returned = back[reverseIndex]
                            if (returned.x.isFinite()) row["reverse_x"] = returned.x
                            if (returned.y.isFinite()) row["reverse_y"] = returned.y
                            if (backError[reverseIndex].isFinite()) row["reverse_lk_error"] = backError[reverseIndex]
                            if (inFrame(returned, previous) && backError[reverseIndex].isFinite() &&
                                backError[reverseIndex] >= 0f && backError[reverseIndex] <= MAX_LK_ERROR
                            ) {
                                val mapped = reverseMapping?.let { mapAffine(returned, it) } ?: returned
                                if (mapped.x.isFinite()) row["returned_previous_x"] = mapped.x
                                if (mapped.y.isFinite()) row["returned_previous_y"] = mapped.y
                                if (reverseMapping == null || inFrame(mapped, previous)) {
                                    row["fb_error"] = maxOf(abs(mapped.x-inputPoints[slot].x), abs(mapped.y-inputPoints[slot].y)).toFloat()
                                }
                            }
                        }
                        next[forwardIndex] // Only status-valid forward slots were submitted to reverse LK.
                    } else null
                }
            }
            if (matches.size < minimumSurvivors) return failed(VisualTrackingFailure.FORWARD_BACKWARD_MISMATCH)
            val estimate = estimateMotion(matches, originalFeatureCount, minimumSurvivors, source, detection, mats,
                target, inputFeatures, inputPoints)
            if (estimate.failure == null) return estimate.copy(materialPoint = advancedPoint)
            if (estimate.failure != VisualTrackingFailure.INSUFFICIENT_COVERAGE) return estimate
            val measuredNext = directNext ?: return estimate
            val measuredFlags = directFlags ?: return estimate
            val measuredErrors = directErrors ?: return estimate
            val measuredIndices = directIndices ?: return estimate
            // Coverage-only candidate: independently validate the already measured direct forward.
            // Never borrow the prealigned reverse coordinates, masks or auxiliary point.
            try {
                budget.charge()
                stageObserver?.invoke(source.key, target.key, "COVERAGE_DIRECT_FORWARD") {
                    captureStage(source, detection, inputFeatures, inputPoints, measuredIndices,
                        "source/previous/target gray pixels; stage=reused direct coarse target") { slot, row ->
                        row["lk_status"] = measuredFlags[slot].toInt()
                        if (measuredFlags[slot].toInt() == 1) {
                            if (measuredErrors[slot].isFinite()) row["lk_error"] = measuredErrors[slot]
                            measuredNext[slot]
                        } else null
                    }
                }
                // The primary reverse has been consumed; reuse its owned Mats against original before.
                reverseInput.fromArray(*measuredIndices.map { measuredNext[it] }.toTypedArray())
                budget.charge()
                flow(after, before, reverseInput, reversePoints, reverseStatus, reverseError)
                val directBack = reversePoints.toArray()
                val directBackStatus = reverseStatus.toArray()
                val directBackError = reverseError.toArray()
                if (directBack.size != measuredIndices.size || directBackStatus.size != directBack.size ||
                    directBackError.size != directBack.size
                ) return estimate
                var directAdvancedPoint: VisualMotionPoint? = null
                val directMatches = measuredIndices.mapIndexedNotNull { reverseIndex, originalIndex ->
                    if (directBackStatus[reverseIndex].toInt() != 1 || !inFrame(directBack[reverseIndex], previous) ||
                        !directBackError[reverseIndex].isFinite() || directBackError[reverseIndex] < 0f ||
                        directBackError[reverseIndex] > MAX_LK_ERROR
                    ) return@mapIndexedNotNull null
                    val originalPoint = inputPoints[originalIndex]
                    val fbError = maxOf(abs(directBack[reverseIndex].x - originalPoint.x),
                        abs(directBack[reverseIndex].y - originalPoint.y)).toFloat()
                    if (fbError >= MAX_FB_ERROR) return@mapIndexedNotNull null
                    if (originalIndex == inputFeatures.size) {
                        directAdvancedPoint = point?.copy(x = measuredNext[originalIndex].x.toFloat(),
                            y = measuredNext[originalIndex].y.toFloat())
                        return@mapIndexedNotNull null
                    }
                    Match(inputFeatures[originalIndex].copy(currentX = measuredNext[originalIndex].x.toFloat(),
                        currentY = measuredNext[originalIndex].y.toFloat()), fbError,
                        maxOf(measuredErrors[originalIndex], directBackError[reverseIndex]))
                }
                stageObserver?.invoke(source.key, target.key, "COVERAGE_DIRECT_REVERSE_FB") {
                    val keptSlots = inputFeatures.indices.filter { slot -> directMatches.any {
                        it.feature.sourceX == inputFeatures[slot].sourceX && it.feature.sourceY == inputFeatures[slot].sourceY
                    } } + listOfNotNull(directAdvancedPoint?.let { inputFeatures.size })
                    captureStage(source, detection, inputFeatures, inputPoints, keptSlots,
                        "stage=direct target pixels; reverse=original previous pixels; returned=original previous pixels") { slot, row ->
                        val reverseIndex = measuredIndices.indexOf(slot)
                        if (reverseIndex >= 0) {
                            row["reverse_status"] = directBackStatus[reverseIndex].toInt()
                            if (directBackStatus[reverseIndex].toInt() == 1) {
                                val returned = directBack[reverseIndex]
                                if (returned.x.isFinite()) row["reverse_x"] = returned.x
                                if (returned.y.isFinite()) row["reverse_y"] = returned.y
                                if (directBackError[reverseIndex].isFinite()) row["reverse_lk_error"] = directBackError[reverseIndex]
                                if (inFrame(returned, previous) && directBackError[reverseIndex].isFinite() &&
                                    directBackError[reverseIndex] >= 0f && directBackError[reverseIndex] <= MAX_LK_ERROR
                                ) {
                                    row["returned_previous_x"] = returned.x
                                    row["returned_previous_y"] = returned.y
                                    row["fb_error"] = maxOf(abs(returned.x - inputPoints[slot].x),
                                        abs(returned.y - inputPoints[slot].y)).toFloat()
                                }
                            }
                            measuredNext[slot]
                        } else null
                    }
                }
                if (directMatches.size < minimumSurvivors) return estimate
                budget.charge()
                val directEstimate = estimateMotion(directMatches, originalFeatureCount, minimumSurvivors,
                    source, detection, mats, target, inputFeatures, inputPoints, "COVERAGE_DIRECT_")
                stageObserver?.invoke(source.key, target.key, "COVERAGE_DIRECT_RESULT") {
                    StageSnapshot("independently validated direct estimate; quality denominator=original detector count",
                        emptyList(), linkedMapOf("success" to if (directEstimate.failure == null) 1 else 0,
                            "quality" to directEstimate.quality, "residual_px" to directEstimate.residual,
                            "original_feature_count" to originalFeatureCount,
                            "surviving_feature_count" to directEstimate.features.size,
                            "median_forward_backward_error_px" to directEstimate.diagnosticMedianForwardBackwardErrorPx,
                            "median_lk_error" to directEstimate.diagnosticMedianLkError))
                }
                return if (directEstimate.failure == null) directEstimate.copy(materialPoint = directAdvancedPoint) else estimate
            } catch (exceeded: TrackingBudgetExceeded) {
                throw exceeded
            } catch (_: RuntimeException) {
                return estimate
            } catch (_: LinkageError) {
                return estimate
            }
        } catch (exceeded: TrackingBudgetExceeded) {
            throw exceeded
        } catch (_: RuntimeException) {
            return failed(VisualTrackingFailure.NATIVE_TRACKING_FAILED)
        } catch (_: LinkageError) {
            return failed(VisualTrackingFailure.NATIVE_TRACKING_FAILED)
        } finally {
            release(mats)
        }
    }

    private fun estimateMotion(
        matches: List<Match>,
        originalFeatureCount: Int,
        minimumSurvivors: Int,
        source: GrayTrackingFrame,
        detection: DetectionCandidate,
        mats: MutableList<Mat>,
        target: GrayTrackingFrame,
        inputFeatures: List<VisualMotionFeature>,
        inputPoints: List<Point>,
        stagePrefix: String = "",
    ): VisualMotionEstimate {
        val fit = if (allowSimilarityTransform) {
            similarity(matches, source, mats) ?: return failed(VisualTrackingFailure.INCONSISTENT_MOTION)
        } else {
            // Check the surviving correspondence set before trimming translation residuals.
            if (!translationShapeValid(matches.map { it.feature }, source, detection)) {
                return failed(VisualTrackingFailure.UNSUPPORTED_SCALE_OR_ROTATION)
            }
            MotionFit(translation(matches, source), matches)
        }
        var transform = fit.transform
        stageObserver?.invoke(source.key, target.key, "${stagePrefix}FINAL_RANSAC") {
            captureFitStage(source, detection, inputFeatures, inputPoints, matches, fit.matches, fit.matches, transform)
        }
        if (allowSimilarityTransform) {
            val scale = hypot(transform.a, transform.c * source.height / source.width)
            val angle = abs(atan2(transform.c * source.height / source.width, transform.a))
            if (scale !in 0.5f..2f || angle > (PI / 4).toFloat()) {
                return failed(VisualTrackingFailure.UNSUPPORTED_SCALE_OR_ROTATION)
            }
        }
        var inliers = fit.matches.filter { residual(it.feature, transform, source) <= MAX_MOTION_RESIDUAL }
        stageObserver?.invoke(source.key, target.key, "${stagePrefix}FINAL_RESIDUAL") {
            captureFitStage(source, detection, inputFeatures, inputPoints, matches, fit.matches, inliers, transform)
        }
        if (inliers.size < minimumSurvivors) return failed(VisualTrackingFailure.INCONSISTENT_MOTION)
        if (!allowSimilarityTransform) {
            transform = translation(inliers, source)
            inliers = inliers.filter { residual(it.feature, transform, source) <= MAX_MOTION_RESIDUAL }
            if (inliers.size < minimumSurvivors) return failed(VisualTrackingFailure.INCONSISTENT_MOTION)
        }
        val kept = inliers.map { it.feature }
        val support = supportCoverage(kept, source, detection)
        stageObserver?.invoke(source.key, target.key, "${stagePrefix}FINAL_SUPPORT") {
            captureFitStage(source, detection, inputFeatures, inputPoints, matches, fit.matches, inliers, transform, support)
        }
        if (support.span < 0.25f || support.area < 0.10f) {
            return failed(VisualTrackingFailure.INSUFFICIENT_COVERAGE)
        }
        val residual = median(inliers.map { residual(it.feature, transform, source) })
        // Ratios use measured support relative to the actual polygon extent, or bbox without a mask.
        // 40% spread saturates support quality; FB, model residual and LK L1 error remain penalties.
        val supportQuality = (minOf(support.span, sqrt(support.area)) / 0.40f).coerceIn(0f, 1f)
        val featureSupportQuality = (kept.size.toFloat() / originalFeatureCount) * supportQuality
        val medianForwardBackwardErrorPx = median(inliers.map { it.fbError })
        val qualityBeforeLk = featureSupportQuality *
            (1f - medianForwardBackwardErrorPx / MAX_FB_ERROR).coerceIn(0f, 1f) *
            (1f - residual / MAX_MOTION_RESIDUAL).coerceIn(0f, 1f)
        val medianLkError = median(inliers.map { it.lkError })
        val quality = qualityBeforeLk * (1f - medianLkError / MAX_LK_ERROR).coerceIn(0f, 1f)
        return VisualMotionEstimate(kept, transform, quality.coerceIn(0f, 1f), residual,
            diagnosticMedianForwardBackwardErrorPx = medianForwardBackwardErrorPx,
            diagnosticMedianLkError = medianLkError)
    }

    /** Called only by a selected lazy observer. No native calls or image references escape. */
    private fun captureStage(
        source: GrayTrackingFrame,
        detection: DetectionCandidate,
        inputFeatures: List<VisualMotionFeature>,
        inputPoints: List<Point>,
        keptSlots: List<Int>,
        coordinateSystem: String,
        actualSupport: Support? = null,
        transform: VisualAffineTransform? = null,
        populate: (Int, MutableMap<String, Number?>) -> Point?,
    ): StageSnapshot {
        val supportFeatures = mutableListOf<VisualMotionFeature>()
        val rows = inputPoints.indices.map { slot ->
            val original = inputFeatures.getOrNull(slot)
            val row = linkedMapOf<String, Number?>(
                "slot" to slot, "auxiliary" to if (original == null) 1 else 0,
                "previous_x" to inputPoints[slot].x, "previous_y" to inputPoints[slot].y,
                "kept" to if (slot in keptSlots) 1 else 0,
            )
            if (original != null) {
                row["source_x"] = original.sourceX; row["source_y"] = original.sourceY
            }
            val stagePoint = populate(slot, row)
            if (stagePoint != null && stagePoint.x.isFinite() && stagePoint.y.isFinite()) {
                row["stage_x"] = stagePoint.x; row["stage_y"] = stagePoint.y
                if (original != null && slot in keptSlots) {
                    supportFeatures += original.copy(currentX = stagePoint.x.toFloat(), currentY = stagePoint.y.toFloat())
                }
            }
            row.toMap()
        }
        // Empty intermediate support is measurable zero; do not call maxOf/minOf on an empty set.
        val support = actualSupport ?: if (supportFeatures.isEmpty()) Support(0f, 0f)
            else supportCoverage(supportFeatures, source, detection)
        val values = linkedMapOf<String, Number?>(
            "gray_width" to source.width, "gray_height" to source.height,
            "input_geometry_count" to inputFeatures.size, "retained_geometry_count" to supportFeatures.size,
            "support_span" to support.span, "support_area" to support.area,
        )
        transform?.let {
            values.putAll(mapOf("fit_a_norm" to it.a, "fit_b_norm" to it.b, "fit_c_norm" to it.c,
                "fit_d_norm" to it.d, "fit_tx_norm" to it.tx, "fit_ty_norm" to it.ty))
        }
        return StageSnapshot(coordinateSystem, rows, values)
    }

    private fun captureFitStage(
        source: GrayTrackingFrame,
        detection: DetectionCandidate,
        inputFeatures: List<VisualMotionFeature>,
        inputPoints: List<Point>,
        candidates: List<Match>,
        ransacInliers: List<Match>,
        kept: List<Match>,
        transform: VisualAffineTransform,
        actualSupport: Support? = null,
    ): StageSnapshot {
        fun Match.sameSource(feature: VisualMotionFeature) =
            this.feature.sourceX == feature.sourceX && this.feature.sourceY == feature.sourceY
        val keptSlots = inputFeatures.indices.filter { slot -> kept.any { it.sameSource(inputFeatures[slot]) } }
        return captureStage(source, detection, inputFeatures, inputPoints, keptSlots,
            "source/previous/target gray pixels; stage=target; fit coefficients=source-to-target normalized",
            actualSupport, transform) { slot, row ->
            val original = inputFeatures.getOrNull(slot)
            val match = original?.let { feature -> candidates.firstOrNull { it.sameSource(feature) } }
            if (match != null) {
                row["ransac_inlier"] = if (ransacInliers.any { it === match }) 1 else 0
                val measuredResidual = residual(match.feature, transform, source)
                if (measuredResidual.isFinite()) row["fit_residual_px"] = measuredResidual
                row["fb_error"] = match.fbError; row["max_forward_reverse_lk_error"] = match.lkError
                Point(match.feature.currentX.toDouble(), match.feature.currentY.toDouble())
            } else null
        }
    }

    private fun similarity(matches: List<Match>, frame: GrayTrackingFrame, mats: MutableList<Mat>): MotionFit? {
        val from = own(mats, MatOfPoint2f())
        from.fromArray(*matches.map { Point(it.feature.sourceX.toDouble(), it.feature.sourceY.toDouble()) }.toTypedArray())
        val to = own(mats, MatOfPoint2f())
        to.fromArray(*matches.map { Point(it.feature.currentX.toDouble(), it.feature.currentY.toDouble()) }.toTypedArray())
        val inliers = own(mats, Mat())
        val matrix = own(mats, Calib3d.estimateAffinePartial2D(from, to, inliers, Calib3d.RANSAC, 2.0, 2000L, 0.99, 10L))
        if (matrix.empty() || matrix.rows() != 2 || matrix.cols() != 3 || matrix.type() != CvType.CV_64FC1 ||
            inliers.total() != matches.size.toLong() || inliers.type() != CvType.CV_8UC1
        ) return null
        val values = DoubleArray(6)
        if (matrix.get(0, 0, values) != values.size * Double.SIZE_BYTES) return null
        if (values.any { !it.isFinite() }) return null
        if (values[0] * values[4] - values[1] * values[3] <= 0.0) return null
        val inlierFlags = ByteArray(matches.size)
        if (inliers.get(0, 0, inlierFlags) != inlierFlags.size) return null
        val transform = VisualAffineTransform(
            a = values[0].toFloat(), b = (values[1] * frame.height / frame.width).toFloat(),
            c = (values[3] * frame.width / frame.height).toFloat(), d = values[4].toFloat(),
            tx = (values[2] / frame.width).toFloat(), ty = (values[5] / frame.height).toFloat(),
        )
        return transform.takeIf {
            listOf(it.a, it.b, it.c, it.d, it.tx, it.ty).all(Float::isFinite) &&
                it.areaScale.isFinite() && it.areaScale > 0f
        }?.let { MotionFit(it, matches.filterIndexed { index, _ -> inlierFlags[index].toInt() == 1 }) }
    }

    private fun translation(matches: List<Match>, frame: GrayTrackingFrame) = VisualAffineTransform(
        tx = median(matches.map { it.feature.currentX - it.feature.sourceX }) / frame.width,
        ty = median(matches.map { it.feature.currentY - it.feature.sourceY }) / frame.height,
    )

    private fun affineValues(matrix: Mat): DoubleArray? {
        if (matrix.empty() || matrix.rows() != 2 || matrix.cols() != 3 || matrix.type() != CvType.CV_64FC1) return null
        val values = DoubleArray(6)
        if (matrix.get(0, 0, values) != values.size * Double.SIZE_BYTES || values.any { !it.isFinite() }) return null
        val determinant = values[0] * values[4] - values[1] * values[3]
        return values.takeIf { determinant.isFinite() && determinant > 0.0 }
    }

    private fun mapAffine(point: Point, values: DoubleArray) = Point(
        // LK point buffers use float pixels, including the inverse map used by the FB check.
        (values[0] * point.x + values[1] * point.y + values[2]).toFloat().toDouble(),
        (values[3] * point.x + values[4] * point.y + values[5]).toFloat().toDouble(),
    )

    private fun residual(feature: VisualMotionFeature, transform: VisualAffineTransform, frame: GrayTrackingFrame): Float {
        val predicted = transform.map(Point2(feature.sourceX.toFloat() / frame.width, feature.sourceY.toFloat() / frame.height))
        return hypot(feature.currentX - predicted.x * frame.width, feature.currentY - predicted.y * frame.height)
    }

    private fun translationShapeValid(features: List<VisualMotionFeature>, frame: GrayTrackingFrame, detection: DetectionCandidate): Boolean {
        val extent = supportExtent(frame, detection)
        val minimumDistance = minOf(extent.first, extent.second) * 0.35f
        val scales = mutableListOf<Float>()
        val angles = mutableListOf<Float>()
        for (i in features.indices) for (j in i + 1 until features.size) {
            val sx = (features[j].sourceX - features[i].sourceX).toFloat()
            val sy = (features[j].sourceY - features[i].sourceY).toFloat()
            val distance = hypot(sx, sy)
            if (distance < maxOf(1f, minimumDistance)) continue
            val tx = features[j].currentX - features[i].currentX
            val ty = features[j].currentY - features[i].currentY
            scales += hypot(tx, ty) / distance
            angles += abs(atan2(sx * ty - sy * tx, sx * tx + sy * ty))
        }
        return scales.size >= 3 && median(scales) in 0.96f..1.04f && median(angles) <= 0.04f
    }

    private fun supportCoverage(features: List<VisualMotionFeature>, frame: GrayTrackingFrame, detection: DetectionCandidate): Support {
        val extent = supportExtent(frame, detection)
        if (extent.first <= 0f || extent.second <= 0f) return Support(0f, 0f)
        val sourcePoints = features.map { Point2(it.sourceX.toFloat(), it.sourceY.toFloat()) }
        val targetPoints = features.map { Point2(it.currentX, it.currentY) }
        val spanX = minOf(sourcePoints.maxOf { it.x } - sourcePoints.minOf { it.x }, targetPoints.maxOf { it.x } - targetPoints.minOf { it.x })
        val spanY = minOf(sourcePoints.maxOf { it.y } - sourcePoints.minOf { it.y }, targetPoints.maxOf { it.y } - targetPoints.minOf { it.y })
        return Support(
            minOf(spanX / extent.first, spanY / extent.second),
            minOf(hullArea(sourcePoints), hullArea(targetPoints)) / (extent.first * extent.second),
        )
    }

    private fun supportExtent(frame: GrayTrackingFrame, detection: DetectionCandidate): Pair<Float, Float> {
        val polygon = detection.polygonNorm
        return if (polygon.isEmpty()) detection.bboxNorm.width * frame.width to detection.bboxNorm.height * frame.height
        else (polygon.maxOf { it.x } - polygon.minOf { it.x }) * frame.width to
            (polygon.maxOf { it.y } - polygon.minOf { it.y }) * frame.height
    }

    private fun hullArea(points: List<Point2>): Float {
        val sorted = points.distinct().sortedWith(compareBy<Point2> { it.x }.thenBy { it.y })
        if (sorted.size < 3) return 0f
        fun half(input: List<Point2>): List<Point2> {
            val hull = mutableListOf<Point2>()
            for (point in input) {
                while (hull.size >= 2 && cross(hull[hull.lastIndex - 1], hull.last(), point) <= 0f) hull.removeAt(hull.lastIndex)
                hull += point
            }
            return hull.dropLast(1)
        }
        val hull = half(sorted) + half(sorted.asReversed())
        var twiceArea = 0f
        for (index in hull.indices) {
            val next = hull[(index + 1) % hull.size]
            twiceArea += hull[index].x * next.y - hull[index].y * next.x
        }
        return abs(twiceArea) / 2f
    }

    private fun pointInside(x: Float, y: Float, polygon: List<Point2>): Boolean {
        var inside = false
        var previous = polygon.last()
        for (current in polygon) {
            if ((current.y > y) != (previous.y > y) &&
                x < (previous.x - current.x) * (y - current.y) / (previous.y - current.y) + current.x
            ) inside = !inside
            previous = current
        }
        return inside
    }

    private fun flow(before: Mat, after: Mat, from: MatOfPoint2f, to: MatOfPoint2f, status: MatOfByte, error: MatOfFloat) {
        Video.calcOpticalFlowPyrLK(
            before, after, from, to, status, error, Size(21.0, 21.0), 3,
            TermCriteria(TermCriteria.COUNT or TermCriteria.EPS, 30, 0.01), 0, 1e-4,
        )
    }

    private fun inFrame(point: Point, frame: GrayTrackingFrame): Boolean = point.x.isFinite() && point.y.isFinite() &&
        point.x >= 0.0 && point.y >= 0.0 && point.x < frame.width && point.y < frame.height

    private fun <T : Mat> own(mats: MutableList<Mat>, mat: T): T = mat.also { mats += it }

    private fun release(mats: List<Mat>) {
        for (index in mats.indices.reversed()) mats[index].release()
    }

    private fun failed(failure: VisualTrackingFailure) = VisualMotionEstimate(emptyList(), VisualAffineTransform(), 0f, 0f, failure)
    private fun median(values: List<Float>): Float = values.sorted().let { (it[(it.size - 1) / 2] + it[it.size / 2]) / 2f }
    private fun hypot(x: Float, y: Float): Float = sqrt(x * x + y * y)
    private fun cross(a: Point2, b: Point2, c: Point2): Float = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)
    private data class Match(val feature: VisualMotionFeature, val fbError: Float, val lkError: Float)
    private data class MotionFit(val transform: VisualAffineTransform, val matches: List<Match>)
    private data class Support(val span: Float, val area: Float)

    private companion object {
        val initializationLock = Any()
        var initializationSucceeded: Boolean? = null
        const val MAX_FEATURES = 40
        const val MIN_SURVIVORS = 6
        const val MAX_FB_ERROR = 1f
        const val MAX_MOTION_RESIDUAL = 2f
        const val MAX_LK_ERROR = 28f
    }
}
