package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** S14-14 source connection only; this does not simulate an Android permission revocation. */
class MainActivityGatewayWalkFinalPermissionStaticTest {
    @Test
    fun finalServerStartResultRechecksMandatoryPermissionsBeforeCollectingReadiness() {
        val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        val finalCheck = source.substringAfter("private fun currentGatewayWalkStartReadinessOrNull(")
            .substringBefore("private fun isGatewayWalkOperationCurrent(")
        val permissions = finalCheck.indexOf("missingRequiredWalkSessionPermissions(WalkSessionAction.START_WALK)")
        val readiness = finalCheck.indexOf("captureWalkSessionReadiness(")
        assertTrue("Final server response must recheck microphone as well as camera/location", permissions >= 0 && readiness > permissions)
        assertTrue(finalCheck.substring(permissions, readiness).contains("isNotEmpty()) return null"))
    }
}
