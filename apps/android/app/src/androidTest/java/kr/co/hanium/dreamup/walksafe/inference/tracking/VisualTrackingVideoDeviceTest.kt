package kr.co.hanium.dreamup.walksafe.inference.tracking

import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import kr.co.hanium.dreamup.walksafe.depth.IndexedObjectGeometry
import kr.co.hanium.dreamup.walksafe.depth.MaskPolygonExtractor
import kr.co.hanium.dreamup.walksafe.depth.ObjectTracker
import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Local public-video replay using production gray-frame/tracker/ObjectTracker APIs.
 * Does not launch an Activity, access a camera/account, or run a detector/Depth pipeline.
 * Full target GT is opened only after all predictions are collected. A completed test
 * establishes replay execution and at least one intermediate observation, not safety.
 */
@RunWith(AndroidJUnit4::class)
class VisualTrackingVideoDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val assets get() = instrumentation.context.assets

    @Test
    fun replaysBagWith400MsLateDetectorBoxes() = runReplay(defaultDelayMs = 400)

    @Test
    fun replaysBagWithoutDetectorDelayForDiagnosis() = runReplay(defaultDelayMs = 0)

    private fun runReplay(defaultDelayMs: Int) {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Requires -e allowVisualTrackingVideoTest true",
            arguments.getString("allowVisualTrackingVideoTest") == "true")
        val delayMs = intOption(arguments, "visualTrackingDelayMs", defaultDelayMs, setOf(0, 100, 200, 400))
        val budgetMs = intOption(arguments, "visualTrackingBudgetMs", 5, setOf(5, 10, 15))
        val adaptiveEnabled = when (val value = arguments.getString("visualTrackingAdaptiveBudget")) {
            null, "false" -> false
            "true" -> true
            else -> error("visualTrackingAdaptiveBudget must be true or false, got $value")
        }
        val similarity = when (val value = arguments.getString("visualTrackingSimilarity")) {
            null, "true" -> true
            "false" -> false
            else -> error("visualTrackingSimilarity must be true or false, got $value")
        }
        val delayFrames = delayMs * FPS / 1000
        val sources = readSparseSources()
        assertEquals((1..FRAME_COUNT step 12).toList(), sources.keys.toList())
        val config = VisualTrackingConfig(
            backend = VisualTrackingBackend.OPENCV_PYRAMIDAL_LK,
            maxFeaturesPerObject = 40,
            allowSimilarityTransform = similarity,
            maxExecutionNs = budgetMs * 1_000_000L,
        )
        val tracker = InterFrameDetectionTracker(config)
        val adaptiveBudget = if (adaptiveEnabled) AdaptiveVisualTrackingBudget().also { it.reset() } else null
        val initializeStarted = System.nanoTime()
        assertTrue("OpenCV tracking backend initialization failed", tracker.initialize())
        val initializeNs = System.nanoTime() - initializeStarted
        val identities = ObjectTracker()
        val extractor = MaskPolygonExtractor()
        val output = mutableListOf<Prediction>()
        var batch: Source? = null
        var assignedId: String? = null
        var previousSource = -1
        var decodeNs = 0L
        val replayStarted = System.nanoTime()
        try {
            for (number in 1..FRAME_COUNT) {
                val sourceDelivered = sources[number - delayFrames]
                if (sourceDelivered != null) batch = sourceDelivered
                val decodeStarted = System.nanoTime()
                val luma = decodeLuma(number)
                decodeNs += System.nanoTime() - decodeStarted
                val started = System.nanoTime()
                val targetKey = key(number)
                val frame = GrayTrackingFrame.copyFromLuma(targetKey, WIDTH, HEIGHT,
                    ByteBuffer.wrap(luma), WIDTH, 1, maxEdge = 192)
                assertEquals(192, frame.width)
                assertEquals(144, frame.height)
                val admission = tracker.offerFrame(frame)
                assertTrue("Frame $number admission: ${admission.failure}", admission.accepted)
                if (admission.reset) adaptiveBudget?.reset()
                // Match Main: feed every admitted CPU image, even before any detector source arrives.
                val selectedBudgetNs = adaptiveBudget?.budgetForFrame(targetKey) ?: config.maxExecutionNs
                val prepared = System.nanoTime()
                val current = batch
                if (current == null) {
                    output += Prediction(number, prepareNs = prepared - started, selectedBudgetNs = selectedBudgetNs)
                    continue
                }
                val result = tracker.trackFrom(key(current.frame), listOf(current.candidate), targetKey,
                    executionBudgetNs = selectedBudgetNs)
                val trackedAt = System.nanoTime()
                assertEquals(selectedBudgetNs, result.metrics.executionBudgetNs)
                assertEquals(targetKey, result.trackedTargetKey)
                assertEquals(key(current.frame), result.detectorSourceKey)
                val observation = result.observations.single()
                assertEquals(0, observation.sourceIndex)
                assertEquals(observation.status == VisualTrackingStatus.TRACKED, observation.geometry != null)
                observation.geometry?.let {
                    assertEquals(current.candidate.className, it.className)
                    assertEquals(current.candidate.detectionConfidence, it.detectionConfidence, 0f)
                }
                // Match ObjectDepthRuntimePipeline.processTrackedObservation: unfinished work
                // does not consume a source confirmation, clear its ID, or mark tracks missed.
                val identityDeferred = result.observations.any {
                    it.failure == VisualTrackingFailure.WORK_BUDGET_EXCEEDED ||
                        it.failure == VisualTrackingFailure.TIME_BUDGET_EXCEEDED
                }
                var sourceConsumed = false
                if (!identityDeferred) {
                    val indexed = observation.geometry?.let {
                        listOf(IndexedObjectGeometry(0, extractor.extract(it)))
                    }.orEmpty()
                    val sourceChanged = previousSource != current.frame
                    if (sourceChanged) {
                        assignedId = identities.updateWithAssignments(indexed, targetKey.capturedAtElapsedRealtimeMs)
                            .singleOrNull()?.track?.trackId
                        previousSource = current.frame
                        sourceConsumed = true
                    } else {
                        val identified = assignedId?.let { id -> indexed.map { id to it.geometry } }.orEmpty()
                        identities.applyTrackedObservations(identified, targetKey.capturedAtElapsedRealtimeMs)
                    }
                }
                val id = assignedId?.takeIf { candidate ->
                    !identityDeferred && observation.geometry != null && identities.activeTracks().any {
                        it.trackId == candidate && it.missedFrames == 0
                    }
                }
                val completed = System.nanoTime()
                output += Prediction(
                    frame = number, source = current.frame, delivered = sourceDelivered != null,
                    status = observation.status.name, failure = observation.failure?.name.orEmpty(),
                    box = observation.geometry?.bboxNorm, trackId = id,
                    prepareNs = prepared - started, trackerNs = trackedAt - prepared,
                    idNs = completed - trackedAt, quality = observation.trackingQuality,
                    features = observation.survivingFeatureCount, edges = result.metrics.processedEdges,
                    retainedBytes = result.metrics.retainedImageBytes,
                    identityDeferred = identityDeferred, sourceConsumed = sourceConsumed,
                    selectedBudgetNs = selectedBudgetNs, executionBudgetNs = result.metrics.executionBudgetNs,
                    executionBudgetOverrun = result.metrics.executionBudgetOverrun,
                    reportedTrackerNs = result.metrics.durationNs,
                )
            }
        } finally {
            tracker.clear()
            adaptiveBudget?.reset()
        }
        val replayNs = System.nanoTime() - replayStarted
        // Evaluation labels are deliberately unavailable to the replay loop above.
        val groundTruth = readEvaluationGroundTruth()
        assertEquals(FRAME_COUNT, output.size)
        assertEquals(FRAME_COUNT, groundTruth.size)
        val evaluated = output.filter { it.source != null }
        val emitted = evaluated.filter { it.box != null }
        val intermediate = evaluated.filter { !it.delivered }
        val intermediateEmitted = intermediate.count { it.box != null }
        val camera = evaluated.filter { groundTruth.getValue(it.frame).cameraMotion == true }
        val ids = emitted.mapNotNull { it.trackId }
        val idChanges = ids.zipWithNext().count { it.first != it.second }
        val intervals = evaluated.size.toDouble() / FPS
        val failures = evaluated.filter { it.failure.isNotEmpty() }.groupingBy { it.failure }.eachCount()
        val callBudgets = evaluated.map { requireNotNull(it.executionBudgetNs) }
        val adaptiveCadenceVerified = output.take(6).all { it.selectedBudgetNs == 5_000_000L } &&
            output.drop(6).all { it.selectedBudgetNs in 9_999_999L..10_000_000L }
        val runId = UUID.randomUUID().toString()
        val directory = File(instrumentation.targetContext.filesDir, "tracking-validation/$runId")
        check(directory.mkdirs()) { "Cannot create test result directory" }
        val raw = File(directory, "delay-$delayMs-budget-$budgetMs-adaptive-$adaptiveEnabled-similarity-$similarity-predictions.tsv")
        raw.bufferedWriter().use { writer ->
            writer.appendLine("frame\tsource\tdetector_delivery\tstatus\tfailure\tx\ty\twidth\theight\ttrack_id\tquality\tfeatures\tprepare_ns\ttracker_ns\tid_ns\tprocessed_edges\tretained_bytes\tidentity_deferred\tsource_consumed\tselected_budget_ns\texecution_budget_ns\texecution_budget_overrun\treported_tracker_ns")
            output.forEach { p -> writer.appendLine(listOf(p.frame, p.source, p.delivered, p.status, p.failure,
                p.box?.x?.times(WIDTH), p.box?.y?.times(HEIGHT), p.box?.width?.times(WIDTH), p.box?.height?.times(HEIGHT),
                p.trackId, p.quality, p.features, p.prepareNs, p.trackerNs, p.idNs, p.edges, p.retainedBytes,
                p.identityDeferred, p.sourceConsumed, p.selectedBudgetNs, p.executionBudgetNs,
                p.executionBudgetOverrun, p.reportedTrackerNs)
                .joinToString("\t") { it?.toString().orEmpty() }) }
        }
        val summary = JSONObject()
            .put("scope", "PUBLIC_VIDEO_2D_TRACKING_COMPONENT_REPLAY")
            .put("device", Build.MODEL).put("sdk", Build.VERSION.SDK_INT)
            .put("backend", config.backend.name).put("maxFeaturesPerObject", config.maxFeaturesPerObject)
            .put("allowSimilarityTransform", config.allowSimilarityTransform)
            .put("maxExecutionMs", callBudgets.max() / 1e6).put("initializationMs", initializeNs / 1e6)
            .put("configuredFixedBudgetMs", budgetMs).put("adaptiveBudgetEnabled", adaptiveEnabled)
            .put("budgetPolicy", if (adaptiveEnabled) "ADMITTED_CPU_FRAME_CADENCE" else "FIXED")
            .put("nativeHardPreemption", false)
            .put("callBudgetMinMs", callBudgets.min() / 1e6).put("callBudgetMaxMs", callBudgets.max() / 1e6)
            .put("admittedFrameBudgetMinMs", output.minOf { it.selectedBudgetNs } / 1e6)
            .put("admittedFrameBudgetMaxMs", output.maxOf { it.selectedBudgetNs } / 1e6)
            .put("admittedFrameBudgetNsHistogram", JSONObject(output.groupingBy { it.selectedBudgetNs.toString() }.eachCount()))
            .put("adaptiveThirtyFpsBudgetVerified", if (adaptiveEnabled) adaptiveCadenceVerified else JSONObject.NULL)
            .put("executionBudgetOverrunCalls", evaluated.count { it.executionBudgetOverrun == true })
            .put("publishedExecutionBudgetOverrunFrames", emitted.count { it.executionBudgetOverrun == true })
            .put("unpublishedExecutionBudgetOverrunCalls", evaluated.count { it.box == null && it.executionBudgetOverrun == true })
            .put("sequence", "VOT2016/bag").put("frames", FRAME_COUNT).put("fps", FPS)
            .put("actualVideoSeconds", FRAME_COUNT.toDouble() / FPS)
            .put("videoFirstToLastTimestampSeconds", (FRAME_COUNT - 1).toDouble() / FPS)
            .put("evaluatedVideoSeconds", intervals).put("processingWallSeconds", replayNs / 1e9)
            .put("processingFramesPerSecond", FRAME_COUNT * 1e9 / replayNs)
            .put("decodeAndLumaSeconds", decodeNs / 1e9)
            .put("sourceEveryMs", 400).put("deliveryDelayMs", delayMs).put("defaultMethodDelayMs", defaultDelayMs)
            .put("grayWidth", 192).put("grayHeight", 144)
            .put("trackerCalls", evaluated.size).put("detectorDeliveries", evaluated.count { it.delivered })
            .put("trackedFrames", emitted.size).put("lostFrames", evaluated.size - emitted.size)
            .put("acceptedObservationsPerVideoSecond", emitted.size / intervals)
            .put("intermediateFrames", intermediate.size).put("intermediateTrackedFrames", intermediateEmitted)
            .put("iouMeanLostAsZero", evaluated.map { iou(it.box, groundTruth.getValue(it.frame).box) }.average())
            .put("iouMeanTrackedOnly", emitted.map { iou(it.box, groundTruth.getValue(it.frame).box) }
                .takeIf { it.isNotEmpty() }?.average() ?: JSONObject.NULL)
            .put("cameraMotionTaggedFrames", camera.size)
            .put("cameraMotionIoUMeanLostAsZero", camera.map { iou(it.box, groundTruth.getValue(it.frame).box) }.average())
            .put("runtimeUniqueIds", JSONArray(ids.distinct())).put("singleTargetIdChanges", idChanges)
            .put("trackedWithoutRuntimeId", emitted.count { it.trackId == null })
            .put("identityDeferredFrames", evaluated.count { it.identityDeferred })
            .put("terminalLostFrames", evaluated.count { it.box == null && !it.identityDeferred })
            .put("sourceConsumptionUpdates", evaluated.count { it.sourceConsumed })
            .put("confirmedDetectorUpdates", evaluated.count { it.sourceConsumed && it.box != null })
            .put("identityPolicy", "MATCH_PRODUCT_SKIP_PENDING_BUDGET")
            .put("failures", JSONObject(failures))
            .put("trackerCallMs", timings(evaluated.map { it.trackerNs }))
            .put("grayPreparationMs", timings(output.map { it.prepareNs }))
            .put("idUpdateMs", timings(evaluated.map { it.idNs }))
            .put("maxRetainedImageBytes", output.maxOf { it.retainedBytes })
            .put("rawOutput", raw.absolutePath)
            .put("detectorAccuracyValidated", false).put("depthValidated", false)
            .put("liveCameraCadenceValidated", false).put("activityLaunched", false)
        val summaryFile = File(directory, "summary.json")
        summaryFile.writeText(summary.toString(2))
        Log.i(TAG, summary.toString())
        instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "\n$TAG ${summary}\nSummary: ${summaryFile.absolutePath}\n")
        })
        if (adaptiveEnabled) assertTrue("30 FPS admitted-frame budget schedule mismatch; see ${summaryFile.absolutePath}", adaptiveCadenceVerified)
        assertTrue("No intermediate video tracking; see ${summaryFile.absolutePath}", intermediateEmitted > 0)
    }

    private fun intOption(arguments: Bundle, name: String, defaultValue: Int, allowed: Set<Int>): Int {
        val raw = arguments.getString(name) ?: return defaultValue
        val value = raw.toIntOrNull()
        require(value in allowed) { "$name must be one of $allowed, got $raw" }
        return requireNotNull(value)
    }

    private fun readSparseSources(): Map<Int, Source> = assets.open("$BASE/detector-sources.tsv")
        .bufferedReader().use { reader -> reader.readLines().drop(1).associate { line ->
            val p = line.split('\t')
            val frame = p[0].toInt()
            frame to Source(frame, DetectionCandidate("bag", 1f, rect(p)))
        } }

    private fun readEvaluationGroundTruth(): Map<Int, GroundTruth> = assets.open("$BASE/evaluation-gt.tsv")
        .bufferedReader().use { reader -> reader.readLines().drop(1).associate { line ->
            val p = line.split('\t')
            p[0].toInt() to GroundTruth(rect(p), when (p.getOrNull(5)) { "1" -> true; "0" -> false; else -> null })
        } }

    private fun decodeLuma(frame: Int): ByteArray {
        val bitmap = assets.open("$BASE/color/${frame.toString().padStart(8, '0')}.jpg").use {
            requireNotNull(BitmapFactory.decodeStream(it)) { "JPEG decode failed at $frame" }
        }
        try {
            check(bitmap.width == WIDTH && bitmap.height == HEIGHT)
            val argb = IntArray(WIDTH * HEIGHT)
            bitmap.getPixels(argb, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
            return ByteArray(argb.size) { i ->
                val value = argb[i]
                val red = value shr 16 and 255
                val green = value shr 8 and 255
                val blue = value and 255
                ((299 * red + 587 * green + 114 * blue + 500) / 1000).toByte()
            }
        } finally { bitmap.recycle() }
    }

    private fun key(frame: Int) = VisualFrameKey(1L, frame.toLong(),
        1_000_000_000L + (frame - 1L) * 1_000_000_000L / FPS,
        1_000L + (frame - 1L) * 1000L / FPS, 1L)

    private fun rect(p: List<String>) = RectNorm(p[1].toFloat() / WIDTH, p[2].toFloat() / HEIGHT,
        p[3].toFloat() / WIDTH, p[4].toFloat() / HEIGHT)

    private fun iou(a: RectNorm?, b: RectNorm): Double {
        if (a == null) return 0.0
        val intersection = max(0f, min(a.x + a.width, b.x + b.width) - max(a.x, b.x)) *
            max(0f, min(a.y + a.height, b.y + b.height) - max(a.y, b.y))
        return (intersection / (a.area + b.area - intersection)).toDouble()
    }

    private fun timings(values: List<Long>): JSONObject {
        val sorted = values.sorted()
        return JSONObject().put("mean", values.average() / 1e6)
            .put("p50", sorted[(ceil(sorted.size * .50).toInt() - 1).coerceAtLeast(0)] / 1e6)
            .put("p95", sorted[(ceil(sorted.size * .95).toInt() - 1).coerceAtLeast(0)] / 1e6)
            .put("max", sorted.last() / 1e6)
    }

    private data class Source(val frame: Int, val candidate: DetectionCandidate)
    private data class GroundTruth(val box: RectNorm, val cameraMotion: Boolean?)
    private data class Prediction(
        val frame: Int, val source: Int? = null, val delivered: Boolean = false,
        val status: String = "NO_DETECTION", val failure: String = "", val box: RectNorm? = null,
        val trackId: String? = null, val prepareNs: Long = 0L, val trackerNs: Long = 0L,
        val idNs: Long = 0L, val quality: Float = 0f, val features: Int = 0, val edges: Int = 0,
        val retainedBytes: Int = 0,
        val identityDeferred: Boolean = false, val sourceConsumed: Boolean = false,
        val selectedBudgetNs: Long = 0L, val executionBudgetNs: Long? = null,
        val executionBudgetOverrun: Boolean? = null, val reportedTrackerNs: Long? = null,
    )

    private companion object {
        const val TAG = "VisualTrackingVideo"
        const val BASE = "tracking-validation/vot2016-bag"
        const val FRAME_COUNT = 196
        const val WIDTH = 480
        const val HEIGHT = 360
        const val FPS = 30
    }
}
