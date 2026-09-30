package kr.co.hanium.dreamup.walksafe.voice

import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoskStreamingPostStopStartRaceTest {
    @Test
    fun stopDuringAudioStartReleasesStaleSourceBeforeReplacementBecomesReady() {
        val loader = Executors.newSingleThreadExecutor()
        val oldLoadEntered = CountDownLatch(1)
        val allowOldLoad = CountDownLatch(1)
        val oldStartEntered = CountDownLatch(1)
        val allowOldStart = CountDownLatch(1)
        val oldSourceStopped = CountDownLatch(1)
        val replacementReady = CountDownLatch(1)
        val stopReturned = AtomicBoolean(false)
        val oldMicrophoneRunning = AtomicBoolean(false)
        val replacementMicrophoneRunning = AtomicBoolean(false)
        val oldStopCount = AtomicInteger()
        val factoryCount = AtomicInteger()
        val readyRuns = ConcurrentLinkedQueue<Long>()
        val errors = ConcurrentLinkedQueue<VoskStreamingError>()
        val transcriber = VoskStreamingTranscriber(
            context = null,
            modelDirectory = File("unused-test-model"),
            isInputSuppressed = { false },
            listener = object : VoskStreamingListener {
                override fun onReady(runId: Long) {
                    readyRuns.add(runId)
                    replacementReady.countDown()
                }

                override fun onTranscript(runId: Long, transcript: VoskTranscript) = Unit

                override fun onError(runId: Long, error: VoskStreamingError) {
                    errors.add(error)
                }
            },
            loader = loader,
            preparedRunFactory = {
                when (factoryCount.incrementAndGet()) {
                    1 -> {
                        oldLoadEntered.countDown()
                        check(allowOldLoad.await(5, TimeUnit.SECONDS))
                        VoskPreparedRun(
                            audioSource = object : VoskPcmSource {
                                override fun start() {
                                    oldStartEntered.countDown()
                                    check(allowOldStart.await(5, TimeUnit.SECONDS))
                                    check(stopReturned.get())
                                    oldMicrophoneRunning.set(true)
                                }

                                override fun stop() {
                                    oldMicrophoneRunning.set(false)
                                    oldStopCount.incrementAndGet()
                                    oldSourceStopped.countDown()
                                }
                            },
                            recognizer = null,
                            model = null,
                        )
                    }

                    2 -> VoskPreparedRun(
                        audioSource = object : VoskPcmSource {
                            override fun start() {
                                replacementMicrophoneRunning.set(true)
                            }

                            override fun stop() {
                                replacementMicrophoneRunning.set(false)
                            }
                        },
                        recognizer = null,
                        model = null,
                    )

                    else -> error("unexpected model load")
                }
            },
        )

        try {
            val oldRun = checkNotNull(transcriber.start())
            assertTrue(oldLoadEntered.await(2, TimeUnit.SECONDS))
            allowOldLoad.countDown()
            assertTrue(oldStartEntered.await(2, TimeUnit.SECONDS))

            transcriber.stop()
            stopReturned.set(true)
            val replacementRun = checkNotNull(transcriber.start())
            assertTrue(replacementRun != oldRun)
            assertFalse(replacementReady.await(100, TimeUnit.MILLISECONDS))

            allowOldStart.countDown()
            assertTrue(oldSourceStopped.await(2, TimeUnit.SECONDS))
            assertTrue(replacementReady.await(2, TimeUnit.SECONDS))
            assertFalse(oldMicrophoneRunning.get())
            assertTrue(replacementMicrophoneRunning.get())
            assertEquals(1, oldStopCount.get())
            assertEquals(listOf(replacementRun), readyRuns.toList())
            assertTrue(errors.isEmpty())
        } finally {
            allowOldLoad.countDown()
            allowOldStart.countDown()
            transcriber.close()
            loader.awaitTermination(2, TimeUnit.SECONDS)
        }
    }
}
