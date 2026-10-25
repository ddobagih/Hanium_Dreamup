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
        assertTrue(recognition.contains("phrases, confidenceScores, effects::acceptsControlConfidence,"))
        assertFalse(recognition.contains("phrases.firstOrNull()"))
    }

    @Test
    fun rejectedAdmissionsAreLoggedWithReasonButWithoutTokens() {
        val transition = functionBlock("private fun transitionWalkSession(")
        val rejection = transition.indexOf("WalkSessionTransitionDiagnostics.rejectionOrNull(event, transition)")
        assertTrue(rejection > transition.indexOf("lifecycle().handle(event, effects::beforePublication)"))
        val record = transition.substring(rejection)
            .substringAfter("\"walk_session_transition_rejected\"")
            .substringBefore("handleRawCollectionTransition(")
        assertTrue(record.contains("\"rejection\" to rejection.name"))
        assertTrue(record.contains("\"reason\" to transition.reason.name"))
        assertFalse(record.contains("token", ignoreCase = true))
        assertFalse(record.contains("readiness"))
    }

    private fun functionBlock(signature: String): String = MainActivityRefactorSource.function(source, signature)
}
