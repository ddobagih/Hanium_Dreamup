package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class MainActivityCameraXCropRectStaticTest {
    @Test
    fun cameraXCopiesUseProxyCropWhileArCoreKeepsItsImageCrop() {
        val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        val copyArguments = Regex("OwnedYuvFrame\\.copyOf\\(([^)]*)\\)")
            .findAll(source)
            .map { it.groupValues[1] }
            .toList()

        assertEquals(
            listOf(
                "mediaImage, imageProxy.cropRect",
                "mediaImage, imageProxy.cropRect",
                "cameraImage",
            ),
            copyArguments,
        )
    }
}
