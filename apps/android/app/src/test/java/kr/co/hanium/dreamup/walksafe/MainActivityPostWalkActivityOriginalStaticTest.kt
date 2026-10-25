package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADD-MOTION-043 / owner decision 3(b): DEBUG activity originals follow the RAW rule (after the walk,
 * while charging). No stillness evidence is wired into any transfer once the walk has ended.
 */
class MainActivityPostWalkActivityOriginalStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun activityOriginalAdmissionUsesTheWalkStateAndChargingInsteadOfStillness() {
        assertTrue(source.contains("private val activityOriginalUploadAdmission = ActivityOriginalUploadAdmissionController()"))
        assertEquals(1, Regex("""ActivityOriginalUploadAdmissionController\(""").findAll(source).count())
        assertFalse(source.contains("physicalStationaryEvidence"))
        assertFalse(source.contains("transferStationaryEvidenceConfirmedAt"))
        assertFalse(source.contains("TransferStationaryEvidencePolicy"))
        val allowed = functionBlock("private fun sensitiveDebugTransferAllowed(")
        val admit = functionBlock("private fun runIfActivityOriginalUploadAllowed(")
        for (block in listOf(allowed, admit)) {
            assertTrue(block.contains("walkState = currentActivityOriginalWalkState()"))
            assertTrue(block.contains("charging = chargingStateProbe::isCharging"))
            assertTrue(block.contains("permissionSessionPolicy.snapshot().mobileNetworkPreference"))
        }
        assertTrue(functionBlock("private fun currentActivityOriginalWalkState(").contains("else WalkSessionState.ACTIVE"))
        val transition = functionBlock("private fun transitionWalkSession(")
        assertTrue(transition.contains("reports.onWalkStateChanged(transition.current.state)"))
        assertTrue(source.contains("activityOriginalUploadAdmission.onWalkStateChanged(state, ::cancelActivityOriginalUploads)"))
        assertFalse(source.contains("activityOriginalUploadAdmission.onSensorSample("))
        assertFalse(source.contains("activityOriginalUploadAdmission.stationarySnapshot("))
    }

    private fun functionBlock(signature: String): String = MainActivityRefactorSource.function(source, signature)
}
