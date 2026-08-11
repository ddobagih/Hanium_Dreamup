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
