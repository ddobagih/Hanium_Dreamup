package kr.co.hanium.dreamup.walksafe

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Reports why [kr.co.hanium.dreamup.walksafe.device.AndroidKoreanTextToSpeechSynthesisProbe]
 * accepts or rejects this device. The probe needs a Korean voice whose
 * `isNetworkConnectionRequired` is false; `setLanguage` returning an available
 * code is not enough, so this lists every Korean voice and that flag.
 */
@RunWith(AndroidJUnit4::class)
class KoreanTtsVoiceInventoryDeviceTest {
    @Test
    fun reportKoreanVoiceInventory() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // The probe uses the system default engine; Google is checked too so the
        // report says whether switching engines is a way out.
        val report = listOf(null, "com.google.android.tts")
            .joinToString(" || ") { inventory(it) }
        Log.i(LOG_TAG, report)
        instrumentation.sendStatus(0, Bundle().apply { putString(STATUS_KEY, report) })
        assertTrue(report.contains("initialized="))
    }

    private fun inventory(engine: String?): String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val ready = CountDownLatch(1)
        var initStatus = TextToSpeech.ERROR
        lateinit var tts: TextToSpeech

        tts = if (engine == null) {
            TextToSpeech(context) { status -> initStatus = status; ready.countDown() }
        } else {
            TextToSpeech(context, { status -> initStatus = status; ready.countDown() }, engine)
        }
        val initialized = ready.await(INIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        val summary = buildString {
            append("engineRequested=").append(engine ?: "default")
            append(", initialized=").append(initialized)
            append(", initStatus=").append(initStatus)
            if (initialized && initStatus == TextToSpeech.SUCCESS) {
                append(", engine=").append(tts.defaultEngine)
                append(", setLanguage(ko)=").append(tts.setLanguage(Locale.KOREAN))
                val korean = runCatching {
                    tts.voices.orEmpty().filter {
                        it.locale.language.equals(Locale.KOREAN.language, ignoreCase = true)
                    }
                }.getOrDefault(emptyList())
                append(", koreanVoices=").append(korean.size)
                append(", offlineKoreanVoices=")
                append(korean.count { !it.isNetworkConnectionRequired })
                korean.forEach {
                    append(" | ").append(it.name)
                    append(" networkRequired=").append(it.isNetworkConnectionRequired)
                    append(" features=").append(it.features)
                }
            }
        }
        runCatching { tts.shutdown() }
        return summary
    }

    private companion object {
        const val INIT_TIMEOUT_SECONDS = 15L
        const val LOG_TAG = "WalkSafeKoreanTtsTest"
        const val STATUS_KEY = "walksafe_korean_tts_inventory"
    }
}
