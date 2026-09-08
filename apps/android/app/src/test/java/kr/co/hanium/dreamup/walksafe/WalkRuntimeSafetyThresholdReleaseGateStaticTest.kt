package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two of the twelve safe-stop causes — FRAME_AGE_EXCEEDED and INFERENCE_LATENCY_EXCEEDED — stay
 * inert while `productionThresholdProfile` is null, because no approved timing threshold exists
 * yet. That is deliberate and `WalkRuntimeSafetyCoordinatorTest` locks it.
 *
 * What is not deliberate is that the gap is silent. A release build already refuses to exist
 * without a gateway origin; it must refuse the same way while those two stops cannot fire, so the
 * missing threshold has to be decided rather than shipped past.
 */
class WalkRuntimeSafetyThresholdReleaseGateStaticTest {
    private val buildScript = File("build.gradle.kts").readText()
    private val coordinatorSource = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/device/WalkRuntimeSafetyCoordinator.kt",
    ).readText()

    @Test
    fun releaseBuildRefusesUnapprovedRuntimeSafetyThresholds() {
        val guard = buildScript
            .substringAfter("val validateWalkSafeRuntimeSafetyThresholds by tasks.registering {")
            .substringBefore("}")

        assertTrue(guard.contains("walkSafeRuntimeSafetyThresholdApproved"))
        assertTrue(buildScript.contains("preReleaseBuild"))
        assertTrue(buildScript.contains("dependsOn(validateWalkSafeRuntimeSafetyThresholds)"))
    }

    @Test
    fun theGuardReadsTheSameDeclarationTheRuntimeReads() {
        // A guard that checks its own copy of the value would pass while the runtime ships null.
        assertTrue(
            buildScript.contains("device/WalkRuntimeSafetyCoordinator.kt"),
        )
        assertTrue(
            buildScript.contains("productionThresholdProfile"),
        )
        assertTrue(
            coordinatorSource.contains("val productionThresholdProfile"),
        )
    }

    @Test
    fun debugBuildsStillRunWithoutApprovedThresholds() {
        // Field testing has to keep working before the thresholds are decided; only release stops.
        val guard = buildScript
            .substringAfter("val validateWalkSafeRuntimeSafetyThresholds by tasks.registering {")
            .substringBefore("if (name == \"preReleaseBuild\")")

        assertTrue(!guard.contains("preDebugBuild"))
    }
}
