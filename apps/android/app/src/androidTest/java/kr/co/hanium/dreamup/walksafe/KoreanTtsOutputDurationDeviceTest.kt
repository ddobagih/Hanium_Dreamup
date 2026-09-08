package kr.co.hanium.dreamup.walksafe

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Measures what the Korean TTS probe's output actually looks like on this device.
 *
 * The probe accepts any file longer than zero bytes, so a header-only file passes. Choosing a real
 * floor needs the numbers a working synthesis produces, and byte size alone is not portable — the
 * same sentence is a different size per engine sample rate. This reports both the size and the
 * duration parsed from the WAV header, per engine, so the floor can be set on duration.
 */
@RunWith(AndroidJUnit4::class)
class KoreanTtsOutputDurationDeviceTest {
    @Test
    fun measureProbeUtteranceAcrossEngines() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val report = listOf(null, "com.google.android.tts", "com.samsung.SMT")
            .joinToString("\n") { measure(it) }
        Log.i(LOG_TAG, report)
        instrumentation.sendStatus(0, Bundle().apply { putString(STATUS_KEY, report) })
        assertTrue(report.contains("engine="))
    }

    private fun measure(engine: String?): String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val ready = CountDownLatch(1)
        var initStatus = TextToSpeech.ERROR
        lateinit var tts: TextToSpeech
        tts = if (engine == null) {
            TextToSpeech(context) { status -> initStatus = status; ready.countDown() }
        } else {
            TextToSpeech(context, { status -> initStatus = status; ready.countDown() }, engine)
        }
        if (!ready.await(15L, TimeUnit.SECONDS) || initStatus != TextToSpeech.SUCCESS) {
            runCatching { tts.shutdown() }
            return "engine=${engine ?: "default"} init=FAILED"
        }

        val languageStatus = tts.setLanguage(Locale.KOREAN)
        // The probe insists on an offline voice; measure the same voice it would pick.
        val offlineKorean = runCatching {
            tts.voices.orEmpty()
                .filter {
                    it.locale.language.equals(Locale.KOREAN.language, ignoreCase = true) &&
                        !it.isNetworkConnectionRequired
                }
                .sortedBy { it.name }
                .firstOrNull()
        }.getOrNull()
        offlineKorean?.let { runCatching { tts.setVoice(it) } }

        val output = File.createTempFile("walksafe-tts-measure-", ".wav", context.cacheDir)
        val done = CountDownLatch(1)
        var failed = false
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = done.countDown()
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                failed = true
                done.countDown()
            }
            override fun onError(utteranceId: String?, errorCode: Int) {
                failed = true
                done.countDown()
            }
        })
        val queued = tts.synthesizeToFile(PROBE_TEXT_KO, null, output, "measure")
        val completed = done.await(15L, TimeUnit.SECONDS)
        runCatching { tts.shutdown() }

        val bytes = if (output.isFile) output.length() else -1L
        val wav = parseWav(output)
        runCatching { output.delete() }

        return "engine=${engine ?: "default"} actual=${tts.defaultEngine} " +
            "setLanguage=$languageStatus voice=${offlineKorean?.name ?: "none"} " +
            "queued=$queued completed=$completed failed=$failed " +
            "bytes=$bytes $wav"
    }

    /** Reads the RIFF fmt/data chunks; returns the fields a duration floor would be set from. */
    private fun parseWav(file: File): String {
        if (!file.isFile || file.length() < 12L) return "wav=absent"
        val data = runCatching { file.readBytes() }.getOrNull() ?: return "wav=unreadable"
        if (data.size < 12 || String(data, 0, 4) != "RIFF" || String(data, 8, 4) != "WAVE") {
            return "wav=notRiff firstBytes=${data.take(4).joinToString("") { "%02x".format(it) }}"
        }
        var offset = 12
        var sampleRate = 0
        var channels = 0
        var bits = 0
        var dataBytes = 0L
        while (offset + 8 <= data.size) {
            val id = String(data, offset, 4)
            val size = le32(data, offset + 4)
            val body = offset + 8
            if (id == "fmt " && body + 16 <= data.size) {
                channels = le16(data, body + 2)
                sampleRate = le32(data, body + 4)
                bits = le16(data, body + 14)
            } else if (id == "data") {
                dataBytes = minOf(size.toLong(), (data.size - body).toLong())
            }
            offset = body + size + (size and 1)
            if (size <= 0) break
        }
        val byteRate = sampleRate.toLong() * channels * (bits / 8)
        val durationMs = if (byteRate > 0L) dataBytes * 1000L / byteRate else -1L
        return "sampleRate=$sampleRate ch=$channels bits=$bits " +
            "dataBytes=$dataBytes durationMs=$durationMs"
    }

    private fun le16(data: ByteArray, at: Int): Int =
        (data[at].toInt() and 0xff) or ((data[at + 1].toInt() and 0xff) shl 8)

    private fun le32(data: ByteArray, at: Int): Int =
        (data[at].toInt() and 0xff) or
            ((data[at + 1].toInt() and 0xff) shl 8) or
            ((data[at + 2].toInt() and 0xff) shl 16) or
            ((data[at + 3].toInt() and 0xff) shl 24)

    private companion object {
        // Must stay identical to AndroidKoreanTextToSpeechSynthesisProbe.PROBE_TEXT_KO.
        const val PROBE_TEXT_KO = "기기 점검 안내입니다."
        const val LOG_TAG = "WalkSafeTtsDuration"
        const val STATUS_KEY = "walksafe_tts_output_duration"
    }
}
