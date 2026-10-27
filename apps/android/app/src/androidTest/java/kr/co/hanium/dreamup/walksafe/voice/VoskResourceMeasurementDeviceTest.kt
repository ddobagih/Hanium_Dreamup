package kr.co.hanium.dreamup.walksafe.voice

import android.os.Debug
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

/** Native model resource measurement only: synthetic silence, no microphone or command execution. */
@RunWith(AndroidJUnit4::class)
class VoskResourceMeasurementDeviceTest {
    /** Real APK assets/private filesystem, with corruption confined to a disposable copy. */
    @Test fun installedHashesAndIsolatedCorruptionRequireRepair() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertTrue(InstrumentationRegistry.getArguments().getString("allowVoskIntegrityProbe") == "true")
        val context = instrumentation.targetContext
        val actual = checkNotNull(BundledVoskModelInstaller.installedModelOrNull(context))
        val assetRoot = "voice-models/vosk-model-small-ko-0.22"
        val manifest = parseBundledVoskModelManifest(context.assets.open("$assetRoot/MODEL_FILES.sha256").use { it.readBytes() })
        val parent = File(context.cacheDir, "vosk-integrity-${java.util.UUID.randomUUID()}")
        fun report(value: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "VOSK_INTEGRITY $value\n") })
        try {
            val copy = BundledVoskModelInstaller.install(parent, manifest) { context.assets.open("$assetRoot/$it") }
            org.junit.Assert.assertNotEquals(actual.canonicalPath, copy.canonicalPath)
            report("installed_hashes_valid=true asset_files=${manifest.files.size} isolated_copy_ready=true")
            val entry = manifest.files.minBy { File(copy, it.relativePath).length() }
            val victim = File(copy, entry.relativePath)
            for (kind in listOf("changed_bytes", "missing_file", "symlink")) {
                when (kind) {
                    "changed_bytes" -> victim.appendBytes(byteArrayOf(0x21))
                    "missing_file" -> assertTrue(victim.delete())
                    "symlink" -> {
                        assertTrue(victim.delete())
                        java.nio.file.Files.createSymbolicLink(victim.toPath(), File(actual, entry.relativePath).toPath())
                    }
                }
                org.junit.Assert.assertNull("Corruption accepted: $kind", BundledVoskModelInstaller.installedModelOrNull(parent, manifest))
                // Never ask recursive cleanup to follow a link into the live model.
                if (kind == "symlink") java.nio.file.Files.delete(victim.toPath())
                val repaired = BundledVoskModelInstaller.install(parent, manifest) { context.assets.open("$assetRoot/$it") }
                org.junit.Assert.assertEquals(copy.canonicalPath, repaired.canonicalPath)
                org.junit.Assert.assertNotNull(BundledVoskModelInstaller.installedModelOrNull(parent, manifest))
                org.junit.Assert.assertEquals(actual, BundledVoskModelInstaller.installedModelOrNull(context))
                report("case=$kind rejected=true isolated_repair_valid=true live_model_unchanged=true")
            }
        } finally {
            parent.walkTopDown().filter { java.nio.file.Files.isSymbolicLink(it.toPath()) }
                .forEach { java.nio.file.Files.delete(it.toPath()) }
            parent.deleteRecursively()
        }
    }

    @Test fun repeatedNativeModelOpenDecodeClose() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertTrue(InstrumentationRegistry.getArguments().getString("allowVoskResourceMeasurement") == "true")
        val context = instrumentation.targetContext
        val directory = checkNotNull(BundledVoskModelInstaller.installedModelOrNull(context))
        val rows = JSONArray()
        fun memory() = JSONObject().apply {
            val info = Debug.MemoryInfo()
            Debug.getMemoryInfo(info)
            put("pssKiB", info.totalPss)
            put("nativeAllocatedBytes", Debug.getNativeHeapAllocatedSize())
        }
        val before = memory()
        repeat(12) { index ->
            val began = SystemClock.elapsedRealtimeNanos()
            val row = JSONObject().put("cycle", index + 1)
            Model(directory.absolutePath).use { model ->
                Recognizer(model, 16_000f).use { recognizer ->
                    recognizer.setWords(true)
                    recognizer.setPartialWords(false)
                    recognizer.setMaxAlternatives(0)
                    recognizer.setEndpointerMode(Recognizer.EndpointerMode.SHORT)
                    row.put("openMs", (SystemClock.elapsedRealtimeNanos() - began) / 1_000_000.0)
                    val silence = ShortArray(1_600)
                    repeat(20) { recognizer.acceptWaveForm(silence, silence.size) }
                    row.put("opened", memory())
                }
            }
            row.put("openDecodeCloseMs", (SystemClock.elapsedRealtimeNanos() - began) / 1_000_000.0)
            SystemClock.sleep(250)
            row.put("afterClose", memory())
            rows.put(row)
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "VOSK_RESOURCE $row\n") })
        }
        val result = JSONObject().put("before", before).put("cycles", rows)
            .put("scope", "Bundled native model/recognizer, 12 open-decode-close cycles, synthetic silence; no forced GC. Not actual wake latency, microphone, camera load, long-run leak or accuracy certification.")
        File(context.cacheDir, "walkmate-vosk-resources.json").writeText(result.toString(2))
    }
}
