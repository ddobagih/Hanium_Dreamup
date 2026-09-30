package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Introduced as expected-red wiring regressions for S03-1 and S03-18.
 * These assertions are not ignored and must be satisfied by the parent-owned Main fix.
 * Source ordering is not evidence of asynchronous activation, latency, or physical output.
 */
class MainActivityResumeReadinessPersistenceStaticTest {
    private val source = sequenceOf(
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"),
        File("app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"),
    ).first { it.isFile }.readText()

    @Test
    fun voiceResumeChecksFreshReadinessBeforeConfirmationTransition() {
        val resume = compact(method("handleWalkSessionResumeRecognition"))
        val guard =
            "if(response==WalkSessionResumeConfirmation.START&&" +
                "!recheckWalkSessionResumeBeforeConfirmation(confirmationToken))return"

        assertBefore(
            resume,
            guard,
            "transitionWalkSession(",
            "S03-1: voice START must reject failed fresh readiness before the confirmation transition",
        )
        assertBefore(
            resume,
            guard,
            "WalkSessionEvent.ResumeConfirmationReceived(",
            "S03-1: a cached resume token alone must not authorize the voice confirmation",
        )
        assertBefore(
            resume,
            "WalkSessionEvent.ResumeConfirmationReceived(",
            "activateWalkSessionRuntime(",
            "S03-1: runtime activation must follow the guarded confirmation transition",
        )
        assertFreshResumeReadinessGate()
    }

    @Test
    fun buttonResumeChecksFreshReadinessBeforeConfirmationTransition() {
        val resume = compact(method("confirmWalkSessionResumeFromButton"))
        val guard = "if(!recheckWalkSessionResumeBeforeConfirmation(token))return"

        assertBefore(
            resume,
            guard,
            "transitionWalkSession(",
            "S03-1: the resume button must reject failed fresh readiness before the transition",
        )
        assertBefore(
            resume,
            guard,
            "WalkSessionEvent.ResumeConfirmationReceived(",
            "S03-1: the button must not consume a cached confirmation without the fresh gate",
        )
        assertBefore(
            resume,
            "WalkSessionEvent.ResumeConfirmationReceived(",
            "activateWalkSessionRuntime(",
            "S03-1: the button must not activate resources before guarded confirmation",
        )
        assertFreshResumeReadinessGate()
    }

    @Test
    fun runtimeActivationRequiresSuccessfulInterruptionMarkerCommit() {
        val persist = compact(method("persistWalkSessionInterruptionMarker"))
        assertTrue(
            "S03-18: propagate commit success instead of discarding its Boolean result",
            persist.startsWith("privatefunpersistWalkSessionInterruptionMarker():Boolean{"),
        )
        assertTrue(
            "S03-18: the returned result must come from the marker commit, not a constant true",
            Regex(
                "return(?:try\\{)?stepLengthPrefs\\.edit\\(\\)" +
                    "\\.putBoolean\\(PREF_WALK_SESSION_INTERRUPTED,interrupted\\)\\.commit\\(\\)",
            ).containsMatchIn(persist),
        )
        assertFalse(
            "S03-18: persistence failures must never be converted to successful writes",
            persist.contains("returntrue") || persist.contains("getOrDefault(true)"),
        )

        val activation = compact(method("activateWalkSessionRuntime"))
        val guard = "if(!persistWalkSessionInterruptionMarker()){"
        assertTrue(
            "S03-18: runtime activation must branch on a failed marker write",
            activation.contains(guard),
        )
        val failedWrite = blockAt(activation, guard)
        assertTrue(
            "S03-18: a failed marker write must recheck and cancel existing outputs",
            failedWrite.contains(
                "enterWalkSessionForegroundRecheckAndCancelOutputs(" +
                    "\"interruption_marker_write_failed\")",
            ),
        )
        assertTrue(
            "S03-18: retry must remain explicit after a failed marker write",
            failedWrite.contains("walkSessionResumeRetryRequiresUserAction=true"),
        )
        assertTrue(
            "S03-18: the failed-write branch must exit before resource activation",
            failedWrite.endsWith("returnfalse}") || failedWrite.endsWith("return}"),
        )
        listOf(
            "scheduleGatewayWalkRenewal(",
            "activateOfficialEnvironmentRuntime(",
            "activatePhoneMountingRuntime(",
            "beginRuntimeCameraHandoff(",
            "startWalkSessionRuntimeAfterCameraRelease(",
        ).forEach { start ->
            assertBefore(
                activation,
                guard,
                start,
                "S03-18: successful persistence must gate $start",
            )
            assertFalse(
                "S03-18: the failed-write branch must not start $start",
                failedWrite.contains(start),
            )
        }
    }

    @Test
    fun resumeSuccessIsNotAnnouncedAtActivationAdmission() {
        listOf("handleWalkSessionResumeRecognition", "confirmWalkSessionResumeFromButton").forEach { name ->
            val resume = method(name)
            assertTrue(resume.contains("transition.changed"))
            assertTrue(resume.contains("walkSessionResumeActivationToken ="))
            assertTrue(resume.contains("if (!activateWalkSessionRuntime())"))
            assertFalse(resume.contains("PriorityUserPractice.RESUME.instructionKo"))
        }
        val complete = compact(method("completePendingWalkSessionResumeActivation"))
        listOf(
            "token.epoch!=expectedEpoch",
            "!isActivityForeground",
            "!walkSessionLifecycle.isRuntimeEpochCurrent(expectedEpoch)",
            "runtimeCameraHandoffEpoch==expectedEpoch",
            "!navigationEnvironmentOutputsAllowed()",
            "!readiness.isReady",
            "readiness.plan.mode!=token.readiness.plan.mode",
            "walkSessionResumeActivationToken!=token",
        ).forEach { guard ->
            assertBefore(complete, guard, "speakInteraction(PriorityUserPractice.RESUME.instructionKo)", guard)
        }
        assertBefore(
            complete,
            "walkSessionResumeActivationToken=nullwalkSessionResumeRetryRequiresUserAction=false",
            "speakInteraction(PriorityUserPractice.RESUME.instructionKo)",
            "Claim the current completion before announcing it",
        )
    }

