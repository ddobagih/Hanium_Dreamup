package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S03-13 Main consumer wiring only; these tests do not execute an Activity or Android settings. */
class MainActivityPermissionRecoveryBindingStaticTest {
    private val source = sequenceOf(
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"),
        File("app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"),
    ).first { it.isFile }.readText()

    @Test
    fun fullRecheckPublishesOnlyItsBoundActorSessionWalkAndOriginalRequest() {
        val recheck = compact(method("completePermissionRecoveryRecheckIfPossible"))
        assertTrue(recheck.contains("valrecheckBinding=beginPermissionRecoveryRecheck()"))
        assertTrue(recheck.contains("resultBinding=recheckBinding"))
        assertTrue(
            recheck.contains(
                "currentPermissionRecoveryBinding(recheckBinding.recheckRequestId)==recheckBinding",
            ),
        )
        val binding = compact(method("currentPermissionRecoveryBinding"))
        listOf(
            "authority.currentActorId()",
            "GatewaySessionProcessCoordinator.snapshot()",
            "actorBindingSha256=actorDigest(actorId.toByteArray(Charsets.UTF_8))",
            "gatewaySessionGeneration=gateway.generation",
            "walkEpoch=authority.lifecycleOrNull()!!.snapshot().epoch",
            "recheckRequestId=recheckRequestId",
        ).forEach { assertTrue("missing original scope source: $it", binding.contains(it)) }
        assertFalse("a scope getter must preserve the request identity", binding.contains("UUID"))

        val beginWrapper = compact(MainActivityRefactorSource.member(source, "beginPermissionRecoveryRecheck"))
        assertTrue(beginWrapper.contains("=permissionFlowController.beginPermissionRecoveryRecheck()"))
        val begin = compact(method("beginPermissionRecoveryRecheck"))
        assertTrue(begin.contains("PermissionRecoveryGateState.AWAITING_EXPLICIT_RESUME"))
        assertTrue(begin.contains("currentPermissionRecoveryBinding(existing.recheckRequestId)==existing"))
        assertBefore(begin, "returnexisting", "newRecheckRequestId()")
        assertTrue(begin.contains("permissionRecoveryGate.recheckRequired(binding)"))

        val invalidate = compact(method("invalidatePermissionRecoveryScopeIfChanged"))
        assertBefore(invalidate, "if(!permissionRecoveryGate.blocksAutomaticResourceStart)return", "permissionRecoveryGate.recheckRequired()")
        assertTrue(invalidate.contains("currentPermissionRecoveryBinding(binding.recheckRequestId)==binding"))
        val sessionChangedWrapper = compact(MainActivityRefactorSource.member(source, "onGatewayProcessSessionChanged"))
        assertBefore(sessionChangedWrapper,
            "actorSessionLifecycleCoordinator.onGatewayProcessSessionChanged(snapshot,consentAuthorityChanged)",
            "refreshGuidanceDiagnosticAuthority()")
        val sessionLifecycleOwner = File("src/main/java/kr/co/hanium/dreamup/walksafe/session/gateway/ActorSessionLifecycleCoordinator.kt").readText()
        assertTrue(compact(MainActivityRefactorSource.member(sessionLifecycleOwner, "onGatewayProcessSessionChanged"))
            .contains("permissions.invalidateRecoveryScope()"))
        assertTrue(compact(MainActivityRefactorSource.composition(source, "actorSessionLifecycleCoordinator"))
            .contains("overridefuninvalidateRecoveryScope(){invalidatePermissionRecoveryScopeIfChanged()}"))
        assertTrue(compact(method("transitionWalkSession")).contains("effects.onEpochChanged()"))
        assertTrue(compact(MainActivityRefactorSource.composition(source, "walkSessionTransitionController"))
            .contains("invalidatePermissionRecoveryScopeIfChanged()"))
        assertTrue(compact(method("updatePermissionRecoveryUi")).contains("invalidatePermissionRecoveryScopeIfChanged()"))
        assertTrue(compact(method("restorePermissionRecoveryGateFromPrefs")).contains("preferences.restoreRecovery()"))
        val preferences = File("src/main/java/kr/co/hanium/dreamup/walksafe/session/permission/AndroidPermissionPreferences.kt").readText()
        assertTrue(compact(preferences).contains("PermissionRecoveryGate.restoredBlocked(affected)"))
        val wiring = compact(MainActivityRefactorSource.composition(source, "permissionFlowController"))
        assertTrue(wiring.contains("overridefuncurrentActorId()=currentReporterUserId()"))
        assertTrue(wiring.contains("actorDigest=::sha256Hex"))
        assertTrue(wiring.contains("overridefunlifecycleOrNull()=if(::walkSessionLifecycle.isInitialized)walkSessionLifecycleelsenull"))
    }

