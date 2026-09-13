package kr.co.hanium.dreamup.walksafe.inference

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.io.File

class DelegateFallbackRuntimeTest {
    @Test
    fun gpuSuccessReportsAttachedDelegateWithoutCpuFallback() {
        val created = mutableListOf<String>()
        val session = DelegateFallbackRuntime(ModelRuntimeOptions("gpu", 2)) {
            created += it
            FakeRuntime(it)
        }
        assertEquals("gpu", session.run { it.delegate })
        assertEquals(listOf("gpu"), created)
        assertEquals("gpu", session.runtime.requestedDelegate)
        assertEquals("gpu", session.runtime.activeDelegate)
        assertEquals(2, session.runtime.numThreads)
        assertFalse(session.runtime.fallbackUsed)
        assertNull(session.runtime.fallbackReason)
        assertEquals(false, session.runtime.gpuPrecisionLossAllowed)
        session.close()
    }

    @Test
    fun missingGpuNativeLibraryFallsBackDuringInitialization() {
        val session = DelegateFallbackRuntime(ModelRuntimeOptions("gpu", 4)) {
            if (it == "gpu") throw UnsatisfiedLinkError("missing native library")
            FakeRuntime(it)
        }
        assertEquals("cpu", session.run { it.delegate })
        assertEquals("gpu", session.runtime.requestedDelegate)
        assertEquals("cpu", session.runtime.activeDelegate)
        assertTrue(session.runtime.fallbackUsed)
        assertEquals("gpu_initialization_UnsatisfiedLinkError", session.runtime.fallbackReason)
        assertNull(session.runtime.gpuPrecisionLossAllowed)
        session.close()
    }

    @Test
    fun invokeFailureClosesGpuBeforeCreatingCpuAndNeverRetriesGpu() {
        val events = mutableListOf<String>()
        val session = DelegateFallbackRuntime(ModelRuntimeOptions("gpu", 6)) {
            events += "create:$it"
            FakeRuntime(it) { events += "close:$it" }
        }
        val result = session.run {
            events += "run:${it.delegate}"
            if (it.delegate == "gpu") throw IllegalStateException("invoke failed")
            7
        }
        assertEquals(7, result)
        assertEquals(listOf("create:gpu", "run:gpu", "close:gpu", "create:cpu", "run:cpu"), events)
        assertEquals("gpu_invoke_IllegalStateException", session.runtime.fallbackReason)
        assertEquals("cpu", session.run { it.delegate })
        session.close()
        session.close()
        assertEquals(1, events.count { it == "close:cpu" })
        assertThrows(IllegalStateException::class.java) { session.run { it.delegate } }
    }

    @Test
    fun disabledFallbackPreservesRequestedDelegateFailure() {
        val failure = IllegalArgumentException("unsupported graph")
        val created = mutableListOf<String>()
        assertSame(failure, assertThrows(IllegalArgumentException::class.java) {
            DelegateFallbackRuntime(ModelRuntimeOptions("gpu", fallbackToCpu = false)) {
                created += it
                throw failure
                @Suppress("UNREACHABLE_CODE")
                FakeRuntime(it)
            }
        })
        assertEquals(listOf("gpu"), created)
    }

    @Test
    fun disabledInvokeFallbackDoesNotCreateCpu() {
        val created = mutableListOf<String>()
        val session = DelegateFallbackRuntime(ModelRuntimeOptions("gpu", fallbackToCpu = false)) {
            created += it
            FakeRuntime(it)
        }
        assertThrows(IllegalStateException::class.java) {
            session.run<Unit> { throw IllegalStateException("failed") }
        }
        assertEquals(listOf("gpu"), created)
        assertFalse(session.runtime.fallbackUsed)
        session.close()
    }

    @Test
    fun cpuInvokeFailureIsNotRetried() {
        var calls = 0
        val session = DelegateFallbackRuntime(ModelRuntimeOptions("cpu")) { FakeRuntime(it) }
        assertThrows(IllegalArgumentException::class.java) {
            session.run<Unit> {
                calls += 1
                throw IllegalArgumentException("bad input")
            }
        }
        assertEquals(1, calls)
        assertFalse(session.runtime.fallbackUsed)
        session.close()
    }

    @Test
    fun fatalFailureDoesNotAttemptFallback() {
        var creations = 0
        val fatal = OutOfMemoryError("test")
        val session = DelegateFallbackRuntime(ModelRuntimeOptions("gpu")) {
            creations += 1
            FakeRuntime(it)
        }
        assertSame(fatal, assertThrows(OutOfMemoryError::class.java) {
            session.run<Unit> { throw fatal }
        })
        assertEquals(1, creations)
        session.close()
    }

