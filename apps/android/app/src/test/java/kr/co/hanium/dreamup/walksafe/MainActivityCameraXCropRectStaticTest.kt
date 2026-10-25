package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityCameraXCropRectStaticTest {
    @Test
    fun cameraXCopiesUseProxyCropWhileArCoreKeepsItsImageCrop() {
        val main = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        assertTrue(MainActivityRefactorSource.member(main, "analyzeCameraFallbackFrame")
            .contains("primaryCameraFallbackDetectorWorker.analyze(frameCapture"))
        assertTrue(MainActivityRefactorSource.member(main, "copyRuntimeRecognitionInput")
            .contains("frameRecognition.copyFrameRecognitionInput(image, frame"))
        assertTrue(MainActivityRefactorSource.member(main, "startPostLoginCameraPipelinePreflight").contains("postLoginDeviceProbes.startCamera(binding)"))
        assertTrue(MainActivityRefactorSource.property(main, "postLoginDeviceProbes").contains("postLoginDeviceProbesHolder.value"))
        assertTrue(MainActivityRefactorSource.property(main, "postLoginDeviceProbesHolder").contains("AndroidPostLoginDeviceProbes.android("))
        val fallback = MainActivityRefactorSource.member(File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/runtime/camera/CameraFallbackController.kt").readText(), "copyOwnedFrame", occurrence = 1)
        val preflight = MainActivityRefactorSource.member(File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/device/postlogin/AndroidPostLoginDeviceProbes.kt").readText(), "requestBind", occurrence = 1)
        val ar = MainActivityRefactorSource.member(File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/frame/ArFrameRecognitionCapture.kt").readText(), "copyFrameRecognitionInput")
        val copy = Regex("OwnedYuvFrame\\.copyOf\\(([^\\n]*)\\)")
        // Each actual pixel source has one copy site: CameraX carries the proxy crop; AR uses Image's crop.
        val arguments = listOf(fallback, preflight, ar).map { owner ->
            val calls = copy.findAll(owner).toList()
            assertEquals(1, calls.size)
            calls.single().groupValues[1].removeSuffix(" }")
        }
        assertEquals(listOf("requireNotNull(image), proxy.cropRect", "it, proxy.cropRect", "image"), arguments)
    }
}
