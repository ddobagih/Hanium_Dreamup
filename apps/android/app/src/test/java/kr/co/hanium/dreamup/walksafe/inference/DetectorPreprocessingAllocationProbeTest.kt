package kr.co.hanium.dreamup.walksafe.inference

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import java.nio.ByteBuffer
import kr.co.hanium.dreamup.walksafe.UprightCameraImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** JVM measurements of the real preprocessing functions; these are not Android field timings. */
class DetectorPreprocessingAllocationProbeTest {
    @Test
    fun measureCurrentOwnedDecodeRotationAndBilinearBeforeChangingItsAllocations() {
        val allocation = ManagementFactory.getThreadMXBean() as? ThreadMXBean
        assumeTrue("Thread allocation accounting unavailable", allocation?.isThreadAllocatedMemorySupported == true)
        val bean = requireNotNull(allocation)
        val wasEnabled = bean.isThreadAllocatedMemoryEnabled
        if (!wasEnabled) {
            try {
                bean.isThreadAllocatedMemoryEnabled = true
            } catch (_: SecurityException) {
                assumeTrue("Thread allocation accounting cannot be enabled", false)
            }
        }
        try {
            for ((width, height) in listOf(640 to 480, 720 to 1280)) {
                for (turns in listOf(0, 1)) {
                    val ownedBytes = measure(bean, width, height, turns, reused = false)
                    val reusedBytes = measure(bean, width, height, turns, reused = true)
                    val largeArrayBytes = width.toLong() * height * 4L * if (turns == 0) 1 else 2
                    assertTrue("The warmed owner must remove its per-frame RGB arrays",
                        ownedBytes - reusedBytes >= largeArrayBytes * .99)
                }
            }
        } finally {
            if (!wasEnabled) bean.isThreadAllocatedMemoryEnabled = false
        }
    }

    private fun measure(bean: ThreadMXBean, width: Int, height: Int, turns: Int, reused: Boolean): Double {
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val frame = OwnedYuvFrame.copyOf(width, height,
            ByteBuffer.wrap(ByteArray(width * height) { (it * 31).toByte() }),
            ByteBuffer.wrap(ByteArray(chromaWidth * chromaHeight) { (it * 7).toByte() }),
            ByteBuffer.wrap(ByteArray(chromaWidth * chromaHeight) { (it * 11).toByte() }),
            width, 1, chromaWidth, 1)
        val processor = YuvImagePreprocessor()
        val scratch = DetectorRgbScratch(processor)
        fun decode() = if (reused) scratch.decode(frame) else frame.toArgb()
        fun rotate(image: ArgbImage) = if (reused) scratch.rotate(image, turns) else UprightCameraImage.rotate(image, turns)
        repeat(WARMUP) {
            processor.preprocessBilinear(rotate(decode()), MODEL_SIZE)
        }
        val decodeNs = LongArray(SAMPLES)
        val rotationNs = LongArray(SAMPLES)
        val bilinearNs = LongArray(SAMPLES)
        val totalNs = LongArray(SAMPLES)
        val allocated = LongArray(SAMPLES)
        val collectionsBefore = collections()
        val collectionMsBefore = collectionMs()
        var checksum = 0
        for (i in 0 until SAMPLES) {
            val bytesBefore = bean.getThreadAllocatedBytes(Thread.currentThread().id)
            assumeTrue("Current thread allocation unavailable", bytesBefore >= 0L)
            val start = System.nanoTime()
            val decoded = decode()
            val decodedAt = System.nanoTime()
            val upright = rotate(decoded)
            val rotatedAt = System.nanoTime()
            val input = processor.preprocessBilinear(upright, MODEL_SIZE)
            val end = System.nanoTime()
            val bytesAfter = bean.getThreadAllocatedBytes(Thread.currentThread().id)
            assumeTrue("Current thread allocation unavailable", bytesAfter >= 0L)
            allocated[i] = bytesAfter - bytesBefore
            decodeNs[i] = decodedAt - start
            rotationNs[i] = rotatedAt - decodedAt
            bilinearNs[i] = end - rotatedAt
            totalNs[i] = end - start
            val value = input.inputBuffer.getFloat((MODEL_SIZE * MODEL_SIZE / 2) * 3 * 4)
            assertTrue(value.isFinite() && value in 0f..1f)
            assertEquals(MODEL_SIZE * MODEL_SIZE * 3 * 4, input.inputBuffer.limit())
            assertTrue(allocated[i] >= 0L)
            checksum = checksum xor decoded.pixels[0] xor upright.pixels.last() xor value.toRawBits()
        }
        val gcCount = collectionsBefore?.let { before -> collections()?.minus(before) }
        val gcMs = collectionMsBefore?.let { before -> collectionMs()?.minus(before) }
        val mode = if (reused) "REUSED" else "BASELINE"
        println("PREPROCESS_JVM_$mode width=$width height=$height turns=$turns samples=$SAMPLES " +
            "bytes_per_frame=${allocated.average()} total_p50_ms=${quantileMs(totalNs, .5)} " +
            "total_p95_ms=${quantileMs(totalNs, .95)} decode_p50_ms=${quantileMs(decodeNs, .5)} " +
            "rotation_p50_ms=${quantileMs(rotationNs, .5)} bilinear_p50_ms=${quantileMs(bilinearNs, .5)} " +
            "process_gc_count=${gcCount ?: "unavailable"} process_gc_ms=${gcMs ?: "unavailable"} checksum=$checksum")
        return allocated.average()
    }

    private fun collections(): Long? = ManagementFactory.getGarbageCollectorMXBeans()
        .map { it.collectionCount }.takeIf { it.all { count -> count >= 0L } }?.sum()

    private fun collectionMs(): Long? = ManagementFactory.getGarbageCollectorMXBeans()
        .map { it.collectionTime }.takeIf { it.all { time -> time >= 0L } }?.sum()

    private fun quantileMs(values: LongArray, quantile: Double): Double =
        values.sorted()[(kotlin.math.ceil(values.size * quantile).toInt() - 1).coerceIn(values.indices)] / 1_000_000.0

    private companion object {
        const val MODEL_SIZE = 768
        const val WARMUP = 8
        const val SAMPLES = 16
    }
}
