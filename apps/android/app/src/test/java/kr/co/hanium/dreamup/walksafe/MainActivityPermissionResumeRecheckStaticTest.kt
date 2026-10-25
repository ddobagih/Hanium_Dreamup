package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S02-16: the explicit resume tap re-observes permissions and identity before any start. */
class MainActivityPermissionResumeRecheckStaticTest {
    private val source =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun explicitResumeUsesPressTimePermissionsAndIdentityBeforeStarting() {
        val resume = MainActivityRefactorSource.function(
            source,
            "private fun resumePermissionRecoveryFromExplicitUserAction",
        )
        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                resume,
                "PermissionRecoveryGateState.AWAITING_EXPLICIT_RESUME",
                "acknowledgeExplicitResume(",
                "observed = currentObservedPermissionSnapshot()",
                "requiredPermissions = requiredStartWalkObservedPermissions()",
                "!permissionRecoveryPrerequisitesReady(readiness.decision())",
                "persistPermissionRecoveryGate()",
                "if (permissionRecoveryGate.blocksAutomaticResourceStart) {",
                "completePermissionRecoveryRecheckIfPossible()",
                "return true",
                "runtime.startAfterPermissionRecovery()",
            ),
        )
        val binding = MainActivityRefactorSource.composition(source, "permissionFlowController")
        assertTrue(binding.contains("override fun decision() = startupReadinessEvidence.currentDecision"))
        assertTrue(binding.contains("override fun startAfterPermissionRecovery() { ensurePermissionsThenStart() }"))
        assertFalse(resume.contains("acknowledgeExplicitResume()"))
        assertFalse(source.contains("permissionRecoveryGate.acknowledgeExplicitResume()"))
    }

    @Test
    fun explicitResumeAndAutomaticRecheckShareTheFullPrerequisitePredicate() {
        val predicate = MainActivityRefactorSource.function(
            source,
            "private fun permissionRecoveryPrerequisitesReady",
        )
        listOf(
            "if (decision == null) return false",
            "authority.firstRunComplete()",
            "authority.currentActorId() != null",
            "readiness.gatewayReady()",
            "decision.mayConfirmAndStart",
            "readiness.blockReason(decision) == null",
        ).forEach { term -> assertTrue(term, predicate.contains(term)) }
        val recheck = MainActivityRefactorSource.function(
            source,
            "private fun completePermissionRecoveryRecheckIfPossible",
        )
        assertTrue(recheck.contains("permissionRecoveryPrerequisitesReady(decision)"))
        val binding = MainActivityRefactorSource.composition(source, "permissionFlowController")
        assertTrue(binding.contains("override fun firstRunComplete() = firstRunOnboardingComplete()"))
        assertTrue(binding.contains("override fun currentActorId() = currentReporterUserId()"))
        assertTrue(binding.contains("override fun gatewayReady() = isGatewaySessionReadyForCurrentActor()"))
        assertTrue(binding.contains("override fun blockReason(decision: WalkSafeStartupCapabilityDecision) = walkSessionReadinessBlockReason(decision)"))
        val permissionSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/session/permission/PermissionFlowController.kt").readText()
        assertEquals(
            3,
            Regex(Regex.escape("permissionRecoveryPrerequisitesReady(")).findAll(permissionSource).count(),
        )
    }
}
