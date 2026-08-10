package kr.co.hanium.dreamup.walksafe.inference

import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import org.junit.Assert.assertEquals
import org.junit.Test

class YuvImagePreprocessorTest {
    @Test
    fun modelRectToImageRectRemovesVerticalLetterboxPaddingForLandscapeImage() {
        val transform = LetterboxTransform(
            imageWidth = 1280,
            imageHeight = 720,
            modelSize = 640,
            scale = 0.5f,
            padX = 0f,
            padY = 140f,
        )
        val modelRect = RectNorm(
            x = 0.25f,
            y = 230f / 640f,
            width = 0.5f,
            height = 180f / 640f,
        )

        val imageRect = transform.modelRectToImageRect(modelRect)

        assertEquals(0.25f, imageRect.x, 0.001f)
        assertEquals(0.25f, imageRect.y, 0.001f)
        assertEquals(0.50f, imageRect.width, 0.001f)
        assertEquals(0.50f, imageRect.height, 0.001f)
    }

    @Test
    fun modelRectToImageRectRemovesHorizontalLetterboxPaddingForPortraitImage() {
        val transform = LetterboxTransform(
            imageWidth = 720,
            imageHeight = 1280,
            modelSize = 640,
            scale = 0.5f,
            padX = 140f,
            padY = 0f,
        )
        val modelRect = RectNorm(
            x = 230f / 640f,
            y = 0.25f,
            width = 180f / 640f,
            height = 0.5f,
        )

        val imageRect = transform.modelRectToImageRect(modelRect)

        assertEquals(0.25f, imageRect.x, 0.001f)
        assertEquals(0.25f, imageRect.y, 0.001f)
        assertEquals(0.50f, imageRect.width, 0.001f)
        assertEquals(0.50f, imageRect.height, 0.001f)
    }

    @Test
    fun modelRectToImageRectClampsBoxesThatOverlapLetterboxPadding() {
        val transform = LetterboxTransform(
            imageWidth = 1280,
            imageHeight = 720,
            modelSize = 640,
            scale = 0.5f,
            padX = 0f,
            padY = 140f,
        )
        val modelRect = RectNorm(
            x = 0.25f,
            y = 0.10f,
            width = 0.50f,
            height = 0.40f,
        )

        val imageRect = transform.modelRectToImageRect(modelRect)

        assertEquals(0.25f, imageRect.x, 0.001f)
        assertEquals(0.00f, imageRect.y, 0.001f)
        assertEquals(0.50f, imageRect.width, 0.001f)
        assertEquals(0.50f, imageRect.height, 0.001f)
    }
}
