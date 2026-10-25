package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S03-1: resume confirmation must pass the runtime gates the start path checks before its
 * lifecycle transition (permission recovery gate and server walk authority), in addition to the
 * fresh RESUME_WALK readiness. Source ordering only; not evidence of device behaviour.
 */
class MainActivityResumeAdmissionSymmetryStaticTest {
    private val source = sequenceOf(
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"),
        File("app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"),
    ).first { it.isFile }.readText()

    private val leaseGuard =
        "if(environment.activeLeaseOrNull(snapshot.epoch)==null){"

    @Test
    fun startPathChecksRuntimeGatesBeforeStartRequested() {
        val activation = compact(method("completeGatewayWalkActivation"))
        listOf(
            "runtime.blocksAutomaticStart()",
            "authority.activeLeaseOrNull(snapshot.epoch)==null",
            "runtime.currentStartReadinessOrNull(token)",
        ).forEach { gate ->
            assertBefore(
                activation,
                gate,
                "runtime.startRequested(token,currentReadiness)",
                "start reference gate must precede StartRequested: $gate",
            )
        }
    }

    @Test
    fun resumeRecheckRejectsMissingWalkAuthorityBeforeFreshReadiness() {
        val gate = code(method("recheckWalkSessionResumeBeforeConfirmation"))
        assertTrue("S03-1: resume must check the server walk lease", gate.contains(leaseGuard))
        assertBefore(
            gate,
            "snapshot.confirmationToken!=token",
            leaseGuard,
            "S03-1: token/state checks come first",
        )
        assertBefore(
            gate,
            leaseGuard,
            "readiness.capture(",
            "S03-1: a missing lease must reject before readiness is collected",
        )
        val lost = blockAt(gate, leaseGuard)
        assertTrue(
            "S03-1: a missing lease must use the existing authority-lost safety stop",
            lost.contains("effects.authorityLost()") &&
                blockAt(compact(method("recheckWalkSessionResumeBeforeConfirmation")), leaseGuard)
                    .contains("effects.authorityLost(\"walk_lease_missing_on_resume_confirmation\")"),
        )
        assertTrue("S03-1: a missing lease must reject the resume", lost.endsWith("returnnull}"))
    }

    @Test
    fun resumeRecheckRequiresOpenPermissionAndRevalidationGates() {
        val gate = code(method("recheckWalkSessionResumeBeforeConfirmation"))
        val gatesOpen =
            "valruntimeGatesOpen=" +
                "environment.permissionAllowsAutomaticStart()&&" +
                "!environment.gatewayRevalidationPending()&&readiness.isConfirmed()"
        assertTrue(
            "S03-1: resume must consult the permission recovery gate and pending lease revalidation",
            gate.contains(gatesOpen),
        )
        val guardedCapture =
            "if(runtimeGatesOpen&&decision!=null&&readiness.blockReason(decision)==null)" +
                "{readiness.capture(decision,WalkSessionAction.RESUME_WALK,recordResourceDiagnostics=true)}elsenull"
        assertTrue(
            "S03-1: fresh readiness may only be collected while the runtime gates are open",
            gate.contains(guardedCapture),
        )
        assertBefore(
            gate,
            gatesOpen,
            guardedCapture,
            "S03-1: gates are evaluated before collection",
        )
        assertBefore(
            gate,
            guardedCapture,
            "effects.foregroundRecheck()",
            "S03-1: closed gates fall through to the existing recheck rejection",
        )
        assertTrue(method("recheckWalkSessionResumeBeforeConfirmation").contains(
            "effects.foregroundRecheck(\"resume_readiness_changed\")",
        ))
        assertTrue("S03-1: closed gates must reject", blockAt(gate, "privatefunrecheckWalkSessionResumeBeforeConfirmation").endsWith("returnnull}"))
    }

    @Test
    fun bothResumeEntryPointsUseTheGuardedRecheckBeforeTransition() {
        listOf(
            "handleWalkSessionResumeRecognition" to
                "recheckWalkSessionResumeBeforeConfirmation(confirmationToken)",
            "confirmWalkSessionResumeFromButton" to
                "recheckWalkSessionResumeBeforeConfirmation(token)",
        ).forEach { (name, call) ->
            val body = code(method(name))
            assertBefore(
                body,
                "$call?:return",
                "WalkSessionEvent.ResumeConfirmationReceived(",
                "S03-1: $name must run the guarded recheck before the resume transition",
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

    private fun method(name: String): String = MainActivityRefactorSource.function(source, "private fun $name(")

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
