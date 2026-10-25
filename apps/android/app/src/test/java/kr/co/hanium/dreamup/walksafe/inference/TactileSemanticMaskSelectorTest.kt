package kr.co.hanium.dreamup.walksafe.inference.unknown.segmentation

import com.google.ar.core.Frame
import java.util.BitSet
import kr.co.hanium.dreamup.walksafe.UprightCameraImage
import kr.co.hanium.dreamup.walksafe.depth.CameraImageTimestampEvidence
import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import kr.co.hanium.dreamup.walksafe.depth.Point2
import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import kr.co.hanium.dreamup.walksafe.inference.*
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamFrameToken
import org.junit.Assert.*
import org.junit.Test

/** Same Java package grants access to the existing immutable mask fixture constructor. No JNI runs. */
class TactileSemanticMaskSelectorTest {
    @Test
    fun uniqueActualFootprintPreservesHolesComponentsScoresAndOpaqueMaskIdentity() {
        val capture = capture()
        val pixels = mutableListOf<Pair<Int, Int>>()
        for (y in 4..6) for (x in 4..6) if (x != 5 || y != 5) pixels += x to y
        pixels += listOf(10 to 10, 11 to 10, 10 to 11, 11 to 11)
        // Reported mask box and ROI are much larger than the actual foreground.
        val mask = mask(pixels, anchor = 999, score = .61f, roi = YuvCropRect(3, 3, 13, 13))
        val before = mask.copyPackedBits()
        for (semantic in TactileSemanticKind.values()) {
            val detection = detection(semantic.className, .84f)
            val selected = selector().select(named(capture, listOf(detection)), masks(capture, listOf(mask)), NOW_NS)
            val candidate = selected.candidates.single()
            assertEquals(semantic, candidate.semantic)
            assertSame(capture, candidate.capture)
            assertSame(mask, candidate.instanceMask)
            assertEquals(999, candidate.instanceMask.anchorIndex())
            assertEquals(.61f, candidate.instanceMask.score(), 0f)
            assertEquals(.84f, candidate.detection.detectionConfidence, 0f)
            assertEquals(config().unifiedWalksafe!!.classes.indexOf(semantic.className), candidate.modelClassIndex)
            assertEquals(if (semantic.name.startsWith("DAMAGED")) .3f else .25f, candidate.configuredThreshold, 0f)
            assertEquals(pixels.size, candidate.sensorMask.area)
            for (y in 0 until 16) for (x in 0 until 16) {
                assertEquals("original sensor bit $x,$y", mask.contains(x, y), candidate.sensorMask.contains(x, y))
            }
            assertFalse(candidate.sensorMask.contains(5, 5))
            assertFalse(candidate.sensorMask.contains(8, 8))
            val fact = selected.decisions.single().maskFacts.single()
            assertEquals(pixels.size, fact.containedPixelCount)
            assertEquals(pixels.size, fact.overlappingPixelCount)
            assertEquals(TactileSemanticMaskStatus.ASSOCIATE_CANDIDATE, selected.decisions.single().status)
        }
        assertArrayEquals(before, mask.copyPackedBits())
    }

    @Test
    fun separateNonOverlappingNamedBlocksEachKeepTheirOwnUniqueMask() {
        val capture = capture()
        val first = detection(box = RectNorm(0f, 0f, .25f, .25f))
        val second = detection(TactileSemanticKind.DOT.className, box = RectNorm(.75f, .75f, .25f, .25f))
        val firstMask = mask(listOf(2 to 2), anchor = 700)
        val secondMask = mask(listOf(12 to 12), anchor = 701)
        val result = selector().select(named(capture, listOf(first, second)),
            masks(capture, listOf(firstMask, secondMask)), NOW_NS)
        assertEquals(listOf(0, 1), result.candidates.map { it.namedDetectionIndex })
        assertSame(firstMask, result.candidates[0].instanceMask)
        assertSame(secondMask, result.candidates[1].instanceMask)
        assertTrue(result.decisions.all { it.overlappingDetectionIndices.isEmpty() })
    }

