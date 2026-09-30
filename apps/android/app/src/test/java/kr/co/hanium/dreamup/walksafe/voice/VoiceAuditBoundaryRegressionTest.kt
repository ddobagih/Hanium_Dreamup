package kr.co.hanium.dreamup.walksafe.voice

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class VoiceAuditBoundaryRegressionTest {
    @Test fun incompleteWordConfidenceNeverBecomesACompleteSentenceScore() {
        for (missing in listOf("{}", "{\"conf\":2}", "{\"conf\":-1}")) {
            val transcript = parseVoskTranscript(
                "{\"text\":\"길라잡이 중지\",\"result\":[{\"conf\":0.99},$missing]}", true,
            )
            assertNotNull(transcript)
            assertNull(transcript!!.confidence)
            assertFalse(transcript.isTrustedForCommand())
        }
        assertFalse(VoskTranscript("신고해줘", 1.5f, true).isTrustedForCommand())
    }

    @Test fun oversizedCommandIsRejectedInsteadOfDroppingItsTrailingCorrection() {
        assertNull(parseVoskTranscript("{\"text\":\"${"가".repeat(257)} 취소\"}", true))
        assertNotNull(parseVoskTranscript("{\"text\":\"${"가".repeat(256)}\"}", true))
        assertNull(parseVoskTranscript("{\"text\":{\"command\":\"신고\"}}", true))
    }

    @Test fun awaitedCommandKeepsPlaceWordsBeforeAnEmbeddedWakePhrase() {
        assertEquals(WakePhraseCommandExtraction.Command("서울 길라잡이 서점으로 안내해줘"),
            WakePhraseCommandExtractor.extractFinalTranscript("서울 길라잡이 서점으로 안내해줘", WakePhraseCommandMode.COMMAND_AWAITED))
        assertEquals(WakePhraseCommandExtraction.Command("서울역으로 안내해줘"),
            WakePhraseCommandExtractor.extractFinalTranscript("길라 잡이 서울역으로 안내해줘", WakePhraseCommandMode.COMMAND_AWAITED))
        // Wake-required mode accepts only direct address at the start (WakePhraseCommandExtractorTest):
        // a leading word before the wake phrase is narration/noise, not a command.
        assertEquals(WakePhraseCommandExtraction.NotAddressed,
            WakePhraseCommandExtractor.extractFinalTranscript("잡음 길라잡이 도움말"))
        assertEquals(WakePhraseCommandExtraction.Command("도움말"),
            WakePhraseCommandExtractor.extractFinalTranscript("길라잡이 도움말"))
    }

    @Test fun installedManifestChecksEveryFileAndDetectsSameSizeCorruption() {
        val directory = Files.createTempDirectory("vosk-audit-").toFile()
        try {
            val bytes = "valid-model".toByteArray()
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val file = File(directory, "model.bin").apply { writeBytes(bytes) }
            val manifest = BundledVoskModelManifest("manifest", listOf(BundledVoskModelFile(digest, "model.bin")))
            assertTrue(validateInstalledVoskFiles(directory, manifest, true))
            file.writeBytes(ByteArray(bytes.size))
            assertTrue(validateInstalledVoskFiles(directory, manifest, false))
            assertFalse(validateInstalledVoskFiles(directory, manifest, true))
            file.writeBytes(byteArrayOf())
            assertFalse(validateInstalledVoskFiles(directory, manifest, false))
            file.writeBytes(bytes)
            assertTrue(validateInstalledVoskFiles(directory, manifest, false))
            assertTrue(validateInstalledVoskFiles(directory, manifest, true))
            val missingOnly = manifest.copy(files = manifest.files + BundledVoskModelFile(digest, "missing.bin"))
            assertFalse(validateInstalledVoskFiles(directory, missingOnly, false))
            assertFalse(validateInstalledVoskFiles(directory, missingOnly, true))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun installedLookupRejectsSameSizeCorruptionAndInstallerRepairsIt() {
        val parent = Files.createTempDirectory("vosk-lookup-").toFile()
        try {
            val modelFiles = mapOf(
                "am/final.mdl" to "acoustic-model".toByteArray(),
                "conf/mfcc.conf" to "mfcc-config".toByteArray(),
                "conf/model.conf" to "model-config".toByteArray(),
                "graph/Gr.fst" to "grammar-graph".toByteArray(),
                "graph/HCLr.fst" to "lexicon-graph".toByteArray(),
            )
            val manifestBytes = modelFiles.entries.joinToString("\n", postfix = "\n") { (path, bytes) ->
                val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                "$hash  $path"
            }.toByteArray()
            val manifest = parseBundledVoskModelManifest(manifestBytes)
            val assets = modelFiles + ("MODEL_FILES.sha256" to manifestBytes)
            val openAsset: (String) -> ByteArrayInputStream = { path -> ByteArrayInputStream(assets.getValue(path)) }

            val installed = BundledVoskModelInstaller.install(parent, manifest, openAsset)
            assertEquals(installed, BundledVoskModelInstaller.installedModelOrNull(parent, manifest))

            val payload = File(installed, "am/final.mdl")
            val original = payload.readBytes()
            val tampered = original.clone().apply { this[0] = (this[0].toInt() xor 1).toByte() }
            payload.writeBytes(tampered)
            assertEquals(original.size.toLong(), payload.length())
            assertEquals(manifest.manifestSha256, File(installed, ".verified-model").readText())
            assertNull(BundledVoskModelInstaller.installedModelOrNull(parent, manifest))

            val repaired = BundledVoskModelInstaller.install(parent, manifest, openAsset)
            assertEquals(installed, repaired)
            assertArrayEquals(original, File(repaired, "am/final.mdl").readBytes())
            assertTrue(validateInstalledVoskFiles(repaired, manifest, verifyHashes = true))
            assertEquals(repaired, BundledVoskModelInstaller.installedModelOrNull(parent, manifest))
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test fun modelSymlinksAreRejectedWithoutTouchingExternalFiles() {
        val parent = Files.createTempDirectory("vosk-symlink-parent-").toFile()
        val outside = Files.createTempDirectory("vosk-symlink-outside-").toFile()
        val modelFiles = mapOf(
            "am/final.mdl" to "acoustic-model".toByteArray(),
            "conf/mfcc.conf" to "mfcc-config".toByteArray(),
            "conf/model.conf" to "model-config".toByteArray(),
            "graph/Gr.fst" to "grammar-graph".toByteArray(),
            "graph/HCLr.fst" to "lexicon-graph".toByteArray(),
        )
        val manifestBytes = modelFiles.entries.joinToString("\n", postfix = "\n") { (path, bytes) ->
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            "$hash  $path"
        }.toByteArray()
        val manifest = parseBundledVoskModelManifest(manifestBytes)
        val assets = modelFiles + ("MODEL_FILES.sha256" to manifestBytes)
        val openAsset = { path: String -> ByteArrayInputStream(assets.getValue(path)) }
        val outsideModel = BundledVoskModelInstaller.install(outside, manifest, openAsset)
        val rootLink = File(parent, outsideModel.name)
        val nestedLink = File(rootLink, "am")
        val original = File(outsideModel, "am/final.mdl").readBytes()
        try {
            Files.createSymbolicLink(rootLink.toPath(), outsideModel.toPath())
            assertNull(BundledVoskModelInstaller.installedModelOrNull(parent, manifest))
            assertInstallRejectsSymlink(parent, manifest, openAsset)
            assertArrayEquals(original, File(outsideModel, "am/final.mdl").readBytes())

            Files.delete(rootLink.toPath())
            val installed = BundledVoskModelInstaller.install(parent, manifest, openAsset)
            assertEquals(installed, BundledVoskModelInstaller.installedModelOrNull(parent, manifest))

            val externalAm = File(outside, "external-am")
            Files.move(nestedLink.toPath(), externalAm.toPath())
            Files.createSymbolicLink(nestedLink.toPath(), externalAm.toPath())
            assertNull(BundledVoskModelInstaller.installedModelOrNull(parent, manifest))
            assertInstallRejectsSymlink(parent, manifest, openAsset)
            assertArrayEquals(original, File(externalAm, "final.mdl").readBytes())

            Files.delete(nestedLink.toPath())
            Files.move(externalAm.toPath(), nestedLink.toPath())
            assertEquals(installed, BundledVoskModelInstaller.installedModelOrNull(parent, manifest))

            val inRootAm = File(installed, "am-copy")
            Files.move(nestedLink.toPath(), inRootAm.toPath())
            Files.createSymbolicLink(nestedLink.toPath(), inRootAm.toPath())
            assertNull(BundledVoskModelInstaller.installedModelOrNull(parent, manifest))
            assertInstallRejectsSymlink(parent, manifest, openAsset)
            assertArrayEquals(original, File(inRootAm, "final.mdl").readBytes())
        } finally {
            if (Files.isSymbolicLink(nestedLink.toPath())) Files.delete(nestedLink.toPath())
            if (Files.isSymbolicLink(rootLink.toPath())) Files.delete(rootLink.toPath())
            parent.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    private fun assertInstallRejectsSymlink(
        parent: File,
        manifest: BundledVoskModelManifest,
        openAsset: (String) -> ByteArrayInputStream,
    ) {
        try {
            BundledVoskModelInstaller.install(parent, manifest, openAsset)
            fail("Installation accepted a symbolic link")
        } catch (_: IOException) {
            // Rejection must happen before replacing the linked model.
        }
    }

    @Test fun independentCleanupContinuesAfterAudioOrRecognizerFailure() {
        val attempts = mutableListOf<String>()
        val lock = Any()
        var decoderState = "dirty"
        releaseVoskResources(
            decoderLock = lock,
            stopAudio = {
                assertFalse(Thread.holdsLock(lock))
                attempts += "audio"
                throw IllegalStateException("audio")
            },
            closeRecognizer = {
                assertTrue(Thread.holdsLock(lock))
                attempts += "recognizer"
                throw IllegalStateException("recognizer")
            },
            closeModel = {
                assertTrue(Thread.holdsLock(lock))
                attempts += "model"
            },
            resetDecoderState = {
                assertTrue(Thread.holdsLock(lock))
                attempts += "reset"
                decoderState = "reset"
            },
        )
        assertEquals(listOf("audio", "recognizer", "model", "reset"), attempts)
        assertEquals("reset", decoderState)
    }

    @Test fun fatalCleanupWaitsForRemainingActionsAndDecoderResetBeforeRethrowing() {
        for (fatalAt in listOf("audio", "recognizer", "model")) {
            val lock = Any()
            val attempts = mutableListOf<String>()
            val fatal: Error = if (fatalAt == "recognizer") UnsatisfiedLinkError("native close") else AssertionError(fatalAt)
            fun cleanup(name: String) {
                assertEquals(name != "audio", Thread.holdsLock(lock))
                attempts += name
                if (name == fatalAt) throw fatal
            }
            val thrown = try {
                releaseVoskResources(
                    decoderLock = lock,
                    stopAudio = { cleanup("audio") },
                    closeRecognizer = { cleanup("recognizer") },
                    closeModel = { cleanup("model") },
                    resetDecoderState = { cleanup("reset") },
                )
                null
            } catch (failure: Throwable) {
                failure
            }
            assertSame(fatalAt, fatal, thrown)
            assertEquals(fatalAt, listOf("audio", "recognizer", "model", "reset"), attempts)
            assertFalse(Thread.holdsLock(lock))
        }
    }

    @Test fun firstFatalIdentityIsPreservedAndLaterFatalsAreSuppressed() {
        val first = AssertionError("audio")
        val second = UnsatisfiedLinkError("recognizer")
        val third = AssertionError("model")
        val attempts = mutableListOf<String>()
        val thrown = try {
            releaseVoskResources(
                decoderLock = Any(),
                stopAudio = { attempts += "audio"; throw first },
                closeRecognizer = { attempts += "recognizer"; throw second },
                closeModel = { attempts += "model"; throw third },
                resetDecoderState = { attempts += "reset" },
            )
            null
        } catch (failure: Throwable) {
            failure
        }
        assertSame(first, thrown)
        assertEquals(listOf("audio", "recognizer", "model", "reset"), attempts)
        assertEquals(listOf(second, third), first.suppressed.toList())
    }

    @Test fun repeatedFatalObjectDoesNotSelfSuppressOrSkipLaterCleanup() {
        val fatal = AssertionError("same fatal")
        val attempts = mutableListOf<String>()
        val thrown = try {
            releaseVoskResources(
                decoderLock = Any(),
                stopAudio = { attempts += "audio"; throw fatal },
                closeRecognizer = { attempts += "recognizer"; throw fatal },
                closeModel = { attempts += "model"; throw IllegalStateException("ordinary cleanup failure") },
                resetDecoderState = { attempts += "reset" },
            )
            null
        } catch (failure: Throwable) {
            failure
        }
        assertSame(fatal, thrown)
        assertEquals(listOf("audio", "recognizer", "model", "reset"), attempts)
        assertTrue(fatal.suppressed.isEmpty())
    }

    @Test fun decoderResetFailurePreservesAnEarlierFatalOtherwisePropagatesItself() {
        for (first in listOf<Throwable?>(null, AssertionError("audio"))) {
            val resetFailure = IllegalStateException("reset")
            val attempts = mutableListOf<String>()
            val thrown = try {
                releaseVoskResources(
                    decoderLock = Any(),
                    stopAudio = { attempts += "audio"; if (first != null) throw first },
                    closeRecognizer = { attempts += "recognizer" },
                    closeModel = { attempts += "model" },
                    resetDecoderState = { attempts += "reset"; throw resetFailure },
                )
                null
            } catch (failure: Throwable) {
                failure
            }
            assertSame(first ?: resetFailure, thrown)
            assertEquals(listOf("audio", "recognizer", "model", "reset"), attempts)
            if (first != null) assertSame(resetFailure, first.suppressed.single())
        }
    }

    @Test fun audioReadFailureReleasesCaptureAndNotifiesOnceEvenWhenDetachObserverThrows() {
        for (observerThrows in listOf(false, true)) {
            val events = ConcurrentLinkedQueue<String>()
            val readyRuns = ConcurrentLinkedQueue<Long>()
            val errors = ConcurrentLinkedQueue<Pair<Long, VoskStreamingError>>()
            val ready = CountDownLatch(1)
            val audioStopped = CountDownLatch(1)
            lateinit var readError: (Int) -> Unit
            val audioSource = Proxy.newProxyInstance(
                VoskPcmSource::class.java.classLoader,
                arrayOf(VoskPcmSource::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "start" -> { events.add("start"); null }
                    "stop" -> { events.add("stop"); audioStopped.countDown(); null }
                    else -> throw AssertionError("Unexpected audio method: ${method.name}")
                }
            } as VoskPcmSource
            val transcriber = VoskStreamingTranscriber(
                context = null,
                modelDirectory = File("unused"),
                isInputSuppressed = { false },
                listener = object : VoskStreamingListener {
                    override fun onReady(runId: Long) {
                        readyRuns.add(runId)
                        events.add("ready")
                        ready.countDown()
                    }
                    override fun onTranscript(runId: Long, transcript: VoskTranscript) = Unit
                    override fun onError(runId: Long, error: VoskStreamingError) {
                        errors.add(runId to error)
                        events.add("error")
                    }
                },
                loader = Executors.newSingleThreadExecutor(),
                preparedRunFactory = { callback ->
                    readError = callback
                    VoskPreparedRun(audioSource, null, null)
                },
                onRuntimeResourcesDetached = {
                    events.add("detached")
                    if (observerThrows) throw IllegalStateException("detach observer")
                },
            )
            try {
                val runId = requireNotNull(transcriber.start())
                assertTrue("transcriber did not become ready", ready.await(5, TimeUnit.SECONDS))
                assertTrue(transcriber.isRunning())

                readError(-1)
                readError(-1)
                assertTrue("audio was not stopped", audioStopped.await(5, TimeUnit.SECONDS))

                assertFalse(transcriber.isRunning())
                assertEquals(listOf(runId), readyRuns.toList())
                assertEquals(listOf(runId to VoskStreamingError.AUDIO_READ_FAILED), errors.toList())
                val sequence = events.toList()
                assertEquals(5, sequence.size)
                assertEquals(listOf("start", "ready", "detached"), sequence.take(3))
                assertTrue(sequence.indexOf("error") > sequence.indexOf("detached"))
                assertTrue(sequence.indexOf("stop") > sequence.indexOf("detached"))
            } finally {
                transcriber.close()
            }
        }
    }

    @Test fun readyAlreadyPostedCannotReviveACancelledOrClosedProbe() {
        for (close in listOf(false, true)) {
            val harness = Probe()
            assertTrue(harness.probe.start())
            harness.listener.onReady(1L)
            if (close) harness.probe.close() else harness.probe.cancel()
            harness.drain()
            assertEquals(0, harness.ready)
            assertEquals(1, harness.results.size)
            assertEquals(if (close) VoskWakePhraseProbeFailure.CLOSED else VoskWakePhraseProbeFailure.CANCELLED,
                harness.results.single().failure)
        }
    }

    @Test fun outputThatStartsAfterFinalWasQueuedPreventsHomeWakeHandoff() {
        val harness = Probe()
        harness.probe.start()
        harness.listener.onReady(1L)
        harness.drain()
        harness.listener.onTranscript(1L, VoskTranscript("길라잡이 신고해줘", 0.95f, true))
        harness.suppressed = true
        harness.drain()
        assertTrue(harness.results.isEmpty())
        harness.suppressed = false
        harness.listener.onTranscript(1L, VoskTranscript("길라잡이 도움말", 0.95f, true))
        harness.drain()
        assertEquals("도움말", harness.results.single().commandTranscript?.text)
    }

    private class Probe {
        lateinit var listener: VoskStreamingListener
        val posts = ArrayDeque<() -> Unit>()
        val results = mutableListOf<VoskWakePhraseProbeResult>()
        var suppressed = false
        var ready = 0
        val probe = VoskWakePhraseProbe(
            streamFactory = VoskWakePhraseStreamFactory { callback ->
                listener = callback
                object : VoskWakePhraseStream {
                    override fun start() = Unit
                    override fun stop() = Unit
                    override fun close() = Unit
                }
            },
            dispatcher = object : VoskWakePhraseProbeDispatcher {
                override fun post(task: () -> Unit) { posts += task }
                override fun postDelayed(delayMs: Long, task: () -> Unit) = VoskWakePhraseProbeCancellation { }
            },
            timeoutMs = 1_000L,
            onResult = { results += it },
            onReady = { ready += 1 },
            listenUntilWake = true,
            isInputSuppressed = { suppressed },
        )
        fun drain() { while (posts.isNotEmpty()) posts.removeFirst().invoke() }
    }
}
