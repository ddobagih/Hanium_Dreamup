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


    @Test
    fun diagnosesRecordedSource49Edge68To69WithEightFeatures() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Requires -e allowVisualTrackingVideoTest true",
            arguments.getString("allowVisualTrackingVideoTest") == "true")
        val config = VisualTrackingConfig(
            backend = VisualTrackingBackend.OPENCV_PYRAMIDAL_LK,
            maxFeaturesPerObject = 40,
            allowSimilarityTransform = true,
            maxExecutionNs = 5_000_000L,
        )
        // COARSE_VALID inputs from the recorded current target69, in original slot order.
        // stage_x/y are outputs and are deliberately not used to reconstruct these features.
        val features8 = listOf(
            VisualMotionFeature(120, 59, floatArrayOf(), Float.fromBits(0x42bab96a), Float.fromBits(0x421cb9fc)),
            VisualMotionFeature(117, 57, floatArrayOf(), Float.fromBits(0x42b43789), Float.fromBits(0x42148b00)),
            VisualMotionFeature(103, 64, floatArrayOf(), Float.fromBits(0x42991c2d), Float.fromBits(0x42287eee)),
            VisualMotionFeature(114, 57, floatArrayOf(), Float.fromBits(0x42af7180), Float.fromBits(0x4215197e)),
            VisualMotionFeature(104, 71, floatArrayOf(), Float.fromBits(0x429c3f5f), Float.fromBits(0x42406a09)),
            VisualMotionFeature(107, 65, floatArrayOf(), Float.fromBits(0x42a08eaf), Float.fromBits(0x422ee6ac)),
            VisualMotionFeature(114, 62, floatArrayOf(), Float.fromBits(0x42b1a929), Float.fromBits(0x422859cf)),
            VisualMotionFeature(111, 60, floatArrayOf(), Float.fromBits(0x42ab2d3d), Float.fromBits(0x421f79da)),
        )
        val box49 = readSparseSources().getValue(49).candidate
        val gray49 = GrayTrackingFrame.copyFromLuma(key(49), WIDTH, HEIGHT,
            ByteBuffer.wrap(decodeLuma(49)), WIDTH, 1, maxEdge = 192)
        val gray68 = GrayTrackingFrame.copyFromLuma(key(68), WIDTH, HEIGHT,
            ByteBuffer.wrap(decodeLuma(68)), WIDTH, 1, maxEdge = 192)
        val gray69 = GrayTrackingFrame.copyFromLuma(key(69), WIDTH, HEIGHT,
            ByteBuffer.wrap(decodeLuma(69)), WIDTH, 1, maxEdge = 192)
        listOf(gray49, gray68, gray69).forEach { frame ->
            assertEquals(192, frame.width)
            assertEquals(144, frame.height)
        }
        val stages = linkedMapOf<String, OpenCvPyramidalMotionEstimator.StageSnapshot>()
        var duplicateStageCallbacks = 0
        var stageBoundExceeded = false
        val estimator = OpenCvPyramidalMotionEstimator(config.maxFeaturesPerObject,
            config.allowSimilarityTransform) { source, target, stage, snapshot ->
            if (source == gray49.key && target == gray69.key) {
                if (stage in stages) duplicateStageCallbacks++
                else if (stages.size < 16) stages[stage] = snapshot()
                else stageBoundExceeded = true
            }
        }
        val initializeStarted = System.nanoTime()
        assertTrue("OpenCV tracking backend initialization failed", estimator.initialize())
        val initializeNs = System.nanoTime() - initializeStarted
        val inputSlots = JSONArray(features8.mapIndexed { slot, feature ->
            JSONObject().put("slot", slot).put("auxiliary", 0)
                .put("source_x", feature.sourceX).put("source_y", feature.sourceY)
                .put("previous_x", feature.currentX.toDouble()).put("previous_y", feature.currentY.toDouble())
                .put("previous_x_float_bits", "%08x".format(feature.currentX.toRawBits()))
                .put("previous_y_float_bits", "%08x".format(feature.currentY.toRawBits()))
                .put("template_length", feature.template.size)
        })
        val budget = TrackingWorkBudget(config, System::nanoTime, System.nanoTime(), 9_999_999L)
        var estimate: VisualMotionEstimate? = null
        var thrownBudgetFailure: VisualTrackingFailure? = null
        try {
            estimate = estimator.advance(gray49, gray68, gray69, box49, features8, 17, budget, null)
        } catch (exceeded: TrackingBudgetExceeded) {
            thrownBudgetFailure = exceeded.failure
        }
        val elapsedNs = System.nanoTime() - budget.startedAtNs
        val sha256: (ByteArray) -> String = { bytes ->
            java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
        val inputFrames = JSONArray(listOf(gray49, gray68, gray69).map { frame ->
            val jpeg = "$BASE/color/${frame.key.frameId.toString().padStart(8, '0')}.jpg"
            JSONObject().put("key", frameKeyJson(frame.key)).put("gray_width", frame.width)
                .put("gray_height", frame.height).put("jpeg_asset", jpeg)
                .put("jpeg_sha256", assets.open(jpeg).use { sha256(it.readBytes()) })
                .put("gray_pixels_sha256", sha256(frame.copyPixels()))
        })
        val result = estimate?.let { actual ->
            val transform = actual.transformNorm
            JSONObject().put("failure", actual.failure?.name ?: JSONObject.NULL)
                .put("quality", actual.quality).put("residual_px", actual.residual)
                .put("surviving_feature_count", actual.features.size)
                .put("material_point_present", actual.materialPoint != null)
                .put("median_forward_backward_error_px", actual.diagnosticMedianForwardBackwardErrorPx ?: JSONObject.NULL)
                .put("median_lk_error", actual.diagnosticMedianLkError ?: JSONObject.NULL)
                .put("transform_norm", JSONObject().put("a", transform.a).put("b", transform.b)
                    .put("c", transform.c).put("d", transform.d).put("tx", transform.tx).put("ty", transform.ty))
                .put("features", JSONArray(actual.features.map { feature ->
                    JSONObject().put("source_x", feature.sourceX).put("source_y", feature.sourceY)
                        .put("current_x", feature.currentX.toDouble()).put("current_y", feature.currentY.toDouble())
                        .put("current_x_float_bits", "%08x".format(feature.currentX.toRawBits()))
                        .put("current_y_float_bits", "%08x".format(feature.currentY.toRawBits()))
                }))
        }
        val box = box49.bboxNorm
        val runId = UUID.randomUUID().toString()
        val directory = File(instrumentation.targetContext.filesDir, "tracking-validation/$runId")
        check(directory.mkdirs()) { "Cannot create test result directory" }
        val file = File(directory, "recorded69-single-edge-diagnostic.json")
        check(!file.exists()) { "Diagnostic must not replace an existing result" }
        val diagnosis = JSONObject()
            .put("scope", "RECORDED69_EIGHT_INPUT_SINGLE_EDGE_DIAGNOSTIC_NOT_TRACKER_HISTORY_OR_FIELD_VALIDATION")
            .put("run_id", runId).put("selector", "VisualTrackingVideoDeviceTest#diagnosesRecordedSource49Edge68To69WithEightFeatures")
            .put("target_package", instrumentation.targetContext.packageName)
            .put("device", Build.MODEL).put("sdk", Build.VERSION.SDK_INT)
            .put("analysis_source", "paired-analysis/paired-tracking-analysis.json")
            .put("analysis_source_sha256", "d2872a1fb91b8a5ab8e363fe4963224a438ddc948cebe347cc59db9e65ed86f5")
            .put("recorded_input_source", "current/results/3e54c331-adaf-4c5a-a12d-f52eff6372c9/source49-stage-observations.json")
            .put("recorded_input_source_sha256", "cea0bb388c436a39f6b9e63423c45667e2c34ecaef85b948dae26a0d12d5b07e")
            .put("recorded_input_stage", "COARSE_VALID").put("recorded_input_target_frame", 69)
            .put("input_coordinate_system", "source49 and previous68 gray pixels; stage output coordinates excluded")
            .put("frames", inputFrames).put("features", inputSlots)
            .put("detector_source_asset", "$BASE/detector-sources.tsv")
            .put("detector_source_sha256", assets.open("$BASE/detector-sources.tsv").use { sha256(it.readBytes()) })
            .put("box49", JSONObject().put("class_name", box49.className).put("confidence", box49.detectionConfidence)
                .put("x_norm", box.x).put("y_norm", box.y).put("width_norm", box.width).put("height_norm", box.height))
            .put("box_is_detector_source_not_ground_truth", true)
            .put("original_feature_count", 17).put("input_feature_count", features8.size)
            .put("maximum_quality_from_input_support", 8.0 / 17.0)
            .put("material_point_input", JSONObject.NULL).put("seed_called", false)
            .put("backend", config.backend.name).put("max_features_per_object", config.maxFeaturesPerObject)
            .put("allow_similarity_transform", config.allowSimilarityTransform)
            .put("configured_initial_budget_ns", config.maxExecutionNs)
            .put("execution_budget_ns", budget.executionBudgetNs).put("budget_started_at_ns", budget.startedAtNs)
            .put("pixel_comparisons_charged", budget.pixelComparisons)
            .put("max_pixel_comparisons_per_call", config.maxPixelComparisonsPerCall)
            .put("charge_call_count", JSONObject.NULL).put("charge_call_count_exposed_by_budget", false)
            .put("initialization_before_budget", true).put("initialization_ns", initializeNs)
            .put("elapsed_ns", elapsedNs).put("execution_budget_overrun", elapsedNs >= budget.executionBudgetNs)
            .put("native_hard_preemption", false).put("advance_returned", estimate != null)
            .put("thrown_budget_failure", thrownBudgetFailure?.name ?: JSONObject.NULL)
            .put("actual_result", result ?: JSONObject.NULL)
            .put("stage_observer_copy_inside_budget", true).put("edge_limit", 1)
            .put("stages_per_edge_limit", 16).put("slots_per_stage_limit", 40)
            .put("fallback_stage_prefix", "COVERAGE_DIRECT_")
            .put("fallback_observed", stages.keys.any { it.startsWith("COVERAGE_DIRECT_") })
            .put("duplicate_stage_callbacks_not_recopied", duplicateStageCallbacks)
            .put("bound_exceeded", stageBoundExceeded)
            .put("observations", JSONArray(stages.map { (stage, snapshot) ->
                JSONObject().put("source_key", frameKeyJson(gray49.key)).put("target_key", frameKeyJson(gray69.key))
                    .put("stage", stage).put("stage_branch", if (stage.startsWith("COVERAGE_DIRECT_")) "COVERAGE_DIRECT" else "PRIMARY")
                    .put("coordinate_system", snapshot.coordinateSystem)
                    .put("values", JSONObject(snapshot.scalarValues))
                    .put("slots", JSONArray(snapshot.slots.map { JSONObject(it) }))
            }))
            .put("diagnostic_only_no_algorithm_success_assertion", true)
            .put("tracking_publication_and_quality_threshold_evaluated", false)
            .put("accumulated_history_reproduced", false).put("field_validated", false)
        file.writeText(diagnosis.toString(2))
        check(!stageBoundExceeded && stages.size <= 16 && stages.values.all { it.slots.size <= 40 })
        Log.i(TAG, diagnosis.toString())
        instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "\n$TAG DIAGNOSTIC ONLY; no algorithm PASS/FAIL assertion: ${file.absolutePath}\n${diagnosis}\n")
        })
    }

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
        val observeSource49Stages = when (val value = arguments.getString("visualTrackingSource49Stages")) {
            null, "false" -> false
            "true" -> true
            else -> error("visualTrackingSource49Stages must be true or false, got $value")
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
        val stageSnapshots = if (observeSource49Stages)
            linkedMapOf<Pair<VisualFrameKey, String>, OpenCvPyramidalMotionEstimator.StageSnapshot>() else null
        var duplicateStageCallbacks = 0
        var stageBoundExceeded = false
        val tracker = if (observeSource49Stages) {
            check(defaultDelayMs == 400 && delayMs == 400 && budgetMs == 5 && adaptiveEnabled && similarity &&
                config.maxFeaturesPerObject == 40) { "Stage observation requires unchanged Video400/5ms/adaptive/similarity/max40" }
            val selectedSource = key(49)
            val records = checkNotNull(stageSnapshots)
            InterFrameDetectionTracker(config, System::nanoTime,
                OpenCvPyramidalMotionEstimator(config.maxFeaturesPerObject, config.allowSimilarityTransform) { source, target, stage, snapshot ->
                    if (source == selectedSource && target.frameId in 50L..69L && target == key(target.frameId.toInt())) {
                        val edgeStage = target to stage
                        if (edgeStage in records) duplicateStageCallbacks++
                        else if (records.size < 20 * 16) records[edgeStage] = snapshot() // Synchronous numeric copies inside the real tracking budget.
                        else stageBoundExceeded = true
                    }
                })
        } else InterFrameDetectionTracker(config)
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
        val stageFile = stageSnapshots?.let { records ->
            val beforeBoundary = output.single { it.frame == 68 }
            val boundary = output.single { it.frame == 69 }
            val reproduced = beforeBoundary.source == 49 && beforeBoundary.status == VisualTrackingStatus.TRACKED.name &&
                boundary.source == 49 && boundary.failure == VisualTrackingFailure.INSUFFICIENT_COVERAGE.name && boundary.edges == 1
            val finalSupport = records[key(69) to "FINAL_SUPPORT"]
            val supportSpan = finalSupport?.scalarValues?.get("support_span")?.toFloat()
            val supportArea = finalSupport?.scalarValues?.get("support_area")?.toFloat()
            val supportObserved = supportSpan?.isFinite() == true && supportArea?.isFinite() == true &&
                finalSupport?.slots?.any { it["auxiliary"]?.toInt() == 0 && it["kept"]?.toInt() == 1 &&
                    it["stage_x"]?.toDouble()?.isFinite() == true && it["stage_y"]?.toDouble()?.isFinite() == true } == true
            val observationEstablished = reproduced && supportObserved &&
                (supportSpan?.let { it < 0.25f } == true || supportArea?.let { it < 0.10f } == true)
            val values = JSONArray()
            records.forEach { (edgeStage, snapshot) ->
                values.put(JSONObject().put("source_key", frameKeyJson(key(49)))
                    .put("target_key", frameKeyJson(edgeStage.first)).put("stage", edgeStage.second)
                    .put("stage_branch", if (edgeStage.second.startsWith("COVERAGE_DIRECT_")) "COVERAGE_DIRECT" else "PRIMARY")
                    .put("coordinate_system", snapshot.coordinateSystem)
                    .put("values", JSONObject(snapshot.scalarValues))
                    .put("slots", JSONArray(snapshot.slots.map { JSONObject(it) })))
            }
            val observation = JSONObject()
                .put("scope", "SOURCE49_STAGE_OBSERVATION_WITH_REAL_BUDGET_OVERHEAD")
                .put("source_frame", 49).put("target_frame_first", 50).put("target_frame_last", 69)
                .put("edge_limit", 20).put("slots_per_stage_limit", 40).put("stages_per_edge_limit", 16)
                .put("fallback_stage_prefix", "COVERAGE_DIRECT_")
                .put("fallback_target_frames_observed", JSONArray(records.keys.filter { it.second.startsWith("COVERAGE_DIRECT_") }
                    .map { it.first.frameId }.distinct()))
                .put("duplicate_stage_callbacks_not_recopied", duplicateStageCallbacks)
                .put("bound_exceeded", stageBoundExceeded)
                .put("observer_selection_and_copy_inside_tracker_clock", true)
                .put("missing_status_dependent_fields_are_unavailable_not_zero", true)
                .put("auxiliary_excluded_from_fit_and_coverage", true)
                .put("original_68_TRACKED_to_69_COVERAGE_edge1_reproduced", reproduced)
                .put("frame69_final_support_observed", supportObserved)
                .put("stage_observation_established", observationEstablished)
                .put("non_reproduction_is_not_product_PASS_or_FAIL", true)
                .put("frame68", JSONObject().put("status", beforeBoundary.status).put("failure", beforeBoundary.failure)
                    .put("source", beforeBoundary.source).put("processed_edges", beforeBoundary.edges))
                .put("frame69", JSONObject().put("status", boundary.status).put("failure", boundary.failure)
                    .put("source", boundary.source).put("processed_edges", boundary.edges))
                .put("observations", values)
            val file = File(directory, "source49-stage-observations.json")
            file.writeText(observation.toString(2))
            check(!stageBoundExceeded && records.size <= 20 * 16 && records.values.all { it.slots.size <= 40 })
            file
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
        if (stageFile != null) summary.put("source49StageObservation", stageFile.absolutePath)
        val summaryFile = File(directory, "summary.json")
        summaryFile.writeText(summary.toString(2))
        Log.i(TAG, summary.toString())
        instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "\n$TAG ${summary}\nSummary: ${summaryFile.absolutePath}\n")
        })
        if (adaptiveEnabled) assertTrue("30 FPS admitted-frame budget schedule mismatch; see ${summaryFile.absolutePath}", adaptiveCadenceVerified)
        assertTrue("No intermediate video tracking; see ${summaryFile.absolutePath}", intermediateEmitted > 0)
    }

    private fun frameKeyJson(key: VisualFrameKey) = JSONObject()
        .put("epoch", key.epoch).put("frameId", key.frameId).put("cameraTimestampNs", key.cameraTimestampNs)
        .put("capturedAtElapsedRealtimeMs", key.capturedAtElapsedRealtimeMs).put("geometryVersion", key.geometryVersion)

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