    @Test
    fun fullGroundOrAdjacentTilePixelsOutsideNamedBoxAreNeverClippedIntoCandidates() {
        val capture = capture()
        for (pixels in listOf(listOf(5 to 5, 14 to 14), listOf(5 to 5, 12 to 5))) {
            val mask = mask(pixels)
            val before = mask.copyPackedBits()
            val selected = selector().select(named(capture), masks(capture, listOf(mask)), NOW_NS)
            assertTrue(selected.candidates.isEmpty())
            assertEquals(TactileSemanticMaskReason.NO_CONTAINED_MASK, selected.decisions.single().reason)
            val fact = selected.decisions.single().maskFacts.single()
            assertEquals(1, fact.containedPixelCount)
            assertEquals(1, fact.overlappingPixelCount)
            assertEquals(2, fact.sensorMask!!.area)
            assertTrue(fact.sensorMask.contains(pixels[1].first, pixels[1].second))
            assertArrayEquals(before, mask.copyPackedBits())
        }
    }

    @Test
    fun pixelCenterInsideButPixelCellCrossingBoxEdgeIsNotContained() {
        val capture = capture()
        // The center of sensor pixel(4,5) is inside, but its left edge .25 is outside .275.
        val d = detection(box = RectNorm(.275f, .25f, .4f, .5f))
        val selected = selector().select(named(capture, listOf(d)), masks(capture, listOf(mask(listOf(4 to 5)))), NOW_NS)
        assertTrue(selected.candidates.isEmpty())
        val fact = selected.decisions.single().maskFacts.single()
        assertEquals(0, fact.containedPixelCount)
        assertEquals(1, fact.overlappingPixelCount)
    }

    @Test
    fun multipleMasksAndOverlappingOrSharedTactileSemanticsRemainAmbiguous() {
        val capture = capture()
        val first = mask(listOf(5 to 5), anchor = 4)
        val second = mask(listOf(9 to 9), anchor = 8)
        val multiple = selector().select(named(capture), masks(capture, listOf(first, second)), NOW_NS)
        assertTrue(multiple.candidates.isEmpty())
        assertEquals(TactileSemanticMaskReason.MULTIPLE_CONTAINED_MASKS, multiple.decisions.single().reason)
        assertEquals(TactileSemanticMaskStatus.AMBIGUOUS, multiple.decisions.single().status)

        val normal = detection(score = .76f)
        val damaged = detection(TactileSemanticKind.DAMAGED_LINE.className, .91f)
        val shared = selector().select(named(capture, listOf(normal, damaged)), masks(capture, listOf(first)), NOW_NS)
        assertTrue(shared.candidates.isEmpty())
        assertEquals(listOf(.76f, .91f), shared.decisions.map { it.detection.detectionConfidence })
        for ((index, decision) in shared.decisions.withIndex()) {
            assertEquals(TactileSemanticMaskReason.SHARED_MASK, decision.reason)
            assertEquals(TactileSemanticMaskStatus.AMBIGUOUS, decision.status)
            assertEquals(listOf(1 - index), decision.maskFacts.single().sharedDetectionIndices)
            assertEquals(listOf(1 - index), decision.overlappingDetectionIndices)
        }
        // Boxes overlap, although this mask is contained only by the first named box.
        val overlap = selector().select(named(capture, listOf(normal,
            damaged.copy(bboxNorm = RectNorm(.5f, .25f, .4f, .5f)))), masks(capture, listOf(first)), NOW_NS)
        assertTrue(overlap.candidates.isEmpty())
        assertTrue(overlap.decisions.all { it.reason == TactileSemanticMaskReason.OVERLAPPING_TACTILE_DETECTIONS })
    }

