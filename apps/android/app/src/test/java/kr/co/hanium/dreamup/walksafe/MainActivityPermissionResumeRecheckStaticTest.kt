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
        val resume = ReportStaticSourceInspector.functionBlock(
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
                "!permissionRecoveryPrerequisitesReady(startupCapabilityDecision)",
                "persistPermissionRecoveryGate()",
                "if (permissionRecoveryGate.blocksAutomaticResourceStart) {",
                "completePermissionRecoveryRecheckIfPossible()",
                "return true",
                "ensurePermissionsThenStart()",
            ),
        )
        assertFalse(resume.contains("acknowledgeExplicitResume()"))
        assertFalse(source.contains("permissionRecoveryGate.acknowledgeExplicitResume()"))
    }

    @Test
    fun explicitResumeAndAutomaticRecheckShareTheFullPrerequisitePredicate() {
        val predicate = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun permissionRecoveryPrerequisitesReady",
        )
        listOf(
            "if (decision == null) return false",
            "firstRunOnboardingComplete()",
            "currentReporterUserId() != null",
            "isGatewaySessionReadyForCurrentActor()",
            "decision.mayConfirmAndStart",
            "walkSessionReadinessBlockReason(decision) == null",
        ).forEach { term -> assertTrue(term, predicate.contains(term)) }
        val recheck = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun completePermissionRecoveryRecheckIfPossible",
        )
        assertTrue(recheck.contains("permissionRecoveryPrerequisitesReady(decision)"))
        assertEquals(
            3,
            Regex(Regex.escape("permissionRecoveryPrerequisitesReady(")).findAll(source).count(),
        )
    }
}
