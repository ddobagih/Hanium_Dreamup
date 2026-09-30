package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityPermissionSafetyStopStaticTest {
    private val source =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun activeOrPausedWalkSafetyStopsOnObservedLossOfBothCoreGuidancePermissions() {
        val observation = source
            .substringAfter("private fun applyObservedPermissionStateChange(reason: String) {")
            .substringBefore("private fun isRouteLocationPermissionReady()")

        assertTrue(observation.contains("PermissionDependencyPolicy.evaluate("))
        assertTrue(observation.contains("currentObservedPermissionSnapshot()"))
        assertTrue(observation.contains("sessionSnapshot.state in setOf(WalkSessionState.ACTIVE, WalkSessionState.PAUSED)"))
        assertTrue(observation.contains(").requiresWholeWalkSafetyStop"))
        assertTrue(observation.contains("enterWalkSessionSafetyStopAndCancelOutputs(\"core_guidance_permissions_unavailable\")"))
        assertTrue(observation.contains("enterPermissionRecoveryBarrier("))
    }

    @Test
    fun freshWalkReadinessBlocksWhenBothCoreGuidancePermissionsAreMissing() {
        val readiness = source
            .substringAfter("private fun walkSessionReadinessBlockReason(")
            .substringBefore("private fun missingRequiredWalkSessionPermissions(")

        assertTrue(readiness.contains("PermissionDependencyPolicy.evaluate("))
        assertTrue(readiness.contains("currentObservedPermissionSnapshot()"))
        assertTrue(readiness.contains(").requiresWholeWalkSafetyStop"))
    }
}
