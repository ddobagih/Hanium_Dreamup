package kr.co.hanium.dreamup.walksafe.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

class GpuSerializationCacheTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun modelTokenHashesAllBytesWithoutChangingTheInputCursor() {
        val source = ByteBuffer.allocateDirect(8).putLong(12L).apply { position(3); limit(6) }
        val original = contract().modelToken(source)
        assertTrue(original.matches(Regex("[0-9a-f]{64}")))
        assertEquals(3, source.position())
        assertEquals(6, source.limit())
        assertEquals(original, contract().modelToken(ByteBuffer.allocate(8).putLong(12L)))
        source.clear()
        source.put(7, 13)
        assertNotEquals(original, contract().modelToken(source))
    }

    @Test
    fun everyCompilationContractChangeInvalidatesTheToken() {
        val source = ByteBuffer.wrap(byteArrayOf(1, 2, 3))
        val initial = contract()
        val baseline = initial.modelToken(source)
        listOf(
            initial.copy(inputSize = 640), initial.copy(numThreads = 2),
            initial.copy(precisionLossAllowed = true), initial.copy(nativeRuntimeVersion = "2.20.0"),
            initial.copy(deviceFingerprint = "another-os-build"), initial.copy(supportedAbis = listOf("x86_64")),
            initial.copy(sdkInt = 35), initial.copy(optionsVersion = "v2"),
            initial.copy(gpuArtifactVersion = "gpu:2.0.0"),
        ).forEach { assertNotEquals(it.toString(), baseline, it.modelToken(source)) }
        assertNotEquals(
            initial.copy(supportedAbis = listOf("a", "bc")).modelToken(source),
            initial.copy(supportedAbis = listOf("ab", "c")).modelToken(source),
        )
    }

    @Test
    fun disabledCacheNeverTouchesDirectoryModelOrRuntimeProviders() {
        val cache = GpuSerializationCache.prepare(false) { error("must not prepare") }
        assertEquals("disabled", cache.status)
        assertNull(cache.modelToken)
        assertEquals("uncached", cache.create { assertNull(it); "uncached" })
    }

    @Test
    fun unavailablePrivateDirectoryKeepsUncachedGpuAvailable() {
        val blockedRoot = temporaryFolder.newFile()
        val cache = GpuSerializationCache.prepare(true) {
            GpuSerializationParameters(GpuSerializationCache.privateDirectory(blockedRoot), "unused")
        }
        assertEquals("unavailable", cache.status)
        assertEquals("IllegalStateException", cache.failureReason)
        cache.create { assertNull(it) }
    }

    @Test
    fun privateDirectoryIsStableAndConfinedToCodeCache() {
        val root = temporaryFolder.newFolder()
        val first = GpuSerializationCache.privateDirectory(root)
        assertEquals(root.canonicalFile, requireNotNull(first.parentFile).canonicalFile)
        assertEquals(first, GpuSerializationCache.privateDirectory(root))
        assertTrue(first.isDirectory)
    }

    @Test
    fun parameterPreparationIoFailureDoesNotPreventGpuCreation() {
        val cache = GpuSerializationCache.prepare(true) { throw IOException("disk unavailable") }
        assertEquals("unavailable", cache.status)
        assertEquals("IOException", cache.failureReason)
        cache.create { assertNull(it) }
    }

    @Test
    fun configuredIsProvenanceAndDoesNotClaimACacheHit() {
        val parameters = parameters()
        val cache = GpuSerializationCache.prepare(true) { parameters }
        assertEquals("prepared", cache.status)
        cache.create { assertSame(parameters, it) }
        assertEquals("configured", cache.status)
        assertEquals(parameters.modelToken, cache.modelToken)
        assertNull(cache.failureReason)
    }

    @Test
    fun cachedInitializationFailureRetriesOnceAndKeepsBypassForLaterCreations() {
        val cache = GpuSerializationCache.prepare(true, ::parameters)
        val attempts = mutableListOf<Boolean>()
        val create: (GpuSerializationParameters?) -> String = {
            attempts += it != null
            if (it != null) throw IllegalStateException("cached GPU failed after cleanup")
            "uncached GPU"
        }
        assertEquals("uncached GPU", cache.create(create))
        assertEquals("uncached GPU", cache.create(create))
        assertEquals(listOf(true, false, false), attempts)
        assertEquals("bypassed_after_initialization_failure", cache.status)
        assertEquals("IllegalStateException", cache.failureReason)
    }

    @Test
    fun failedUncachedRetryFlowsIntoExistingCpuFallbackOnce() {
        val cache = GpuSerializationCache.prepare(true, ::parameters)
        val attempts = mutableListOf<String>()
        val runtime = DelegateFallbackRuntime(ModelRuntimeOptions("gpu")) { delegate ->
            if (delegate == "gpu") cache.create {
                attempts += if (it == null) "gpu:uncached" else "gpu:cached"
                throw IllegalStateException("GPU failed")
            } else {
                attempts += delegate
                Closeable {}
            }
        }
        assertEquals(listOf("gpu:cached", "gpu:uncached", "cpu"), attempts)
        assertEquals("cpu", runtime.runtime.activeDelegate)
        assertTrue(runtime.runtime.fallbackUsed)
        runtime.close()
    }

    @Test
    fun disabledCpuFallbackStillPermitsRecoveryUsingTheRequestedGpu() {
        val cache = GpuSerializationCache.prepare(true, ::parameters)
        val runtime = DelegateFallbackRuntime(ModelRuntimeOptions("gpu", fallbackToCpu = false)) { delegate ->
            assertEquals("gpu", delegate)
            cache.create {
                if (it != null) throw UnsatisfiedLinkError("serialization option unavailable")
                Closeable {}
            }
        }
        assertEquals("gpu", runtime.runtime.activeDelegate)
        assertFalse(runtime.runtime.fallbackUsed)
        runtime.close()
    }

    @Test
    fun cleanupFailureDoesNotOpenUncachedGpuOrCpu() {
        val cache = GpuSerializationCache.prepare(true, ::parameters)
        val failure = DetectorNativeCleanupFailure(IllegalStateException("failed to close native runtime"))
        var attempts = 0
        val actual = assertThrows(DetectorNativeCleanupFailure::class.java) {
            DelegateFallbackRuntime<Closeable>(ModelRuntimeOptions("gpu")) {
                cache.create { attempts++; throw failure }
            }
        }
        assertSame(failure, actual)
        assertEquals(1, attempts)
    }

    @Test
    fun cleanupFailureAlsoStopsTheOuterLegacyModelFallback() {
        val failure = DetectorNativeCleanupFailure(IllegalStateException("failed native close"))
        var legacyAttempts = 0
        val actual = assertThrows(DetectorNativeCleanupFailure::class.java) {
            TfliteAndroidFrameDetector.loadModelOrNull<Closeable> { throw failure }
                ?: run { legacyAttempts++; Closeable {} }
        }
        assertSame(failure, actual)
        assertEquals(0, legacyAttempts)
        assertNull(TfliteAndroidFrameDetector.loadModelOrNull<Closeable> { throw IllegalStateException("missing model") })
    }

    @Test
    fun fatalErrorsAreNotRetriedAndRepeatedErrorInstanceDoesNotSelfSuppress() {
        val cache = GpuSerializationCache.prepare(true, ::parameters)
        var attempts = 0
        assertThrows(OutOfMemoryError::class.java) { cache.create { attempts++; throw OutOfMemoryError() } }
        assertEquals(1, attempts)
        val failure = IllegalStateException("same failure")
        assertSame(failure, assertThrows(IllegalStateException::class.java) { cache.create { throw failure } })
        assertEquals(0, failure.suppressed.size)
    }

    private fun contract() = GpuSerializationContract(768, 4, false, "2.19.0", "device-os-build", listOf("arm64-v8a"), 33)
    private fun parameters() = GpuSerializationParameters(File(temporaryFolder.root, "cache"), "a".repeat(64))
}
