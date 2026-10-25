package kr.co.hanium.dreamup.walksafe.depth

import java.util.Collections
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualMaterialPoint
import kr.co.hanium.dreamup.walksafe.navigation.FrozenImageToDepthTransform
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Original body capture and transform. Main still admits the real runtime, frame and lease. */
class TrackedMeasuredBodyBinding private constructor(
    internal val producer: MeasuredBodySpace,
    internal val capture: MeasuredBodySpace.Capture,
    internal val error: BodyDepthObservationError,
) {
    companion object {
        fun freeze(producer: MeasuredBodySpace, originalCapture: MeasuredBodySpace.Capture?,
            originalMapper: FrozenImageToDepthTransform?, currentOriginalBodyKey: MeasuredBodyCaptureKey?,
            error: BodyDepthObservationError?): TrackedMeasuredBodyBinding? {
            val capture = originalCapture ?: return null
            val bounds = error ?: return null
            if (originalMapper == null || capture.registration.producer !== producer || capture.key != currentOriginalBodyKey ||
                capture.mapper !== originalMapper || originalMapper.frameId != capture.key.frameTimestampNs ||
                bounds.metricPositionErrorM?.let { !it.isFinite() || it < 0.0 } != false ||
                bounds.axialQuantizationErrorBoundM?.let { !it.isFinite() || it < 0.0 } != false ||
                bounds.basis.isNullOrBlank()) return null
            return TrackedMeasuredBodyBinding(producer,capture,bounds.copy())
        }
    }

    internal fun matches(snapshot: DepthFrameSnapshot, observation: CurrentTrackedFrameObservation,
        mapperFrameId: Long, key: MeasuredBodyCaptureKey?): Boolean {
        val original = capture.key
        val target = observation.targetKey
        // The service/AR epoch and the body's walk epoch are independent domains. Do not convert.
        return key == original && original.frameTimestampNs == target.frameId &&
            original.cameraImageTimestampNs == target.cameraTimestampNs && original.geometryVersion == target.geometryVersion &&
            original.capturedAtElapsedNs / 1_000_000L == target.capturedAtElapsedRealtimeMs &&
            mapperFrameId == target.frameId && capture.mapper.frameId == target.frameId &&
            observation.matchesDepthSnapshot(snapshot) && snapshot.cameraPoseEvidence == capture.pose &&
            snapshot.cameraPoseEvidence?.referenceId == original.poseReferenceId &&
            snapshot.cameraPoseEvidence?.imageProjection == capture.projection &&
            snapshot.semanticFrame === capture.semantic
    }
}

/** Original publication and opaque visual point; clearance is only an exact, observed-cell join. */
class TrackedMeasuredBodyDepth internal constructor(
    val originalOutput: TrackedObjectDepth,
    val originalVisualMaterialPoint: VisualMaterialPoint?,
    val clearance: MeasuredBodyPointClearance?,
)

/**
 * Always calls the existing producer once, including absent/invalid body bindings. Its outputs,
 * ordering, source indices, warnings and leases remain unchanged. The owned wrapper list is
 * immutable; originalOutput retains the existing DTO's ownership contract, without cloning it.
 * No body collision, physical floor/foot, CONNECTED, walking or risk authority is supplied.
 */
fun ObjectDepthRuntimePipeline.processTrackedObservationWithMeasuredBody(
    snapshot: DepthFrameSnapshot, observation: CurrentTrackedFrameObservation,
    mapper: CoordinateMapper, mapperFrameId: Long, nowElapsedRealtimeMs: Long,
    motionContext: MotionContext = MotionContext(), imageQuarterTurns: Int? = 0,
    binding: TrackedMeasuredBodyBinding? = null, currentOriginalBodyKey: MeasuredBodyCaptureKey? = null,
    nowElapsedNs: Long,
): List<TrackedMeasuredBodyDepth> {
    val outputs = processTrackedObservation(snapshot,observation,mapper,mapperFrameId,nowElapsedRealtimeMs,
        motionContext,imageQuarterTurns)
    return Collections.unmodifiableList(outputs.map { output ->
        joinTrackedMeasuredBodyDepth(output,snapshot,observation,mapper,mapperFrameId,motionContext,
            imageQuarterTurns,binding,currentOriginalBodyKey,nowElapsedNs)
    })
}

