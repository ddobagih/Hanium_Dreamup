package kr.co.hanium.dreamup.walksafe.inference

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** Actual SDK asset/factory boundary; only in-memory config changes, no asset or account writes. */
@RunWith(AndroidJUnit4::class)
class PrimaryModelFailClosedDeviceTest {
    @Test fun missingPrimaryDoesNotSelectThePresentLegacyConfiguration() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = TwoModelRuntimeConfig.load(context)
        val primary = requireNotNull(original.unifiedWalksafe)
        val config = original.copy(fallbackModelKey = null, unifiedWalksafe = primary.copy(
            enabled = true, asset = "models/controlled-missing-primary.tflite", integrityFailure = null,
            runtime = primary.runtime.copy(delegate = "cpu", fallbackToCpu = false)))
        assertUnavailable(config, DetectorLoadFailureCode.ASSET_MISSING)
    }

    @Test fun changedPrimaryHashDoesNotSelectThePresentLegacyConfiguration() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = TwoModelRuntimeConfig.load(context)
        val primary = requireNotNull(original.unifiedWalksafe)
        val originalHash = requireNotNull(primary.artifactSha256)
        val changedHash = (if (originalHash[0] == '0') "1" else "0") + originalHash.substring(1)
        val config = original.copy(fallbackModelKey = null, unifiedWalksafe = primary.copy(
            enabled = true, artifactSha256 = changedHash, integrityFailure = null,
            runtime = primary.runtime.copy(delegate = "cpu", fallbackToCpu = false)))
        assertUnavailable(config, DetectorLoadFailureCode.HASH_MISMATCH)
    }

    private fun assertUnavailable(config: TwoModelRuntimeConfig, expected: DetectorLoadFailureCode) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val loaded = TfliteAndroidFrameDetector.createWithStatus(context, config,
            preprocessingStrategy = YuvPreprocessingStrategy.FUSED_YUV_TO_TENSOR)
        try {
            assertEquals(TwoModelRuntimeConfig.UNIFIED_MODEL_KEY, config.primaryModelKey)
            assertNull(config.fallbackModelKey)
            assertFalse(loaded.detectorAvailable)
            assertFalse(loaded.fallbackUsed)
            assertNull(loaded.modelKey)
            assertNull(loaded.detector)
            assertEquals(expected, loaded.failureCode)
        } finally {
            // Release an unexpected native result even when an assertion reports a regression.
            loaded.detector?.close()
        }
    }
}
