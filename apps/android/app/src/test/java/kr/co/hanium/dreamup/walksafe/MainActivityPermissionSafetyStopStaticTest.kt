package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityPermissionSafetyStopStaticTest {
    private val source =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun activeOrPausedWalkSafetyStopsOnObservedLossOfBothCoreGuidancePermissions() {
        val observation = MainActivityRefactorSource.function(source, "fun applyObservedPermissionStateChange(")

        assertTrue(observation.contains("PermissionDependencyPolicy.evaluate("))
        assertTrue(observation.contains("currentObservedPermissionSnapshot()"))
        assertTrue(observation.contains("sessionSnapshot.state in setOf(WalkSessionState.ACTIVE, WalkSessionState.PAUSED)"))
        assertTrue(observation.contains(").requiresWholeWalkSafetyStop"))
        assertTrue(observation.contains("runtime.safetyStop(\"core_guidance_permissions_unavailable\")"))
        assertTrue(observation.contains("enterPermissionRecoveryBarrier("))
    }

    @Test
    fun freshWalkReadinessBlocksWhenBothCoreGuidancePermissionsAreMissing() {
        val readiness = MainActivityRefactorSource.function(source, "fun walkSessionReadinessBlockReason(")

        assertTrue(readiness.contains("PermissionDependencyPolicy.evaluate("))
        assertTrue(readiness.contains("sources.observedPermissions()"))
        assertTrue(MainActivityRefactorSource.composition(source, "walkReadinessBlockController").contains("override fun observedPermissions() = currentObservedPermissionSnapshot()"))
        assertTrue(readiness.contains(").requiresWholeWalkSafetyStop"))
    }
}