internal fun joinTrackedMeasuredBodyDepth(output: TrackedObjectDepth, snapshot: DepthFrameSnapshot,
    observation: CurrentTrackedFrameObservation, mapper: CoordinateMapper, mapperFrameId: Long,
    motionContext: MotionContext, imageQuarterTurns: Int?, binding: TrackedMeasuredBodyBinding?,
    currentKey: MeasuredBodyCaptureKey?, nowElapsedNs: Long): TrackedMeasuredBodyDepth {
    val index = output.sourceDetectionIndex
    val row = index?.let { i -> observation.observations.singleOrNull { it.sourceIndex == i } }
    val point = row?.materialPoint?.takeIf { row.detectorSourceKey == observation.sourceKey &&
        row.trackedTargetKey == observation.targetKey && it.targetKey == observation.targetKey }
    fun unknown() = TrackedMeasuredBodyDepth(output,point,null)
    val matched = binding?.takeIf { it.matches(snapshot,observation,mapperFrameId,currentKey) } ?: return unknown()
    if (point == null || output.frameId != observation.targetKey.frameId ||
        output.timestampMs != observation.timestampMs || output.depthAvailability != DepthAvailability.MEASURED ||
        output.spatialCoverageLimited || output.depthReprojected != false ||
        motionContext.copy(cameraPoseEvidence=snapshot.cameraPoseEvidence).reliableCameraPoseEvidence() == null
    ) return unknown()
    val geometry = observation.measuredGeometry(index,MaskPolygonExtractor(),imageQuarterTurns) ?: return unknown()
    if (geometry.bboxNorm != output.bboxNorm || geometry.polygonNorm != output.polygonNorm ||
        point.imagePointNorm.x !in geometry.bboxNorm.x..(geometry.bboxNorm.x+geometry.bboxNorm.width) ||
        point.imagePointNorm.y !in geometry.bboxNorm.y..(geometry.bboxNorm.y+geometry.bboxNorm.height)) return unknown()
    val raw = output.source == DepthSource.ARCORE_RAW_DEPTH
    val depth = when (output.source) {
        DepthSource.ARCORE_RAW_DEPTH -> snapshot.rawDepth?.takeIf { snapshot.hasFreshMetricRawDepth }
        DepthSource.ARCORE_FULL_DEPTH -> snapshot.fullDepth?.takeIf { snapshot.hasFreshFullDepth }
        else -> null
    } ?: return unknown()
    val confidence = if (raw) snapshot.rawConfidence ?: return unknown() else null
    val timestamp = (if (raw) snapshot.rawDepthTimestampNs else snapshot.fullDepthTimestampNs) ?: return unknown()
    val originalTimestamp = if (raw) matched.capture.rawTimestampNs else matched.capture.fullTimestampNs
    if (timestamp != originalTimestamp || matched.capture.dimensions(output.source) != (depth.width to depth.height)) return unknown()
    val mapped = mapper.imageToDepth(point.imagePointNorm) ?: return unknown()
    val originalMapped = matched.capture.mapper.map(point.imagePointNorm) ?: return unknown()
    val polygon = mapper.imagePolygonToDepthPolygon(geometry.polygonNorm)
    val originalPolygon = geometry.polygonNorm.map { matched.capture.mapper.map(it) ?: return unknown() }
    if (mapped != originalMapped || polygon != originalPolygon) return unknown()
    val sampler = DepthSampler()
    // Reuse exactly Estimator's Raw30/.35 and Full50/null populations, not a new admission gate.
    val options = if (raw) DepthSampleOptions() else DepthSampleOptions(minValidSamples=50,minConfidence=null)
    val stats = sampler.sampleObjectDepth(depth,confidence,polygon,options)
    if (stats.medianM != output.depthMedianM || stats.p20M != output.depthP20M || stats.iqrM != output.depthIqrM ||
        stats.validSampleCount != output.validSampleCount || stats.validSampleRatio != output.validSampleRatio) return unknown()
    val ownZ = sampler.sampleMaterialPointDepth(depth,confidence,polygon,mapped,options.minConfidence) ?: return unknown()
    val median = stats.medianM ?: return unknown()
    // Same foreground qualification as Estimator.materialPointInAnchor; never replace own Z by median.
    if (abs(ownZ-median) > max(.25f,(stats.madM ?: 0f)*3f)) return unknown()
    val x = (mapped.x*(depth.width-1)).roundToInt()
    val y = (mapped.y*(depth.height-1)).roundToInt()
    val pixelConfidence = confidence?.values?.get(y*depth.width+x)?.toInt()?.and(0xff)
    val clearance = matched.producer.pointClearance(matched.capture,currentKey,point.imagePointNorm,
        output.source,matched.error,nowElapsedNs)?.takeIf {
        // Body uses floor(uv*size); the material sampler uses round(uv*(size-1)). No cell repair.
        it.depthX == x && it.depthY == y && it.source == output.source && it.depthTimestampNs == timestamp &&
            (-it.cameraPoint.z).toFloat() == ownZ && it.depthPixelConfidence == pixelConfidence
    }
    return TrackedMeasuredBodyDepth(output,point,clearance)
}
