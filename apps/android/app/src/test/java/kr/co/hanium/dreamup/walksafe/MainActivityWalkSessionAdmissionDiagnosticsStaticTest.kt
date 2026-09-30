package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S03-12: resume recognition passes every recognizer alternative to the normalizer.
 * S03-28: rejected start/resume/readiness admissions are recorded without token values.
 */
class MainActivityWalkSessionAdmissionDiagnosticsStaticTest {
    private val source = listOf(
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"),
        File("app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"),
        File("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"),
    ).first(File::isFile).readText()

    @Test
    fun resumeRecognitionUsesAllAlternatives() {
        val recognition = functionBlock("private fun handleWalkSessionResumeRecognition(")
        assertTrue(recognition.contains("WalkSessionResumeConfirmation.fromRecognizedText(phrases)"))
        assertFalse(recognition.contains("phrases.firstOrNull()"))
    }

    @Test
    fun rejectedAdmissionsAreLoggedWithReasonButWithoutTokens() {
        val transition = functionBlock("private fun transitionWalkSession(")
        val rejection = transition.indexOf("WalkSessionTransitionDiagnostics.rejectionOrNull(event, transition)")
        assertTrue(rejection > transition.indexOf("walkSessionLifecycle.handle(event)"))
        val record = transition.substring(rejection)
            .substringAfter("\"walk_session_transition_rejected\"")
            .substringBefore("handleRawCollectionTransition(")
        assertTrue(record.contains("\"rejection\" to rejection.name"))
        assertTrue(record.contains("\"reason\" to transition.reason.name"))
        assertFalse(record.contains("token", ignoreCase = true))
        assertFalse(record.contains("readiness"))
    }

    private fun functionBlock(signature: String): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "missing $signature" }
        val open = source.indexOf('{', source.indexOf(')', start))
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return source.substring(start, index + 1)
                }
            }
        }
        error("unterminated $signature")
    }
}
