package kr.co.hanium.dreamup.walksafe.voice

import android.annotation.TargetApi
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.ModelDownloadListener
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SDK-only diagnostic for the separately launched -no-audio AVD, never a physical device.
 * EXTRA_AUDIO_SOURCE can be ignored by a recognizer. The opt-in attests the external AVD
 * launch contract; neither the flag nor an exact transcript proves that no virtual mic opened.
 */
@TargetApi(Build.VERSION_CODES.TIRAMISU)
@RunWith(AndroidJUnit4::class)
class OnDeviceExternalPcmDeviceTest {

    @Test
    @TargetApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun downloadKoreanOnDeviceModelWhenExplicitlyAllowed() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assertEquals("EXPLICIT_VIRTUAL_AUDIO_OPT_IN_REQUIRED", "true", args.getString("virtual_audio_only"))
        assertEquals("EXPLICIT_ASR_DOWNLOAD_OPT_IN_REQUIRED", "true", args.getString("allow_korean_asr_download"))
        val emulatorHardware = Build.HARDWARE.lowercase(Locale.ROOT) in setOf("ranchu", "goldfish")
        val emulatorBuild = Build.PRODUCT.startsWith("sdk") || Build.FINGERPRINT.contains("generic")
        assertTrue("PHYSICAL_DEVICE_EXECUTION_FORBIDDEN", emulatorHardware && emulatorBuild)
        assertTrue("DOWNLOAD_CALLBACK_REQUIRES_API_34", Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        val context = instrumentation.targetContext
        assertTrue("NOT_READY: ON_DEVICE_SERVICE_UNAVAILABLE", SpeechRecognizer.isOnDeviceRecognitionAvailable(context))

        data class DownloadSupport(
            val installed: Boolean = false,
            val pending: Boolean = false,
            val downloadable: Boolean = false,
            val status: String,
        )

        val active = AtomicBoolean(true)
        val queryGeneration = AtomicLong()
        val downloadDone = CountDownLatch(1)
        val downloadStatus = AtomicReference<String?>()
        val lastProgressBucket = AtomicLong(-1)
        var recognizer: SpeechRecognizer? = null
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)

        fun querySupport(phase: String): DownloadSupport {
            val token = queryGeneration.incrementAndGet()
            val done = CountDownLatch(1)
            val result = AtomicReference<DownloadSupport?>()
            fun complete(value: DownloadSupport) {
                if (active.get() && queryGeneration.get() == token && result.compareAndSet(null, value)) {
                    done.countDown()
                }
            }
            try {
                instrumentation.runOnMainSync {
                    checkNotNull(recognizer).checkRecognitionSupport(
                        intent,
                        context.mainExecutor,
                        object : RecognitionSupportCallback {
                            override fun onSupportResult(support: RecognitionSupport) {
                                complete(
                                    DownloadSupport(
                                        installed = support.installedOnDeviceLanguages.any(::isKorean),
                                        pending = support.pendingOnDeviceLanguages.any(::isKorean),
                                        downloadable = support.supportedOnDeviceLanguages.any(::isKorean),
                                        status = "SUPPORTED",
                                    ),
                                )
                            }

                            override fun onError(error: Int) = complete(DownloadSupport(status = "ERROR_$error"))
                        },
                    )
                }
                if (!done.await(10, TimeUnit.SECONDS)) complete(DownloadSupport(status = "TIMEOUT"))
                return checkNotNull(result.get()).also {
                    report(
                        instrumentation,
                        "korean_asr_download phase=$phase support=${it.status} ko_installed=${it.installed} " +
                            "ko_pending=${it.pending} ko_downloadable=${it.downloadable}",
                    )
                }
            } finally {
                queryGeneration.compareAndSet(token, token + 1)
            }
        }

        fun completeDownload(value: String) {
            if (active.get() && downloadStatus.compareAndSet(null, value)) downloadDone.countDown()
        }