    @Test
    fun emptySamOutputAndEmptyBitsPreserveTheirDistinctFactsWithoutRectangleFallback() {
        val capture = capture()
        val emptyOutput = selector().select(named(capture), masks(capture, emptyList()), NOW_NS)
        assertEquals(TactileSemanticMaskReason.MASKS_EMPTY, emptyOutput.decisions.single().reason)
        val emptyMask = mask(emptyList())
        val emptyBits = selector().select(named(capture), masks(capture, listOf(emptyMask)), NOW_NS)
        assertTrue(emptyBits.candidates.isEmpty())
        val fact = emptyBits.decisions.single().maskFacts.single()
        assertSame(emptyMask, fact.instanceMask)
        assertEquals(0, fact.sensorMask!!.area)
        assertEquals(TactileSemanticMaskReason.EMPTY_MASK, fact.reason)
        assertEquals(TactileSemanticMaskStatus.UNAVAILABLE, emptyBits.decisions.single().status)
    }

    @Test
    fun coarseAndNonTactileClassesCannotGainRefinedSemanticsFromSamAnchorIndex() {
        val capture = capture()
        val mask = mask(listOf(5 to 5), anchor = config().unifiedWalksafe!!.classes.indexOf(TactileSemanticKind.LINE.className))
        for (name in listOf("normal_tactile_block", "damaged_tactile_block", "person", "pothole")) {
            val selected = selector().select(named(capture, listOf(detection(name))), masks(capture, listOf(mask)), NOW_NS)
            assertTrue(selected.candidates.isEmpty())
            assertEquals(TactileSemanticMaskReason.NO_REFINED_TACTILE_SEMANTIC, selected.decisions.single().reason)
            assertNull(selected.decisions.single().semantic)
            assertEquals(name, selected.decisions.single().detection.className)
            assertEquals(.8f, selected.decisions.single().detection.detectionConfidence, 0f)
        }
    }

    @Test
    fun actualConfiguredModelClassThresholdAndCpuFallbackArePreserved() {
        val capture = capture()
        val masks = masks(capture, listOf(mask(listOf(5 to 5))))
        val atThreshold = detection(score = .25f)
        val actualCpuFallback = AndroidDetectionResult(listOf(atThreshold), timing = AndroidDetectorTiming(
            modelKey = TwoModelRuntimeConfig.UNIFIED_MODEL_KEY,
            modelRuntime = AndroidDetectorRuntime("gpu", "cpu", 4, fallbackUsed = true),
        ))
        val accepted = selector().select(TactileNamedSemanticEvidence.fromResult(capture, actualCpuFallback,
            TactileSemanticCoordinates.SENSOR_ORIGINAL), masks, NOW_NS).candidates.single()
        assertEquals(.25f, accepted.detection.detectionConfidence, 0f)
        assertEquals(.25f, accepted.configuredThreshold, 0f)
        assertEquals(MODEL_SHA, accepted.modelArtifactSha256)
        val below = selector().select(named(capture, listOf(detection(score = .249f))), masks, NOW_NS)
        assertEquals(TactileSemanticMaskReason.BELOW_CONFIGURED_THRESHOLD, below.decisions.single().reason)
        assertFalse(below.decisions.single().thresholdPassed)
        for (key in listOf(null, TwoModelRuntimeConfig.LEGACY_TWO_MODEL_KEY, TwoModelRuntimeConfig.WALKMATE_REPORT_MODEL_KEY)) {
            val result = selector().select(named(capture, modelKey = key), masks, NOW_NS)
            assertEquals(TactileSemanticMaskReason.ACTIVE_MODEL_UNCONFIRMED, result.reason)
        }
        val disabled = config().let { it.copy(unifiedWalksafe = it.unifiedWalksafe!!.copy(enabled = false)) }
        assertEquals(TactileSemanticMaskReason.ACTIVE_MODEL_UNCONFIRMED,
            selector(disabled).select(named(capture), masks, NOW_NS).reason)
        val withoutClass = config().let { it.copy(unifiedWalksafe = it.unifiedWalksafe!!.copy(classes = listOf("person"))) }
        assertEquals(TactileSemanticMaskReason.CLASS_NOT_IN_ACTIVE_MODEL,
            selector(withoutClass).select(named(capture), masks, NOW_NS).decisions.single().reason)
        val disallowed = config().let { it.copy(unifiedWalksafe = it.unifiedWalksafe!!.copy(allowlist = setOf("person"))) }
        assertEquals(TactileSemanticMaskReason.CLASS_NOT_ALLOWED,
            selector(disallowed).select(named(capture), masks, NOW_NS).decisions.single().reason)
    }

