package kr.co.hanium.dreamup.walksafe.inference

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Fixed images only: no activity, camera, account or navigation interaction. */
@RunWith(AndroidJUnit4::class)
class CameraModelPositiveDeviceTest {
    @Test fun configuredModelProducesPositiveDetections() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixtures = RuntimeCalibrationFixtures(context)
        for (delegate in listOf("gpu", "cpu")) {
        val load = TfliteAndroidFrameDetector.createWithStatus(context,
            runtimeOverride = ModelRuntimeOptions(delegate = delegate, numThreads = 4),
            preprocessingStrategy = YuvPreprocessingStrategy.FUSED_YUV_TO_TENSOR)
        val detector = requireNotNull(load.detector) { load.reason }
        detector.use {
            var positives = 0
            for (fixture in fixtures.loadManifest().fixtures.filter {
                it.required && RuntimeCalibrationFixtureRole.POSITIVE in it.roles
            }) {
                val result = detector.detectCalibrationImage(fixtures.loadArgb(fixture))
                val raw = requireNotNull(detector.copyLastUnifiedOutputForTest())
                val scores = raw.copyOfRange(4 * 12096, raw.size)
                assertTrue("Detection boxes collapsed after undoing letterboxing: ${fixture.id}",
                    result.detections.any { it.bboxNorm.width > 0.01f && it.bboxNorm.height > 0.01f })
                Log.i("CameraModelPositiveTest", "delegate=$delegate fixture=${fixture.id} count=${result.detections.size} ms=${result.timing.modelInferenceMs} scores=${scores.minOrNull()}..${scores.maxOrNull()} invalid=${scores.count { !it.isFinite() || it !in 0f..1f }} classes=${result.detections.map { it.className }}")
                if (result.detections.isNotEmpty()) positives++
            }
            assertTrue("Configured model returned zero for every positive fixture", positives > 0)
        }
        }
    }
}
