package kr.co.hanium.dreamup.walksafe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugOverlayContinuityTest {
    @Test
    fun smallMovementInterpolatesOnDisplayTimeAndStopsAtObservedTarget() {
        val continuity = DebugOverlayContinuity()
        continuity.update(listOf(box(0f)), 1L, 1_000L, 1_050L)
        continuity.update(listOf(box(20f)), 2L, 1_100L, 1_150L)

        val halfway = continuity.render(1_190L).boxes.single()
        assertEquals(10f, halfway.box.rect.left, 0.001f)
        assertTrue(halfway.interpolated)
        assertEquals(2L, halfway.sourceFrameId)
        assertEquals(1_100L, halfway.sourceCapturedAtElapsedRealtimeMs)
        assertEquals(90L, halfway.sourceAgeMs)
        assertEquals(0L, continuity.render(1_190L).nextRedrawDelayMs)

        assertEquals(20f, continuity.render(1_230L).boxes.single().box.rect.left, 0.001f)
        assertFalse(continuity.render(1_230L).boxes.single().interpolated)
        assertEquals(20f, continuity.render(1_800L).boxes.single().box.rect.left, 0.001f)
    }

    @Test
    fun repeatedSourceNeitherRestartsTransitionNorRenewsSourceAge() {
        val continuity = DebugOverlayContinuity()
        continuity.update(listOf(box(0f)), 1L, 1_000L, 1_000L)
        continuity.update(listOf(box(20f)), 2L, 1_100L, 1_100L)
        continuity.update(listOf(box(20f)), 2L, 1_100L, 1_140L)

        assertEquals(20f, continuity.render(1_180L).boxes.single().box.rect.left, 0.001f)
        continuity.update(listOf(box(20f)), 2L, 2_200L, 2_200L)
        assertEquals(1_100L, continuity.render(2_200L).boxes.single().sourceAgeMs)
        assertTrue(continuity.render(2_300L).boxes.isEmpty())
    }

    @Test
    fun newObservationDuringTransitionStartsFromCurrentlyDisplayedPosition() {
        val continuity = DebugOverlayContinuity()
        continuity.update(listOf(box(0f)), 1L, 1_000L, 1_000L)
        continuity.update(listOf(box(20f)), 2L, 1_100L, 1_100L)
        continuity.update(listOf(box(30f)), 3L, 1_140L, 1_140L)

        assertEquals(10f, continuity.render(1_140L).boxes.single().box.rect.left, 0.001f)
        assertEquals(20f, continuity.render(1_180L).boxes.single().box.rect.left, 0.001f)
        assertEquals(30f, continuity.render(1_220L).boxes.single().box.rect.left, 0.001f)
    }

    @Test
    fun abruptDisplacementAndScaleChangesSnapWithoutSweepingAcrossScreen() {
        val continuity = DebugOverlayContinuity()
        continuity.update(listOf(box(0f)), 1L, 1_000L, 1_000L)
        continuity.update(listOf(box(100f)), 2L, 1_100L, 1_100L)
        assertEquals(100f, continuity.render(1_100L).boxes.single().box.rect.left, 0.001f)
        assertFalse(continuity.render(1_100L).boxes.single().interpolated)

        val resized = box(100f).copy(rect = DebugOverlayContinuity.Rect(100f, 0f, 300f, 100f))
        continuity.update(listOf(resized), 3L, 1_200L, 1_200L)
        assertEquals(resized.rect, continuity.render(1_200L).boxes.single().box.rect)
        assertFalse(continuity.render(1_200L).boxes.single().interpolated)
    }

    @Test
    fun completedEmptyRemovesAllBoxesImmediatelyAndRejectsOlderFrames() {
        val continuity = DebugOverlayContinuity()
        continuity.update(listOf(box(0f)), 1L, 1_000L, 1_000L)
        continuity.update(emptyList(), 2L, 1_100L, 1_100L)
        assertTrue(continuity.render(1_100L).boxes.isEmpty())
        assertNull(continuity.render(1_100L).nextRedrawDelayMs)

        continuity.update(listOf(box(0f)), 1L, 1_000L, 1_110L)
        assertTrue(continuity.render(1_110L).boxes.isEmpty())
    }

    @Test
    fun unmatchedObjectIsRemovedRatherThanHeldAsGhost() {
        val continuity = DebugOverlayContinuity()
        continuity.update(listOf(box(0f), box(200f)), 1L, 1_000L, 1_000L)
        continuity.update(listOf(box(10f)), 2L, 1_100L, 1_100L)

        assertEquals(1, continuity.render(1_100L).boxes.size)
        assertEquals(10f, continuity.render(1_180L).boxes.single().box.rect.left, 0.001f)
    }

    @Test
    fun expirationIsScheduledAndHappensWithoutAnyNewDetectorInput() {
        val continuity = DebugOverlayContinuity()
        continuity.update(listOf(box(0f)), 1L, 1_000L, 1_100L)

        assertEquals(1_100L, continuity.render(1_100L).nextRedrawDelayMs)
        assertEquals(1, continuity.render(2_199L).boxes.size)
        assertTrue(continuity.render(2_200L).boxes.isEmpty())
        assertNull(continuity.render(2_200L).nextRedrawDelayMs)
        continuity.update(listOf(box(0f)), 1L, 1_000L, 2_300L)
        assertTrue(continuity.render(2_300L).boxes.isEmpty())
    }

    @Test
    fun clearForViewportOrSessionChangeDropsTransitionAndPreviousIdentity() {
        val continuity = DebugOverlayContinuity()
        continuity.update(listOf(box(0f)), 5L, 1_000L, 1_000L)
        continuity.update(listOf(box(20f)), 6L, 1_100L, 1_100L)
        continuity.clear()
        assertTrue(continuity.render(1_120L).boxes.isEmpty())

        continuity.update(listOf(box(10f)), 1L, 1_130L, 1_130L)
        assertEquals(10f, continuity.render(1_130L).boxes.single().box.rect.left, 0.001f)
        assertFalse(continuity.render(1_130L).boxes.single().interpolated)
    }

    @Test
    fun expiredOrFutureCaptureDoesNotAppearOrKeepPreviousBoxes() {
        val continuity = DebugOverlayContinuity()
        continuity.update(listOf(box(0f)), 1L, 1_000L, 1_000L)
        continuity.update(listOf(box(10f)), 2L, 1_100L, 2_300L)
        assertTrue(continuity.render(2_300L).boxes.isEmpty())

        continuity.update(listOf(box(20f)), 3L, 2_500L, 2_400L)
        assertTrue(continuity.render(2_400L).boxes.isEmpty())
        assertTrue(continuity.render(2_600L).boxes.isEmpty())
    }

    @Test
    fun partialToCompleteOfSameCaptureCanAddBoxesAndClearThemWithoutRenewingAge() {
        val continuity = DebugOverlayContinuity()
        continuity.update(listOf(box(0f)), 1L, 1_000L, 1_050L, sourceComplete = false)
        continuity.update(listOf(box(0f), box(200f)), 1L, 1_000L, 1_200L)
        assertEquals(2, continuity.render(1_200L).boxes.size)
        assertTrue(continuity.render(1_200L).boxes.all { it.sourceAgeMs == 200L })
        continuity.update(listOf(box(0f)), 1L, 1_000L, 1_210L, sourceComplete = false)
        assertEquals(2, continuity.render(1_210L).boxes.size)

        continuity.update(listOf(box(0f)), 2L, 1_300L, 1_300L, sourceComplete = false)
        continuity.update(emptyList(), 2L, 1_300L, 1_400L)
        assertTrue(continuity.render(1_400L).boxes.isEmpty())
    }

    @Test
    fun completingUnchangedPartialDoesNotRestartItsTransition() {
        val continuity = DebugOverlayContinuity()
        continuity.update(listOf(box(0f)), 1L, 1_000L, 1_000L)
        continuity.update(listOf(box(20f)), 2L, 1_100L, 1_100L, sourceComplete = false)
        continuity.update(listOf(box(20f)), 2L, 1_100L, 1_140L)

        assertEquals(20f, continuity.render(1_180L).boxes.single().box.rect.left, 0.001f)
        assertFalse(continuity.render(1_180L).boxes.single().interpolated)
    }

    @Test
    fun completingPartialWithNewBoxAndLabelKeepsExistingTransitionDeadline() {
        val continuity = DebugOverlayContinuity()
        continuity.update(listOf(box(0f)), 1L, 1_000L, 1_000L)
        continuity.update(listOf(box(20f)), 2L, 1_100L, 1_100L, sourceComplete = false)
        val relabeled = box(20f).copy(label = "person 91%")
        continuity.update(listOf(relabeled, box(200f)), 2L, 1_100L, 1_140L)

        val complete = continuity.render(1_180L).boxes
        assertEquals(listOf(20f, 200f), complete.map { it.box.rect.left })
        assertEquals("person 91%", complete.first().box.label)
        assertTrue(complete.none { it.interpolated })
    }

    @Test
    fun additionalSameClassBoxBeforeExistingBoxCannotStealItsTransition() {
        val continuity = DebugOverlayContinuity()
        continuity.update(listOf(box(0f)), 1L, 1_000L, 1_000L)
        continuity.update(listOf(box(20f)), 2L, 1_100L, 1_100L, sourceComplete = false)
        val relabeled = box(20f).copy(label = "person 91%")
        continuity.update(listOf(box(30f), relabeled), 2L, 1_100L, 1_140L)

        val immediately = continuity.render(1_140L).boxes
        assertEquals(30f, immediately.first().box.rect.left, 0.001f)
        assertEquals(10f, immediately.last().box.rect.left, 0.001f)
        assertEquals("person 91%", immediately.last().box.label)
        val finished = continuity.render(1_180L).boxes
        assertEquals(listOf(30f, 20f), finished.map { it.box.rect.left })
        assertTrue(finished.none { it.interpolated })
    }

    @Test
    fun sameClassBoxesMatchPreviousRectanglesAtMostOnce() {
        val continuity = DebugOverlayContinuity()
        continuity.update(listOf(box(0f), box(30f)), 1L, 1_000L, 1_000L)
        continuity.update(listOf(box(10f), box(20f)), 2L, 1_100L, 1_100L)

        assertEquals(listOf(5f, 25f), continuity.render(1_140L).boxes.map { it.box.rect.left })
    }

    @Test
    fun differentClassAndBestOutputAreNeverInterpolatedWithEachOther() {
        val continuity = DebugOverlayContinuity()
        continuity.update(listOf(box(0f)), 1L, 1_000L, 1_000L)
        val differentClass = box(10f).copy(className = "car")
        val bestOutput = box(20f).copy(best = true)
        continuity.update(listOf(differentClass, bestOutput), 2L, 1_100L, 1_100L)

        assertEquals(listOf(10f, 20f), continuity.render(1_100L).boxes.map { it.box.rect.left })
        assertTrue(continuity.render(1_100L).boxes.none { it.interpolated })
    }

    @Test
    fun invalidRectangleCannotPoisonNextValidObservation() {
        val continuity = DebugOverlayContinuity()
        val invalid = box(0f).copy(rect = DebugOverlayContinuity.Rect(Float.NaN, 0f, 100f, 100f))
        continuity.update(listOf(invalid), 1L, 1_000L, 1_000L)
        assertTrue(continuity.render(1_000L).boxes.isEmpty())
        continuity.update(listOf(box(10f)), 2L, 1_100L, 1_100L)
        assertEquals(10f, continuity.render(1_100L).boxes.single().box.rect.left, 0.001f)
    }

    @Test
    fun legacyInputCanBeDisplayedWithoutInventingMotion() {
        val continuity = DebugOverlayContinuity()
        continuity.update(listOf(box(0f)), 1L, 1_000L, 1_000L, animate = false)
        continuity.update(listOf(box(20f)), 2L, 1_100L, 1_100L, animate = false)

        assertEquals(20f, continuity.render(1_100L).boxes.single().box.rect.left, 0.001f)
        assertFalse(continuity.render(1_100L).boxes.single().interpolated)
        assertTrue(continuity.render(2_300L).boxes.isEmpty())
    }

    private fun box(left: Float) = DebugOverlayContinuity.Box(
        rect = DebugOverlayContinuity.Rect(left, 0f, left + 100f, 100f),
        label = "person 90%",
        best = false,
        className = "person",
    )
}
