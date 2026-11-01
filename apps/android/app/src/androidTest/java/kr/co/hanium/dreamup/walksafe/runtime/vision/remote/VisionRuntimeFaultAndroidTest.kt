package kr.co.hanium.dreamup.walksafe.runtime.vision.remote

import android.os.Build
import android.os.Parcel
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kr.co.hanium.dreamup.walksafe.BuildConfig
import kr.co.hanium.dreamup.walksafe.inference.TwoModelRuntimeConfig
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch

/** Opt-in, emulator-only Binder/mapping/fault evidence. This is not model or camera performance proof. */
@RunWith(AndroidJUnit4::class)
class VisionRuntimeFaultAndroidTest {
    private lateinit var client: RemoteVisionClient
    private var lease: VisionCohortLease? = null
    @Before fun setup() {
        assumeTrue(BuildConfig.DEBUG && Build.VERSION.SDK_INT >= 31)
        assumeTrue("Explicit emulator fault-test opt-in required",
            InstrumentationRegistry.getArguments().getString("visionRemoteFaultTests") == "true")
        assumeTrue("Physical devices are excluded", Build.HARDWARE in setOf("ranchu", "goldfish") ||
            Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"))
        client = RemoteVisionClient.forMainProcess(InstrumentationRegistry.getInstrumentation().targetContext)
    }
    @After fun cleanup() {
        lease?.let { client.requestExit(it, "fault_test_cleanup")
            client.actualReleasedFuture(it.cohortId).toCompletableFuture().get(10, TimeUnit.SECONDS) }
    }
    private fun deadline(ms: Long = 5_000) = SystemClock.elapsedRealtime() + ms
    private fun acquire(): VisionCohortLease = client.acquire("fault-test", deadline()).also { lease = it }
    private fun spec(): VisionModelSpec {
        val model = requireNotNull(TwoModelRuntimeConfig.load(InstrumentationRegistry.getInstrumentation().targetContext).unifiedWalksafe)
        return VisionModelSpec(VisionRole.PRIMARY, TwoModelRuntimeConfig.UNIFIED_MODEL_KEY, model.asset,
            requireNotNull(model.artifactSha256), VisionRuntimeCodec.encodeOptions(VisionRuntimeOptions("cpu", 1)),
            intArrayOf(1, model.inputSize, model.inputSize, 3), arrayOf(model.outputTensorShape()))
    }
    private fun fake(lease: VisionCohortLease): VisionModelHandle {
        client.injectDebugFault(lease, "FAKE_ENGINE", deadline())
        return client.open(lease, spec(), deadline(10_000))
    }
    private fun invoke(handle: VisionModelHandle, ms: Long = 5_000): VisionInvokeResult {
        val spec = spec()
        val input = ByteBuffer.allocateDirect(VisionRuntimeProtocol.tensorBytes(spec.inputShape)).order(ByteOrder.LITTLE_ENDIAN)
        val output = ByteBuffer.allocateDirect(VisionRuntimeProtocol.tensorBytes(spec.outputShapes[0])).order(ByteOrder.LITTLE_ENDIAN)
        output.putFloat(0, 123f)
        val result = client.invoke(handle, input, arrayOf(output), 768, 768, VisionPurpose.BENCHMARK, deadline(ms))
        assertEquals(0f, output.getFloat(0), 0f)
        return result
    }

    @Test fun acquireIsSharedButDuplicateOpenIsRejectedAndNormalCloseIsOnlyModelRelease() {
        val lease = acquire(); val handle = fake(lease)
        assertSame(lease, client.acquire("fault-test", deadline()))
        assertThrows(IllegalArgumentException::class.java) { client.open(lease, spec(), deadline()) }
        assertNotEquals(Process.myPid(), VisionRuntimeCodec.objectJson(handle.runtimeJson).getInt("modelOwnerPid"))
        invoke(handle)
        assertTrue(client.close(handle, deadline()).nativeCloseConfirmed)
        assertFalse(client.modelReleasedFuture(handle.modelInstanceId).toCompletableFuture().get().processDeath)
        assertFalse(client.actualReleasedFuture(lease.cohortId).toCompletableFuture().isDone)
    }
    @Test fun differentOwnerBusyDoesNotBorrowNativeOwnershipOrRetireCurrentCohort() {
        val current = acquire(); val handle = fake(current)
        val busy = assertThrows(VisionCohortBusyException::class.java) { client.acquire("different-owner", deadline()) }
        assertSame(client.actualReleasedFuture(current.cohortId), busy.availabilityReceipt)
        assertFalse(busy.availabilityReceipt.toCompletableFuture().isDone)
        assertNull(client.peekLease("different-owner"))
        assertSame(current, client.peekLease("fault-test"))
        invoke(handle)
    }
    @Test fun transferredInstanceHasNewEpochAndOldOwnerCannotCloseOrExitIt() {
        val old = acquire(); val handle = fake(old)
        val snapshot = requireNotNull(client.retirementSnapshot("fault-test"))
        assertSame(old, snapshot.lease)
        assertSame(client.actualReleasedFuture(old.cohortId), snapshot.actualReleased)
        assertNull(client.retirementSnapshot("another-owner"))
        client.park(old, deadline())
        val transfer = client.transfer(old, "fault-test-next", deadline())
        lease = transfer.lease
        val current = transfer.handles.single()
        assertEquals(handle.modelInstanceId, current.modelInstanceId)
        assertEquals(old.ownerEpoch + 1, current.ownerEpoch)
        assertNull(client.retirementSnapshot("fault-test"))
        client.requestExit(snapshot.lease, "stale_snapshot_must_not_exit")
        assertThrows(IllegalStateException::class.java) { client.confirmAdoption(old, deadline()) }
        client.confirmAdoption(transfer.lease, deadline())
        assertThrows(IllegalStateException::class.java) { client.close(handle, deadline()) }
        assertThrows(IllegalStateException::class.java) { client.open(old, spec(), deadline()) }
        invoke(current)
        assertFalse(snapshot.actualReleased.toCompletableFuture().isDone)
        assertSame(transfer.lease, client.acquire("fault-test-next", deadline()))
    }
    @Test fun blockedInitIsRecoveredByActualChildDeathThenAnotherCohortCanStart() {
        val old = acquire()
        client.injectDebugFault(old, "OPEN_HANG", deadline())
        assertThrows(VisionRuntimeException::class.java) { client.open(old, spec(), deadline(1_500)) }
        val receipt = client.actualReleasedFuture(old.cohortId).toCompletableFuture().get(10, TimeUnit.SECONDS)
        assertEquals(VisionReleaseProof.CHILD_DEATH, receipt.releaseProof)
        assertTrue(receipt.childPid > 0 && receipt.childPid != Process.myPid())
        val next = acquire(); val handle = fake(next)
        assertNotEquals(old.cohortId, next.cohortId)
        invoke(handle)
    }
    @Test fun lostTransferResponseCannotStrandNewEpochOrGrantOldOwnerExitAuthority() {
        val old = acquire(); fake(old)
        client.injectDebugFault(old, "DROP_TRANSFER_REPLY", deadline())
        client.park(old, deadline())
        assertThrows(VisionRuntimeException::class.java) { client.transfer(old, "never-adopted", deadline(1_500)) }
        assertEquals(VisionReleaseProof.CHILD_DEATH,
            client.actualReleasedFuture(old.cohortId).toCompletableFuture().get(10, TimeUnit.SECONDS).releaseProof)
    }
    @Test fun missingTargetOrPartialJavaAttachDoesNotDisarmChildAdoptionDeadline() {
        val old = acquire(); fake(old)
        client.park(old, deadline())
        val result = client.transfer(old, "target-never-ready", deadline(1_500))
        lease = result.lease
        assertNull(client.peekLease("fault-test"))
        assertSame(result.lease, client.peekLease("target-never-ready"))
        // One (or zero) wrappers can exist, but no frames enter before explicit registry confirmation.
        assertThrows(IllegalStateException::class.java) { invoke(result.handles.single(), 500) }
        assertEquals(VisionReleaseProof.CHILD_DEATH,
            client.actualReleasedFuture(old.cohortId).toCompletableFuture().get(10, TimeUnit.SECONDS).releaseProof)
    }
    @Test fun expiredParkCannotLeaveParentPermanentlyParkedWithoutChildWatchdog() {
        val lease = acquire(); fake(lease)
        assertThrows(VisionRuntimeException::class.java) { client.park(lease, SystemClock.elapsedRealtime() - 1) }
        assertEquals(VisionReleaseProof.CHILD_DEATH,
            client.actualReleasedFuture(lease.cohortId).toCompletableFuture().get(10, TimeUnit.SECONDS).releaseProof)
    }
    @Test fun delayedOldInvokeCannotBorrowTransferredEpoch() = delayedHandleOperation(false)
    @Test fun delayedOldCloseCannotCloseTransferredNativeInstance() = delayedHandleOperation(true)
    private fun delayedHandleOperation(close: Boolean) {
        val old = acquire(); val handle = fake(old)
        val entered = CountDownLatch(1); val resume = CountDownLatch(1)
        val failure = CompletableFuture<Throwable>()
        client.debugBeforeHandleDispatch = { if (it === handle) {
            entered.countDown(); check(resume.await(5, TimeUnit.SECONDS))
        } }
        val thread = Thread {
            try { if (close) client.close(handle, deadline()) else invoke(handle)
                failure.complete(AssertionError("stale operation accepted")) }
            catch (error: Throwable) { failure.complete(error) }
        }.apply { start() }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            client.park(old, deadline())
            val moved = client.transfer(old, "fault-test-next", deadline())
            lease = moved.lease
            client.confirmAdoption(moved.lease, deadline())
            client.debugBeforeHandleDispatch = null; resume.countDown()
            assertTrue(failure.get(5, TimeUnit.SECONDS) is VisionStaleAuthorityException)
            invoke(moved.handles.single())
            assertFalse(client.modelReleasedFuture(handle.modelInstanceId).toCompletableFuture().isDone)
        } finally { client.debugBeforeHandleDispatch = null; resume.countDown(); thread.join(5_000) }
    }
    @Test fun delayedOldExitCannotRetireNewOwnerAfterIdentityLookup() {
        val old = acquire(); fake(old)
        val entered = CountDownLatch(1); val resume = CountDownLatch(1)
        val finished = CompletableFuture<Unit>()
        client.debugBeforeExitRetirement = { if (it === old) { entered.countDown(); check(resume.await(5, TimeUnit.SECONDS)) } }
        val thread = Thread {
            try { client.requestExit(old, "late_old_exit"); finished.complete(Unit) }
            catch (error: Throwable) { finished.completeExceptionally(error) }
        }.apply { start() }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            client.park(old, deadline()); val moved = client.transfer(old, "fault-test-next", deadline())
            lease = moved.lease; client.confirmAdoption(moved.lease, deadline())
            client.debugBeforeExitRetirement = null; resume.countDown(); finished.get(5, TimeUnit.SECONDS)
            invoke(moved.handles.single())
            assertFalse(client.actualReleasedFuture(old.cohortId).toCompletableFuture().isDone)
        } finally { client.debugBeforeExitRetirement = null; resume.countDown(); thread.join(5_000) }
    }
    @Test fun blockedInvokeDoesNotPublishOutputAndBothPhysicalReceiptsWaitForChildDeath() {
        val lease = acquire(); val handle = fake(lease)
        client.injectDebugFault(lease, "INVOKE_HANG", deadline())
        assertThrows(VisionRuntimeException::class.java) { invoke(handle, 1_000) }
        assertEquals(VisionReleaseProof.CHILD_DEATH,
            client.actualReleasedFuture(lease.cohortId).toCompletableFuture().get(10, TimeUnit.SECONDS).releaseProof)
        assertTrue(client.modelReleasedFuture(handle.modelInstanceId).toCompletableFuture().get(10, TimeUnit.SECONDS).processDeath)
    }
    @Test fun blockedCloseReportsFailureButLaterDeathStillCompletesPhysicalReceipt() {
        val lease = acquire(); val handle = fake(lease)
        client.injectDebugFault(lease, "CLOSE_HANG", deadline())
        assertThrows(VisionRuntimeException::class.java) { client.close(handle, deadline(1_000)) }
        val actual = client.actualReleasedFuture(lease.cohortId)
        val retirement = client.retirementSnapshot("fault-test")
        if (retirement == null) {
            // Null is safe only when the concurrent physical-release publication already finished.
            assertTrue(actual.toCompletableFuture().isDone)
        } else {
            assertSame(lease, retirement.lease)
            assertSame(actual, retirement.actualReleased)
        }
        assertTrue(client.modelReleasedFuture(handle.modelInstanceId).toCompletableFuture().get(10, TimeUnit.SECONDS).processDeath)
        assertEquals(VisionReleaseProof.CHILD_DEATH,
            client.actualReleasedFuture(lease.cohortId).toCompletableFuture().get(10, TimeUnit.SECONDS).releaseProof)
    }
    @Test fun parcelTransportHasExactSizesReadOnlyInputAndExplicitMappingRelease() {
        val source = ByteBuffer.allocateDirect(4).order(ByteOrder.LITTLE_ENDIAN).apply { putInt(0, 7) }
        val target = ByteBuffer.allocateDirect(4).order(ByteOrder.LITTLE_ENDIAN)
        var parent: VisionSharedMemory? = null
        val parcel = Parcel.obtain()
        try {
            val exchange = VisionSharedMemory.parent(source, arrayOf(target), 4, intArrayOf(4)) { parent = it }
            exchange.write(parcel); parcel.setDataPosition(0)
            val child = VisionSharedMemory.child(parcel, 4, intArrayOf(4))
            try {
                assertTrue(requireNotNull(child.input).isReadOnly)
                assertEquals(7, requireNotNull(child.input).getInt(0))
                child.outputs[0].putFloat(0, 42f)
            } finally { child.close(); child.close() }
            exchange.copyOutputs(arrayOf(target))
            assertEquals(42f, target.getFloat(0), 0f)
        } finally { parent?.close(); parcel.recycle() }
    }
}
