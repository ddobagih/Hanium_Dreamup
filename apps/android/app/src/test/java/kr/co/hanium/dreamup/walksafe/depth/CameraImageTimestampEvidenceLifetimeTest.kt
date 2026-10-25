package kr.co.hanium.dreamup.walksafe.depth

import android.media.Image
import com.google.ar.core.Frame
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import org.junit.Assert.*
import org.junit.Test

/** Actual receipt with an overridden SDK getter. No GC, camera acquisition or native clock call. */
class CameraImageTimestampEvidenceLifetimeTest {
    @Test fun constructionDoesNotQueryTheFrameClock() {
        val frame = CountingFrame().also { it.forbidTimestamp = true }
        val receipt = receipt(Any(), frame)
        assertSame(frame, reference(receipt).get())
        assertEquals(0, frame.timestampReads)
        assertEquals(900L, receipt.cameraImageTimestampNs)
    }

    @Test fun callerStrongCurrentFrameKeepsExactReferenceValidationAndOneClockReadPerCall() {
        val owner = Any()
        val currentFrame = CountingFrame()
        val receipt = receipt(owner, currentFrame)
        assertSame(currentFrame, reference(receipt).get())
        assertEquals(900L, receipt.timestampFor(owner, 7L, currentFrame))
        assertEquals(1, currentFrame.timestampReads)
        assertEquals(900L, receipt.timestampFor(owner, 7L, currentFrame))
        assertEquals(2, currentFrame.timestampReads)
        assertEquals(900L, receipt.cameraImageTimestampNs)
    }

    @Test fun equalButForeignOwnerIsRejectedBeforeAnyFrameClockQuery() {
        val owner = EqualOwner()
        val foreign = EqualOwner()
        assertEquals(owner, foreign)
        assertNotSame(owner, foreign)
        val frame = CountingFrame().also { it.forbidTimestamp = true }
        assertNull(receipt(owner, frame).timestampFor(foreign, 7L, frame))
        assertEquals(0, frame.timestampReads)
    }

    @Test fun foreignEpochIsRejectedBeforeAnyFrameClockQuery() {
        val owner = Any()
        val frame = CountingFrame().also { it.forbidTimestamp = true }
        val receipt = receipt(owner, frame)
        assertNull(receipt.timestampFor(owner, 8L, frame))
        assertEquals(0, frame.timestampReads)
    }

    @Test fun foreignFrameWithIdenticalTimestampCannotReplaceTheOriginalReference() {
        val owner = Any()
        val original = CountingFrame().also { it.forbidTimestamp = true }
        val foreign = CountingFrame().also { it.forbidTimestamp = true }
        assertNotSame(original, foreign)
        assertEquals(original.value, foreign.value)
        assertNull(receipt(owner, original).timestampFor(owner, 7L, foreign))
        assertEquals(0, original.timestampReads)
        assertEquals(0, foreign.timestampReads)
    }

    @Test fun invalidStoredFrameClockIsRejectedBeforeAnySdkGetter() {
        for (storedTime in listOf(0L, -1L)) {
            val owner = Any()
            val frame = CountingFrame().also { it.forbidTimestamp = true }
            val receipt = CameraImageTimestampEvidence(owner, 7L, frame, storedTime, 900L)
            assertNull(receipt.timestampFor(owner, 7L, frame))
            assertEquals(0, frame.timestampReads)
        }
    }

    @Test fun invalidCpuClockPreservesOriginalSdkReadThenRejectOrder() {
        for (cpuTime in listOf(0L, -1L)) {
            val owner = Any()
            val frame = CountingFrame()
            val receipt = CameraImageTimestampEvidence(owner, 7L, frame, 1_000L, cpuTime)
            assertNull(receipt.timestampFor(owner, 7L, frame))
            assertEquals(1, frame.timestampReads)
            assertEquals(cpuTime, receipt.cameraImageTimestampNs)
        }
    }

    @Test fun mutatedOrInvalidLiveFrameClockIsReadExactlyOnceAndDoesNotRenewTheReceipt() {
        for (liveTime in listOf(1_001L, 0L, -1L)) {
            val owner = Any()
            val frame = CountingFrame()
            val receipt = receipt(owner, frame)
            frame.value = liveTime
            assertNull(receipt.timestampFor(owner, 7L, frame))
            assertEquals(1, frame.timestampReads)
            assertEquals(900L, receipt.cameraImageTimestampNs)
        }
    }

    @Test fun deterministicallyClearedReferenceIsRejectedWithoutQueryEvenWithStrongCurrentParameter() {
        val owner = Any()
        val currentFrame = CountingFrame().also { it.forbidTimestamp = true }
        val receipt = receipt(owner, currentFrame)
        val reference = reference(receipt)
        assertSame(currentFrame, reference.get())
        reference.clear() // Simulates a cleared weak receipt without relying on collection timing.
        assertNull(reference.get())
        assertNull(receipt.timestampFor(owner, 7L, currentFrame))
        assertEquals(0, currentFrame.timestampReads)
        assertEquals(900L, receipt.cameraImageTimestampNs)
    }

    @Test fun receiptFieldLayoutOwnsOneTypedWeakReferenceAndNoStrongFrameOrImageField() {
        val frame = CountingFrame()
        val receipt = receipt(Any(), frame)
        val fields = CameraImageTimestampEvidence::class.java.declaredFields.filterNot {
            Modifier.isStatic(it.modifiers)
        }
        assertFalse(fields.any { Frame::class.java.isAssignableFrom(it.type) })
        assertFalse(fields.any { Image::class.java.isAssignableFrom(it.type) })
        val weakField = fields.single { it.type == WeakReference::class.java }
        val signature = weakField.genericType as ParameterizedType
        assertEquals(Frame::class.java, signature.actualTypeArguments.single())
        for (field in fields) {
            field.isAccessible = true
            assertNotSame("Receipt must not directly store the native frame in ${field.name}", frame, field.get(receipt))
        }
        assertSame(frame, reference(receipt).get())
        assertEquals(0, frame.timestampReads)
    }

    @Test fun sdkGetterFailurePreservesItsExactCauseBeforeTheOriginalCpuPositiveCheck() {
        for (cpuTime in listOf(900L, 0L)) {
            val owner = Any()
            val failure = IllegalStateException("original frame clock failure")
            val frame = CountingFrame().also { it.failure = failure }
            val receipt = CameraImageTimestampEvidence(owner, 7L, frame, 1_000L, cpuTime)
            try {
                receipt.timestampFor(owner, 7L, frame)
                fail("The original getter failure must propagate")
            } catch (seen: IllegalStateException) {
                assertSame(failure, seen)
            }
            assertEquals(1, frame.timestampReads)
        }
    }

    private fun receipt(owner: Any, frame: Frame) = CameraImageTimestampEvidence(owner, 7L, frame, 1_000L, 900L)

    private fun reference(receipt: CameraImageTimestampEvidence): WeakReference<*> =
        CameraImageTimestampEvidence::class.java.declaredFields.single {
            it.type == WeakReference::class.java
        }.let { field -> field.isAccessible = true; field.get(receipt) as WeakReference<*> }

    private class EqualOwner {
        override fun equals(other: Any?) = other is EqualOwner
        override fun hashCode() = 1
    }

    private class CountingFrame : Frame() {
        var value = 1_000L
        var timestampReads = 0
        var forbidTimestamp = false
        var failure: RuntimeException? = null
        override fun getTimestamp(): Long {
            timestampReads += 1
            check(!forbidTimestamp) { "Rejected evidence must not query the frame clock" }
            failure?.let { throw it }
            return value
        }
    }
}