        try {
            instrumentation.runOnMainSync {
                recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                recognizer?.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) = Unit
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() = Unit
                    override fun onError(error: Int) = Unit
                    override fun onResults(results: Bundle?) = Unit
                    override fun onPartialResults(partialResults: Bundle?) = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                })
            }
            val before = querySupport("before")
            assertEquals("NOT_READY: SUPPORT_QUERY_FAILED", "SUPPORTED", before.status)
            if (before.installed) {
                report(instrumentation, "korean_asr_download terminal=ALREADY_INSTALLED download_requested=false")
                return
            }
            assertTrue("NOT_READY_PENDING: duplicate download not requested", !before.pending)
            assertTrue("NOT_READY_UNSUPPORTED: Korean ASR is not downloadable", before.downloadable)

            report(
                instrumentation,
                "korean_asr_download requested=true locale=ko-KR recognition_started=false " +
                    "timeout_seconds=180 timeout_does_not_guarantee_service_download_cancellation=true",
            )
            instrumentation.runOnMainSync {
                checkNotNull(recognizer).triggerModelDownload(
                    intent,
                    context.mainExecutor,
                    object : ModelDownloadListener {
                        override fun onProgress(progress: Int) {
                            if (!active.get() || downloadStatus.get() != null || progress !in 0..100) return
                            val bucket = (progress / 10).toLong()
                            if (lastProgressBucket.getAndSet(bucket) != bucket) {
                                report(instrumentation, "korean_asr_download progress=$progress")
                            }
                        }

                        override fun onSuccess() = completeDownload("SUCCESS")
                        override fun onScheduled() = completeDownload("SCHEDULED")
                        override fun onError(error: Int) = completeDownload("ERROR_$error")
                    },
                )
            }
            if (!downloadDone.await(180, TimeUnit.SECONDS)) completeDownload("TIMEOUT")
            val outcome = checkNotNull(downloadStatus.get())
            report(instrumentation, "korean_asr_download callback=$outcome")
            assertEquals("NOT_READY: download did not finish immediately", "SUCCESS", outcome)

            val after = querySupport("after")
            assertEquals("NOT_READY_AFTER_DOWNLOAD: SUPPORT_QUERY_FAILED", "SUPPORTED", after.status)
            assertTrue("NOT_READY_AFTER_DOWNLOAD: Korean ASR installation not confirmed", after.installed)
            report(instrumentation, "korean_asr_download terminal=INSTALLED_CONFIRMED recognition_started=false")
        } finally {
            active.set(false)
            queryGeneration.incrementAndGet()
            instrumentation.runOnMainSync { recognizer?.destroy() }
        }
    }

    @Test
    fun recognizesProvidedSyntheticPcmOnVirtualDevice() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val segmentedSession = args.getString("segmented_pcm_session", "true").let {
            require(it == "true" || it == "false") { "INVALID_SEGMENTED_PCM_SESSION_FLAG" }
            it == "true"
        }
        assertEquals("EXPLICIT_VIRTUAL_AUDIO_OPT_IN_REQUIRED", "true", args.getString("virtual_audio_only"))
        val emulatorHardware = Build.HARDWARE.lowercase(Locale.ROOT) in setOf("ranchu", "goldfish")
        val emulatorBuild = Build.PRODUCT.startsWith("sdk") || Build.FINGERPRINT.contains("generic")
        assertTrue("PHYSICAL_DEVICE_EXECUTION_FORBIDDEN", emulatorHardware && emulatorBuild)
        assertTrue("NOT_READY: EXTERNAL_AUDIO_REQUIRES_API_33", Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)

        val context = instrumentation.targetContext
        val cache = context.cacheDir.canonicalFile
        val allowedDirectory = File(cache, "voice-diagnostics-1788654121099").canonicalFile
        assertEquals("SYNTHETIC_DIRECTORY_OUTSIDE_TARGET_CACHE", cache, allowedDirectory.parentFile)
        val fixtures = listOf(
            Fixture("HELP", "도움말", "help_wav"),
            Fixture("START", "안내 시작", "start_wav"),
            Fixture("DESTINATION", "서울역으로 안내해줘", "destination_wav"),
        )
        val sources = fixtures.map { fixture ->
            val argument = checkNotNull(args.getString(fixture.argument)) {
                "EXPLICIT_SYNTHETIC_WAV_ARGUMENT_REQUIRED: ${fixture.argument}"
            }
            val source = File(argument).canonicalFile
            assertEquals("ONLY_APPROVED_SYNTHETIC_DIRECTORY_ALLOWED", allowedDirectory, source.parentFile)
            assertTrue(
                "ONLY_APPROVED_SYNTHETIC_FIXTURE_ALLOWED: ${fixture.id}",
                source.name in setOf("v0_${fixture.id}.wav", "v1_${fixture.id}.wav"),
            )
            assertTrue("SYNTHETIC_WAV_NOT_FOUND: ${fixture.id}", source.isFile)
            source
        }
        assertEquals("DUPLICATE_SYNTHETIC_WAV_ARGUMENTS", fixtures.size, sources.toSet().size)
        assertTrue(
            "NOT_READY: ON_DEVICE_SERVICE_UNAVAILABLE",
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context),
        )
        report(
            instrumentation,
            "source=preexisting_synthetic_wav sdk=${Build.VERSION.SDK_INT} " +
                "virtual_audio_opt_in=true host_audio_disabled=external_AVD_launch_contract " +
                "virtual_mic_fallback=possible segmented_session=$segmentedSession product_mapping=not_exercised",
        )

        val generation = AtomicLong()
        val failures = mutableListOf<String>()
        fixtures.zip(sources).forEach { (fixture, source) ->
            val pcm = readOriginalPcm(source)
            report(
                instrumentation,
                "${fixture.id} rate=${pcm.sampleRate} channels=${pcm.channels} format=PCM16 " +
                    "samples=${pcm.bytes.size / 2} duration_ms=${pcm.bytes.size.toLong() * 1_000 / (pcm.sampleRate * 2)} " +
                    "resampling=none wav_header_forwarded=false",
            )
            val result = recognizeFixture(instrumentation, context, fixture, pcm, generation, segmentedSession)
            val exact = result.text.filterNot(Char::isWhitespace) == fixture.text.filterNot(Char::isWhitespace)
            report(
                instrumentation,
                "${fixture.id} terminal=${result.status} ready_callback=${result.ready} " +
                    "partial_last=${result.partial} recognized=${result.text} raw_exact_match=$exact " +
                    "external_source_acceptance=${if (exact) "inferred_not_proven" else "not_established"} " +
                    "human_accuracy=not_measured",
            )
            if (result.status !in setOf("RESULTS", "SEGMENTED_END") || !exact) {
                failures += "${fixture.id}:${result.status}:${if (exact) "exact" else "text_mismatch"}"
            }
        }
        assertTrue("EXTERNAL_PCM_DIAGNOSTIC_FAILED: ${failures.joinToString()}", failures.isEmpty())
    }

    private fun recognizeFixture(
        instrumentation: Instrumentation,
        context: Context,
        fixture: Fixture,
        pcm: Pcm,
        generation: AtomicLong,
        segmentedSession: Boolean,
    ): DiagnosticResult {
        val token = generation.incrementAndGet()
        val supportDone = CountDownLatch(1)
        val support = AtomicReference<SupportResult?>()
        val terminalDone = CountDownLatch(1)
        val terminal = AtomicReference<Terminal?>()
        val ready = AtomicBoolean(false)
        val acceptingResults = AtomicBoolean(false)
        val partial = AtomicReference("")
        val segments = mutableListOf<RecognitionBatch>()
        val raw = File.createTempFile("on-device-synthetic-", ".pcm", context.cacheDir)
        var queryPfd: ParcelFileDescriptor? = null
        var recognitionPfd: ParcelFileDescriptor? = null
        var recognizer: SpeechRecognizer? = null

        fun current() = generation.get() == token
        fun complete(value: Terminal) {
            if (current() && acceptingResults.get() && terminal.compareAndSet(null, value)) {
                terminalDone.countDown()
            }
        }

        try {
            raw.writeBytes(pcm.bytes)
            queryPfd = ParcelFileDescriptor.open(raw, ParcelFileDescriptor.MODE_READ_ONLY)
            val queryIntent = recognitionIntent(checkNotNull(queryPfd), pcm, segmentedSession)
            instrumentation.runOnMainSync {
                recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                recognizer?.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        if (current() && acceptingResults.get()) ready.set(true)
                    }

                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() = Unit

                    override fun onError(error: Int) = complete(Terminal("ERROR_$error"))

                    override fun onResults(results: Bundle?) {
                        complete(Terminal("RESULTS", recognitionBatch(results)))
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        if (current() && acceptingResults.get() && terminal.get() == null) {
                            partial.set(recognitionBatch(partialResults).texts.firstOrNull().orEmpty())
                        }
                    }

                    override fun onSegmentResults(segmentResults: Bundle) {
                        if (current() && acceptingResults.get() && terminal.get() == null) {
                            val batch = recognitionBatch(segmentResults)
                            synchronized(segments) { segments += batch }
                            report(instrumentation, "${fixture.id} segment ${batch.summary()}")
                        }
                    }

                    override fun onEndOfSegmentedSession() = complete(Terminal("SEGMENTED_END"))
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                })
                recognizer?.checkRecognitionSupport(
                    queryIntent,
                    context.mainExecutor,
                    object : RecognitionSupportCallback {
                        override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                            val installed = recognitionSupport.installedOnDeviceLanguages.any(::isKorean)
                            val result = SupportResult(
                                installed,
                                "ko_installed=$installed " +
                                    "ko_pending=${recognitionSupport.pendingOnDeviceLanguages.any(::isKorean)} " +
                                    "ko_downloadable=${recognitionSupport.supportedOnDeviceLanguages.any(::isKorean)} " +
                                    "audio_source_support=not_separately_attested",
                            )
                            if (current() && support.compareAndSet(null, result)) supportDone.countDown()
                        }

                        override fun onError(error: Int) {
                            if (current() && support.compareAndSet(null, SupportResult(false, "query_error=$error"))) {
                                supportDone.countDown()
                            }
                        }
                    },
                )
            }
            if (!supportDone.await(10, TimeUnit.SECONDS)) {
                support.compareAndSet(null, SupportResult(false, "query_timeout=true"))
            }
            val capability = checkNotNull(support.get())
            report(instrumentation, "${fixture.id} support ${capability.summary}")
            queryPfd.close()
            queryPfd = null
            if (!capability.ready) {
                return DiagnosticResult("NOT_READY", "", partial.get(), ready.get())
            }

            // A fresh descriptor prevents support-query reads from advancing the recognition input.
            // The finite PCM file supplies EOF without a potentially blocked pipe-writer thread.
            recognitionPfd = ParcelFileDescriptor.open(raw, ParcelFileDescriptor.MODE_READ_ONLY)
            val intent = recognitionIntent(checkNotNull(recognitionPfd), pcm, segmentedSession)
            acceptingResults.set(true)
            instrumentation.runOnMainSync { recognizer?.startListening(intent) }
            if (!terminalDone.await(20, TimeUnit.SECONDS)) {
                complete(Terminal("TIMEOUT"))
            }
            acceptingResults.set(false)
            val completed = checkNotNull(terminal.get())
            val segmentSnapshot = synchronized(segments) { segments.toList() }
            completed.results?.let { report(instrumentation, "${fixture.id} final ${it.summary()}") }
            val text = completed.results?.texts?.firstOrNull()
                ?: segmentSnapshot.mapNotNull { it.texts.firstOrNull() }.joinToString(" ")
            return DiagnosticResult(completed.status, text.singleLine(), partial.get(), ready.get())
        } finally {
            acceptingResults.set(false)
            generation.compareAndSet(token, token + 1)
            try {
                instrumentation.runOnMainSync {
                    try {
                        recognizer?.cancel()
                    } finally {
                        recognizer?.destroy()
                    }
                }
            } finally {
                try {
                    queryPfd?.close()
                } finally {
                    try {
                        recognitionPfd?.close()
                    } finally {
                        raw.delete()
                    }
                }
            }
        }
    }

    private fun recognitionIntent(pfd: ParcelFileDescriptor, pcm: Pcm, segmentedSession: Boolean): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pfd)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, pcm.channels)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, pcm.sampleRate)
            .apply {
                if (segmentedSession) {
                    putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
                }
            }

    private fun recognitionBatch(bundle: Bundle?): RecognitionBatch {
        val texts = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            .take(3).map { it.singleLine() }
        val scores = bundle?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)?.take(3)
        return RecognitionBatch(texts, scores)
    }

    private fun readOriginalPcm(file: File): Pcm {
        assertTrue("WAV_SIZE_INVALID", file.length() in 44L..2_000_000L)
        val wav = file.readBytes()
        val buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        fun tag(offset: Int) = String(wav, offset, 4, Charsets.US_ASCII)
        assertEquals("WAV_RIFF_MISSING", "RIFF", tag(0))
        assertEquals("WAV_TYPE_UNSUPPORTED", "WAVE", tag(8))
        var sampleRate = 0
        var channels = 0
        var data: ByteArray? = null
        var offset = 12
        while (offset + 8 <= wav.size) {
            val size = buffer.getInt(offset + 4)
            assertTrue("WAV_CHUNK_INVALID", size >= 0 && offset.toLong() + 8 + size <= wav.size)
            val start = offset + 8
            when (tag(offset)) {
                "fmt " -> {
                    assertTrue("WAV_FORMAT_TRUNCATED", size >= 16)
                    assertEquals("WAV_NOT_INTEGER_PCM", 1, buffer.getShort(start).toInt())
                    channels = buffer.getShort(start + 2).toInt()
                    assertEquals("WAV_NOT_MONO", 1, channels)
                    sampleRate = buffer.getInt(start + 4)
                    assertTrue("WAV_SAMPLE_RATE_INVALID", sampleRate in 8_000..192_000)
                    assertEquals("WAV_NOT_PCM16", 16, buffer.getShort(start + 14).toInt())
                    assertEquals("WAV_BLOCK_ALIGNMENT_INVALID", channels * 2, buffer.getShort(start + 12).toInt())
                    assertEquals("WAV_BYTE_RATE_INVALID", sampleRate * channels * 2, buffer.getInt(start + 8))
                }
                "data" -> data = wav.copyOfRange(start, start + size)
            }
            offset = start + size + (size and 1)
        }
        val pcm = checkNotNull(data) { "WAV_DATA_MISSING" }
        assertTrue("WAV_FORMAT_MISSING", sampleRate > 0 && channels == 1)
        assertTrue("WAV_PCM_EMPTY_OR_TRUNCATED", pcm.isNotEmpty() && pcm.size % 2 == 0)
        return Pcm(pcm, sampleRate, channels)
    }

    private fun isKorean(tag: String): Boolean {
        val normalized = tag.lowercase(Locale.ROOT).replace('_', '-')
        return normalized == "ko" || normalized.startsWith("ko-")
    }

    private fun String.singleLine() = replace('\n', ' ').replace('\r', ' ').take(160)

    private fun report(instrumentation: Instrumentation, value: String) {
        instrumentation.sendStatus(0, Bundle().apply { putString("walksafe_external_pcm", value) })
    }

    private data class Fixture(val id: String, val text: String, val argument: String)
    private data class Pcm(val bytes: ByteArray, val sampleRate: Int, val channels: Int)
    private data class SupportResult(val ready: Boolean, val summary: String)
    private data class Terminal(val status: String, val results: RecognitionBatch? = null)
    private data class DiagnosticResult(val status: String, val text: String, val partial: String, val ready: Boolean)

    private data class RecognitionBatch(val texts: List<String>, val scores: List<Float>?) {
        fun summary(): String = texts.mapIndexed { index, text ->
            "candidate=$index text=$text confidence=${scores?.getOrNull(index)?.toString() ?: "missing"}"
        }.joinToString(" | ").ifEmpty { "text=empty confidence=missing" }
    }
}
