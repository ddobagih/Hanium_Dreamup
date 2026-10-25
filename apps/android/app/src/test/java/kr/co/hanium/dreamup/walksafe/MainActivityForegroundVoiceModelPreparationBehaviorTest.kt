package kr.co.hanium.dreamup.walksafe

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

/** Executes the HOME lookup, including preparation races, without installing a model or running audio. */
class MainActivityForegroundVoiceModelPreparationBehaviorTest {
    @Test fun repeatedHomeRendersJoinPreparationInsteadOfOpeningModelFiles() {
        val main = MainActivity()
        var requests = 0
        val request = { requests++; set(main, "handsFreeVoiceModelPreparing", true) }
        repeat(10) { assertNull(MainActivityControllerTestFixture.voiceInput(main).foregroundVoiceModelDirectoryOrNull(request)) }
        assertEquals(1, requests)
        assertFalse(field(main, "foregroundHomeWakeFailed") as Boolean)
    }

    @Test fun completedPreparationIsReusedWithoutAnotherWorkerRequest() {
        val main = MainActivity()
        val directory = Files.createTempDirectory("verified-voice-model").toFile()
        try {
            set(main, "handsFreeVoiceModelDirectory", directory)
            assertSame(directory, MainActivityControllerTestFixture.voiceInput(main).foregroundVoiceModelDirectoryOrNull { fail("already prepared") })
        } finally { directory.delete() }
    }

    @Test fun deletedPreparedDirectoryIsNotReusedAndCanBePreparedAgain() {
        val main = MainActivity()
        val directory = Files.createTempDirectory("removed-voice-model").toFile()
        directory.delete()
        set(main, "handsFreeVoiceModelDirectory", directory)
        var requests = 0
        assertNull(MainActivityControllerTestFixture.voiceInput(main).foregroundVoiceModelDirectoryOrNull { requests++ })
        assertNull(field(main, "handsFreeVoiceModelDirectory"))
        assertEquals(1, requests)
    }

    @Test fun genuinePreparationFailureDoesNotRetryOnEveryRender() {
        val main = MainActivity()
        set(main, "handsFreeVoiceModelPreparationFailed", true)
        repeat(10) { assertNull(MainActivityControllerTestFixture.voiceInput(main).foregroundVoiceModelDirectoryOrNull { fail("failure loop") }) }
    }

    @Test fun destroyedActivityCannotStartOrPublishAHomeLookup() {
        val main = MainActivity()
        set(main, "handsFreeVoiceDestroyed", true)
        assertNull(MainActivityControllerTestFixture.voiceInput(main).foregroundVoiceModelDirectoryOrNull { fail("destroyed owner") })
    }

    @Test fun homeUsesThePreparedLookupAndTheInstallerRemainsOnItsWorker() {
        val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        val factory = MainActivityRefactorSource.member(source, "createVoiceInputController")
        assertTrue(factory.contains("val platform = AndroidVoiceInputPlatform(this)"))
        assertTrue(factory.contains("platform = platform"))
        assertTrue(MainActivityRefactorSource.member(source, "refreshForegroundHomeWakeListening")
            .contains("voiceInputController.refreshForegroundHomeWakeListening()"))
        val core = File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/input/VoiceInputSessionController.kt").readText()
        assertTrue(MainActivityRefactorSource.member(core, "refreshForegroundHomeWakeListening")
            .contains("wake.refreshForegroundHomeWakeListening()"))
        assertTrue(core.contains("override fun modelDirectoryOrNull() = foregroundVoiceModelDirectoryOrNull()"))
        val home = MainActivityRefactorSource.member(File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/voice/input/ForegroundWakeInputController.kt").readText(),
            "refreshForegroundHomeWakeListening")
        assertTrue(home.contains("session.modelDirectoryOrNull()"))
        assertFalse(home.contains("installedModelOrNull"))
        assertTrue(home.substringAfter("if (directory == null)").contains("cancelForegroundHomeWakeListening()"))
        assertTrue(MainActivityRefactorSource.member(core, "prepareHandsFreeVoiceModel")
            .contains("handsFreeRuntime.prepareHandsFreeVoiceModel()"))
        val runtime = File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/input/HandsFreeRuntimeSession.kt").readText()
        val worker = MainActivityRefactorSource.member(runtime, "prepareHandsFreeVoiceModel")
        assertTrue(worker.indexOf("platform.executeModelWork") < worker.indexOf("platform.prepareInstalledModel()"))
        assertTrue(worker.contains("generation != handsFreeVoiceModelGeneration"))
        val installer = MainActivityRefactorSource.member(File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/voice/input/AndroidVoiceInputPlatform.kt").readText(),
            "prepareInstalledModel")
        assertTrue(installer.contains("BundledVoskModelInstaller.installedModelOrNull(context)"))

    }

    private fun field(main: MainActivity, name: String): Any? = MainActivityControllerTestFixture
        .field(name).apply { isAccessible = true }.get(main)
    private fun set(main: MainActivity, name: String, value: Any?) = MainActivityControllerTestFixture
        .field(name).apply { isAccessible = true }.set(main, value)
}
