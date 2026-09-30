package kr.co.hanium.dreamup.walksafe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataCaptureLogTest {
    @Test
    fun keepsOnlyMostRecentEntriesInMemory() {
        val log = MetadataCaptureLog(maxEntries = 2)

        log.append(entry(frameTimestampMs = 1L))
        log.append(entry(frameTimestampMs = 2L))
        log.append(entry(frameTimestampMs = 3L))

        assertEquals(listOf(2L, 3L), log.recentEntries().map { it.frameTimestampMs })
    }

    @Test
    fun summaryMarksSuppressedStaleDetectionsWithoutImageFields() {
        val log = MetadataCaptureLog(maxEntries = 3)

        log.append(
            entry(
                frameTimestampMs = 10L,
                detectionsUsedForDepth = false,
                staleReason = "age>2500ms",
            ),
        )

        val summary = log.summaryText()
        assertTrue(summary.contains("suppressed"))
        assertTrue(summary.contains("stale=age>2500ms"))
        assertTrue(summary.contains("frameDelta=1ms"))
        assertTrue(summary.contains("top=person"))
        assertTrue(summary.contains("depth=person"))
        assertTrue(summary.contains("risk=1.10m"))
        assertTrue(summary.contains("score=80%"))
        assertTrue(summary.contains("preview=1080x1920"))
        assertTrue(summary.contains("depthSize=160x120"))
        assertTrue(summary.contains("transform=identity"))
        assertTrue(summary.contains("fallback=arcore_mapper_not_connected"))
    }

    @Test
    fun storedEntriesCapEveryTextAndListField() {
        // S1617-35 (originals/16-17.txt:82): 30 entries must also bound the bytes per entry.
        val log = MetadataCaptureLog(maxEntries = 3)
        val long = "x".repeat(10_000)
        val models = List(100) { "model-$it-" + "m".repeat(200) }
        log.append(
            entry(frameTimestampMs = 1L, staleReason = long).copy(
                detectorModelKey = long,
                detectorLoadedModelKey = long,
                detectorModelLoadReason = long,
                detectorCompletedModels = models,
                detectorSkippedModels = models,
                overlaySelection = long,
                overlayStaleReason = long,
                topDetectionClassName = long,
                bestDepthClassName = long,
                bestDepthTrackId = long,
                bestDepthSource = long,
                overlayTransformPath = long,
                depthTransformPath = long,
                transformPath = long,
                fallbackReason = long,
            ),
        )

        val stored = log.recentEntries().single()
        val text = MetadataCaptureLog.MAX_TEXT_CHARS
        listOf(
            stored.detectorModelKey, stored.detectorLoadedModelKey, stored.detectorModelLoadReason,
            stored.overlaySelection, stored.overlayStaleReason, stored.staleReason,
            stored.topDetectionClassName, stored.bestDepthClassName, stored.bestDepthTrackId,
            stored.bestDepthSource, stored.overlayTransformPath, stored.depthTransformPath,
            stored.transformPath, stored.fallbackReason,
        ).forEach { assertEquals(text, it?.length) }
        listOf(stored.detectorCompletedModels, stored.detectorSkippedModels).forEach { list ->
            assertEquals(MetadataCaptureLog.MAX_LIST_ITEMS, list.size)
            assertTrue(list.all { it.length <= MetadataCaptureLog.MAX_LIST_ITEM_CHARS })
            assertTrue(list.first().startsWith("model-0-"))
        }
        assertTrue(log.summaryText().length < 4_000)
    }

    @Test
    fun ordinaryEntriesAreStoredUnchanged() {
        val log = MetadataCaptureLog(maxEntries = 3)
        val ordinary = entry(frameTimestampMs = 1L, staleReason = "age>2500ms").copy(
            detectorCompletedModels = listOf("coco", "custom"),
            detectorModelLoadReason = "detector_assets_unavailable:HASH_MISMATCH",
        )
        log.append(ordinary)
        assertEquals(ordinary, log.recentEntries().single())
    }

    @Test
    fun cappingNeverLeavesHalfASurrogatePair() {
        val log = MetadataCaptureLog(maxEntries = 1)
        val emoji = "\uD83D\uDEB6"
        val value = "a".repeat(MetadataCaptureLog.MAX_TEXT_CHARS - 1) + emoji
        log.append(entry(frameTimestampMs = 1L, staleReason = value))
        val stored = log.recentEntries().single().staleReason!!
        assertEquals(MetadataCaptureLog.MAX_TEXT_CHARS - 1, stored.length)
        assertTrue(!Character.isHighSurrogate(stored.last()))
    }

    @Test
    fun clearRemovesSessionLocalMetadata() {
        val log = MetadataCaptureLog(maxEntries = 3)
        log.append(entry(frameTimestampMs = 1L))

        log.clear()

        assertEquals(emptyList<MetadataCaptureLogEntry>(), log.recentEntries())
        assertEquals("capture log: empty", log.summaryText())
    }

    private fun entry(
        frameTimestampMs: Long,
        detectionsUsedForDepth: Boolean = true,
        staleReason: String? = null,
    ): MetadataCaptureLogEntry {
        return MetadataCaptureLogEntry(
            frameTimestampMs = frameTimestampMs,
            detectorFrameTimestampMs = frameTimestampMs - 1L,
            detectorAgeMs = 100L,
            detectorFrameDeltaMs = 1L,
            detectionCount = 1,
            detectionsUsedForDepth = detectionsUsedForDepth,
            staleReason = staleReason,
            topDetectionClassName = "person",
            topDetectionConfidence = 0.91f,
            topDetectionBbox = MetadataRect(0.10f, 0.20f, 0.30f, 0.40f),
            bestDepthClassName = "person",
            bestDepthTrackId = "track-1",
            bestDepthSource = "ARCORE_RAW_DEPTH",
            bestDepthDetectionConfidence = 0.91f,
            bestDepthConfidenceScore = 0.80f,
            bestDepthMedianM = 1.2f,
            bestDepthP20M = 1.1f,
            bestDepthRiskDistanceM = 1.1f,
            bestDepthIqrM = 0.1f,
            bestDepthValidSampleCount = 12,
            bestDepthValidSampleRatio = 0.75f,
            bestDepthBbox = MetadataRect(0.10f, 0.20f, 0.30f, 0.40f),
            previewWidth = 1080,
            previewHeight = 1920,
            depthWidth = 160,
            depthHeight = 120,
            transformPath = "identity",
            fallbackReason = "arcore_mapper_not_connected",
        )
    }
}
