package kr.co.hanium.dreamup.walksafe.voice

import android.os.Bundle
import android.speech.SpeechRecognizer
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Android Bundle contracts only. No microphone, recognizer session, activity, or model is used. */
@RunWith(AndroidJUnit4::class)
class OfflineSpeechResultMetadataDeviceTest {
    @Test
    fun platformTagPreservesOriginalCandidatesZeroScoresAndOtherKeys() {
        val source = Bundle().apply {
            putStringArrayList(
                SpeechRecognizer.RESULTS_RECOGNITION,
                arrayListOf("도움말", "도움말."),
            )
            putFloatArray(SpeechRecognizer.CONFIDENCE_SCORES, floatArrayOf(0f, 0f))
            putInt("synthetic_fixture_marker", 37)
        }
        val originalKeys = source.keySet().toSet()
        val tagged = checkNotNull(withOfflineSpeechResultEngine(source, OfflineSpeechEngine.PLATFORM))

        assertNotSame(source, tagged)
        assertEquals(OfflineSpeechEngine.PLATFORM, offlineSpeechResultEngine(tagged))
        assertNull(offlineSpeechResultEngine(source))
        assertEquals(originalKeys, source.keySet())
        assertEquals(
            arrayListOf("도움말", "도움말."),
            source.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION),
        )
        assertEquals(
            source.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION),
            tagged.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION),
        )
        assertArrayEquals(
            floatArrayOf(0f, 0f),
            checkNotNull(source.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)),
            0f,
        )
        assertArrayEquals(
            floatArrayOf(0f, 0f),
            checkNotNull(tagged.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)),
            0f,
        )
        assertEquals(37, tagged.getInt("synthetic_fixture_marker"))
        tagged.putInt("synthetic_fixture_marker", 99)
        assertEquals(37, source.getInt("synthetic_fixture_marker"))
    }

    @Test
    fun retaggingACopyDoesNotChangeTheVoskOriginOrProviderConfidence() {
        val source = Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("도움말"))
            putFloatArray(SpeechRecognizer.CONFIDENCE_SCORES, floatArrayOf(-1f))
        }
        val vosk = checkNotNull(withOfflineSpeechResultEngine(source, OfflineSpeechEngine.VOSK))
        val platform = checkNotNull(withOfflineSpeechResultEngine(vosk, OfflineSpeechEngine.PLATFORM))

        assertEquals(OfflineSpeechEngine.VOSK, offlineSpeechResultEngine(vosk))
        assertEquals(OfflineSpeechEngine.PLATFORM, offlineSpeechResultEngine(platform))
        assertNull(offlineSpeechResultEngine(source))
        assertArrayEquals(
            floatArrayOf(-1f),
            checkNotNull(platform.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)),
            0f,
        )
        assertArrayEquals(
            floatArrayOf(-1f),
            checkNotNull(vosk.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)),
            0f,
        )
    }

    @Test
    fun absentBackendMetadataDoesNotEnableThePlatformZeroPreviewException() {
        val untagged = Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("도움말"))
            putFloatArray(SpeechRecognizer.CONFIDENCE_SCORES, floatArrayOf(0f))
        }
        assertNull(offlineSpeechResultEngine(untagged))
        assertNull(offlineSpeechResultEngine(null))
        assertNull(withOfflineSpeechResultEngine(null, OfflineSpeechEngine.PLATFORM))
        val decision = assessPlatformVoiceCandidate(
            phrases = checkNotNull(untagged.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)),
            confidenceScores = untagged.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES),
            backend = offlineSpeechResultEngine(untagged),
            isFinal = true,
            isHomeContext = true,
        )
        assertEquals(PlatformVoiceCandidateDisposition.NOT_APPLICABLE, decision.disposition)
        assertNull(decision.previewAction)
    }

    @Test
    fun suppliedUnexaminedAlternativesRemainVisibleAfterBackendTagging() {
        val source = Bundle()
        assertFalse(hasAdditionalOfflineSpeechAlternatives(source))
        source.putParcelableArrayList("results_alternatives", arrayListOf<Bundle>())
        assertFalse(hasAdditionalOfflineSpeechAlternatives(source))
        source.putParcelableArrayList("results_alternatives", arrayListOf(Bundle()))
        val tagged = checkNotNull(withOfflineSpeechResultEngine(source, OfflineSpeechEngine.PLATFORM))
        assertTrue(hasAdditionalOfflineSpeechAlternatives(source))
        assertTrue(hasAdditionalOfflineSpeechAlternatives(tagged))
        assertNull(offlineSpeechResultEngine(source))
    }
}
