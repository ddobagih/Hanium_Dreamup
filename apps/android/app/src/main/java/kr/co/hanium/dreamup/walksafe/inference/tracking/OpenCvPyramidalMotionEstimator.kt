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
) : VisualMotionEstimator {
    init {
        require(maxFeatures in MIN_SURVIVORS..MAX_FEATURES)
    }

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
        val mats = mutableListOf<Mat>()
        try {
            val before = own(mats, Mat(previous.height, previous.width, CvType.CV_8UC1))
            before.put(0, 0, previous.copyPixels())
            val after = own(mats, Mat(target.height, target.width, CvType.CV_8UC1))
            after.put(0, 0, target.copyPixels())
            val currentPoints = own(mats, MatOfPoint2f())
            currentPoints.fromArray(*inputFeatures.map { Point(it.currentX.toDouble(), it.currentY.toDouble()) }.toTypedArray())
            val forwardPoints = own(mats, MatOfPoint2f())
            val forwardStatus = own(mats, MatOfByte())
            val forwardError = own(mats, MatOfFloat())
            flow(before, after, currentPoints, forwardPoints, forwardStatus, forwardError)
            val next = forwardPoints.toArray()
            val status = forwardStatus.toArray()
            val error = forwardError.toArray()
            if (next.size != inputFeatures.size || status.size != next.size || error.size != next.size) {
                return failed(VisualTrackingFailure.NATIVE_TRACKING_FAILED)
            }
            // status==0 errors are undefined; never feed invalid forward points into reverse LK.
            val forwardIndices = next.indices.filter {
                status[it].toInt() == 1 && inFrame(next[it], target) &&
                    error[it].isFinite() && error[it] >= 0f && error[it] <= MAX_LK_ERROR
            }
            if (forwardIndices.size < minimumSurvivors) return failed(VisualTrackingFailure.PHOTOMETRIC_MISMATCH)
            val reverseInput = own(mats, MatOfPoint2f())
            reverseInput.fromArray(*forwardIndices.map { next[it] }.toTypedArray())
            val reversePoints = own(mats, MatOfPoint2f())
            val reverseStatus = own(mats, MatOfByte())
            val reverseError = own(mats, MatOfFloat())
            flow(after, before, reverseInput, reversePoints, reverseStatus, reverseError)
            val back = reversePoints.toArray()
            val backStatus = reverseStatus.toArray()
            val backError = reverseError.toArray()
            if (back.size != forwardIndices.size || backStatus.size != back.size || backError.size != back.size) {
                return failed(VisualTrackingFailure.NATIVE_TRACKING_FAILED)
            }
            val matches = forwardIndices.mapIndexedNotNull { reverseIndex, forwardIndex ->
                if (backStatus[reverseIndex].toInt() != 1 || !inFrame(back[reverseIndex], previous) ||
                    !backError[reverseIndex].isFinite() || backError[reverseIndex] < 0f ||
                    backError[reverseIndex] > MAX_LK_ERROR
                ) return@mapIndexedNotNull null
                val original = inputFeatures[forwardIndex]
                val fbError = maxOf(
                    abs(back[reverseIndex].x - original.currentX),
                    abs(back[reverseIndex].y - original.currentY),
                ).toFloat()
                if (fbError >= MAX_FB_ERROR) return@mapIndexedNotNull null
                Match(
                    original.copy(currentX = next[forwardIndex].x.toFloat(), currentY = next[forwardIndex].y.toFloat()),
                    fbError,
                    maxOf(error[forwardIndex], backError[reverseIndex]),
                )
            }
            if (matches.size < minimumSurvivors) return failed(VisualTrackingFailure.FORWARD_BACKWARD_MISMATCH)
            return estimateMotion(matches, originalFeatureCount, minimumSurvivors, source, detection, mats)
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
        if (allowSimilarityTransform) {
            val scale = hypot(transform.a, transform.c * source.height / source.width)
            val angle = abs(atan2(transform.c * source.height / source.width, transform.a))
            if (scale !in 0.5f..2f || angle > (PI / 4).toFloat()) {
                return failed(VisualTrackingFailure.UNSUPPORTED_SCALE_OR_ROTATION)
            }
        }
        var inliers = fit.matches.filter { residual(it.feature, transform, source) <= MAX_MOTION_RESIDUAL }
        if (inliers.size < minimumSurvivors) return failed(VisualTrackingFailure.INCONSISTENT_MOTION)
        if (!allowSimilarityTransform) {
            transform = translation(inliers, source)
            inliers = inliers.filter { residual(it.feature, transform, source) <= MAX_MOTION_RESIDUAL }
            if (inliers.size < minimumSurvivors) return failed(VisualTrackingFailure.INCONSISTENT_MOTION)
        }
        val kept = inliers.map { it.feature }
        val support = supportCoverage(kept, source, detection)
        if (support.span < 0.25f || support.area < 0.10f) {
            return failed(VisualTrackingFailure.INSUFFICIENT_COVERAGE)
        }
        val residual = median(inliers.map { residual(it.feature, transform, source) })
        // Ratios use measured support relative to the actual polygon extent, or bbox without a mask.
        // 40% spread saturates support quality; FB, model residual and LK L1 error remain penalties.
        val supportQuality = (minOf(support.span, sqrt(support.area)) / 0.40f).coerceIn(0f, 1f)
        val quality = (kept.size.toFloat() / originalFeatureCount) * supportQuality *
            (1f - median(inliers.map { it.fbError }) / MAX_FB_ERROR).coerceIn(0f, 1f) *
            (1f - residual / MAX_MOTION_RESIDUAL).coerceIn(0f, 1f) *
            (1f - median(inliers.map { it.lkError }) / MAX_LK_ERROR).coerceIn(0f, 1f)
        return VisualMotionEstimate(kept, transform, quality.coerceIn(0f, 1f), residual)
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
