package kr.co.hanium.dreamup.walksafe.inference.unknown

import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class FastSamNativeBridgeTest {
    private fun options() = FastSamRuntimeOptions(FastSamRuntimeOptions.Backend.CPU, 1, false,
        Long.MAX_VALUE, 800, false)

    @Test fun unsupportedApiAndParentProcessAreRejectedBeforeNativeFactoryAccess() {
        assertThrows(IllegalArgumentException::class.java) { FastSamNativeBridge.requireProcess(30, "app:vision_runtime", "app", false) }
        assertThrows(IllegalArgumentException::class.java) { FastSamNativeBridge.requireProcess(31, "app", "app", true) }
        assertThrows(IllegalArgumentException::class.java) { FastSamNativeBridge.requireProcess(31, "app:fixture_runtime", "app", false) }
        FastSamNativeBridge.requireProcess(31, "app:fixture_runtime", "app", true)
        FastSamNativeBridge.requireProcess(31, "app:vision_runtime", "app", false)
    }

    @Test fun primaryRoleOrChangedAssetCannotCreateTheFastSamBridge() {
        val spec = FastSamNativeBridge.spec(options())
        assertThrows(IllegalArgumentException::class.java) { FastSamNativeBridge.validateSpec(spec.copy(role = VisionRole.PRIMARY)) }
        assertThrows(IllegalArgumentException::class.java) { FastSamNativeBridge.validateSpec(spec.copy(assetPath = "models/other.tflite")) }
        assertThrows(IllegalArgumentException::class.java) { FastSamNativeBridge.validateSpec(spec.copy(assetSha256 = "0".repeat(64))) }
        assertThrows(IllegalArgumentException::class.java) { FastSamNativeBridge.validateSpec(spec.copy(outputShapes = arrayOf(intArrayOf(1, 1)))) }
    }

    @Test fun argbAndRawFloatWireUseLittleEndianWithoutBorrowingOrChangingBufferPositions() {
        val engine = Engine(options())
        val bridge = FastSamNativeBridge.LocalEngine(engine, FastSamNativeBridge.spec(options()))
        val input = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(0xff102030.toInt()).putInt(0xffa0b0c0.toInt())
        input.flip()
        val outputs = outputs()
        val result = bridge.invoke(input, outputs, 2, 1)
        assertArrayEquals(intArrayOf(0xff102030.toInt(), 0xffa0b0c0.toInt()), engine.pixels)
        assertEquals(0, input.position()); assertTrue(outputs.all { it.position() == 0 })
        assertEquals(1.25f, outputs[0].order(ByteOrder.LITTLE_ENDIAN).getFloat(0), 0f)
        assertEquals(-3.5f, outputs[1].order(ByteOrder.LITTLE_ENDIAN).getFloat(outputs[1].limit() - 4), 0f)
        engine.raw[0][0] = 9f
        assertEquals(1.25f, outputs[0].getFloat(0), 0f)
        val observed = VisionRuntimeCodec.objectJson(result.runtimeJson)
        assertEquals(1234L, observed.getLong("modelOwnerPid"))
        assertEquals(17L, observed.getLong("modelOwnerThreadId"))
        assertEquals(2.0, result.inferenceMs, 0.0)
        bridge.close(); assertTrue(engine.closed)
    }

    @Test fun invalidWireLengthAndReadOnlyOutputsCannotReachNativeInvoke() {
        val engine = Engine(options())
        val bridge = FastSamNativeBridge.LocalEngine(engine, FastSamNativeBridge.spec(options()))
        assertThrows(IllegalArgumentException::class.java) { bridge.invoke(ByteBuffer.allocate(4), outputs(), 2, 1) }
        val readOnly = outputs().also { it[1] = it[1].asReadOnlyBuffer() }
        assertThrows(IllegalArgumentException::class.java) { bridge.invoke(ByteBuffer.allocate(8), readOnly, 2, 1) }
        assertEquals(0, engine.invocations)
        bridge.close()
    }

    @Test fun nativeInvokeAndCloseStayOnTheCreationOwner() {
        val engine = Engine(options())
        val bridge = FastSamNativeBridge.LocalEngine(engine, FastSamNativeBridge.spec(options()))
        val error = CompletableFuture<Throwable>()
        Thread {
            try { bridge.close(); error.complete(AssertionError("wrong owner accepted")) }
            catch (failure: Throwable) { error.complete(failure) }
        }.apply { start(); join(3000) }
        assertTrue(error.get(3, TimeUnit.SECONDS) is IllegalStateException)
        assertFalse(engine.closed)
        bridge.close()
    }

    private fun outputs() = arrayOf(ByteBuffer.allocate(FastSamModelContract.DETECTION_FLOATS * 4),
        ByteBuffer.allocate(FastSamModelContract.PROTOTYPE_FLOATS * 4))

    private class Engine(private val options: FastSamRuntimeOptions) : FastSamEngine {
        var pixels = intArrayOf()
        var invocations = 0
        var closed = false
        val raw = arrayOf(FloatArray(FastSamModelContract.DETECTION_FLOATS), FloatArray(FastSamModelContract.PROTOTYPE_FLOATS))
        override fun runtime() = FastSamRuntimeInfo(options, options.requestedBackend, null, 17L, 4.0,
            "not_applicable", null, null, 0, 1234L, null, null)
        override fun invoke(ownedArgb: IntArray, width: Int, height: Int): FastSamEngine.Output {
            invocations++; pixels = ownedArgb
            raw[0][0] = 1.25f; raw[1][raw[1].lastIndex] = -3.5f
            return FastSamEngine.Output(raw, 1.0, 2.0, 3.0)
        }
        override fun close() { closed = true }
    }
}