    @Test
    fun delayedReleaseCannotCancelANewerCameraHandoff() {
        val release = method("startWalkSessionRuntimeAfterCameraRelease")
        assertTrue(release.contains("cancelRuntimeCameraHandoff(expectedEpoch)"))
        val cancel = compact(method("cancelRuntimeCameraHandoff"))
        assertBefore(
            cancel,
            "expectedEpoch!=null&&runtimeCameraHandoffEpoch!=expectedEpoch",
            "runtimeCameraHandoffEpoch=null",
            "Old camera release callbacks must preserve the newer handoff",
        )
        assertTrue(method("transitionWalkSession").contains("walkSessionResumeActivationToken = null"))
    }

    @Test
    fun completionRechecksRuntimeEvidenceAfterReleaseAndRuntimeFrames() {
        val release = method("startWalkSessionRuntimeAfterCameraRelease")
        assertTrue(release.contains("completePendingWalkSessionResumeActivation(expectedEpoch)"))
        assertTrue(source.contains("statusText.post { completePendingWalkSessionResumeActivation(epoch) }"))
        val complete = method("completePendingWalkSessionResumeActivation")
        assertTrue(complete.contains("captureWalkSessionReadiness(decision, WalkSessionAction.RESUME_WALK)"))
        assertTrue(complete.contains("walkSessionReadinessBlockReason(decision) != null"))
    }

    private fun assertFreshResumeReadinessGate() {
        val gate = compact(method("recheckWalkSessionResumeBeforeConfirmation"))
        listOf(
            "!isActivityForeground",
            "snapshot.state!=WalkSessionState.PAUSED",
            "snapshot.recoveryStage!=WalkSessionRecoveryStage.AWAITING_RESUME_CONFIRMATION",
            "snapshot.epoch!=token.epoch",
            "snapshot.confirmationToken!=token",
        ).forEach { condition ->
            assertBefore(
                gate,
                condition,
                "captureWalkSessionReadiness(",
                "S03-1: current foreground/state/token checks must precede new observation: $condition",
            )
        }
        assertBefore(
            gate,
            "walkSessionReadinessBlockReason(decision)==null",
            "captureWalkSessionReadiness(",
            "S03-1: current permission/environment eligibility must gate fresh collection",
        )
        assertTrue(
            "S03-1: collect RESUME_WALK observations, not the saved token readiness",
            Regex(
                "captureWalkSessionReadiness\\((?:decision=)?decision," +
                    "(?:action=)?WalkSessionAction\\.RESUME_WALK,?\\)",
            ).containsMatchIn(gate),
        )
        val readyReturn =
            "if(readiness!=null&&readiness.isReady&&" +
                "readiness.epoch==token.epoch&&" +
                "readiness.plan.action==WalkSessionAction.RESUME_WALK&&" +
                "readiness.plan.action==token.readiness.plan.action&&" +
                "readiness.plan.mode==token.readiness.plan.mode)returntrue"
        assertBefore(
            gate,
            "captureWalkSessionReadiness(",
            readyReturn,
            "S03-1: only freshly ready, epoch/action/mode-matched observations may pass",
        )
        assertFalse(
            "S03-1: do not authorize resume before collecting fresh observations",
            gate.substringBefore("captureWalkSessionReadiness(").contains("returntrue"),
        )
        assertBefore(
            gate,
            readyReturn,
            "enterWalkSessionForegroundRecheckAndCancelOutputs(\"resume_readiness_changed\")",
            "S03-1: failed current readiness must invalidate confirmation through the existing recheck",
        )
        assertTrue(
            "S03-1: failed fresh readiness must return false",
            gate.endsWith("returnfalse}"),
        )
        val eligibility = compact(method("walkSessionReadinessBlockReason"))
        listOf(
            "missingRequiredWalkSessionPermissions(action)",
            "officialEnvironmentBlockReason()",
            "phoneMountingBlockReason()",
        ).forEach { observation ->
            assertTrue(
                "S03-1: the delegated eligibility gate must still consult $observation",
                eligibility.contains(observation),
            )
        }
    }

    private fun assertBefore(text: String, first: String, second: String, message: String) {
        val firstIndex = text.indexOf(first)
        val secondIndex = text.indexOf(second)
        assertTrue(message, firstIndex >= 0 && secondIndex >= 0 && firstIndex < secondIndex)
    }

    private fun compact(text: String): String = text.replace(Regex("\\s+"), "")

    private fun method(name: String): String = blockAt(source, "private fun $name(")

    private fun blockAt(text: String, marker: String): String {
        val markerIndex = text.indexOf(marker)
        check(markerIndex >= 0) { "missing block marker: $marker" }
        val open = text.indexOf('{', markerIndex)
        check(open >= 0) { "missing opening brace: $marker" }
        var depth = 0
        for (index in open until text.length) {
            when (text[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return text.substring(markerIndex, index + 1)
                }
            }
        }
        error("unterminated block: $marker")
    }
}