    @Test
    fun failedCpuReplacementKeepsOriginalFailureAndDoesNotDoubleCloseGpu() {
        var closes = 0
        val gpuFailure = IllegalStateException("gpu failure")
        val cpuFailure = IllegalArgumentException("cpu failure")
        val session = DelegateFallbackRuntime(ModelRuntimeOptions("gpu")) {
            if (it == "cpu") throw cpuFailure
            FakeRuntime(it) { closes += 1 }
        }
        assertSame(cpuFailure, assertThrows(IllegalArgumentException::class.java) {
            session.run<Unit> { throw gpuFailure }
        })
        assertTrue(cpuFailure.suppressed.contains(gpuFailure))
        session.close()
        assertEquals(1, closes)
        assertThrows(IllegalStateException::class.java) { session.run { it.delegate } }
    }

    @Test
    fun failedNativeCleanupStopsFallbackInsteadOfOpeningAnotherRuntime() {
        val created = mutableListOf<String>()
        val closeFailure = IllegalStateException("native close failed")
        val session = DelegateFallbackRuntime(ModelRuntimeOptions("gpu")) {
            created += it
            FakeRuntime(it) { throw closeFailure }
        }
        val invokeFailure = IllegalArgumentException("invoke failed")
        assertSame(invokeFailure, assertThrows(IllegalArgumentException::class.java) {
            session.run<Unit> { throw invokeFailure }
        })
        assertTrue(invokeFailure.suppressed.contains(closeFailure))
        assertEquals(listOf("gpu"), created)
        session.close()
    }

    @Test
    fun nnapiUsesSameExplicitFallbackProvenance() {
        val session = DelegateFallbackRuntime(ModelRuntimeOptions("nnapi")) {
            if (it == "nnapi") throw IllegalStateException("unavailable")
            FakeRuntime(it)
        }
        assertEquals("nnapi", session.runtime.requestedDelegate)
        assertEquals("cpu", session.runtime.activeDelegate)
        assertEquals("nnapi_initialization_IllegalStateException", session.runtime.fallbackReason)
        session.close()
    }

    @Test
    fun programmaticOverridesValidateThreadsAndDelegateBeforeLoadingNativeRuntime() {
        assertThrows(IllegalArgumentException::class.java) { ModelRuntimeOptions("gpu", 0) }
        assertEquals(9, ModelRuntimeOptions("cpu", 9).numThreads)
        assertThrows(IllegalArgumentException::class.java) { ModelRuntimeOptions("cpu", -1) }
        assertThrows(IllegalArgumentException::class.java) { ModelRuntimeOptions("unknown", 4) }
        assertFalse(ModelRuntimeOptions("gpu").gpuPrecisionLossAllowed)
        assertTrue(ModelRuntimeOptions("gpu", gpuPrecisionLossAllowed = true).gpuPrecisionLossAllowed)
        assertTrue(ModelRuntimeOptions("gpu").gpuSerializationCacheEnabled)
    }

    @Test
    fun legacyGpuRequiresOwnerEvenWhenItsEnabledFlagIsFalse() {
        val loaded = TwoModelRuntimeConfig.parse(File("src/main/assets/model-config/two_model_runtime.json").readText())
        val cpu = ModelRuntimeOptions("cpu")
        val config = loaded.copy(
            unifiedWalksafe = loaded.unifiedWalksafe?.copy(runtime = cpu),
            customTactile = loaded.customTactile?.copy(runtime = cpu),
            cocoGeneral = loaded.cocoGeneral?.copy(runtime = cpu),
        )
        assertFalse(config.requiresGpuThreadOwner)
        val mixed = config.copy(
            unifiedWalksafe = config.unifiedWalksafe?.copy(enabled = false),
            cocoGeneral = requireNotNull(config.cocoGeneral).copy(
                enabled = false,
                runtime = ModelRuntimeOptions("gpu"),
            ),
        )
        assertTrue(mixed.requiresGpuThreadOwner)
    }

    @Test
    fun gpuJsonSettingPreservesPrecisionPolicyAndCpuDefaults() {
        val json = JSONObject(File("src/main/assets/model-config/two_model_runtime.json").readText())
        json.getJSONObject("models").getJSONObject("unified_walksafe").put("runtime", JSONObject()
            .put("delegate", "gpu")
            .put("gpu_precision_loss_allowed", true)
            .put("gpu_serialization_cache_enabled", false))
        val config = TwoModelRuntimeConfig.parse(json.toString())
        assertTrue(config.requiresGpuThreadOwner)
        assertEquals("gpu", config.unifiedWalksafe?.runtime?.delegate)
        assertEquals(true, config.unifiedWalksafe?.runtime?.gpuPrecisionLossAllowed)
        assertEquals(false, config.unifiedWalksafe?.runtime?.gpuSerializationCacheEnabled)
        assertEquals("cpu", config.cocoGeneral?.runtime?.delegate)
    }

    private class FakeRuntime(val delegate: String, private val onClose: () -> Unit = {}) : Closeable {
        override fun close() = onClose()
    }
}
