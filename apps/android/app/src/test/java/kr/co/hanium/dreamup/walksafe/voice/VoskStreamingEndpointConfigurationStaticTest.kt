package kr.co.hanium.dreamup.walksafe.voice

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wiring checks only; native endpoint timing and human recognition need device evidence. */
class VoskStreamingEndpointConfigurationStaticTest {
    @Test
    fun shortModeRemainsDefaultAndOnlyWakeEndpointOptInOverridesItsDelays() {
        assertTrue(transcriber.contains("private val homeWakeEndpointing: Boolean = false"))
        assertTrue(
            Regex(
                """setEndpointerMode\(Recognizer\.EndpointerMode\.SHORT\)\s+if \(homeWakeEndpointing\) \{[^}]*setEndpointerDelays\(5\.0f, 0\.35f, 5\.0f\)\s+}""",
            ).containsMatchIn(decoderService),
        )
        assertEquals(1, Regex("""setEndpointerDelays\(""").findAll(decoderService).count())
        assertTrue(transcriber.contains("RemoteVoskDecoder.open("))
        assertTrue(transcriber.contains("homeWakeEndpointing = homeWakeEndpointing"))
    }

    @Test
    fun wakeEndpointingDefaultsToListeningModeAndCanBeChosenIndependently() {
        assertTrue(probe.contains("listenUntilWake: Boolean = false"))
        assertTrue(probe.contains("wakeEndpointing: Boolean = listenUntilWake"))
        assertTrue(probe.contains("homeWakeEndpointing = wakeEndpointing"))
        assertTrue(probe.contains("homeWakeEndpointing: Boolean,"))
        assertTrue(probe.contains("homeWakeEndpointing = homeWakeEndpointing"))
        assertEquals(1, Regex("homeWakeEndpointing = wakeEndpointing").findAll(probe).count())
        assertEquals(0, Regex("homeWakeEndpointing = listenUntilWake").findAll(probe).count())
    }

    @Test
    fun boundedDeviceCheckChoosesWakeEndpointingWithoutChangingHomeOrTheTwentySecondTimeout() {
        assertTrue(homePlatform.contains("override fun createProbe("))
        val homeFactory = homePlatform.substringAfter("override fun createProbe(")
            .substringBefore("return object : WakeProbeEffect")
        assertTrue(homeFactory.contains("listenUntilWake = true"))
        assertFalse(homeFactory.contains("wakeEndpointing ="))
        assertTrue(deviceProbes.contains("PostLoginWakeProbeFactory { directory, ready, result ->"))
        val deviceFactory = deviceProbes.substringAfter("PostLoginWakeProbeFactory { directory, ready, result ->")
            .substringBefore("object : PostLoginWakeProbe")
        assertTrue(deviceFactory.contains("listenUntilWake = false"))
        assertTrue(deviceFactory.contains("wakeEndpointing = true"))
        assertFalse(deviceFactory.contains("timeoutMs ="))
        assertTrue(probe.contains("timeoutMs: Long = DEFAULT_TIMEOUT_MS"))
        assertTrue(probe.contains("const val DEFAULT_TIMEOUT_MS = 20_000L"))
        assertTrue(probe.contains("val listeningTimeout = if (listenUntilWake) null else try"))
    }

    private companion object {
        val transcriber by lazy { readSource("voice/VoskStreamingTranscriber.kt") }
        val decoderService by lazy { readSource("voice/VoskDecoderService.kt") }
        val probe by lazy { readSource("voice/VoskWakePhraseProbe.kt") }
        val homePlatform by lazy { readSource("voice/input/AndroidVoiceInputPlatform.kt") }
        val deviceProbes by lazy { readSource("device/postlogin/AndroidPostLoginDeviceProbes.kt") }

        fun readSource(name: String): String {
            val source = sequenceOf(
                File("src/main/java"),
                File("app/src/main/java"),
                File("apps/android/app/src/main/java"),
            ).map { File(it, "kr/co/hanium/dreamup/walksafe/$name") }
                .firstOrNull { it.isFile }
            return requireNotNull(source) { "Voice source unavailable for wiring regression" }.readText()
        }
    }
}
