package kr.co.hanium.dreamup.walksafe.inference

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

class PrimaryPreprocessorDrainTest {
    @Test fun serialSamplesReuseCachesAndDrainDropsManagedOwnershipBeforeTheNextSampleRecreatesIt() {
        val backing = ByteBuffer.allocateDirect(48).order(ByteOrder.nativeOrder())
        val requests = AtomicInteger()
        val processor = YuvImagePreprocessor { size -> assertEquals(2, size); requests.incrementAndGet(); backing.duplicate().order(ByteOrder.nativeOrder()) }
        val image = ArgbImage(2, 2, intArrayOf(-16777216, -1, -65536, -16711936))
        val first = processor.preprocessBilinear(image, 2)
        val expected = floats(first.inputBuffer)
        val scratch = cache(processor, "bilinearScratch")[2]
        assertSame(first.inputBuffer, processor.preprocessBilinear(image, 2).inputBuffer)
        assertSame(scratch, cache(processor, "bilinearScratch")[2]); assertEquals(1, requests.get())
        processor.preprocessYuv420(2, 2, ByteBuffer.wrap(ByteArray(4) { 128.toByte() }),
            ByteBuffer.wrap(byteArrayOf(128.toByte())), ByteBuffer.wrap(byteArrayOf(128.toByte())), 2, 1, 1, 1, 2)
        assertTrue(cache(processor, "reusableRgbRows").isNotEmpty())
        processor.clearAfterDrain(); processor.clearAfterDrain()
        listOf("bilinearScratch", "reusableInputBuffers", "reusableRgbRows").forEach { assertTrue(cache(processor, it).isEmpty()) }
        val restored = processor.preprocessBilinear(image, 2)
        assertEquals(2, requests.get()); assertNotSame(scratch, cache(processor, "bilinearScratch")[2])
        assertArrayEquals(expected, floats(restored.inputBuffer), 0f)
        // The provider still owns the same direct backing. This test asserts no free/GC receipt.
        restored.inputBuffer.putFloat(0, .25f); assertEquals(.25f, backing.getFloat(0))
    }

    @Test fun cacheClearCannotRaceAnActiveCpuPreparationAndDoesNotInvalidateTheReturnedTensor() {
        val entered = CountDownLatch(1); val proceed = CountDownLatch(1); val clearing = CountDownLatch(1)
        val processor = YuvImagePreprocessor {
            entered.countDown(); check(proceed.await(5, TimeUnit.SECONDS))
            ByteBuffer.allocateDirect(48).order(ByteOrder.nativeOrder())
        }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val sample = pool.submit<PreprocessedImage> { processor.preprocessBilinear(ArgbImage(2, 2, IntArray(4)), 2) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val clear = pool.submit { clearing.countDown(); processor.clearAfterDrain() }
            assertTrue(clearing.await(2, TimeUnit.SECONDS))
            assertThrows(TimeoutException::class.java) { clear.get(100, TimeUnit.MILLISECONDS) }
            proceed.countDown()
            val output = sample.get(2, TimeUnit.SECONDS); clear.get(2, TimeUnit.SECONDS)
            assertArrayEquals(FloatArray(12), floats(output.inputBuffer), 0f)
            assertTrue(cache(processor, "bilinearScratch").isEmpty()); assertTrue(cache(processor, "reusableInputBuffers").isEmpty())
        } finally { proceed.countDown(); pool.shutdownNow() }
    }

    private fun cache(processor: YuvImagePreprocessor, name: String) = YuvImagePreprocessor::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(processor) as Map<*, *>
    private fun floats(buffer: ByteBuffer) = FloatArray(buffer.capacity() / 4).also {
        buffer.duplicate().order(ByteOrder.nativeOrder()).apply { clear() }.asFloatBuffer().get(it)
    }
}
