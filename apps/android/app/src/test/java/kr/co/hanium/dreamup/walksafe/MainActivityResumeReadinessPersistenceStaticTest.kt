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
        val resume = code(method("handleWalkSessionResumeRecognition"))
        val guard =
            "valcurrentReadiness=if(response==WalkSessionResumeConfirmation.START){" +
                "recheckWalkSessionResumeBeforeConfirmation(confirmationToken)?:return}elseconfirmationToken.readiness"

        assertBefore(
            resume,
            guard,
            "walk.transition(",
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
            "effects.activateRuntime(",
            "S03-1: runtime activation must follow the guarded confirmation transition",
        )
        assertTrue(
            "S03-1: forward the captured readiness object to the lifecycle transition",
            resume.contains("currentReadiness=currentReadiness,"),
        )
        assertFalse(resume.contains("currentReadiness=confirmationToken.readiness,"))
        assertFalse(resume.contains("currentReadiness=token.readiness,"))
        assertFreshResumeReadinessGate()
    }

    @Test
    fun buttonResumeChecksFreshReadinessBeforeConfirmationTransition() {
        val resume = code(method("confirmWalkSessionResumeFromButton"))
        val guard = "valcurrentReadiness=recheckWalkSessionResumeBeforeConfirmation(token)?:return"

        assertBefore(
            resume,
            guard,
            "walk.transition(",
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
            "effects.activateRuntime(",
            "S03-1: the button must not activate resources before guarded confirmation",
        )
        assertTrue(
            "S03-1: forward the captured readiness object to the lifecycle transition",
            resume.contains("currentReadiness=currentReadiness,"),
        )
        assertFalse(resume.contains("currentReadiness=confirmationToken.readiness,"))
        assertFalse(resume.contains("currentReadiness=token.readiness,"))
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
        val guard = "if(!effects.persistInterruptionMarker()){"
        assertTrue(
            "S03-18: runtime activation must branch on a failed marker write",
            activation.contains(guard),
        )
        val failedWrite = blockAt(activation, guard)
        assertTrue(
            "S03-18: a failed marker write must recheck and cancel existing outputs",
            failedWrite.contains(
                "effects.foregroundRecheck(" +
                    "\"interruption_marker_write_failed\")",
            ),
        )
        assertTrue(
            "S03-18: retry must remain explicit after a failed marker write",
            failedWrite.contains("resume.requireUserRetry()"),
        )
        assertTrue(
            "S03-18: the failed-write branch must exit before resource activation",
            failedWrite.endsWith("returnfalse}") || failedWrite.endsWith("return}"),
        )
        listOf(
            "gateway.scheduleGatewayWalkRenewal(",
            "services.activateOfficialEnvironment(",
            "services.activatePhoneMounting(",
            "camera.beginHandoff(",
            "camera.startRuntimeAfterRelease(",
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
            assertTrue(resume.contains("if (!effects.activateRuntime())"))
            assertFalse(resume.contains("PriorityUserPractice.RESUME.instructionKo"))
        }
        val complete = compact(method("completePendingWalkSessionResumeActivation"))
        listOf(
            "token.epoch!=expectedEpoch",
            "!environment.isForeground()",
            "!walk.isRuntimeEpochCurrent(expectedEpoch)",
            "environment.cameraHandoffPending(expectedEpoch)",
            "!environment.navigationOutputsAllowed()",
            "!readiness.isReady",
            "readiness.plan.mode!=token.readiness.plan.mode",
            "walkSessionResumeActivationToken!=token",
        ).forEach { guard ->
            assertBefore(complete, guard, "completeAdmittedWalkSessionResume(token,effects::publishResumeCompletion,effects::requestNavigation)", guard)
        }
        val defaultPublisher = compact(MainActivityRefactorSource.member(source, "completeAdmittedWalkSessionResume"))
        assertTrue(defaultPublisher.contains("publishCompletion:()->Unit={"))
        assertTrue(defaultPublisher.contains("speakInteraction(PriorityUserPractice.RESUME.instructionKo)"))
        val admitted = compact(owner("session/runtime/WalkSessionResumeController.kt", "completeAdmittedWalkSessionResume"))
        assertBefore(admitted,
            "walkSessionResumeActivationToken=nullwalkSessionResumeRetryRequiresUserAction=false",
            "effects.completeAdmittedResume(token.epoch,publishCompletion,requestNavigation)",
            "Claim current completion before invoking the existing publisher")
        val binding = compact(MainActivityRefactorSource.composition(source, "walkSessionResumeController"))
        assertTrue(binding.contains("routeAcquisitionController.completeAdmittedResume(epoch,publishCompletion,requestNavigation)"))
        val route = owner("navigation/session/RouteAcquisitionController.kt", "completeAdmittedResume")
        assertTrue(route.contains("publishCompletion()"))
    }

    @Test
    fun delayedReleaseCannotCancelANewerCameraHandoff() {
        val release = method("startWalkSessionRuntimeAfterCameraRelease")
        assertTrue(release.contains("effects.cancelHandoff(expectedEpoch)"))
        val cancel = compact(method("cancelRuntimeCameraHandoff"))
        assertBefore(
            cancel,
            "expectedEpoch!=null&&runtimeCameraHandoffEpoch!=expectedEpoch",
            "runtimeCameraHandoffEpoch=null",
            "Old camera release callbacks must preserve the newer handoff",
        )
        assertTrue(method("transitionWalkSession").contains("effects.clearResumeActivation()"))
        assertTrue(MainActivityRefactorSource.composition(source, "walkSessionTransitionController").contains("override fun clearResumeActivation() = walkSessionResumeController.clearActivation()"))
        assertTrue(owner("session/runtime/WalkSessionResumeController.kt", "clearActivation").contains("walkSessionResumeActivationToken = null"))
    }

    @Test
    fun completionRechecksRuntimeEvidenceAfterReleaseAndRuntimeFrames() {
        val release = method("startWalkSessionRuntimeAfterCameraRelease")
        assertTrue(release.contains("effects.completeResumeActivation(expectedEpoch)"))
        assertTrue(source.contains("statusText.post { completePendingWalkSessionResumeActivation(epoch) }"))
        val complete = method("completePendingWalkSessionResumeActivation")
        assertTrue(complete.contains("readiness.capture(decision, WalkSessionAction.RESUME_WALK)"))
        assertTrue(complete.contains("readiness.blockReason(decision) != null"))
    }

    private fun assertFreshResumeReadinessGate() {
        val gateSource = code(method("recheckWalkSessionResumeBeforeConfirmation"))
        val gate = blockAt(gateSource, "funrecheckWalkSessionResumeBeforeConfirmation(")
        listOf(
            "!environment.isForeground()",
            "snapshot.state!=WalkSessionState.PAUSED",
            "snapshot.recoveryStage!=WalkSessionRecoveryStage.AWAITING_RESUME_CONFIRMATION",
            "snapshot.epoch!=token.epoch",
            "snapshot.confirmationToken!=token",
        ).forEach { condition ->
            assertBefore(
                gate,
                condition,
                "readiness.capture(",
                "S03-1: current foreground/state/token checks must precede new observation: $condition",
            )
        }
        assertBefore(
            gate,
            "readiness.blockReason(decision)==null",
            "readiness.capture(",
            "S03-1: current permission/environment eligibility must gate fresh collection",
        )
        assertTrue(
            "S03-1: collect RESUME_WALK observations, not the saved token readiness",
            Regex(
                "readiness\\.capture\\(decision,WalkSessionAction\\.RESUME_WALK,recordResourceDiagnostics=true\\)",
            ).containsMatchIn(gate),
        )
        val readyReturn =
            "if(readiness!=null&&readiness.isReady&&this.readiness.isConfirmed()&&" +
                "readiness.epoch==token.epoch&&" +
                "readiness.plan.action==WalkSessionAction.RESUME_WALK&&" +
                "readiness.plan==token.readiness.plan)returnreadiness"
        assertBefore(
            gate,
            "readiness.capture(",
            readyReturn,
            "S03-1: only freshly ready, confirmed, epoch/full-plan-matched observations may pass",
        )
        assertFalse(
            "S03-1: do not authorize resume before collecting fresh observations",
            Regex("return(?!null)[A-Za-z]").containsMatchIn(gate.substringBefore("readiness.capture(")),
        )
        assertBefore(
            gate,
            readyReturn,
            "effects.foregroundRecheck()",
            "S03-1: failed current readiness must invalidate confirmation through the existing recheck",
        )
        assertTrue(
            "S03-1: failed fresh readiness must return no snapshot",
            gate.endsWith("returnnull}"),
        )
        assertTrue(method("recheckWalkSessionResumeBeforeConfirmation").contains(
            "effects.foregroundRecheck(\"resume_readiness_changed\")",
        ))
        val eligibility = code(method("walkSessionReadinessBlockReason"))
        listOf(
            "sources.missingRequiredPermissions(action)",
            "sources.officialEnvironmentBlockReason()",
            "sources.phoneMountingBlockReason()",
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

    private fun code(text: String): String = ReportStaticSourceInspector.maskNonCode(text).filterNot(Char::isWhitespace)

    private fun compact(text: String): String = text.replace(Regex("\\s+"), "")

    private fun method(name: String): String {
        if (name == "cancelRuntimeCameraHandoff") {
            assertTrue(MainActivityRefactorSource.member(source, name).contains("phoneMountingSession.cancelRuntimeCameraHandoff(expectedEpoch)"))
            assertTrue(MainActivityRefactorSource.property(source, "phoneMountingSession").contains("PhoneMountingSessionController("))
            return owner("session/mounting/PhoneMountingSessionController.kt", name)
        }
        return MainActivityRefactorSource.function(source, "private fun $name(")
    }
    private fun owner(path: String, name: String): String = MainActivityRefactorSource.member(
        File("src/main/java/kr/co/hanium/dreamup/walksafe/$path").readText(), name,
    )

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