    @Test
    fun explicitAcknowledgementReobservesTheCurrentScopeUsingTheAwaitingRequestIdentity() {
        val resume = compact(method("resumePermissionRecoveryFromExplicitUserAction"))
        assertTrue(resume.contains("valconfirmedBinding=permissionRecoveryGate.recheckBinding"))
        assertTrue(resume.contains("currentPermissionRecoveryBinding(it.recheckRequestId)"))
        assertBefore(
            resume,
            "confirmedBinding==null||currentBinding==null",
            "permissionRecoveryGate.acknowledgeExplicitResume(",
        )
        assertTrue(resume.contains("observed=currentObservedPermissionSnapshot()"))
        assertTrue(resume.contains("currentBinding=currentBinding"))
        assertBefore(resume, "permissionRecoveryGate.blocksAutomaticResourceStart", "runtime.startAfterPermissionRecovery()")
        assertTrue(resume.contains("completePermissionRecoveryRecheckIfPossible()"))
        assertFalse("acknowledgement cannot invent another request", resume.contains("UUID"))
    }

    @Test
    fun walkPermissionCallbackKeepsBoundRecoveryButPreservesTheInitialUnboundClear() {
        assertTrue(source.contains("permissionFlowController.onRequestPermissionsResult(requestCode, permissions, grantResults)"))
        val owner = File("src/main/java/kr/co/hanium/dreamup/walksafe/session/permission/PermissionFlowController.kt").readText()
        val callback = compact(MainActivityRefactorSource.member(owner, "onRequestPermissionsResult"))
        assertTrue(callback.contains("if(!isPermissionRequestLeaseCurrent(lease))return"))
        assertTrue("an Android result cannot acknowledge a bound full recovery", callback.contains("permissionRecoveryGate.recheckBinding!=null"))
        assertTrue(callback.contains("permissionRecoveryGate.recheckRequired()"))
        assertTrue("an initial unbound request retains its existing clear contract", callback.contains("else{PermissionRecoveryGate()}"))
        assertBefore(callback, "permissionRecoveryGate.recheckBinding!=null", "persistPermissionRecoveryGate()")
    }

    @Test
    fun blockedNoticeConfirmationKeepsBoundRecoveryButAllowsUnboundFeatureRestriction() {
        val confirm = compact(method("onPermissionDenialConfirmed"))
        val blocked = confirm.substringAfter("PermissionRecoveryGateState.BLOCKED->{")
            .substringBefore("PermissionRecoveryGateState.AWAITING_EXPLICIT_RESUME")
        assertTrue("closing a blocked notice cannot acknowledge a bound recovery", blocked.contains("permissionRecoveryGate.recheckBinding!=null"))
        assertTrue(blocked.contains("permissionRecoveryGate.recheckRequired()"))
        assertTrue("the unbound feature restriction notice retains its clear contract", blocked.contains("elsePermissionRecoveryGate()"))
        assertBefore(blocked, "permissionRecoveryGate.recheckBinding!=null", "persistPermissionRecoveryGate()")
    }

    private fun assertBefore(text: String, first: String, second: String) {
        val firstIndex = text.indexOf(first)
        val secondIndex = text.indexOf(second)
        assertTrue("$first must precede $second", firstIndex >= 0 && secondIndex > firstIndex)
    }

    private fun compact(text: String): String = text.replace(Regex("\\s+"), "")

    private fun method(name: String): String = MainActivityRefactorSource.function(source, "private fun $name(")
}
