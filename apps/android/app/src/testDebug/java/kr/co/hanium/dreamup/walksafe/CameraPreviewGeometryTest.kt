package kr.co.hanium.dreamup.walksafe

import org.junit.Assert.*
import org.junit.Test

class CameraPreviewGeometryTest {
    @Test fun portraitPreviewKeepsTheWholeGpuImageInsteadOfCroppingItsSides() {
        val image = CameraPreviewGeometry.orientedSize(1920, 1080, 1)
        assertEquals(CameraPreviewGeometry.Bounds(0, 120, 1080, 1920), CameraPreviewGeometry.fit(1080, 2160, image))
    }

    @Test fun bothPortraitRotationsAndBothLandscapeRotationsShareTheirCorrectAspect() {
        for (turns in 0..3) {
            val size = CameraPreviewGeometry.orientedSize(1920, 1080, turns)
            val expected = if (turns % 2 == 0) CameraPreviewGeometry.Bounds(240, 0, 1920, 1080)
                else CameraPreviewGeometry.Bounds(0, 120, 1080, 1920)
            val parentWidth = if (turns % 2 == 0) 2400 else 1080
            val parentHeight = if (turns % 2 == 0) 1080 else 2160
            assertEquals(expected, CameraPreviewGeometry.fit(parentWidth, parentHeight, size))
        }
    }

    @Test fun actualFourByThreeTextureKeepsItsOwnAspectWithoutAssumingSixteenByNine() {
        assertEquals(CameraPreviewGeometry.Bounds(0, 360, 1080, 1440),
            CameraPreviewGeometry.fit(1080, 2160, CameraPreviewGeometry.orientedSize(1440, 1080, 3)))
    }

    @Test fun resizeRecomputesTheFitAndRoundingNeverOverflowsTheAvailableArea() {
        val size = CameraPreviewGeometry.orientedSize(1920, 1080, 0)
        assertEquals(CameraPreviewGeometry.Bounds(0, 0, 1920, 1080), CameraPreviewGeometry.fit(1920, 1080, size))
        val fit = CameraPreviewGeometry.fit(1001, 2001, size)
        assertEquals(1001, fit.width)
        assertEquals(563, fit.height)
        assertEquals(0, fit.left)
        assertEquals(719, fit.top)
        assertTrue(fit.top + fit.height <= 2001)
    }

    @Test fun pixelCrossProductsDoNotOverflowOnLargeDimensions() {
        assertEquals(CameraPreviewGeometry.Bounds(0, 0, Int.MAX_VALUE, Int.MAX_VALUE),
            CameraPreviewGeometry.fit(Int.MAX_VALUE, Int.MAX_VALUE, CameraPreviewGeometry.ImageSize(Int.MAX_VALUE, Int.MAX_VALUE)))
    }

    @Test fun missingImageUsesInitialSurfaceButInvalidSourceCannotBecomeAValidAspect() {
        assertEquals(CameraPreviewGeometry.Bounds(0, 0, 1080, 2160), CameraPreviewGeometry.fit(1080, 2160, null))
        for (input in listOf(Triple(0, 1080, 0), Triple(1920, -1, 1), Triple(1920, 1080, -1), Triple(1920, 1080, 4))) {
            assertNull(CameraPreviewGeometry.orientedSize(input.first, input.second, input.third))
        }
        assertEquals(CameraPreviewGeometry.Bounds(0, 0, 0, 0), CameraPreviewGeometry.fit(0, 1080, null))
    }

    @Test fun aLayoutRequestCannotAuthorizeAnOldSurfaceEvenWhenItsRevisionHasCommitted() {
        val committed = CameraPreviewGeometry.Viewport(1080, 1920, 2L)
        assertFalse(CameraPreviewGeometry.matches(committed, 1080, 2160, 2L))
        assertFalse(CameraPreviewGeometry.matches(committed, 1080, 1920, 3L))
        assertFalse(CameraPreviewGeometry.matches(null, 1080, 1920, 2L))
        assertTrue(CameraPreviewGeometry.matches(committed, 1080, 1920, 2L))
    }

    @Test fun sameSizeReconnectionOrHalfTurnStillRequiresTheCurrentRequestCommit() {
        val oldSession = CameraPreviewGeometry.Viewport(1080, 1920, 1L)
        assertFalse(CameraPreviewGeometry.matches(oldSession, 1080, 1920, 2L))
        assertTrue(CameraPreviewGeometry.matches(oldSession.copy(revision = 2L), 1080, 1920, 2L))
    }

    @Test fun aRotationRejectedOnUiCanRetryWhenTheDisplayReturnsBeforeTheNextGlFrame() {
        val requests = CameraPreviewGeometry.Requests()
        val first = requireNotNull(requests.request(1, 0))
        assertFalse(requests.shouldRequest(1, 0))
        assertNull(requests.request(1, 0)) // Normal pending frames coalesce.
        requests.reject(first) // UI observed rotation 1, but GL's next observation is 0 again.
        assertNull(requests.current(1, 0))
        val retried = requireNotNull(requests.request(1, 0))
        assertTrue(retried.revision > first.revision)
        assertTrue(requests.isCurrent(retried))
        assertFalse(CameraPreviewGeometry.matches(CameraPreviewGeometry.Viewport(1080, 1920, first.revision),
            1080, 1920, retried.revision))
    }

    @Test fun aLateUiRejectionCannotRetireTheNewerRotationOrSessionRequest() {
        val requests = CameraPreviewGeometry.Requests()
        val portrait = requireNotNull(requests.request(1, 0))
        val landscape = requireNotNull(requests.request(1, 1))
        requests.reject(portrait)
        assertTrue(requests.isCurrent(landscape))
        assertFalse(requests.shouldRequest(1, 1))
        val reconnected = requireNotNull(requests.request(2, 1))
        requests.reject(landscape)
        assertTrue(requests.isCurrent(reconnected))
        assertNull(requests.current(1, 1))
    }

    @Test fun aRecreatedSurfaceNeedsAFreshCommitEvenWithTheSameEpochRotationAndDimensions() {
        val requests = CameraPreviewGeometry.Requests()
        val old = requireNotNull(requests.request(1, 0))
        requests.reset()
        assertFalse(requests.isCurrent(old))
        val recreated = requireNotNull(requests.request(1, 0))
        requests.reject(old)
        assertTrue(requests.isCurrent(recreated))
        assertTrue(recreated.revision > old.revision)
        assertFalse(CameraPreviewGeometry.matches(CameraPreviewGeometry.Viewport(1080, 1920, old.revision),
            1080, 1920, recreated.revision))
    }
}