    @Test
    fun invalidOriginalScoresGeometryAndForeignMaskDimensionsRemainUnavailable() {
        val capture = capture()
        val masks = masks(capture, listOf(mask(listOf(5 to 5))))
        for (score in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f, 1.1f)) {
            val selected = selector().select(named(capture, listOf(detection(score = score))), masks, NOW_NS)
            assertEquals(TactileSemanticMaskReason.INVALID_NAMED_SCORE, selected.decisions.single().reason)
            assertTrue(selected.candidates.isEmpty())
        }
        for (box in listOf(RectNorm(Float.NaN, .25f, .5f, .5f), RectNorm(-.01f, .25f, .6f, .5f),
            RectNorm(.8f, .25f, .3f, .5f), RectNorm(.25f, .25f, 0f, .5f))) {
            val selected = selector().select(named(capture, listOf(detection(box = box))), masks, NOW_NS)
            assertEquals(TactileSemanticMaskReason.INVALID_NAMED_GEOMETRY, selected.decisions.single().reason)
        }
        val foreign = mask(listOf(5 to 5), width = 32)
        val selected = selector().select(named(capture), masks(capture, listOf(foreign)), NOW_NS)
        assertTrue(selected.candidates.isEmpty())
        assertEquals(TactileSemanticMaskReason.FOREIGN_MASK_DIMENSIONS, selected.decisions.single().maskFacts.single().reason)
        val malformed = mask(listOf(5 to 5), reportedArea = 2)
        assertEquals(TactileSemanticMaskReason.INVALID_MASK,
            selector().select(named(capture), masks(capture, listOf(malformed)), NOW_NS).decisions.single().maskFacts.single().reason)
    }

    @Test
    fun sameClockValuesCannotReplaceAnOpaqueImageReceipt() {
        val original = capture()
        val foreign = capture() // all token numbers are equal, but this is a different actual acquisition identity.
        val result = selector().select(named(original), masks(foreign, listOf(mask(listOf(5 to 5)))), NOW_NS)
        assertEquals(TactileSemanticMaskReason.FOREIGN_IMAGE_RECEIPT, result.reason)
        assertTrue(result.candidates.isEmpty())
        assertSame(original, result.namedEvidence.capture)
        assertSame(foreign, result.maskEvidence.capture)
    }

    @Test
    fun everyOriginalCaptureFieldAndActualSamResultTokenMustMatchWithoutLatestRetagging() {
        val capture = capture()
        val original = capture.token
        val foreignTokens = listOf(token(epoch = 2), token(frameId = 8), token(cameraNs = CAMERA_NS + 1),
            token(cpuNs = CPU_NS + 1), token(elapsedNs = CAPTURE_NS + 1), token(geometry = "other"),
            token(width = 32), token(height = 32))
        for (foreignToken in foreignTokens) {
            val retagged = capture.copy(token = foreignToken)
            val sourceMismatch = selector().select(named(capture), masks(retagged, listOf(mask(listOf(5 to 5)))), NOW_NS)
            assertEquals(TactileSemanticMaskReason.FOREIGN_CAPTURE, sourceMismatch.reason)
            val actualResultMismatch = selector().select(named(capture), masks(capture, listOf(mask(listOf(5 to 5))), foreignToken), NOW_NS)
            assertEquals(TactileSemanticMaskReason.FOREIGN_CAPTURE, actualResultMismatch.reason)
            assertSame(original, sourceMismatch.namedEvidence.capture.token)
        }
        for (changed in listOf(capture.copy(imageQuarterTurns = 1),
            capture.copy(originalDeadlineElapsedNs = DEADLINE_NS + 1))) {
            assertEquals(TactileSemanticMaskReason.FOREIGN_CAPTURE,
                selector().select(named(capture), masks(changed, listOf(mask(listOf(5 to 5)))), NOW_NS).reason)
        }
        // Equal field values in separately constructed immutable tokens are valid for this receipt.
        val exactCopy = capture.copy(token = token())
        assertEquals(1, selector().select(named(capture), masks(exactCopy, listOf(mask(listOf(5 to 5))), token()), NOW_NS).candidates.size)
    }

    @Test
    fun originalElapsedRealtimeDeadlineIsCheckedAtNanosecondBoundaryWithoutRenewal() {
        val capture = capture().copy(originalDeadlineElapsedNs = DEADLINE_NS + 123)
        val masks = masks(capture, listOf(mask(listOf(5 to 5))))
        assertEquals(1, selector().select(named(capture), masks, DEADLINE_NS + 123).candidates.size)
        val expired = selector().select(named(capture), masks, DEADLINE_NS + 124)
        assertEquals(TactileSemanticMaskReason.EXPIRED_CAPTURE, expired.reason)
        assertEquals(DEADLINE_NS + 123, expired.namedEvidence.capture.originalDeadlineElapsedNs)
        assertEquals(TactileSemanticMaskReason.INVALID_CAPTURE,
            selector().select(named(capture), masks, CAPTURE_NS - 1).reason)
        for (invalid in listOf(capture.copy(imageQuarterTurns = 4),
            capture.copy(originalDeadlineElapsedNs = CAPTURE_NS), capture.copy(token = token(cpuNs = CPU_NS + 1)))) {
            assertEquals(TactileSemanticMaskReason.INVALID_CAPTURE,
                selector().select(named(invalid), masks(invalid, listOf(mask(listOf(5 to 5)))), NOW_NS).reason)
        }
    }

    @Test
    fun completedEmptySkippedPendingUnavailableFailedAndPartialRemainDifferentFromValidCapture() {
        val capture = capture()
        val packets = listOf(
            TactileNamedSemanticEvidence.fromResult(capture, AndroidDetectionResult.empty()) to TactileSemanticMaskReason.NAMED_COMPLETED_EMPTY,
            TactileNamedSemanticEvidence.skipped(capture) to TactileSemanticMaskReason.NAMED_SKIPPED,
            TactileNamedSemanticEvidence.pending(capture) to TactileSemanticMaskReason.NAMED_PENDING,
            TactileNamedSemanticEvidence.fromResult(capture, AndroidDetectionResult.unavailable()) to TactileSemanticMaskReason.NAMED_UNAVAILABLE,
            TactileNamedSemanticEvidence.fromResult(capture, AndroidDetectionResult.failed()) to TactileSemanticMaskReason.NAMED_FAILED,
            TactileNamedSemanticEvidence.fromResult(capture, AndroidDetectionResult(listOf(detection()), partial = true)) to TactileSemanticMaskReason.NAMED_PARTIAL,
        )
        for ((packet, expected) in packets) {
            val result = selector().select(packet, masks(capture, listOf(mask(listOf(5 to 5)))), NOW_NS)
            assertEquals(expected, result.reason)
            assertTrue(result.candidates.isEmpty())
            assertSame(capture.imageReceipt, result.namedEvidence.capture.imageReceipt)
        }
        val unconfirmedNamed = TactileNamedSemanticEvidence.fromResult(capture,
            AndroidDetectionResult(listOf(detection()), timing = AndroidDetectorTiming(modelKey = TwoModelRuntimeConfig.UNIFIED_MODEL_KEY)))
        assertEquals(TactileSemanticMaskReason.COORDINATES_UNCONFIRMED,
            selector().select(unconfirmedNamed, masks(capture, listOf(mask(listOf(5 to 5)))), NOW_NS).reason)
        assertEquals(TactileSemanticMaskReason.COORDINATES_UNCONFIRMED,
            selector().select(named(capture), TactileInstanceMaskEvidence(capture, capture.token, listOf(mask(listOf(5 to 5)))), NOW_NS).reason)
    }

    @Test
    fun producerQuarterTurnsLetterboxAndCropReturnOriginalPortraitAndLandscapePixels() {
        for ((width, height) in listOf(8 to 16, 16 to 8)) for (q in 0..3) {
            val capture = capture(width, height, q)
            val sourceBox = YuvCropRect(2, 2, 6, 6)
            val uprightBox = sourceBox.toUpright(width, height, q)
            val upright = UprightCameraImage.rotate(ArgbImage(width, height, IntArray(width * height) { it }), q)
            val scale = minOf(32f / upright.width, 32f / upright.height)
            val transform = LetterboxTransform(upright.width, upright.height, 32, scale,
                (32 - upright.width * scale) / 2f, (32 - upright.height * scale) / 2f)
            val modelBox = RectNorm((uprightBox.left * scale + transform.padX) / 32f,
                (uprightBox.top * scale + transform.padY) / 32f,
                (uprightBox.right - uprightBox.left) * scale / 32f,
                (uprightBox.bottom - uprightBox.top) * scale / 32f)
            val parsed = requireNotNull(transform.modelDetectionToImageDetection(detection(box = modelBox)))
            val sensor = requireNotNull(YuvCropRect(1, 1, width - 1, height - 1).toValidSensorDetection(parsed, width, height, q))
            assertEquals(RectNorm(2f / width, 2f / height, 4f / width, 4f / height), sensor.bboxNorm)
            val mask = mask(listOf(3 to 3, 4 to 4), width, height)
            val candidate = selector().select(named(capture, listOf(sensor)), masks(capture, listOf(mask)), NOW_NS).candidates.single()
            assertEquals(q, candidate.capture.imageQuarterTurns)
            assertEquals(width, candidate.sensorMask.originalWidth)
            assertEquals(height, candidate.sensorMask.originalHeight)
            assertTrue(candidate.sensorMask.contains(3, 3))
            assertTrue(candidate.sensorMask.contains(4, 4))
            assertEquals(2, candidate.sensorMask.area)
            val (rx, ry) = when (q) {
                0 -> 3 to 3
                1 -> height - 1 - 3 to 3
                2 -> width - 1 - 3 to height - 1 - 3
                else -> 3 to width - 1 - 3
            }
            assertEquals(3 * width + 3, upright.pixels[ry * upright.width + rx])
            val wrongQ = capture.copy(imageQuarterTurns = (q + 1) % 4)
            assertEquals(TactileSemanticMaskReason.FOREIGN_CAPTURE,
                selector().select(named(capture, listOf(sensor)), masks(wrongQ, listOf(mask)), NOW_NS).reason)
        }
    }

    @Test
    fun producerCropBoundaryCannotTrimRealGroundMaskIntoSemanticForeground() {
        val capture = capture()
        val crop = YuvCropRect(4, 4, 12, 12)
        val namedCrop = requireNotNull(crop.toValidSensorDetection(detection(box = RectNorm(0f, 0f, 1f, 1f)), 16, 16))
        assertEquals(RectNorm(.25f, .25f, .5f, .5f), namedCrop.bboxNorm)
        val actualFullMask = mask(listOf(5 to 5, 0 to 0))
        val result = selector().select(named(capture, listOf(namedCrop)), masks(capture, listOf(actualFullMask)), NOW_NS)
        assertTrue(result.candidates.isEmpty())
        assertTrue(result.decisions.single().maskFacts.single().sensorMask!!.contains(0, 0))
        assertEquals(1, result.decisions.single().maskFacts.single().containedPixelCount)
    }

    @Test
    fun resultSnapshotsOwnTheirListsAndMaskCopyDoesNotChangeProducerBits() {
        val capture = capture()
        val polygon = mutableListOf(Point2(.3f, .3f), Point2(.4f, .3f), Point2(.4f, .4f))
        val detections = mutableListOf(detection().copy(polygonNorm = polygon))
        val masksList = mutableListOf(mask(listOf(5 to 5)))
        val named = named(capture, detections)
        val masks = masks(capture, masksList)
        detections.clear(); polygon.clear(); masksList.clear()
        val result = selector().select(named, masks, NOW_NS)
        val candidate = result.candidates.single()
        assertEquals(3, candidate.detection.polygonNorm.size)
        val copiedBits = candidate.instanceMask.copyPackedBits()
        copiedBits.fill(0)
        assertTrue(candidate.instanceMask.contains(5, 5))
        assertTrue(candidate.sensorMask.contains(5, 5))
        try {
            (result.candidates as MutableList<*>).clear()
            fail("candidate list must be immutable")
        } catch (_: UnsupportedOperationException) { }
    }

    private fun selector(config: TwoModelRuntimeConfig = config()) = TactileSemanticMaskSelector(config)

    private fun config(): TwoModelRuntimeConfig {
        val classes = TactileSemanticKind.values().map { it.className } + "person"
        val thresholds = classes.associateWith { if (it.startsWith("damaged_")) .3f else .25f }
        return TwoModelRuntimeConfig("actual-fixture", null, TwoModelRuntimeConfig.UNIFIED_MODEL_KEY, null,
            ModelRuntimeConfig(TwoModelRuntimeConfig.UNIFIED_MODEL_KEY, "model/existing.tflite", 768,
                classes, classes.toSet(), thresholds, artifactSha256 = MODEL_SHA), null, null)
    }

    private fun detection(name: String = TactileSemanticKind.LINE.className, score: Float = .8f,
        box: RectNorm = RectNorm(.25f, .25f, .5f, .5f)) = DetectionCandidate(name, score, box)

    private fun token(width: Int = 16, height: Int = 16, q: Int = 0, epoch: Long = 1,
        frameId: Long = 7, cameraNs: Long = CAMERA_NS, cpuNs: Long = CPU_NS,
        elapsedNs: Long = CAPTURE_NS, geometry: String = "geom:${width}x${height}:q$q") =
        FastSamFrameToken(FastSamFrameToken.Source.LIVE_CAMERA, epoch, frameId, cameraNs, cpuNs,
            elapsedNs, geometry, width, height)

    private fun capture(width: Int = 16, height: Int = 16, q: Int = 0): TactileSemanticCapture {
        // Opaque acquisition receipt fixture only: no timestamp getter or native AR call is used.
        val receipt = CameraImageTimestampEvidence(Any(), 1, object : Frame() {}, CAMERA_NS, CPU_NS)
        return TactileSemanticCapture(receipt, token(width, height, q), q, DEADLINE_NS)
    }

    private fun named(capture: TactileSemanticCapture, detections: List<DetectionCandidate> = listOf(detection()),
        modelKey: String? = TwoModelRuntimeConfig.UNIFIED_MODEL_KEY) = TactileNamedSemanticEvidence.fromResult(
        capture, AndroidDetectionResult(detections, timing = AndroidDetectorTiming(modelKey = modelKey)),
        TactileSemanticCoordinates.SENSOR_ORIGINAL)

    private fun masks(capture: TactileSemanticCapture, masks: List<InstanceMask>, actualResultToken: FastSamFrameToken = capture.token) =
        TactileInstanceMaskEvidence(capture, actualResultToken, masks, TactileSemanticCoordinates.SENSOR_ORIGINAL)

    private fun mask(pixels: List<Pair<Int, Int>>, width: Int = 16, height: Int = 16,
        anchor: Int = 300, score: Float = .7f, reportedArea: Int = pixels.size,
        roi: YuvCropRect = YuvCropRect.full(width, height)): InstanceMask {
        val stride = roi.right - roi.left
        val ownedBits = BitSet(stride * (roi.bottom - roi.top))
        pixels.forEach { (x, y) -> ownedBits.set((y - roi.top) * stride + x - roi.left) }
        return InstanceMask(width, height, roi.left, roi.top, roi.right, roi.bottom, reportedArea, anchor,
            0f, 0f, 1f, 1f, score, ownedBits)
    }

    companion object {
        private const val CAMERA_NS = 900_000_000L
        private const val CPU_NS = 890_000_000L
        private const val CAPTURE_NS = 10_000_000_000L
        private const val NOW_NS = CAPTURE_NS + 100_000_000L
        private const val DEADLINE_NS = CAPTURE_NS + 800_000_000L
        private val MODEL_SHA = "d".repeat(64)
    }
}
