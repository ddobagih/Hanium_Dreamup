package kr.co.hanium.dreamup.walksafe.voice

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VoskStreamingLifecycleRaceTest {
    @Test
    fun fatalAudioStopStillCompletesCleanupAndNotifiesStartFailureOnce() {
        val stopFailure = AssertionError("audio stop failed")
        var recognizerCloseCount = 0
        var modelCloseCount = 0
        var stateResetCount = 0
        var notificationCount = 0
        var reportedError: VoskStreamingError? = null

        val propagated = try {
            cleanupThenNotifyVoskFailure(
                cleanup = {
                    releaseVoskResources(
                        decoderLock = Any(),
                        stopAudio = { throw stopFailure },
                        closeRecognizer = { recognizerCloseCount++ },
                        closeModel = { modelCloseCount++ },
                        resetDecoderState = { stateResetCount++ },
                    )
                },
                notify = {
                    notificationCount++
                    reportedError = VoskStreamingError.AUDIO_START_FAILED
                },
            )
            null
        } catch (failure: AssertionError) {
            failure
        }

        assertSame(stopFailure, propagated)
        assertEquals(1, recognizerCloseCount)
        assertEquals(1, modelCloseCount)
        assertEquals(1, stateResetCount)
        assertEquals(1, notificationCount)
        assertEquals(VoskStreamingError.AUDIO_START_FAILED, reportedError)
    }

    @Test
    fun readFailureDuringAudioStartCannotPublishReadyWithADeadMicrophone() {
        val worker = Executors.newSingleThreadExecutor()
        val errors = mutableListOf<VoskStreamingError>()
        val readyCount = AtomicInteger()
        val stopCount = AtomicInteger()
        val errorDelivered = CountDownLatch(1)
        val transcriber = VoskStreamingTranscriber(
            context = null,
            modelDirectory = File("unused-test-model"),
            isInputSuppressed = { false },
            listener = listener(
                onReady = { readyCount.incrementAndGet() },
                onError = { error ->
                    synchronized(errors) { errors += error }
                    errorDelivered.countDown()
                },
            ),
            loader = worker,
            preparedRunFactory = { onReadError ->
                VoskPreparedRun(
                    audioSource = object : VoskPcmSource {
                        override fun start() {
                            onReadError(-3)
                        }

                        override fun stop() {
                            stopCount.incrementAndGet()
                        }
                    },
                    recognizer = null,
                    model = null,
                )
            },
        )
        try {
            assertNotNull(transcriber.start())
            assertTrue(errorDelivered.await(2, TimeUnit.SECONDS))
            assertFalse(transcriber.isRunning())
        } finally {
            transcriber.close()
            assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS))
        }
        assertEquals(0, readyCount.get())
        assertEquals(listOf(VoskStreamingError.AUDIO_READ_FAILED), synchronized(errors) { errors.toList() })
        assertEquals(1, stopCount.get())
    }

    @Test
    fun recreatedTranscriberWaitsForPreviousInstanceMicrophoneRelease() {
        val lease = VoiceMicrophoneLease()
        val previousOwner = Any()
        val nextOwner = Any()
        val previousReady = CountDownLatch(1)
        val previousStopEntered = CountDownLatch(1)
        val allowPreviousRelease = CountDownLatch(1)
        val nextAudioStart = CountDownLatch(1)
        val nextReady = CountDownLatch(1)
        val nextReleased = CountDownLatch(1)
        val errors = mutableListOf<VoskStreamingError>()
        val previous = VoskStreamingTranscriber(
            context = null,
            modelDirectory = File("unused-test-model"),
            isInputSuppressed = { false },
            listener = listener(onReady = { previousReady.countDown() }, onError = { error ->
                synchronized(errors) { errors += error }
            }),
            preparedRunFactory = {
                VoskPreparedRun(
                    audioSource = object : VoskPcmSource {
                        override fun start() {
                            check(lease.acquire(previousOwner))
                        }

                        override fun stop() {
                            previousStopEntered.countDown()
                            allowPreviousRelease.await()
                            lease.release(previousOwner)
                        }
                    },
                    recognizer = null,
                    model = null,
                )
            },
        )
        val next = VoskStreamingTranscriber(
            context = null,
            modelDirectory = File("unused-test-model"),
            isInputSuppressed = { false },
            listener = listener(onReady = { nextReady.countDown() }, onError = { error ->
                synchronized(errors) { errors += error }
            }),
            preparedRunFactory = {
                VoskPreparedRun(
                    audioSource = object : VoskPcmSource {
                        override fun start() {
                            nextAudioStart.countDown()
                            check(lease.acquire(nextOwner))
                        }

                        override fun stop() {
                            lease.release(nextOwner)
                            nextReleased.countDown()
                        }
                    },
                    recognizer = null,
                    model = null,
                )
            },
        )
        try {
            assertNotNull(previous.start())
            assertTrue(previousReady.await(2, TimeUnit.SECONDS))
            previous.close()
            assertTrue(previousStopEntered.await(2, TimeUnit.SECONDS))
            assertNotNull(next.start())
            assertFalse("new instance must not touch the leased microphone", nextAudioStart.await(100, TimeUnit.MILLISECONDS))
            allowPreviousRelease.countDown()
            assertTrue(nextReady.await(2, TimeUnit.SECONDS))
            assertTrue(synchronized(errors) { errors.isEmpty() })
        } finally {
            allowPreviousRelease.countDown()
            previous.close()
            next.close()
            assertTrue(nextReleased.await(2, TimeUnit.SECONDS))
        }
    }

    @Test
    fun blockedOldModelLoadDoesNotDelayNewInstanceBehindItsLoadingWatchdog() {
        val oldLoader = Executors.newSingleThreadExecutor()
        val nextLoader = Executors.newSingleThreadExecutor()
        val oldModelEntered = CountDownLatch(1)
        val allowOldModel = CountDownLatch(1)
        val oldSourceStopped = CountDownLatch(1)
        val nextModelEntered = CountDownLatch(1)
        val nextReady = CountDownLatch(1)
        val nextSourceStopped = CountDownLatch(1)
        val oldReadyCount = AtomicInteger()
        val errors = mutableListOf<VoskStreamingError>()
        val old = VoskStreamingTranscriber(
            context = null,
            modelDirectory = File("unused-test-model"),
            isInputSuppressed = { false },
            listener = listener(onReady = { oldReadyCount.incrementAndGet() }, onError = { error ->
                synchronized(errors) { errors += error }
            }),
            loader = oldLoader,
            preparedRunFactory = {
                oldModelEntered.countDown()
                allowOldModel.await()
                VoskPreparedRun(
                    audioSource = object : VoskPcmSource {
                        override fun start() = Unit
                        override fun stop() { oldSourceStopped.countDown() }
                    },
                    recognizer = null,
                    model = null,
                )
            },
        )
        val next = VoskStreamingTranscriber(
            context = null,
            modelDirectory = File("unused-test-model"),
            isInputSuppressed = { false },
            listener = listener(onReady = { nextReady.countDown() }, onError = { error ->
                synchronized(errors) { errors += error }
            }),
            loader = nextLoader,
            preparedRunFactory = {
                nextModelEntered.countDown()
                VoskPreparedRun(
                    audioSource = object : VoskPcmSource {
                        override fun start() = Unit
                        override fun stop() { nextSourceStopped.countDown() }
                    },
                    recognizer = null,
                    model = null,
                )
            },
        )
        try {
            assertNotNull(old.start())
            assertTrue(oldModelEntered.await(2, TimeUnit.SECONDS))
            old.close()
            assertNotNull(next.start())
            assertTrue("new model loading must run despite the old blocked loader", nextModelEntered.await(2, TimeUnit.SECONDS))
            assertTrue("new recording must become ready before the old load unblocks", nextReady.await(2, TimeUnit.SECONDS))
            assertEquals(0, oldReadyCount.get())
            assertTrue(synchronized(errors) { errors.isEmpty() })
        } finally {
            allowOldModel.countDown()
            old.close()
            next.close()
            assertTrue(oldLoader.awaitTermination(2, TimeUnit.SECONDS))
            assertTrue(nextLoader.awaitTermination(2, TimeUnit.SECONDS))
            assertTrue(oldSourceStopped.await(2, TimeUnit.SECONDS))
            assertTrue(nextSourceStopped.await(2, TimeUnit.SECONDS))
        }
        assertEquals(0, oldReadyCount.get())
    }

    @Test
    fun readErrorDetachCannotLetCloseAndReplacementOvertakeMicrophoneRelease() {
        val lease = VoiceMicrophoneLease()
        val oldOwner = Any()
        val nextOwner = Any()
        val readError = AtomicReference<((Int) -> Unit)>()
        val oldReady = CountDownLatch(1)
        val detached = CountDownLatch(1)
        val allowEnqueue = CountDownLatch(1)
        val oldReleaseEntered = CountDownLatch(1)
        val allowOldRelease = CountDownLatch(1)
        val oldErrorDelivered = CountDownLatch(1)
        val closeReturned = CountDownLatch(1)
        val nextAudioStart = CountDownLatch(1)
        val nextReady = CountDownLatch(1)
        val nextReleased = CountDownLatch(1)
        val nextErrors = mutableListOf<VoskStreamingError>()
        val old = VoskStreamingTranscriber(
            context = null,
            modelDirectory = File("unused-test-model"),
            isInputSuppressed = { false },
            listener = listener(onReady = { oldReady.countDown() }, onError = {
                oldErrorDelivered.countDown()
            }),
            preparedRunFactory = { callback ->
                readError.set(callback)
                VoskPreparedRun(
                    audioSource = object : VoskPcmSource {
                        override fun start() { check(lease.acquire(oldOwner)) }
                        override fun stop() {
                            oldReleaseEntered.countDown()
                            allowOldRelease.await()
                            lease.release(oldOwner)
                        }
                    },
                    recognizer = null,
                    model = null,
                )
            },
            onRuntimeResourcesDetached = {
                detached.countDown()
                allowEnqueue.await()
            },
        )
        val next = VoskStreamingTranscriber(
            context = null,
            modelDirectory = File("unused-test-model"),
            isInputSuppressed = { false },
            listener = listener(onReady = { nextReady.countDown() }, onError = { error ->
                synchronized(nextErrors) { nextErrors += error }
            }),
            preparedRunFactory = {
                VoskPreparedRun(
                    audioSource = object : VoskPcmSource {
                        override fun start() {
                            nextAudioStart.countDown()
                            check(lease.acquire(nextOwner))
                        }
                        override fun stop() {
                            lease.release(nextOwner)
                            nextReleased.countDown()
                        }
                    },
                    recognizer = null,
                    model = null,
                )
            },
        )
        var errorThread: Thread? = null
        var closeThread: Thread? = null
        try {
            assertNotNull(old.start())
            assertTrue(oldReady.await(2, TimeUnit.SECONDS))
            errorThread = Thread { checkNotNull(readError.get())(-3) }.also { it.start() }
            assertTrue(detached.await(2, TimeUnit.SECONDS))
            closeThread = Thread { old.close(); closeReturned.countDown() }.also { it.start() }
            assertFalse("close must not overtake the detached release", closeReturned.await(100, TimeUnit.MILLISECONDS))
            allowEnqueue.countDown()
            assertTrue(oldReleaseEntered.await(2, TimeUnit.SECONDS))
            assertTrue("close returns while native release is blocked", closeReturned.await(2, TimeUnit.SECONDS))
            assertTrue(oldErrorDelivered.await(2, TimeUnit.SECONDS))
            assertNotNull(next.start())
            assertFalse("new mic start must wait for old release", nextAudioStart.await(100, TimeUnit.MILLISECONDS))
            allowOldRelease.countDown()
            assertTrue(nextReady.await(2, TimeUnit.SECONDS))
            assertTrue(synchronized(nextErrors) { nextErrors.isEmpty() })
        } finally {
            allowEnqueue.countDown()
            allowOldRelease.countDown()
            old.close()
            next.close()
            errorThread?.join(2_000)
            closeThread?.join(2_000)
            assertTrue(nextReleased.await(2, TimeUnit.SECONDS))
        }
    }

    private fun listener(
        onReady: () -> Unit,
        onError: (VoskStreamingError) -> Unit,
    ): VoskStreamingListener = object : VoskStreamingListener {
        override fun onReady(runId: Long) = onReady()
        override fun onTranscript(runId: Long, transcript: VoskTranscript) = Unit
        override fun onError(runId: Long, error: VoskStreamingError) = onError(error)
    }
}
