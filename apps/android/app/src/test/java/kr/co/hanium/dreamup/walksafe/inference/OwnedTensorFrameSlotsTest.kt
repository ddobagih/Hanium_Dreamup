package kr.co.hanium.dreamup.walksafe.inference

import java.nio.ByteBuffer
import java.nio.ReadOnlyBufferException
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnedTensorFrameSlotsTest {
    @Test
    fun preparationCanWriteWhileInferenceRetainsItsOwnTensorAndMetadata() {
        val slots = slots()
        ready(slots, 1, marker = 17)
        val inference = accepted(slots.claimLatest(NOW))
        assertTrue(inference.inputBuffer.isDirect)
        assertTrue(inference.inputBuffer.isReadOnly)
        assertEquals(TENSOR_BYTES, inference.inputBuffer.remaining())
        assertThrows(ReadOnlyBufferException::class.java) { inference.inputBuffer.put(0, 1.toByte()) }
        inference.inputBuffer.position(4)
        inference.inputBuffer.limit(12)
        val original = bytes(inference.inputBuffer)
        val preparing = CountDownLatch(1)
        val finishPreparation = CountDownLatch(1)
        try {
            val writer = background {
                val preparation = begin(slots, 2)
                assertTrue(preparation.inputBuffer.isDirect)
                assertFalse(preparation.inputBuffer.isReadOnly)
                fill(preparation, 29)
                preparing.countDown()
                await(finishPreparation)
                slots.completePreparation(preparation, Metadata("second"), NOW)
            }
            await(preparing)
            assertEquals(OverlapFrameStatus.BUSY, slots.beginPreparation(key(3), NOW, NOW).status)
            assertEquals(OverlapFrameStatus.BUSY, slots.claimLatest(NOW).status)
            assertArrayEquals(original, bytes(inference.inputBuffer))
            assertEquals(4, inference.inputBuffer.position())
            assertEquals(12, inference.inputBuffer.limit())
            assertEquals(Metadata("frame-1"), inference.metadata)
            assertEquals(key(1), inference.key)
            assertEquals(NOW, inference.capturedAtElapsedRealtimeMs)

            finishPreparation.countDown()
            assertEquals(OverlapFrameStatus.ACCEPTED, writer.get(5, TimeUnit.SECONDS))
            assertEquals(OverlapFrameStatus.BUSY, slots.claimLatest(NOW).status)
            assertEquals(OverlapFrameStatus.ACCEPTED, slots.completeInference(inference, NOW))
            val next = accepted(slots.claimLatest(NOW))
            assertEquals(key(2), next.key)
            assertEquals(Metadata("second"), next.metadata)
            assertArrayEquals(ByteArray(TENSOR_BYTES) { 29 }, bytes(next.inputBuffer))
            assertEquals(OverlapFrameStatus.ACCEPTED, slots.completeInference(next, NOW))
            assertTrue(slots.isDrained())
        } finally {
            finishPreparation.countDown()
            slots.close()
        }
    }

    @Test
    fun newerAdmissionReplacesOnlyTheReadyTensorWhileInferenceIsActive() {
        val slots = slots()
        ready(slots, 1, marker = 11)
        val inference = accepted(slots.claimLatest(NOW))
        val original = bytes(inference.inputBuffer)
        ready(slots, 2, marker = 22)

        val replacing = slots.beginPreparation(key(3), NOW, NOW)
        assertEquals(key(2), replacing.replacedReadyKey)
        val preparation = accepted(replacing)
        fill(preparation, 33)
        assertArrayEquals(original, bytes(inference.inputBuffer))
        assertEquals(OverlapFrameStatus.ACCEPTED, slots.completePreparation(preparation, Metadata("newest"), NOW))
        assertEquals(OverlapFrameStatus.ACCEPTED, slots.completeInference(inference, NOW))

        val newest = accepted(slots.claimLatest(NOW))
        assertEquals(key(3), newest.key)
        assertEquals(Metadata("newest"), newest.metadata)
        assertArrayEquals(ByteArray(TENSOR_BYTES) { 33 }, bytes(newest.inputBuffer))
        assertEquals(OverlapFrameStatus.ACCEPTED, slots.completeInference(newest, NOW))
        assertEquals(OverlapFrameStatus.NO_READY_FRAME, slots.claimLatest(NOW).status)
        assertTrue(slots.isDrained())
    }

    @Test
    fun cancellingReplacementDoesNotResurrectTheDiscardedReadyFrame() {
        val slots = slots()
        ready(slots, 1)
        val replacement = slots.beginPreparation(key(2), NOW, NOW)
        assertEquals(key(1), replacement.replacedReadyKey)
        val preparation = accepted(replacement)
        assertEquals(OverlapFrameStatus.NO_READY_FRAME, slots.claimLatest(NOW).status)
        assertTrue(slots.cancelPreparation(preparation))
        assertFalse(slots.cancelPreparation(preparation))
        assertEquals(OverlapFrameStatus.NO_READY_FRAME, slots.claimLatest(NOW).status)
        assertTrue(slots.isDrained())
        assertEquals(OverlapFrameStatus.DUPLICATE_FRAME, slots.beginPreparation(key(2), NOW, NOW).status)
        val newer = begin(slots, 3)
        assertTrue(slots.cancelPreparation(newer))
    }

    @Test
    fun incompleteOrManuallyFlippedTensorCannotBecomeReady() {
        listOf(0, TENSOR_BYTES - 1, TENSOR_BYTES).forEach { writtenBytes ->
            val slots = slots()
            val preparation = begin(slots, 1)
            preparation.inputBuffer.put(ByteArray(writtenBytes) { 9 })
            if (writtenBytes == TENSOR_BYTES) preparation.inputBuffer.flip()
            assertEquals(
                OverlapFrameStatus.INCOMPLETE_TENSOR,
                slots.completePreparation(preparation, Metadata("incomplete"), NOW),
            )
            assertTrue(slots.isDrained())
            assertEquals(OverlapFrameStatus.NO_READY_FRAME, slots.claimLatest(NOW).status)
            ready(slots, 2)
            val complete = accepted(slots.claimLatest(NOW))
            assertEquals(0, complete.inputBuffer.position())
            assertEquals(TENSOR_BYTES, complete.inputBuffer.limit())
            assertEquals(OverlapFrameStatus.ACCEPTED, slots.completeInference(complete, NOW))
        }
    }

    @Test
    fun duplicatesAndBackwardTimestampsDoNotDiscardReadyOrAdvanceAdmissionOrder() {
        val slots = slots()
        val baseline = OverlapFrameKey(SESSION, 100, 200)
        val preparation = accepted(slots.beginPreparation(baseline, NOW, NOW))
        fill(preparation, 7)
        assertEquals(OverlapFrameStatus.ACCEPTED, slots.completePreparation(preparation, Metadata("baseline"), NOW))
        val rejected = listOf(
            OverlapFrameKey(SESSION, 100, 10_000) to OverlapFrameStatus.DUPLICATE_FRAME,
            OverlapFrameKey(SESSION, 10_000, 200) to OverlapFrameStatus.DUPLICATE_FRAME,
            OverlapFrameKey(SESSION, 99, 10_000) to OverlapFrameStatus.OUT_OF_ORDER_FRAME,
            OverlapFrameKey(SESSION, 10_000, 199) to OverlapFrameStatus.OUT_OF_ORDER_FRAME,
        )
        rejected.forEach { (key, expected) ->
            val result = slots.beginPreparation(key, NOW, NOW)
            assertEquals(expected, result.status)
            assertNull(result.value)
            assertNull(result.replacedReadyKey)
        }
        val retained = accepted(slots.claimLatest(NOW))
        assertEquals(baseline, retained.key)
        assertEquals(Metadata("baseline"), retained.metadata)
        assertEquals(OverlapFrameStatus.ACCEPTED, slots.completeInference(retained, NOW))
        val next = accepted(slots.beginPreparation(OverlapFrameKey(SESSION, 101, 201), NOW, NOW))
        assertTrue(slots.cancelPreparation(next))
    }

    @Test
    fun busyAdmissionDoesNotConsumeTheSourceKey() {
        val slots = slots()
        val preparing = begin(slots, 1)
        assertEquals(OverlapFrameStatus.BUSY, slots.beginPreparation(key(2), NOW, NOW).status)
        assertTrue(slots.cancelPreparation(preparing))
        val retry = begin(slots, 2)
        assertTrue(slots.cancelPreparation(retry))
    }

    @Test
    fun actualCameraTimestampMismatchPreservesReadyAndDoesNotPoisonAdmissionOrder() {
        val slots = slots()
        val originalKey = OverlapFrameKey(SESSION, 100, 100)
        val original = accepted(slots.beginPreparation(originalKey, NOW, NOW))
        fill(original, 19)
        assertEquals(OverlapFrameStatus.ACCEPTED, slots.completePreparation(original, Metadata("original"), NOW))

        val mismatched = slots.beginPreparation(
            key = OverlapFrameKey(SESSION, 1_000, 1_000),
            capturedAtElapsedRealtimeMs = NOW,
            nowElapsedRealtimeMs = NOW,
            actualCameraImageTimestampNs = 200,
        )
        assertEquals(OverlapFrameStatus.SOURCE_TIMESTAMP_MISMATCH, mismatched.status)
        assertNull(mismatched.value)
        assertNull(mismatched.replacedReadyKey)
        assertNull(mismatched.discardedKey)
        assertArrayEquals(ByteArray(TENSOR_BYTES) { 19 }, bytes(original.inputBuffer))

        val nextKey = OverlapFrameKey(SESSION, 300, 300)
        val next = slots.beginPreparation(nextKey, NOW, NOW, actualCameraImageTimestampNs = 300)
        assertEquals(originalKey, next.replacedReadyKey)
        val replacement = accepted(next)
        fill(replacement, 23)
        assertEquals(OverlapFrameStatus.ACCEPTED, slots.completePreparation(replacement, Metadata("next"), NOW))
        val inference = accepted(slots.claimLatest(NOW))
        assertEquals(nextKey, inference.key)
        assertEquals(Metadata("next"), inference.metadata)
        assertEquals(OverlapFrameStatus.ACCEPTED, slots.completeInference(inference, NOW))
    }

    @Test
    fun wrongSessionInvalidSourceAndInvalidCaptureClockDoNotReplaceReady() {
        val slots = slots()
        ready(slots, 1)
        val invalidKeys = listOf(
            key(2).copy(sessionId = SESSION + 1) to OverlapFrameStatus.WRONG_SESSION,
            key(2).copy(arFrameTimestampNs = 0) to OverlapFrameStatus.INVALID_TIMESTAMP,
            key(2).copy(cameraImageTimestampNs = -1) to OverlapFrameStatus.INVALID_TIMESTAMP,
        )
        invalidKeys.forEach { (key, expected) ->
            assertEquals(expected, slots.beginPreparation(key, NOW, NOW).status)
        }
        assertEquals(OverlapFrameStatus.INVALID_TIMESTAMP, slots.beginPreparation(key(2), NOW + 1, NOW).status)
        assertEquals(OverlapFrameStatus.INVALID_TIMESTAMP, slots.beginPreparation(key(2), -1, NOW).status)
        assertEquals(OverlapFrameStatus.STALE, slots.beginPreparation(key(2), NOW - 801, NOW).status)
        val retained = accepted(slots.claimLatest(NOW))
        assertEquals(key(1), retained.key)
        assertEquals(OverlapFrameStatus.ACCEPTED, slots.completeInference(retained, NOW))
        assertTrue(slots.cancelPreparation(begin(slots, 2)))
    }

    @Test
    fun exactEightHundredMillisecondSourceAgeIsAcceptedAtEveryStage() {
        val slots = slots()
        val now = NOW + 800
        val preparation = accepted(slots.beginPreparation(key(1), NOW, now))
        fill(preparation, 18)
        assertEquals(OverlapFrameStatus.ACCEPTED, slots.completePreparation(preparation, Metadata("boundary"), now))
        val inference = accepted(slots.claimLatest(now))
        assertEquals(NOW, inference.capturedAtElapsedRealtimeMs)
        assertEquals(OverlapFrameStatus.ACCEPTED, slots.completeInference(inference, now))
        assertTrue(slots.isDrained())
    }

    @Test
    fun sourceThatExpiresDuringPreparationIsReleasedWithoutBecomingReady() {
        val slots = slots()
        val preparation = begin(slots, 1)
        fill(preparation, 12)
        assertEquals(OverlapFrameStatus.STALE, slots.completePreparation(preparation, Metadata("expired"), NOW + 801))
        assertTrue(slots.isDrained())
        assertEquals(OverlapFrameStatus.NO_READY_FRAME, slots.claimLatest(NOW + 801).status)
        assertTrue(slots.cancelPreparation(begin(slots, 2, capturedAt = NOW + 801)))
    }

    @Test
    fun sourceThatExpiresWhileReadyCannotStartInference() {
        val slots = slots()
        ready(slots, 1)
        assertEquals(OverlapFrameStatus.STALE, slots.claimLatest(NOW + 801).status)
        assertTrue(slots.isDrained())
        assertEquals(OverlapFrameStatus.NO_READY_FRAME, slots.claimLatest(NOW + 801).status)
        assertTrue(slots.cancelPreparation(begin(slots, 2, capturedAt = NOW + 801)))
    }

    @Test
    fun rejectedReadyClaimReportsItsDiscardedKeyExactlyOnce() {
        listOf(
            NOW + 801 to OverlapFrameStatus.STALE,
            NOW - 1 to OverlapFrameStatus.INVALID_TIMESTAMP,
        ).forEach { (now, expected) ->
            val slots = slots()
            ready(slots, 1)
            val rejected = slots.claimLatest(now)
            assertEquals(expected, rejected.status)
            assertEquals(key(1), rejected.discardedKey)
            assertNull(rejected.value)
            assertNull(rejected.replacedReadyKey)
            assertTrue(slots.isDrained())

            val repeated = slots.claimLatest(now)
            assertEquals(OverlapFrameStatus.NO_READY_FRAME, repeated.status)
            assertNull(repeated.discardedKey)
            slots.close()
            assertNull(slots.claimLatest(NOW).discardedKey)
        }
    }

    @Test
    fun sourceThatExpiresDuringInferenceCannotBePublished() {
        val slots = slots()
        ready(slots, 1)
        val inference = accepted(slots.claimLatest(NOW + 800))
        assertEquals(OverlapFrameStatus.STALE, slots.completeInference(inference, NOW + 801))
        assertTrue(slots.isDrained())
        assertEquals(OverlapFrameStatus.INVALID_LEASE, slots.completeInference(inference, NOW + 801))
        assertTrue(slots.cancelPreparation(begin(slots, 2, capturedAt = NOW + 801)))
    }

    @Test
    fun captureClockCannotMoveIntoTheFutureAtLaterStages() {
        val preparingSlots = slots()
        val preparation = begin(preparingSlots, 1)
        fill(preparation, 1)
        assertEquals(
            OverlapFrameStatus.INVALID_TIMESTAMP,
            preparingSlots.completePreparation(preparation, Metadata("future"), NOW - 1),
        )
        assertTrue(preparingSlots.isDrained())

        val readySlots = slots()
        ready(readySlots, 1)
        assertEquals(OverlapFrameStatus.INVALID_TIMESTAMP, readySlots.claimLatest(NOW - 1).status)
        assertTrue(readySlots.isDrained())

        val inferenceSlots = slots()
        ready(inferenceSlots, 1)
        val inference = accepted(inferenceSlots.claimLatest(NOW))
        assertEquals(OverlapFrameStatus.INVALID_TIMESTAMP, inferenceSlots.completeInference(inference, NOW - 1))
        assertTrue(inferenceSlots.isDrained())
    }

    @Test
    fun stalePreparationLeaseCannotCompleteOrCancelAReusedSlot() {
        val slots = slots()
        val old = begin(slots, 1)
        assertTrue(slots.cancelPreparation(old))
        val current = begin(slots, 2)
        fill(current, 24)
        assertEquals(OverlapFrameStatus.INVALID_LEASE, slots.completePreparation(old, Metadata("old"), NOW))
        assertFalse(slots.cancelPreparation(old))
        assertEquals(OverlapFrameStatus.BUSY, slots.beginPreparation(key(3), NOW, NOW).status)
        assertEquals(OverlapFrameStatus.ACCEPTED, slots.completePreparation(current, Metadata("current"), NOW))
        assertEquals(OverlapFrameStatus.INVALID_LEASE, slots.completePreparation(current, Metadata("duplicate"), NOW))
        val inference = accepted(slots.claimLatest(NOW))
        assertEquals(Metadata("current"), inference.metadata)
        assertArrayEquals(ByteArray(TENSOR_BYTES) { 24 }, bytes(inference.inputBuffer))
        assertEquals(OverlapFrameStatus.ACCEPTED, slots.completeInference(inference, NOW))
    }

    @Test
    fun staleInferenceLeaseCannotReleaseOrRepublishAReusedSlot() {
        val slots = slots()
        ready(slots, 1)
        val old = accepted(slots.claimLatest(NOW))
        assertEquals(OverlapFrameStatus.ACCEPTED, slots.completeInference(old, NOW))
        ready(slots, 2, marker = 27)
        val current = accepted(slots.claimLatest(NOW))
        assertEquals(OverlapFrameStatus.INVALID_LEASE, slots.completeInference(old, NOW))
        assertEquals(OverlapFrameStatus.BUSY, slots.claimLatest(NOW).status)
        assertEquals(key(2), current.key)
        assertArrayEquals(ByteArray(TENSOR_BYTES) { 27 }, bytes(current.inputBuffer))
        assertEquals(OverlapFrameStatus.ACCEPTED, slots.completeInference(current, NOW))
        assertEquals(OverlapFrameStatus.INVALID_LEASE, slots.completeInference(current, NOW))
        assertTrue(slots.isDrained())
    }

    @Test
    fun leasesFromAnotherInstanceCannotChangeMatchingSlots() {
        val first = slots()
        val second = slots()
        val firstPreparation = begin(first, 1)
        val secondPreparation = begin(second, 1)
        fill(firstPreparation, 3)
        fill(secondPreparation, 4)
        assertEquals(OverlapFrameStatus.INVALID_LEASE, second.completePreparation(firstPreparation, Metadata("foreign"), NOW))
        assertFalse(second.cancelPreparation(firstPreparation))
        assertEquals(OverlapFrameStatus.ACCEPTED, first.completePreparation(firstPreparation, Metadata("first"), NOW))
        assertEquals(OverlapFrameStatus.ACCEPTED, second.completePreparation(secondPreparation, Metadata("second"), NOW))
        val firstInference = accepted(first.claimLatest(NOW))
        val secondInference = accepted(second.claimLatest(NOW))
        assertEquals(OverlapFrameStatus.INVALID_LEASE, second.completeInference(firstInference, NOW))
        assertEquals(OverlapFrameStatus.BUSY, second.claimLatest(NOW).status)
        assertEquals(Metadata("second"), secondInference.metadata)
        assertEquals(OverlapFrameStatus.ACCEPTED, first.completeInference(firstInference, NOW))
        assertEquals(OverlapFrameStatus.ACCEPTED, second.completeInference(secondInference, NOW))
        assertTrue(first.isDrained())
        assertTrue(second.isDrained())
    }

    @Test
    fun failedInferenceReleasesItsSlotAndPreservesTheNextReadyFrame() {
        val slots = slots()
        ready(slots, 1)
        val failed = accepted(slots.claimLatest(NOW))
        ready(slots, 2)
        assertEquals(OverlapFrameStatus.FAILED, slots.completeInference(failed, NOW, succeeded = false))
        val next = accepted(slots.claimLatest(NOW))
        assertEquals(key(2), next.key)
        assertEquals(OverlapFrameStatus.ACCEPTED, slots.completeInference(next, NOW))
        assertTrue(slots.isDrained())
    }

    @Test
    fun closePreservesActiveTensorMemoryUntilBothLeasesAreReturned() {
        val slots = slots()
        ready(slots, 1, marker = 31)
        val inference = accepted(slots.claimLatest(NOW))
        inference.inputBuffer.position(3)
        inference.inputBuffer.limit(13)
        val preparation = begin(slots, 2)
        preparation.inputBuffer.put(ByteArray(8) { 41 })
        preparation.inputBuffer.limit(12)
        val inferenceBytes = bytes(inference.inputBuffer)
        val preparationBytes = bytes(preparation.inputBuffer)

        slots.close()
        slots.close()
        assertFalse(slots.isDrained())
        assertEquals(OverlapFrameStatus.CLOSED, slots.beginPreparation(key(3), NOW, NOW).status)
        assertEquals(OverlapFrameStatus.CLOSED, slots.claimLatest(NOW).status)
        assertArrayEquals(inferenceBytes, bytes(inference.inputBuffer))
        assertArrayEquals(preparationBytes, bytes(preparation.inputBuffer))
        assertEquals(3, inference.inputBuffer.position())
        assertEquals(13, inference.inputBuffer.limit())
        assertEquals(8, preparation.inputBuffer.position())
        assertEquals(12, preparation.inputBuffer.limit())

        preparation.inputBuffer.put(8, 55.toByte())
        assertEquals(OverlapFrameStatus.CLOSED, slots.completePreparation(preparation, Metadata("late"), NOW))
        assertFalse(slots.isDrained())
        assertArrayEquals(inferenceBytes, bytes(inference.inputBuffer))
        assertEquals(OverlapFrameStatus.CLOSED, slots.completeInference(inference, NOW))
        assertTrue(slots.isDrained())
        assertEquals(OverlapFrameStatus.INVALID_LEASE, slots.completePreparation(preparation, Metadata("again"), NOW))
        assertEquals(OverlapFrameStatus.INVALID_LEASE, slots.completeInference(inference, NOW))
    }

    @Test
    fun closeImmediatelyDiscardsReadyAndCancellationDrainsActivePreparation() {
        val readySlots = slots()
        ready(readySlots, 1)
        assertFalse(readySlots.isDrained())
        readySlots.close()
        assertTrue(readySlots.isDrained())
        assertEquals(OverlapFrameStatus.CLOSED, readySlots.claimLatest(NOW).status)

        val preparingSlots = slots()
        val preparing = begin(preparingSlots, 1)
        preparingSlots.close()
        assertFalse(preparingSlots.isDrained())
        assertTrue(preparingSlots.cancelPreparation(preparing))
        assertTrue(preparingSlots.isDrained())
        assertFalse(preparingSlots.cancelPreparation(preparing))
    }

    @Test
    fun repeatedClosePreservesTheDiscardedReadyKeyUntilOneClosedClaimConsumesIt() {
        val slots = slots()
        ready(slots, 1)
        slots.close()
        slots.close()
        assertTrue(slots.isDrained())
        assertEquals(OverlapFrameStatus.CLOSED, slots.beginPreparation(key(2), NOW, NOW).status)
        val firstClaim = slots.claimLatest(NOW)
        assertEquals(OverlapFrameStatus.CLOSED, firstClaim.status)
        assertEquals(key(1), firstClaim.discardedKey)
        assertNull(firstClaim.value)
        slots.close()
        val repeatedClaim = slots.claimLatest(NOW)
        assertEquals(OverlapFrameStatus.CLOSED, repeatedClaim.status)
        assertNull(repeatedClaim.discardedKey)
        assertNull(slots.claimLatest(NOW).discardedKey)
    }

    @Test
    fun closeReportsOnlyTheReadyKeyWhileActiveInferenceCompletesSeparately() {
        val slots = slots()
        ready(slots, 1)
        val inference = accepted(slots.claimLatest(NOW))
        ready(slots, 2)
        slots.close()
        assertFalse(slots.isDrained())
        val closedClaim = slots.claimLatest { NOW }
        assertEquals(OverlapFrameStatus.CLOSED, closedClaim.status)
        assertEquals(key(2), closedClaim.discardedKey)
        assertEquals(OverlapFrameStatus.CLOSED, slots.completeInference(inference, NOW))
        assertTrue(slots.isDrained())
        assertNull(slots.claimLatest { NOW }.discardedKey)
    }

    @Test
    fun clockSupplierIsReadUnderTheSlotLockBeforePreparationCanComplete() {
        val slots = slots()
        val preparation = begin(slots, 1)
        fill(preparation, 37)
        val clockEntered = CountDownLatch(1)
        val finishClock = CountDownLatch(1)
        val producerStarted = CountDownLatch(1)
        val producerThread = AtomicReference<Thread>()
        val reads = AtomicInteger()
        try {
            val claiming = background {
                slots.claimLatest {
                    reads.incrementAndGet()
                    clockEntered.countDown()
                    await(finishClock)
                    NOW
                }
            }
            await(clockEntered)
            val producer = background {
                producerThread.set(Thread.currentThread())
                producerStarted.countDown()
                slots.completePreparation(preparation, Metadata("ready-after-clock"), NOW)
            }
            await(producerStarted)
            awaitBlocked(requireNotNull(producerThread.get()))
            assertFalse(producer.isDone)
            finishClock.countDown()
            assertEquals(OverlapFrameStatus.NO_READY_FRAME, claiming.get(5, TimeUnit.SECONDS).status)
            assertEquals(1, reads.get())
            assertEquals(OverlapFrameStatus.ACCEPTED, producer.get(5, TimeUnit.SECONDS))

            val expired = slots.claimLatest { NOW + 801 }
            assertEquals(OverlapFrameStatus.STALE, expired.status)
            assertEquals(key(1), expired.discardedKey)
            assertTrue(slots.isDrained())
        } finally {
            finishClock.countDown()
            slots.close()
        }
    }

    @Test
    fun configuredSourceAgeMayTightenTheBoundary() {
        val slots = OwnedTensorFrameSlots<Metadata>(SESSION, TENSOR_BYTES, maximumSourceAgeMs = 600)
        val preparation = accepted(slots.beginPreparation(key(1), NOW, NOW + 600))
        fill(preparation, 43)
        assertEquals(OverlapFrameStatus.ACCEPTED, slots.completePreparation(preparation, Metadata("boundary"), NOW + 600))
        val inference = accepted(slots.claimLatest { NOW + 600 })
        assertEquals(OverlapFrameStatus.ACCEPTED, slots.completeInference(inference, NOW + 600))
        assertEquals(OverlapFrameStatus.STALE, slots.beginPreparation(key(2), NOW, NOW + 601).status)
        assertTrue(slots.isDrained())
    }

    @Test
    fun sourceAgeConfigurationCannotExceedEightHundredMillisecondsOrBeNonpositive() {
        listOf(-1L, 0L, 801L, Long.MAX_VALUE).forEach { maximumAge ->
            assertThrows(IllegalArgumentException::class.java) {
                OwnedTensorFrameSlots<Metadata>(SESSION, TENSOR_BYTES, maximumSourceAgeMs = maximumAge)
            }
        }
        val strictest = OwnedTensorFrameSlots<Metadata>(SESSION, TENSOR_BYTES, maximumSourceAgeMs = 1)
        assertEquals(OverlapFrameStatus.STALE, strictest.beginPreparation(key(1), NOW, NOW + 2).status)
        assertTrue(strictest.isDrained())
    }

    @Test
    fun claimAndReplacementRaceCannotAssignTheSameTensorToTwoActiveOwners() {
        val slots = slots()
        ready(slots, 1, marker = 61)
        val start = CountDownLatch(1)
        val claiming = background {
            await(start)
            slots.claimLatest(NOW)
        }
        val preparing = background {
            await(start)
            slots.beginPreparation(key(2), NOW, NOW)
        }
        start.countDown()
        val claimed = claiming.get(5, TimeUnit.SECONDS)
        val replacement = preparing.get(5, TimeUnit.SECONDS)
        val lease = accepted(replacement)
        fill(lease, 72)
        when (claimed.status) {
            OverlapFrameStatus.ACCEPTED -> {
                assertNull(replacement.replacedReadyKey)
                val inference = accepted(claimed)
                assertArrayEquals(ByteArray(TENSOR_BYTES) { 61 }, bytes(inference.inputBuffer))
                assertEquals(OverlapFrameStatus.ACCEPTED, slots.completeInference(inference, NOW))
            }
            OverlapFrameStatus.NO_READY_FRAME -> assertEquals(key(1), replacement.replacedReadyKey)
            else -> throw AssertionError("Unexpected claim outcome: ${claimed.status}")
        }
        assertEquals(OverlapFrameStatus.ACCEPTED, slots.completePreparation(lease, Metadata("replacement"), NOW))
        val latest = accepted(slots.claimLatest(NOW))
        assertEquals(key(2), latest.key)
        assertArrayEquals(ByteArray(TENSOR_BYTES) { 72 }, bytes(latest.inputBuffer))
        assertEquals(OverlapFrameStatus.ACCEPTED, slots.completeInference(latest, NOW))
        assertTrue(slots.isDrained())
    }

    private fun slots() = OwnedTensorFrameSlots<Metadata>(SESSION, TENSOR_BYTES)

    private fun key(sequence: Long) = OverlapFrameKey(SESSION, sequence * 1_000, sequence * 1_000 + 20)

    private fun begin(
        slots: OwnedTensorFrameSlots<Metadata>,
        sequence: Long,
        capturedAt: Long = NOW,
    ): TensorPreparationLease = accepted(slots.beginPreparation(key(sequence), capturedAt, capturedAt))

    private fun ready(slots: OwnedTensorFrameSlots<Metadata>, sequence: Long, marker: Int = 1) {
        val preparation = begin(slots, sequence)
        fill(preparation, marker)
        assertEquals(
            OverlapFrameStatus.ACCEPTED,
            slots.completePreparation(preparation, Metadata("frame-$sequence"), NOW),
        )
    }

    private fun fill(preparation: TensorPreparationLease, marker: Int) {
        preparation.inputBuffer.put(ByteArray(TENSOR_BYTES) { marker.toByte() })
    }

    private fun bytes(buffer: ByteBuffer): ByteArray {
        val copy = buffer.duplicate()
        copy.clear()
        return ByteArray(copy.remaining()).also { copy.get(it) }
    }

    private fun <T> accepted(result: FrameSlotResult<T>): T {
        assertEquals(OverlapFrameStatus.ACCEPTED, result.status)
        return requireNotNull(result.value)
    }

    private fun await(latch: CountDownLatch) {
        assertTrue("Timed out waiting for test coordination", latch.await(5, TimeUnit.SECONDS))
    }

    private fun awaitBlocked(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (thread.state != Thread.State.BLOCKED && System.nanoTime() < deadline) {
            assertTrue("Producer completed while the clock callback was still active", thread.isAlive)
            Thread.yield()
        }
        assertEquals("Producer must wait for the clock and claim to release the lock", Thread.State.BLOCKED, thread.state)
    }

    private fun <T> background(block: () -> T): FutureTask<T> {
        val task = FutureTask(Callable { block() })
        Thread(task, "tensor-slot-test").apply {
            isDaemon = true
            start()
        }
        return task
    }

    private data class Metadata(val label: String)

    private companion object {
        const val SESSION = 7L
        const val TENSOR_BYTES = 16
        const val NOW = 1_000L
    }
}
