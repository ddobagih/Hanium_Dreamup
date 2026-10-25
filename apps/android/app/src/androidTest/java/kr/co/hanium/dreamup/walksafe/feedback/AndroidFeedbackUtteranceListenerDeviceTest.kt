package kr.co.hanium.dreamup.walksafe.feedback

import android.speech.tts.TextToSpeech
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** SDK callback bridge only: injects callback events, does not synthesize or prove audible speech. */
@RunWith(AndroidJUnit4::class)
class AndroidFeedbackUtteranceListenerDeviceTest {
    @Test
    fun nativeCallbackBridgePreservesIdsErrorsAndInterruptedFlag() {
        val received = mutableListOf<String>()
        val bridge = AndroidFeedbackUtteranceListener(object : FeedbackSpeechRuntime.Listener {
            override fun onStart(utteranceId: String?) { received += "start:$utteranceId" }
            override fun onDone(utteranceId: String?) { received += "done:$utteranceId" }
            override fun onError(utteranceId: String?) { received += "error:$utteranceId" }
            override fun onError(utteranceId: String?, errorCode: Int) {
                received += "error:$utteranceId:$errorCode"
            }
            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                received += "stop:$utteranceId:$interrupted"
            }
        })
        bridge.onStart("nav-1")
        bridge.onDone("nav-1")
        @Suppress("DEPRECATION")
        bridge.onError("nav-2")
        bridge.onError("nav-3", TextToSpeech.ERROR_SERVICE)
        bridge.onStop("nav-4", false)
        bridge.onStop("risk-5", true)
        bridge.onStart(null)
        bridge.onDone(null)
        assertEquals(
            listOf("start:nav-1", "done:nav-1", "error:nav-2", "error:nav-3:${TextToSpeech.ERROR_SERVICE}",
                "stop:nav-4:false", "stop:risk-5:true", "start:null", "done:null"),
            received,
        )
    }
}
