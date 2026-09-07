package kr.co.hanium.dreamup.walksafe.voice

import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Actual local TTS waveform -> bundled Vosk -> final -> production wake gate.
 * No microphone, upload, playback, model download, or synthetic recognition callback.
 * Synthetic voice evidence is not a measurement of human speech accuracy.
 */
@RunWith(AndroidJUnit4::class)
class VoskSyntheticWakePhraseDeviceTest {
    @Test(timeout = 180_000L)
    fun syntheticWakeIsAcceptedAndNonWakeIsRejectedByActualModel() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertTrue(
            "BLOCKED: allowVoskSyntheticWakePhraseTest=true is required",
            InstrumentationRegistry.getArguments()
                .getString("allowVoskSyntheticWakePhraseTest").toBoolean(),
        )
        assertTrue(
            "NOT_READY: bundled Korean model asset is unavailable",
            BundledVoskModelInstaller.bundledModelAvailable(context),
        )
        val directory = checkNotNull(BundledVoskModelInstaller.installedModelOrNull(context)) {
            "NOT_READY: existing bundled model must be prepared before this diagnostic"
        }
        val scratch = File(context.cacheDir, "vosk-synthetic-wake-" + SystemClock.elapsedRealtimeNanos())
        assertTrue("Cannot create test-owned cache directory", scratch.mkdir())
        val files = Fixture.entries.associateWith { File(scratch, it.name.lowercase() + ".wav") }
        var speech: TextToSpeech? = null
        try {
            val initialized = CountDownLatch(1)
            val initStatus = AtomicInteger(TextToSpeech.ERROR)
            val tts = onMain(instrumentation) {
                TextToSpeech(context) { status ->
                    initStatus.set(status)
                    initialized.countDown()
                }
            }
            speech = tts
            assertTrue("NOT_READY: TTS initialization timed out", initialized.await(20L, TimeUnit.SECONDS))
            assertEquals("NOT_READY: TTS initialization failed", TextToSpeech.SUCCESS, initStatus.get())
            val voice = checkNotNull(onMain(instrumentation) {
                tts.voices.orEmpty()
                    .filter {
                        it.locale.language == "ko" && !it.isNetworkConnectionRequired &&
                            TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty()
                    }
                    .sortedBy { it.name }
                    .firstOrNull()
            }) { "NOT_READY: no installed offline Korean TTS voice" }
            onMain(instrumentation) {
                assertEquals("Offline voice selection failed", TextToSpeech.SUCCESS, tts.setVoice(voice))
                assertEquals("Cannot set standard fixture rate", TextToSpeech.SUCCESS, tts.setSpeechRate(1f))
                assertEquals("Cannot set standard fixture pitch", TextToSpeech.SUCCESS, tts.setPitch(1f))
                val selected = checkNotNull(tts.voice) { "NOT_READY: selected TTS voice is absent" }
                assertEquals("ko", selected.locale.language)
                assertFalse("Network voice cannot synthesize this diagnostic", selected.isNetworkConnectionRequired)
            }
            val voiceId = voice.name.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(96)
            report(instrumentation, "SYNTHETIC_WAKE voice=$voiceId offline=true human_accuracy=NOT_TESTED")
            val observations = linkedMapOf<Fixture, DecodeSummary>()
            val model = Model(directory.absolutePath)
            try {
                for (fixture in Fixture.entries) {
                    val file = files.getValue(fixture)
                    synthesize(instrumentation, tts, fixture, file)
                    val wav = readMonoPcm16(file)
                    val decoded = decode(model, wav)
                    observations[fixture] = decoded
                    report(
                        instrumentation,
                        "SYNTHETIC_WAKE fixture=" + fixture.name + " rate=" + wav.sampleRate +
                            " samples=" + wav.samples.size + " rms=" + wav.rms.toInt() + " " + decoded,
                    )
                }
            } finally {
                model.close()
            }
            val wake = observations.getValue(Fixture.WAKE)
            val nonWake = observations.getValue(Fixture.NON_WAKE)
            assertTrue(
                "Synthetic wake endpoint gate or non-wake rejection failed. WAKE=$wake NON_WAKE=$nonWake",
                wake.acceptedEndpoints > 0 &&
                    nonWake.acceptedEndpoints == 0 &&
                    nonWake.acceptedEof == 0,
            )
        } finally {
            try {
                if (InstrumentationRegistry.getArguments()
                        .getString("exportSyntheticWakeFixtures").toBoolean()) {
                    val export = File(context.cacheDir, "wake-fixtures-export")
                    check(export.isDirectory || export.mkdir())
                    files.values.filter(File::isFile).forEach { file ->
                        file.copyTo(File(export, file.name), overwrite = true)
                    }
                    report(instrumentation, "SYNTHETIC_WAKE fixtures_exported=true human_audio=false")
                }
                speech?.let { tts ->
                    onMain(instrumentation) {
                        tts.stop()
                        tts.shutdown()
                    }
                }
            } finally {
                val filesRemoved = files.values.map { !it.exists() || it.delete() }.all { it }
                val directoryRemoved = scratch.delete()
                assertTrue("Test-owned synthetic WAV cleanup failed", filesRemoved && directoryRemoved)
            }
        }
    }

    private fun synthesize(
        instrumentation: Instrumentation,
        tts: TextToSpeech,
        fixture: Fixture,
        file: File,
    ) {
        val id = "synthetic_wake_" + fixture.name
        val completed = CountDownLatch(1)
        val terminal = AtomicReference<String?>(null)
        fun finish(utteranceId: String?, result: String) {
            if (utteranceId == id && terminal.compareAndSet(null, result)) completed.countDown()
        }
        onMain(instrumentation) {
            assertEquals(TextToSpeech.SUCCESS, tts.setSpeechRate(fixture.rate))
            assertEquals(
                TextToSpeech.SUCCESS,
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) = finish(utteranceId, "DONE")
                    @Deprecated("Required legacy callback")
                    override fun onError(utteranceId: String?) = finish(utteranceId, "ERROR")
                    override fun onError(utteranceId: String?, errorCode: Int) =
                        finish(utteranceId, "ERROR_" + errorCode)
                    override fun onStop(utteranceId: String?, interrupted: Boolean) =
                        finish(utteranceId, "STOPPED")
                }),
            )
            assertEquals(
                "Synthetic file request was rejected",
                TextToSpeech.SUCCESS,
                tts.synthesizeToFile(fixture.text, Bundle(), file, id),
            )
        }
        assertTrue("Synthetic file completion timed out", completed.await(30L, TimeUnit.SECONDS))
        assertEquals("Synthetic file did not complete", "DONE", terminal.get())
    }

    private fun decode(model: Model, wav: PcmWav): DecodeSummary {
        val recognizer = Recognizer(model, wav.sampleRate.toFloat())
        val summary = DecodeSummary()
        try {
            // Match the production streaming decoder configuration, without a grammar.
            recognizer.setWords(true)
            recognizer.setPartialWords(false)
            recognizer.setMaxAlternatives(0)
            recognizer.setEndpointerMode(Recognizer.EndpointerMode.SHORT)
            val deadline = SystemClock.elapsedRealtime() + 30_000L
            val frameSize = (wav.sampleRate / 50).coerceAtLeast(1)
            fun feed(samples: ShortArray) {
                assertTrue("Native fixture inference exceeded its time bound", SystemClock.elapsedRealtime() < deadline)
                if (recognizer.acceptWaveForm(samples, samples.size)) {
                    recordFinal(recognizer.result, endpoint = true, summary = summary)
                }
            }
            var offset = 0
            while (offset < wav.samples.size) {
                val end = minOf(offset + frameSize, wav.samples.size)
                feed(wav.samples.copyOfRange(offset, end))
                offset = end
            }
            // Test-only trailing silence permits the existing SHORT endpointer to finish.
            // It is not saved, substituted for speech, or a production timeout change.
            var silenceRemaining = wav.sampleRate * 2
            while (silenceRemaining > 0) {
                val count = minOf(frameSize, silenceRemaining)
                feed(ShortArray(count))
                silenceRemaining -= count
            }
            // EOF output is diagnostic only, never promoted to streaming wake success.
            recordFinal(recognizer.finalResult, endpoint = false, summary = summary)
            return summary
        } finally {
            recognizer.close()
        }
    }

    private fun recordFinal(payload: String, endpoint: Boolean, summary: DecodeSummary) {
        val transcript = parseVoskTranscript(payload, isFinal = true) ?: return
        if (endpoint) summary.endpointFinals++ else summary.eofFinals++
        val extraction = WakePhraseCommandExtractor.extractFinalTranscript(
            transcript.text,
            WakePhraseCommandMode.WAKE_PHRASE_REQUIRED,
        )
        val addressed = when (extraction) {
            is WakePhraseCommandExtraction.NotAddressed -> false
            is WakePhraseCommandExtraction.AwaitingCommand -> true
            is WakePhraseCommandExtraction.Command -> true
        }
        val trusted = transcript.isFinal && transcript.isTrustedForCommand()
        if (addressed) summary.phraseMatches++
        if (trusted) summary.trustedFinals++
        transcript.confidence?.takeIf { it.isFinite() }?.let {
            summary.maxConfidence = maxOf(summary.maxConfidence ?: it, it)
        }
        if (addressed && trusted) {
            if (endpoint) summary.acceptedEndpoints++ else summary.acceptedEof++
        }
    }

    private fun readMonoPcm16(file: File): PcmWav {
        assertTrue("Invalid synthetic WAV size", file.length() in 44L..2_000_000L)
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun tag(offset: Int) = String(bytes, offset, 4, Charsets.US_ASCII)
        assertEquals("RIFF", tag(0))
        assertEquals("WAVE", tag(8))
        var rate = 0
        var formatSeen = false
        var dataOffset = -1
        var dataSize = 0
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val size = buffer.getInt(offset + 4)
            val start = offset + 8
            assertTrue("Invalid WAV chunk boundary", size >= 0 && start.toLong() + size <= bytes.size.toLong())
            when (tag(offset)) {
                "fmt " -> {
                    assertTrue("Unsupported WAV format block", size >= 16)
                    assertEquals("PCM encoding is required", 1, buffer.getShort(start).toInt() and 0xffff)
                    assertEquals("Mono synthesis is required", 1, buffer.getShort(start + 2).toInt() and 0xffff)
                    rate = buffer.getInt(start + 4)
                    assertTrue("Unsupported WAV sample rate", rate in 8_000..48_000)
                    assertEquals("Invalid PCM byte rate", rate * 2, buffer.getInt(start + 8))
                    assertEquals("Invalid PCM block alignment", 2, buffer.getShort(start + 12).toInt() and 0xffff)
                    assertEquals("PCM16 synthesis is required", 16, buffer.getShort(start + 14).toInt() and 0xffff)
                    formatSeen = true
                }
                "data" -> {
                    assertEquals("Multiple WAV data chunks are not supported", -1, dataOffset)
                    dataOffset = start
                    dataSize = size
                }
            }
            offset = start + size + (size and 1)
        }
        assertTrue("Missing PCM format or samples", formatSeen && dataOffset >= 0 && dataSize > 0)
        assertEquals("Truncated PCM16 sample", 0, dataSize % 2)
        val samples = ShortArray(dataSize / 2) { buffer.getShort(dataOffset + it * 2) }
        assertTrue("Synthetic utterance is unexpectedly long", samples.size <= rate * 15)
        val rms = sqrt(samples.sumOf { it.toDouble() * it.toDouble() } / samples.size)
        assertTrue("Synthetic waveform is entirely silent", rms > 0.0)
        return PcmWav(rate, samples, rms)
    }

    private fun report(instrumentation: Instrumentation, message: String) {
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", message + "\n") })
    }

    private fun <T> onMain(instrumentation: Instrumentation, action: () -> T): T {
        var result: T? = null
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            try {
                result = action()
            } catch (caught: Throwable) {
                failure = caught
            }
        }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private enum class Fixture(val text: String, val rate: Float = 1f) {
        WAKE("길라잡이"),
        WAKE_SLOW("길라잡이", 0.85f),
        WAKE_FAST("길라잡이", 1.15f),
        NON_WAKE("도움말"),
    }

    private data class PcmWav(val sampleRate: Int, val samples: ShortArray, val rms: Double)

    private data class DecodeSummary(
        var endpointFinals: Int = 0,
        var eofFinals: Int = 0,
        var phraseMatches: Int = 0,
        var trustedFinals: Int = 0,
        var acceptedEndpoints: Int = 0,
        var acceptedEof: Int = 0,
        var maxConfidence: Float? = null,
    )
}
