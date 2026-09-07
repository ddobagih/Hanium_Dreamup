package kr.co.hanium.dreamup.walksafe.voice

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wiring checks only; native endpoint timing and human recognition need device evidence. */
class VoskStreamingEndpointConfigurationStaticTest {
    @Test
    fun shortModeRemainsDefaultAndOnlyHomeOverridesItsDelays() {
        assertTrue(transcriber.contains("private val homeWakeEndpointing: Boolean = false"))
        assertTrue(
            Regex(
                """setEndpointerMode\(Recognizer\.EndpointerMode\.SHORT\)\s+if \(homeWakeEndpointing\) \{[^}]*setEndpointerDelays\(5\.0f, 0\.35f, 5\.0f\)\s+}""",
            ).containsMatchIn(transcriber),
        )
        assertEquals(1, Regex("""setEndpointerDelays\(""").findAll(transcriber).count())
    }

    @Test
    fun onlyTheExistingListenUntilWakeOptInEnablesHomeEndpointing() {
        assertTrue(probe.contains("listenUntilWake: Boolean = false"))
        assertTrue(probe.contains("homeWakeEndpointing = listenUntilWake"))
        assertTrue(probe.contains("homeWakeEndpointing: Boolean,"))
        assertTrue(probe.contains("homeWakeEndpointing = homeWakeEndpointing"))
        assertEquals(1, Regex("homeWakeEndpointing = listenUntilWake").findAll(probe).count())
    }

    private companion object {
        val transcriber by lazy { readVoiceSource("VoskStreamingTranscriber.kt") }
        val probe by lazy { readVoiceSource("VoskWakePhraseProbe.kt") }

        fun readVoiceSource(name: String): String {
            val source = sequenceOf(
                File("src/main/java"),
                File("app/src/main/java"),
                File("apps/android/app/src/main/java"),
            ).map { File(it, "kr/co/hanium/dreamup/walksafe/voice/$name") }
                .firstOrNull { it.isFile }
            return requireNotNull(source) { "Voice source unavailable for wiring regression" }.readText()
        }
    }
}
