package kr.co.hanium.dreamup.walksafe.device

import java.io.File
import kr.co.hanium.dreamup.walksafe.voice.OfflineKoreanSpeechUnavailableCause
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** S13-15: the synthesis probe keeps the Korean-language failure cause for cause-specific guidance. */
class KoreanTextToSpeechUnavailableCauseTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun languageFailureCauseSelectsTheSpecificUnavailableResult() {
        val expected = mapOf(
            OfflineKoreanSpeechUnavailableCause.KOREAN_VOICE_DATA_MISSING to
                KoreanTextToSpeechSynthesisProbeResult.KOREAN_VOICE_DATA_MISSING,
            OfflineKoreanSpeechUnavailableCause.PREFERRED_ENGINE_LACKS_KOREAN to
                KoreanTextToSpeechSynthesisProbeResult.KOREAN_PREFERRED_ENGINE_UNSUPPORTED,
            OfflineKoreanSpeechUnavailableCause.NO_KOREAN_CAPABLE_ENGINE to
                KoreanTextToSpeechSynthesisProbeResult.KOREAN_ENGINE_UNAVAILABLE,
        )
        for ((cause, result) in expected) {
            val engine = LanguageFailureEngine(cause)
            val results = run(engine)
            assertEquals(listOf(result), results)
            assertFalse(results.single().available)
            assertEquals(cause, results.single().unavailableCause)
            assertEquals("no synthesis without an offline Korean voice", 0, engine.synthesisRequests)
        }
        val unknown = run(LanguageFailureEngine(null))
        assertEquals(listOf(KoreanTextToSpeechSynthesisProbeResult.KOREAN_LANGUAGE_UNAVAILABLE), unknown)
        assertNull(unknown.single().unavailableCause)
        assertNull(KoreanTextToSpeechSynthesisProbeResult.SYNTHESIS_ERROR.unavailableCause)
        assertNull(KoreanTextToSpeechSynthesisProbeResult.AVAILABLE.unavailableCause)
    }

    @Test
    fun probeStateKeepsTheCauseOnlyForTheCurrentUnavailableResultAndClearsItOnRecheck() {
        val state = OfflineKoreanTextToSpeechProbeState()
        val first = state.begin()
        assertTrue(state.complete(first, available = false, OfflineKoreanSpeechUnavailableCause.PREFERRED_ENGINE_LACKS_KOREAN))
        assertEquals(OfflineKoreanSpeechUnavailableCause.PREFERRED_ENGINE_LACKS_KOREAN, state.unavailableCause)
        val recheck = state.begin()
        assertNull("returning from settings starts a fresh probe", state.unavailableCause)
        assertNull(state.available)
        assertFalse(state.complete(first, available = false, OfflineKoreanSpeechUnavailableCause.KOREAN_VOICE_DATA_MISSING))
        assertTrue(state.complete(recheck, available = true, OfflineKoreanSpeechUnavailableCause.KOREAN_VOICE_DATA_MISSING))
        assertNull(state.unavailableCause)
        assertEquals(true, state.available)
    }

    @Test
    fun androidEngineClassifiesWithoutSwitchingEnginesOrUsingNetworkVoices() {
        val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/device/AndroidKoreanTextToSpeechSynthesisProbe.kt").readText()
        assertTrue(source.contains("OfflineKoreanSpeechGuidancePolicy.classifyLanguageStatus("))
        assertTrue(source.contains("textToSpeech.engines.size"))
        assertTrue(source.contains("unavailableCause = OfflineKoreanSpeechUnavailableCause.KOREAN_VOICE_DATA_MISSING"))
        assertFalse(source.contains("setEngineByPackageName"))
        assertFalse(Regex("""TextToSpeech\(context, \{[^}]*\}, """).containsMatchIn(source))
        val probe = File("src/main/java/kr/co/hanium/dreamup/walksafe/device/AndroidStartupCapabilityProbe.kt").readText()
        assertTrue(probe.contains("result.unavailableCause,"))
    }

    private fun run(engine: KoreanTextToSpeechProbeEngine): List<KoreanTextToSpeechSynthesisProbeResult> {
        val results = mutableListOf<KoreanTextToSpeechSynthesisProbeResult>()
        val outputFile = temporaryFolder.newFile()
        KoreanTextToSpeechSynthesisProbeSession(
            engineFactory = KoreanTextToSpeechProbeEngineFactory { engine },
            outputFileFactory = KoreanTextToSpeechProbeOutputFileFactory { outputFile },
            timeoutScheduler = KoreanTextToSpeechProbeTimeoutScheduler { _, _ -> KoreanTextToSpeechProbeTimeoutCancellation {} },
            timeoutMs = 1_000L,
            onResult = results::add,
            utteranceId = "cause-test",
        ).start()
        return results
    }

    private class LanguageFailureEngine(private val cause: OfflineKoreanSpeechUnavailableCause?) : KoreanTextToSpeechProbeEngine {
        var synthesisRequests = 0
            private set
        override fun setInitializationListener(listener: (Boolean) -> Unit) = listener(true)
        override fun selectKoreanLanguage(): Boolean = false
        override fun koreanLanguageUnavailableCause(): OfflineKoreanSpeechUnavailableCause? = cause
        override fun setProgressListener(listener: KoreanTextToSpeechProbeProgressListener): Boolean = true
        override fun synthesizeToFile(text: CharSequence, outputFile: File, utteranceId: String): Boolean {
            synthesisRequests += 1
            return false
        }
        override fun close() = Unit
    }
}
