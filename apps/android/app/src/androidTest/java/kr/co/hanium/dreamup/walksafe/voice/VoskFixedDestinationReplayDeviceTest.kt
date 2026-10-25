package kr.co.hanium.dreamup.walksafe.voice

import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.speech.SpeechRecognizer
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import kr.co.hanium.dreamup.walksafe.BuildConfig
import kr.co.hanium.dreamup.walksafe.navigation.AndroidVoiceAction
import kr.co.hanium.dreamup.walksafe.navigation.selectAndroidVoiceAction
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.vosk.Model
import org.vosk.Recognizer

/** One fixed synthetic virtual-input replay, not guest AudioRecord or a production session replay. */
@RunWith(AndroidJUnit4::class)
class VoskFixedDestinationReplayDeviceTest {
    @Test(timeout = 60_000L)
    fun fixedVirtualInputMonitorMapsToSeoulStation() {
        assumeTrue(InstrumentationRegistry.getArguments()
            .getString("allowFixedSyntheticDestinationReplay") == "true")
        assertTrue("EMULATOR_ONLY", Build.HARDWARE in setOf("ranchu", "goldfish"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("kr.co.hanium.dreamup.walksafe", context.packageName)
        assertEquals("ba1415cc0eb72fc05f3c9f741898e3d70c57f9af4ae1d268812d0b6a3eb51d53",
            sha256(File(context.applicationInfo.sourceDir)))
        val input = File(context.cacheDir, "fixed-destination-input-monitor-16k.s16le")
        assertTrue(input.isFile && !Files.isSymbolicLink(input.toPath()))
        assertEquals(944_640L, input.length())
        val bytes = input.readBytes()
        assertEquals(944_640, bytes.size)
        assertEquals("3ea5e22482ac65fc3bfc647ebf675f7b4f24ce7c2c52f73ba581171e4517f986",
            sha256(bytes))
        val samples = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
        val modelDirectory = checkNotNull(BundledVoskModelInstaller.installedModelOrNull(context))
        var transcript: VoskTranscript? = null
        var rawResult: String? = null
        var consumed = 0
        // AndroidPcmAudioSource's private READ_CHUNK_SAMPLES is 320; keep production visibility intact.
        val chunkFrames = 320
        val decodeDeadline = SystemClock.elapsedRealtime() + 30_000L
        Model(modelDirectory.absolutePath).use { model ->
            Recognizer(model, 16_000f).use { recognizer ->
                recognizer.setWords(true)
                recognizer.setPartialWords(false)
                recognizer.setMaxAlternatives(0)
                recognizer.setEndpointerMode(Recognizer.EndpointerMode.SHORT)
                // Production read chunk size; no reset, padding or EOF flush. AudioRecord/IPC are absent.
                while (consumed < samples.size && transcript == null) {
                    assertTrue("DECODE_30_SECONDS_EXPIRED", SystemClock.elapsedRealtime() < decodeDeadline)
                    val chunk = samples.copyOfRange(consumed, minOf(consumed + chunkFrames, samples.size))
                    consumed += chunk.size
                    if (recognizer.acceptWaveForm(chunk, chunk.size)) {
                        rawResult = recognizer.result
                        transcript = parseVoskTranscript(checkNotNull(rawResult), isFinal = true)
                    }
                }
            }
        }
        val bundle = transcript?.toSpeechRecognitionResults()
        val action = bundle?.let {
            selectAndroidVoiceAction(it.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty(),
                it.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES))
        }
        val result = JSONObject()
            .put("input_kind", "FIXED_HOST_VIRTUAL_MONITOR_RESAMPLED_24K_TO_16K")
            .put("guest_pcm_or_production_replay_proved", false)
            .put("chunks_frames", chunkFrames).put("consumed_samples", consumed)
            .put("raw_result", rawResult ?: JSONObject.NULL)
            .put("transcript", transcript?.text ?: JSONObject.NULL)
            .put("confidence", transcript?.confidence ?: JSONObject.NULL)
            .put("selected_action", action?.toString() ?: JSONObject.NULL)
        instrumentation.sendStatus(0, Bundle().apply {
            putString("fixed_destination_replay_result", result.toString())
        })
        assertEquals(AndroidVoiceAction.SearchDestination("서울역"), action)
    }

    /** Replays preserved callback bytes only; it does not recover the original Main transcript. */
    @Test(timeout = 60_000L)
    fun capturedGuestDestinationPcmMapsToSeoulStation() {
        assumeTrue(InstrumentationRegistry.getArguments()
            .getString("allowCapturedGuestDestinationReplay") == "true")
        assertTrue("EMULATOR_ONLY", Build.HARDWARE in setOf("ranchu", "goldfish"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("kr.co.hanium.dreamup.walksafe", context.packageName)
        assertEquals(context.applicationInfo.uid, Process.myUid())
        assertTrue(BuildConfig.DEBUG && !BuildConfig.DEVELOPMENT_QUICK_START &&
            !BuildConfig.WALKSAFE_DEBUG_GUIDANCE_START_BYPASS)
        assertTrue(context.applicationInfo.splitSourceDirs.isNullOrEmpty())
        val appSha = "38d794285c2008bd535223bb1f30f45b0afed0cacc8c47a2789f846234fc451e"
        assertEquals(appSha, sha256(File(context.applicationInfo.sourceDir)))
        val cache = context.cacheDir
        assertTrue(cache.isDirectory && !Files.isSymbolicLink(cache.toPath()))
        assertEquals(Process.myUid(), Os.stat(cache.absolutePath).st_uid)
        val input = File(cache, "fixed-guest-destination-callback-16k.s16le")
        assertTrue(input.isFile && !Files.isSymbolicLink(input.toPath()))
        assertEquals(cache.canonicalFile, input.canonicalFile.parentFile)
        val inputStat = Os.lstat(input.absolutePath)
        assertTrue(OsConstants.S_ISREG(inputStat.st_mode))
        assertEquals(Process.myUid(), inputStat.st_uid)
        assertEquals(0x180, inputStat.st_mode and 0x1ff)
        assertEquals(96_000L, input.length())
        val bytes = input.readBytes()
        assertEquals(96_000, bytes.size)
        val inputSha = "4b3ad64087e46781c1c157dd1e4f6c5de436a49a42b2d742e8ab5e5e3626995c"
        assertEquals(inputSha, sha256(bytes))
        val samples = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
        val modelDirectory = checkNotNull(BundledVoskModelInstaller.installedModelOrNull(context))
        var transcript: VoskTranscript? = null
        var rawResult: String? = null
        var rawPartialResult: String? = null
        var consumed = 0
        val chunkFrames = 320
        val decodeDeadline = SystemClock.elapsedRealtime() + 30_000L
        Model(modelDirectory.absolutePath).use { model ->
            Recognizer(model, 16_000f).use { recognizer ->
                recognizer.setWords(true)
                recognizer.setPartialWords(false)
                recognizer.setMaxAlternatives(0)
                recognizer.setEndpointerMode(Recognizer.EndpointerMode.SHORT)
                // Exactly the preserved samples: no reset, padding, grammar or EOF flush.
                while (consumed < samples.size && transcript == null) {
                    assertTrue("DECODE_30_SECONDS_EXPIRED", SystemClock.elapsedRealtime() < decodeDeadline)
                    val chunk = samples.copyOfRange(consumed, minOf(consumed + chunkFrames, samples.size))
                    consumed += chunk.size
                    if (recognizer.acceptWaveForm(chunk, chunk.size)) {
                        rawResult = recognizer.result
                        transcript = parseVoskTranscript(checkNotNull(rawResult), isFinal = true)
                    }
                }
                if (transcript == null) rawPartialResult = recognizer.partialResult
            }
        }
        val bundle = transcript?.toSpeechRecognitionResults()
        val action = bundle?.let {
            selectAndroidVoiceAction(it.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty(),
                it.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES))
        }
        val output = File(cache, "fixed-guest-destination-replay-${SystemClock.elapsedRealtimeNanos()}.json")
        assertEquals(cache.canonicalFile, output.canonicalFile.parentFile)
        assertTrue(output.createNewFile())
        Os.chmod(output.absolutePath, 0x180)
        val result = JSONObject()
            .put("input_kind", "FIXED_CAPTURED_GUEST_CALLBACK_S16LE_16K_MONO")
            .put("original_Main_transcript_proved", false)
            .put("original_SIGABRT_cause_proved", false)
            .put("original_session_jni_accept_claimed", false)
            .put("app_sha256", appSha).put("uid", Process.myUid())
            .put("input_sha256", inputSha).put("input_bytes", bytes.size)
            .put("input_samples", samples.size).put("sample_rate_hz", 16_000)
            .put("chunks_frames", chunkFrames).put("consumed_samples", consumed)
            .put("raw_result", rawResult ?: JSONObject.NULL)
            .put("raw_partial_result", rawPartialResult ?: JSONObject.NULL)
            .put("transcript", transcript?.text ?: JSONObject.NULL)
            .put("confidence", transcript?.confidence ?: JSONObject.NULL)
            .put("selected_action", action?.toString() ?: JSONObject.NULL)
            .put("private_artifact", output.absolutePath)
        FileOutputStream(output).use { stream ->
            stream.write((result.toString(2) + "\n").toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        assertEquals(Process.myUid(), Os.lstat(output.absolutePath).st_uid)
        assertEquals(0x180, Os.lstat(output.absolutePath).st_mode and 0x1ff)
        instrumentation.sendStatus(0, Bundle().apply {
            putString("captured_guest_destination_replay_result", result.toString())
        })
        assertEquals(AndroidVoiceAction.SearchDestination("서울역"), action)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
