package kr.co.hanium.dreamup.walksafe.debuglog

import java.io.File
import kr.co.hanium.dreamup.walksafe.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Release JVM/source-set evidence only; signed-device network observation remains separate. */
class ReleaseDebugUploadBoundaryTest {
    @Test
    fun debugHttpImplementationsAreAbsentFromTheReleaseClasspath() {
        assertFalse(BuildConfig.DEBUG)
        listOf("HttpFrameCaptureUploader", "HttpMetadataLogUploader").forEach { name ->
            assertThrows(ClassNotFoundException::class.java) {
                Class.forName("kr.co.hanium.dreamup.walksafe.debuglog.$name")
            }
        }
    }

    @Test
    fun releaseFactoriesAndSharedCallersCannotDirectlySelectDebugHttpUploaders() {
        val base = "java/kr/co/hanium/dreamup/walksafe/debuglog"
        mapOf(
            "DebugFrameCaptureUploaderFactory" to "NoopFrameCaptureUploader",
            "DebugMetadataLogUploaderFactory" to "NoopMetadataLogUploader",
        ).forEach { (factory, noop) ->
            val source = File("src/release/$base/$factory.kt").readText()
            assertTrue(source.contains("= $noop"))
        }
        listOf("main", "release").forEach { sourceSet ->
            File("src/$sourceSet/java").walkTopDown().filter { it.extension == "kt" }.forEach { file ->
                val source = file.readText()
                listOf("HttpFrameCaptureUploader", "HttpMetadataLogUploader",
                    "/android/debug/frame-captures", "/android/debug/depth-logs").forEach { forbidden ->
                    assertFalse("$file references $forbidden", source.contains(forbidden))
                }
            }
        }
    }

    @Test
    fun releaseNoopCannotBeEnabledAndFramePayloadIsNotChanged() {
        val metadata = NoopMetadataLogUploader()
        metadata.setEnabled(true)
        assertFalse(metadata.isEnabled())
        assertEquals("server-log=off", metadata.statusText())
        metadata.cancelActiveUpload()
        metadata.close()
        val frame = byteArrayOf(1, 2, 3)
        NoopFrameCaptureUploader.upload(frame, "{}")
        assertTrue(frame.contentEquals(byteArrayOf(1, 2, 3)))
        NoopFrameCaptureUploader.cancelActiveUpload()
        NoopFrameCaptureUploader.close()
    }
}
