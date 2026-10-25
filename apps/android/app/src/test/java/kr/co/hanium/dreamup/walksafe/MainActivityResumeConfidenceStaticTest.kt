package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Actual recognition wiring only; this does not execute Activity or the recognizer. */
class MainActivityResumeConfidenceStaticTest {
    private val source = sequenceOf(
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"),
        File("app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"),
    ).first { it.isFile }.readText()

    @Test
    fun resumeUsesRecognizerScoresAndExistingVoicePolicyBeforeTransition() {
        val results = MainActivityRefactorSource.function(source, "override fun onResults(results: Bundle?)")
            .replace(Regex("\\s+"), "")
        val readScores = results.indexOf("valconfidenceScores=results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)")
        val dispatch = results.indexOf("commands.resumeRecognized(phrases,confidenceScores)")
        assertTrue("recognizer scores must reach the actual resume dispatch", readScores >= 0 && dispatch > readScores)

        val handler = MainActivityRefactorSource.function(source, "private fun handleWalkSessionResumeRecognition(")
            .replace(Regex("\\s+"), "")
        val resolve = handler.indexOf("WalkSessionResumeConfirmation.fromRecognizedText(phrases,confidenceScores,effects::acceptsControlConfidence,")
        val recheck = handler.indexOf("recheckWalkSessionResumeBeforeConfirmation(confirmationToken)")
        val transition = handler.indexOf("WalkSessionEvent.ResumeConfirmationReceived(")
        assertTrue("confidence must be resolved before readiness recheck and lifecycle transition",
            resolve >= 0 && recheck > resolve && transition > recheck)
    }
}
