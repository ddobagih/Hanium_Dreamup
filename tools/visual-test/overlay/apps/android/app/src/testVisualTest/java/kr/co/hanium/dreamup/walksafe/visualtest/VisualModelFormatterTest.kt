package kr.co.hanium.dreamup.walksafe.visualtest

import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import kr.co.hanium.dreamup.walksafe.depth.DepthConfidenceBreakdown
import kr.co.hanium.dreamup.walksafe.depth.DepthSource
import kr.co.hanium.dreamup.walksafe.depth.MaskPolygonExtractor
import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import kr.co.hanium.dreamup.walksafe.depth.TrackedObjectDepth
import kr.co.hanium.dreamup.walksafe.depth.Trend
import kr.co.hanium.dreamup.walksafe.depth.UserFacingDepth
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualModelFormatterTest {
    private val first = DetectionCandidate("person", 0.92f, RectNorm(0.1f, 0.2f, 0.3f, 0.4f))

    @Test
    fun rawAndFullDepthShowMeasuredDistanceAndSeparateConfidence() {
        for (source in listOf(DepthSource.ARCORE_RAW_DEPTH, DepthSource.ARCORE_FULL_DEPTH)) {
            val label = VisualModelFormatter.label(first, listOf(output(first, source)), 17L)
            assertTrue(label, label.contains("탐지 92%"))
            assertTrue(label, label.contains("거리 1.25m"))
            assertTrue(label, label.contains("거리 신뢰도 80%"))
            assertTrue(label, label.contains("표본 12개"))
            assertTrue(label, label.contains(if (source == DepthSource.ARCORE_RAW_DEPTH) "Raw Depth" else "Full Depth"))
        }
    }

    @Test
    fun missingUnknownAndOtherFrameDepthNeverAttachADistance() {
        val measured = output(first)
        for ((outputs, frameId) in listOf(
            emptyList<TrackedObjectDepth>() to 17L,
            listOf(measured.copy(frameId = 18L)) to 17L,
            listOf(measured) to null,
            listOf(measured.copy(className = "car")) to 17L,
            listOf(measured, measured.copy(trackId = "another")) to 17L,
        )) {
            val label = VisualModelFormatter.label(first, outputs, frameId)
            assertTrue(label, label.contains("거리 확인 불가"))
            assertFalse(label, label.contains("1.25m"))
        }
    }

    @Test
    fun nonArCoreSourcesNeverDisplayMetersEvenWhenDistanceIsPresent() {
        for (source in DepthSource.entries.filterNot {
            it == DepthSource.ARCORE_RAW_DEPTH || it == DepthSource.ARCORE_FULL_DEPTH
        }) {
            val label = VisualModelFormatter.label(first, listOf(output(first, source)), 17L)
            assertTrue(label, label.contains("ARCore 거리 아님: ${source.name}"))
            assertFalse(label, label.contains("1.25m"))
        }
    }

    @Test
    fun invalidDepthDistancesStayUnavailableAndZeroRemainsValid() {
        for (distance in listOf(null, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -1f)) {
            val label = VisualModelFormatter.label(first, listOf(output(first).copy(riskDistanceM = distance)), 17L)
            assertTrue(label, label.contains("거리 확인 불가"))
            assertTrue(label, label.contains("ARCore Raw Depth"))
            assertFalse(label, Regex("[0-9]m").containsMatchIn(label))
        }
        val zero = VisualModelFormatter.label(first, listOf(output(first).copy(riskDistanceM = 0f)), 17L)
        assertTrue(zero, zero.contains("거리 0.00m"))
    }

    @Test
    fun sameClassObjectsMatchTheirOwnNormalizedBoxRegardlessOfOrder() {
        val second = first.copy(bboxNorm = RectNorm(0.65f, 0.1f, 0.3f, 0.4f))
        val outputs = listOf(output(second).copy(riskDistanceM = 3.5f), output(first))
        val firstLabel = VisualModelFormatter.label(first, outputs, 17L)
        val secondLabel = VisualModelFormatter.label(second, outputs, 17L)
        assertTrue(firstLabel, firstLabel.contains("1.25m"))
        assertFalse(firstLabel, firstLabel.contains("3.50m"))
        assertTrue(secondLabel, secondLabel.contains("3.50m"))
        assertFalse(secondLabel, secondLabel.contains("1.25m"))
    }

    @Test
    fun descriptionReportsFrameAgePartialStatusAndMatchingDepthCount() {
        val description = VisualModelFormatter.describe(
            listOf(first), listOf(output(first), output(first).copy(frameId = 18L)),
            frameId = 17L, ageMs = 1_501L, partial = true,
        )
        assertTrue(description, description.contains("프레임 17 · 결과 나이 1501ms · 부분 결과"))
        assertTrue(description, description.contains("탐지 1개 · 같은 프레임 Depth 1개"))
        assertTrue(description, description.contains("\n1. person"))
        val empty = VisualModelFormatter.describe(emptyList(), emptyList(), null, null, false)
        assertTrue(empty, empty.contains("프레임 대기 · 결과 나이 확인 불가"))
        assertTrue(empty, empty.contains("현재 탐지된 객체가 없습니다."))
    }

    private fun output(
        detection: DetectionCandidate,
        source: DepthSource = DepthSource.ARCORE_RAW_DEPTH,
    ): TrackedObjectDepth {
        val geometry = MaskPolygonExtractor().extract(detection)
        return TrackedObjectDepth(
            frameId = 17L, timestampMs = 100L, trackId = "track-${detection.bboxNorm.x}",
            className = detection.className, detectionConfidence = detection.detectionConfidence,
            bboxNorm = geometry.bboxNorm, polygonNorm = geometry.polygonNorm,
            maskAreaNorm = geometry.maskAreaNorm, centerNorm = geometry.centerNorm,
            bottomContactNorm = geometry.bottomContactNorm, source = source,
            zDistanceM = 1.3f, rayDistanceM = null, groundDistanceM = null, riskDistanceM = 1.25f,
            validSampleCount = 12, validSampleRatio = 0.9f, depthMedianM = 1.3f,
            depthP20M = 1.25f, depthIqrM = 0.1f, trend = Trend.STABLE,
            approachScore = 0f, approachSpeedMps = null, timeToCollisionMs = null,
            confidence = DepthConfidenceBreakdown(0.8f, 1f, 1f, 1f, 1f, 1f, 1f, 1f),
            userFacing = UserFacingDepth(null, MessageLevel.NONE, null),
        )
    }
}
