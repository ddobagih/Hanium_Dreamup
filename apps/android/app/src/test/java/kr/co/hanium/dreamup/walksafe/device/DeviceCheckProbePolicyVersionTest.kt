package kr.co.hanium.dreamup.walksafe.device

import java.io.File
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A stored pass is bound to the probe policy that produced it, so tightening a threshold without
 * moving the version leaves every device that already passed carrying a verdict the new rules
 * would not grant. After the 2026-09-08 changes SM-A716S restored FULL under an unchanged binding
 * digest, which is what this guards.
 */
class DeviceCheckProbePolicyVersionTest {
    private val storeSource = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/device/AndroidDeviceCheckResultStore.kt",
    ).readText()

    @Test
    fun tighteningTheThresholdsMovedTheProbePolicyVersion() {
        assertNotEquals(
            "20260905-interactive-v1",
            POST_LOGIN_DEVICE_CHECK_PROBE_POLICY_VERSION,
        )
    }

    @Test
    fun theBindingDigestCoversTheProbePolicyVersion() {
        // Without this the version could move and stored passes would survive it anyway.
        val digest = storeSource
            .substringAfter("private fun PostLoginDeviceCheckResultBinding.sha256OrNull()")
            .substringBefore("private fun isValidOptionalPair")

        assertTrue(digest.contains("probePolicyVersion"))
    }
}
