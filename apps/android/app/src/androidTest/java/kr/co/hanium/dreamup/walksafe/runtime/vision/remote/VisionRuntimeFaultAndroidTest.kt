package kr.co.hanium.dreamup.walksafe.runtime.vision.remote

import android.os.Build
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
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
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.locks.ReentrantLock

/** Opt-in, emulator-only Binder/mapping/fault evidence. This is not model or camera performance proof. */
@RunWith(AndroidJUnit4::class)
class VisionRuntimeFaultAndroidTest {
    private lateinit var client: RemoteVisionClient
    private lateinit var scope: VisionDiagnosticScope
    private var lease: VisionCohortLease? = null
    @Before fun setup() {
        assumeTrue(BuildConfig.DEBUG && Build.VERSION.SDK_INT >= 31)
        assumeTrue("Explicit emulator fault-test opt-in required",
            InstrumentationRegistry.getArguments().getString("visionRemoteFaultTests") == "true")
        assumeTrue("Physical devices are excluded", Build.HARDWARE in setOf("ranchu", "goldfish") ||
            Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"))
        scope = VisionDiagnosticScope(InstrumentationRegistry.getInstrumentation().targetContext)
        client = RemoteVisionClient.forMainProcess(InstrumentationRegistry.getInstrumentation().targetContext)
    }
    @After fun cleanup() {
        client.debugBeforeJournalCleanup = null
        lease?.let { client.requestExit(it, "fault_test_cleanup")
            client.actualReleasedFuture(it.cohortId).toCompletableFuture().get(10, TimeUnit.SECONDS)
            client.whenAvailable(it.cohortId).toCompletableFuture().get(10, TimeUnit.SECONDS) }
    }
    private fun deadline(ms: Long = 5_000) = SystemClock.elapsedRealtime() + ms
    private fun acquire(): VisionCohortLease = client.acquireForDiagnostics(scope.owner("fault-test"), deadline(), scope.authority).also { lease = it }
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
        assertSame(lease, client.acquireForDiagnostics(scope.owner("fault-test"), deadline(), scope.authority))
        assertThrows(IllegalArgumentException::class.java) { client.open(lease, spec(), deadline()) }
        assertNotEquals(Process.myPid(), VisionRuntimeCodec.objectJson(handle.runtimeJson).getInt("modelOwnerPid"))
        invoke(handle)
        assertTrue(client.close(handle, deadline()).nativeCloseConfirmed)
        assertFalse(client.modelReleasedFuture(handle.modelInstanceId).toCompletableFuture().get().processDeath)
        assertFalse(client.actualReleasedFuture(lease.cohortId).toCompletableFuture().isDone)
    }
    @Test fun differentOwnerBusyDoesNotBorrowNativeOwnershipOrRetireCurrentCohort() {
        val current = acquire(); val handle = fake(current)
        val busy = assertThrows(VisionCohortBusyException::class.java) { client.acquireForDiagnostics(scope.owner("different-owner"), deadline(), scope.authority) }
        assertSame(client.whenAvailable(current.cohortId), busy.availabilityReceipt)
        assertNotSame(client.actualReleasedFuture(current.cohortId), busy.availabilityReceipt)
        assertFalse(busy.availabilityReceipt.toCompletableFuture().isDone)
        assertNull(client.peekLease("different-owner"))
        assertSame(current, client.peekLease("fault-test"))
        invoke(handle)
    }
    @Test fun sameOwnerNameWithNewImmutableRefCannotBorrowOrRetireAnOldAllocation() {
        val oldOwner = scope.owner("fault-test")
        val previous = acquire(); fake(previous)
        scope.authority.retireOwner(oldOwner)
        val nextOwner = scope.owner("fault-test")
        assertNotSame(oldOwner, nextOwner)
        assertThrows(VisionStaleAuthorityException::class.java) {
            client.acquireForDiagnostics(oldOwner, deadline(), scope.authority)
        }
        val busy = assertThrows(VisionCohortBusyException::class.java) {
            client.acquireForDiagnostics(nextOwner, deadline(), scope.authority)
        }
        assertSame(client.whenAvailable(previous.cohortId), busy.availabilityReceipt)
        client.requestExit(previous, "old_source_retired")
        busy.availabilityReceipt.toCompletableFuture().get(5, TimeUnit.SECONDS)
        val current = client.acquireForDiagnostics(nextOwner, deadline(), scope.authority).also { lease = it }
        val handle = fake(current)
        val completedOld = requireNotNull(client.retirementSnapshot(oldOwner))
        assertSame(previous, completedOld.lease)
        assertSame(busy.availabilityReceipt, completedOld.allocationAvailable)
        assertNotSame(completedOld.allocationAvailable, completedOld.actualReleased)
        assertTrue(completedOld.actualReleased.toCompletableFuture().isDone)
        assertSame(current, requireNotNull(client.retirementSnapshot(nextOwner)).lease)
        assertNotSame(completedOld.actualReleased, client.actualReleasedFuture(current.cohortId))
        client.requestExit(completedOld.lease, "late_old_source_cleanup")
        invoke(handle)
    }
    @Test fun physicalReceiptCallbackCannotHoldClientMetadataLock() {
        val current = acquire(); val handle = fake(current)
        val ownerLock = ReentrantLock(); val callbackEntered = CountDownLatch(1)
        val closed = CompletableFuture<Unit>(); val queried = CompletableFuture<VisionCohortLease?>()
        ownerLock.lock()
        client.modelReleasedFuture(handle.modelInstanceId).whenComplete { _, _ ->
            callbackEntered.countDown()
            if (ownerLock.tryLock(2, TimeUnit.SECONDS)) ownerLock.unlock()
        }
        val closing = Thread {
            try { client.close(handle, deadline()); closed.complete(Unit) }
            catch (error: Throwable) { closed.completeExceptionally(error) }
        }.apply { start() }
        var querying: Thread? = null
        try {
            assertTrue(callbackEntered.await(5, TimeUnit.SECONDS))
            querying = Thread { try { queried.complete(client.peekLease("fault-test")) }
                catch (error: Throwable) { queried.completeExceptionally(error) } }.apply { start() }
            assertSame(current, queried.get(500, TimeUnit.MILLISECONDS))
        } finally {
            ownerLock.unlock(); closing.join(5_000); querying?.join(5_000)
        }
        closed.get(5, TimeUnit.SECONDS)
    }
    @Test fun transferredInstanceHasNewEpochAndOldOwnerCannotCloseOrExitIt() {
        val oldOwner = scope.owner("fault-test")
        val old = acquire(); val handle = fake(old)
        val snapshot = requireNotNull(client.retirementSnapshot("fault-test"))
        assertSame(old, snapshot.lease)
        assertSame(client.actualReleasedFuture(old.cohortId), snapshot.actualReleased)
        assertNull(client.retirementSnapshot("another-owner"))
        client.park(old, deadline())
        val transfer = client.transfer(old, scope.owner("fault-test-next"), deadline())
        lease = transfer.lease
        val current = transfer.handles.single()
        assertEquals(handle.modelInstanceId, current.modelInstanceId)
        assertEquals(old.ownerEpoch + 1, current.ownerEpoch)
        assertNull(client.retirementSnapshot("fault-test"))
        assertNull("A transferred owner must not rejoin its still-live target's physical receipt",
            client.retirementSnapshot(oldOwner))
        client.requestExit(snapshot.lease, "stale_snapshot_must_not_exit")
        assertThrows(IllegalStateException::class.java) { client.confirmAdoption(old, deadline()) }
        client.confirmAdoption(transfer.lease, deadline())
        assertThrows(IllegalStateException::class.java) { client.close(handle, deadline()) }
        assertThrows(IllegalStateException::class.java) { client.open(old, spec(), deadline()) }
        invoke(current)
        assertFalse(snapshot.actualReleased.toCompletableFuture().isDone)
        assertSame(transfer.lease, client.acquireForDiagnostics(scope.owner("fault-test-next"), deadline(), scope.authority))
    }
    @Test fun blockedInitIsRecoveredByActualChildDeathThenAnotherCohortCanStart() {
        val old = acquire()
        client.injectDebugFault(old, "OPEN_HANG", deadline())
        assertThrows(VisionRuntimeException::class.java) { client.open(old, spec(), deadline(1_500)) }
        val receipt = client.actualReleasedFuture(old.cohortId).toCompletableFuture().get(10, TimeUnit.SECONDS)
        assertEquals(VisionReleaseProof.CHILD_DEATH, receipt.releaseProof)
        assertTrue(receipt.childPid > 0 && receipt.childPid != Process.myPid())
        // Native death is physical proof; the next cohort also needs the exact durable cleanup ACK.
        assertSame(receipt, client.whenAvailable(old.cohortId).toCompletableFuture().get(10, TimeUnit.SECONDS))
        val next = acquire(); val handle = fake(next)
        assertNotEquals(old.cohortId, next.cohortId)
        invoke(handle)
    }
    @Test fun failedOpenKeepsItsExactOwnersCompletedRetirementProofWithoutGrantingANewSource() {
        val owner = scope.owner("fault-test")
        val previous = acquire()
        val physical = client.actualReleasedFuture(previous.cohortId)
        client.injectDebugFault(previous, "OPEN_HANG", deadline())
        assertThrows(VisionRuntimeException::class.java) { client.open(previous, spec(), deadline(1_500)) }
        val receipt = physical.toCompletableFuture().get(10, TimeUnit.SECONDS)
        val snapshot = requireNotNull(client.retirementSnapshot(owner))
        assertSame(previous, snapshot.lease)
        assertSame(physical, snapshot.actualReleased)
        assertSame(receipt, snapshot.actualReleased.toCompletableFuture().get())
        val nextOwner = scope.owner("fault-test")
        assertNotSame(owner, nextOwner)
        assertNull(client.retirementSnapshot(nextOwner))
        assertSame(receipt, snapshot.allocationAvailable.toCompletableFuture().get(10, TimeUnit.SECONDS))
        val current = client.acquireForDiagnostics(nextOwner, deadline(), scope.authority).also { lease = it }
        val handle = fake(current)
        assertSame(current, requireNotNull(client.retirementSnapshot(nextOwner)).lease)
        assertSame(previous, requireNotNull(client.retirementSnapshot(owner)).lease)
        client.requestExit(snapshot.lease, "late_completed_owner_snapshot")
        invoke(handle)
    }
    @Test fun typedNativeOutputFaultRevokesItsOwnerBeforePhysicalReceiptPublication() {
        val current = acquire(); val handle = fake(current)
        val owner = scope.owner("fault-test")
        val revokedAtPublication = CompletableFuture<Boolean>()
        client.actualReleasedFuture(current.cohortId).whenComplete { _, error ->
            if (error != null) revokedAtPublication.completeExceptionally(error)
            else revokedAtPublication.complete(scope.authority.ownerRef(owner.ownerId) !== owner &&
                scope.authority.modelRef(current.cohortId, current.ownerEpoch, handle.modelInstanceId) == null)
        }
        client.reportNativeFault(owner, current, VisionNativeFaultReason.NON_FINITE_OUTPUT)
        assertTrue(revokedAtPublication.get(10, TimeUnit.SECONDS))
        assertTrue(client.modelReleasedFuture(handle.modelInstanceId).toCompletableFuture().get(5, TimeUnit.SECONDS).processDeath)
    }
    @Test fun faultReservationClosesPeerAdmissionBeforeTheExternalMetadataHookReturns() {
        val current = acquire(); val handle = fake(current)
        val owner = scope.owner("fault-test")
        val entered = CountDownLatch(1); val resume = CountDownLatch(1)
        val reported = CompletableFuture<Unit>()
        client.debugBeforeFaultMetadata = { captured -> if (captured === current) {
            entered.countDown(); check(resume.await(5, TimeUnit.SECONDS))
        } }
        val thread = Thread {
            try { client.reportNativeFault(owner, current, VisionNativeFaultReason.INVALID_NATIVE_OUTPUT); reported.complete(Unit) }
            catch (error: Throwable) { reported.completeExceptionally(error) }
        }.apply { start() }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            // This ref is deliberately still current in the external authority during the gap.
            assertSame(owner, scope.authority.ownerRef(owner.ownerId))
            assertThrows(VisionStaleAuthorityException::class.java) { client.open(current, spec(), deadline()) }
            assertThrows(VisionStaleAuthorityException::class.java) { invoke(handle) }
            client.requestExit(current, "cleanup_racing_fault_metadata")
            assertFalse(client.actualReleasedFuture(current.cohortId).toCompletableFuture().isDone)
        } finally { client.debugBeforeFaultMetadata = null; resume.countDown(); thread.join(5_000) }
        reported.get(5, TimeUnit.SECONDS)
        client.actualReleasedFuture(current.cohortId).toCompletableFuture().get(10, TimeUnit.SECONDS)
        assertNotSame(owner, scope.authority.ownerRef(owner.ownerId))
    }
    @Test fun observedUnexpectedDeathCannotBeReclassifiedByALaterOrdinaryExit() {
        val current = acquire(); val handle = fake(current)
        val owner = scope.owner("fault-test")
        val entered = CountDownLatch(1); val resume = CountDownLatch(1)
        val revokedAtPublication = CompletableFuture<Boolean>()
        client.actualReleasedFuture(current.cohortId).whenComplete { _, error ->
            if (error != null) revokedAtPublication.completeExceptionally(error)
            else revokedAtPublication.complete(scope.authority.ownerRef(owner.ownerId) !== owner)
        }
        client.debugBeforeFaultMetadata = { captured -> if (captured === current) {
            entered.countDown(); check(resume.await(5, TimeUnit.SECONDS))
        } }
        try {
            val childPid = VisionRuntimeCodec.objectJson(handle.runtimeJson).getInt("modelOwnerPid")
            check(childPid > 0 && childPid != Process.myPid())
            Process.killProcess(childPid) // Only this test's authenticated private child.
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertSame(owner, scope.authority.ownerRef(owner.ownerId))
            client.requestExit(current, "ordinary_exit_after_observed_death")
            assertFalse(client.actualReleasedFuture(current.cohortId).toCompletableFuture().isDone)
        } finally { client.debugBeforeFaultMetadata = null; resume.countDown() }
        assertTrue(revokedAtPublication.get(10, TimeUnit.SECONDS))
        assertEquals(VisionReleaseProof.CHILD_DEATH,
            client.actualReleasedFuture(current.cohortId).toCompletableFuture().get().releaseProof)
    }
    @Test fun ordinaryExitReleasesNativeWithoutClassifyingTheOwnerAsFaulty() {
        val current = acquire(); fake(current)
        val owner = scope.owner("fault-test")
        client.requestExit(current, "ordinary_owner_cleanup")
        client.actualReleasedFuture(current.cohortId).toCompletableFuture().get(10, TimeUnit.SECONDS)
        assertSame(owner, scope.authority.ownerRef(owner.ownerId))
    }
    @Test fun staleTypedOutputFaultCannotRevokeTheTransferredOwner() {
        val old = acquire(); fake(old)
        val previousOwner = scope.owner("fault-test")
        val targetOwner = scope.owner("fault-test-next")
        client.park(old, deadline())
        val moved = client.transfer(old, targetOwner, deadline()).also { lease = it.lease }
        client.confirmAdoption(moved.lease, deadline())
        assertThrows(VisionStaleAuthorityException::class.java) {
            client.reportNativeFault(previousOwner, old, VisionNativeFaultReason.OUTPUT_SHAPE_MISMATCH)
        }
        assertSame(targetOwner, scope.authority.ownerRef(targetOwner.ownerId))
        invoke(moved.handles.single())
        assertFalse(client.actualReleasedFuture(old.cohortId).toCompletableFuture().isDone)
    }
    @Test fun lostTransferResponseCannotStrandNewEpochOrGrantOldOwnerExitAuthority() {
        val old = acquire(); fake(old)
        client.injectDebugFault(old, "DROP_TRANSFER_REPLY", deadline())
        client.park(old, deadline())
        assertThrows(VisionRuntimeException::class.java) { client.transfer(old, scope.owner("never-adopted"), deadline(1_500)) }
        assertEquals(VisionReleaseProof.CHILD_DEATH,
            client.actualReleasedFuture(old.cohortId).toCompletableFuture().get(10, TimeUnit.SECONDS).releaseProof)
    }
    @Test fun missingTargetOrPartialJavaAttachDoesNotDisarmChildAdoptionDeadline() {
        val old = acquire(); fake(old)
        client.park(old, deadline())
        val result = client.transfer(old, scope.owner("target-never-ready"), deadline(1_500))
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
            val moved = client.transfer(old, scope.owner("fault-test-next"), deadline())
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
            client.park(old, deadline()); val moved = client.transfer(old, scope.owner("fault-test-next"), deadline())
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
    @Test fun parkWithoutTransferStillHasABoundedPhysicalRetirementDeadline() {
        val current = acquire(); fake(current)
        client.park(current, deadline(500))
        assertEquals(VisionReleaseProof.CHILD_DEATH,
            client.actualReleasedFuture(current.cohortId).toCompletableFuture().get(2, TimeUnit.SECONDS).releaseProof)
    }
    @Test fun completedHistoryIsBoundedWithoutInvalidatingAlreadyReturnedReceipts() {
        var firstCohort: java.util.concurrent.CompletionStage<VisionReleaseReceipt>? = null
        var firstModel: java.util.concurrent.CompletionStage<VisionModelReleaseReceipt>? = null
        var firstOwner: kr.co.hanium.dreamup.walksafe.device.DeviceCheckOwnerRef? = null
        var lastOwner: kr.co.hanium.dreamup.walksafe.device.DeviceCheckOwnerRef? = null
        repeat(35) { index ->
            val owner = scope.owner("history-owner-$index")
            val current = client.acquireForDiagnostics(owner, deadline(), scope.authority).also { lease = it }
            val handle = fake(current)
            val cohortReceipt = client.actualReleasedFuture(current.cohortId)
            val modelReceipt = client.modelReleasedFuture(handle.modelInstanceId)
            if (index == 0) { firstCohort = cohortReceipt; firstModel = modelReceipt; firstOwner = owner }
            lastOwner = owner
            client.close(handle, deadline()); client.requestExit(current, "bounded_history_test")
            cohortReceipt.toCompletableFuture().get(5, TimeUnit.SECONDS)
            client.whenAvailable(current.cohortId).toCompletableFuture().get(5, TimeUnit.SECONDS)
        }
        fun count(field: String): Int = RemoteVisionClient::class.java.getDeclaredField(field)
            .apply { isAccessible = true }.let { (it.get(client) as Map<*, *>).size }
        val records = count("records"); val models = count("modelReceipts"); val owners = count("ownerRetirements")
        assertTrue("Completed heavy cohorts=$records; retained model receipts=$models; owners=$owners",
            records <= 1 && models <= 32 && owners <= 16)
        assertNull(client.retirementSnapshot(requireNotNull(firstOwner)))
        assertNotNull(client.retirementSnapshot(requireNotNull(lastOwner)))
        assertEquals(VisionReleaseProof.CHILD_DEATH, requireNotNull(firstCohort).toCompletableFuture().get().releaseProof)
        assertFalse(requireNotNull(firstModel).toCompletableFuture().get().processDeath)
    }
    @Test fun saturatedControlLaneIsBoundedAndCannotQueueExitBehindBlockedOperations() {
        val current = acquire(); fake(current)
        val pool = RemoteVisionClient::class.java.getDeclaredField("ipc").apply { isAccessible = true }
            .get(client) as ThreadPoolExecutor
        val idleDeadline = deadline(1_000)
        while ((pool.activeCount != 0 || pool.queue.isNotEmpty()) && SystemClock.elapsedRealtime() < idleDeadline) SystemClock.sleep(5)
        assertTrue("Control lane must be idle before saturation", pool.activeCount == 0 && pool.queue.isEmpty())
        val entered = CountDownLatch(2)
        val resume = CountDownLatch(1)
        try {
            repeat(2) { pool.execute { entered.countDown(); check(resume.await(5, TimeUnit.SECONDS)) } }
            assertTrue("Both fixed workers must be occupied before filling the queue", entered.await(2, TimeUnit.SECONDS))
            repeat(4) { pool.execute { check(resume.await(5, TimeUnit.SECONDS)) } }
            assertEquals(4, pool.queue.size)
            assertThrows(java.util.concurrent.RejectedExecutionException::class.java) { pool.execute {} }
            client.requestExit(current, "saturated_control_retirement")
            assertEquals(VisionReleaseProof.CHILD_DEATH,
                client.actualReleasedFuture(current.cohortId).toCompletableFuture().get(2, TimeUnit.SECONDS).releaseProof)
            assertTrue("Control pool peak=${pool.largestPoolSize}", pool.largestPoolSize <= 2)
        } finally { resume.countDown() }
    }

    private class BindingContext(base: Context) : ContextWrapper(base) {
        @Volatile var rejectIntent = false
        @Volatile var boundConnection: ServiceConnection? = null
        @Volatile var bind: (ServiceConnection) -> Boolean = { false }
        @Volatile var unbind: (ServiceConnection) -> Unit = {}
        override fun getPackageName(): String {
            if (rejectIntent) throw IllegalStateException("controlled pre-SDK Intent failure")
            return super.getPackageName()
        }
        override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
            boundConnection = connection
            return bind(connection)
        }
        override fun unbindService(connection: ServiceConnection) = unbind(connection)
    }
    private fun bindingClient(context: BindingContext) = RemoteVisionClient.forBindingTests(context)
    private fun pool(client: RemoteVisionClient, name: String) = RemoteVisionClient::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(client) as ThreadPoolExecutor

    @Test fun intentConstructionFailureBeforeTheSdkCallIsPositiveNoBindEvidence() {
        val context = BindingContext(InstrumentationRegistry.getInstrumentation().targetContext)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        context.bind = { calls.incrementAndGet(); true }; context.unbind = { calls.incrementAndGet(); Unit }
        context.rejectIntent = true
        val isolated = bindingClient(context)
        val current = isolated.acquireForDiagnostics(scope.owner("intent-failure"), deadline(), scope.authority)
        assertEquals(VisionReleaseProof.NO_NATIVE_START,
            isolated.actualReleasedFuture(current.cohortId).toCompletableFuture().get(3, TimeUnit.SECONDS).releaseProof)
        isolated.whenAvailable(current.cohortId).toCompletableFuture().get(3, TimeUnit.SECONDS)
        assertEquals(0, calls.get())
    }

    @Test fun falseOrSecurityExceptionStillWaitsForSuccessfulUnbindAndItsActualReturn() {
        for (security in listOf(false, true)) {
            val context = BindingContext(InstrumentationRegistry.getInstrumentation().targetContext)
            val attempted = CountDownLatch(1); val bindReturn = CountDownLatch(1)
            val unbindEntered = CountDownLatch(1); val unbindReturn = CountDownLatch(1)
            context.bind = {
                attempted.countDown(); check(bindReturn.await(5, TimeUnit.SECONDS))
                if (security) throw SecurityException("controlled bind failure") else false
            }
            context.unbind = { unbindEntered.countDown(); check(unbindReturn.await(5, TimeUnit.SECONDS)) }
            val isolated = bindingClient(context)
            val current = isolated.acquireForDiagnostics(scope.owner("binding-$security"), deadline(), scope.authority)
            val actual = isolated.actualReleasedFuture(current.cohortId).toCompletableFuture()
            try {
                assertTrue(attempted.await(2, TimeUnit.SECONDS))
                isolated.requestExit(current, "retire_while_bind_has_not_returned")
                assertFalse(actual.isDone)
                bindReturn.countDown()
                assertTrue(unbindEntered.await(2, TimeUnit.SECONDS))
                assertFalse(actual.isDone)
                val busy = assertThrows(VisionCohortBusyException::class.java) {
                    isolated.acquireForDiagnostics(scope.owner("next-binding-$security"), deadline(), scope.authority)
                }
                assertFalse(busy.availabilityReceipt.toCompletableFuture().isDone)
                unbindReturn.countDown()
                assertEquals(VisionReleaseProof.NO_NATIVE_START, actual.get(3, TimeUnit.SECONDS).releaseProof)
                busy.availabilityReceipt.toCompletableFuture().get(3, TimeUnit.SECONDS)
            } finally { bindReturn.countDown(); unbindReturn.countDown() }
        }
    }

    @Test fun aTrueBindFollowedByUnbindIllegalArgumentCannotPublishNoNativeStartOrAvailability() {
        val context = BindingContext(InstrumentationRegistry.getInstrumentation().targetContext)
        val attempted = CountDownLatch(1); val unbindEntered = CountDownLatch(1)
        context.bind = { attempted.countDown(); true }
        context.unbind = { unbindEntered.countDown(); throw IllegalArgumentException("controlled unbind failure") }
        val isolated = bindingClient(context)
        val current = isolated.acquireForDiagnostics(scope.owner("binding-unconfirmed"), deadline(), scope.authority)
        assertTrue(attempted.await(2, TimeUnit.SECONDS))
        isolated.requestExit(current, "no_init_does_not_prove_unbind")
        assertTrue(unbindEntered.await(2, TimeUnit.SECONDS))
        val drained = CompletableFuture<Unit>()
        pool(isolated, "unbinding").execute { drained.complete(Unit) }
        drained.get(2, TimeUnit.SECONDS)
        assertFalse(isolated.actualReleasedFuture(current.cohortId).toCompletableFuture().isDone)
        val busy = assertThrows(VisionCohortBusyException::class.java) {
            isolated.acquireForDiagnostics(scope.owner("binding-unconfirmed-next"), deadline(), scope.authority)
        }
        assertFalse(busy.availabilityReceipt.toCompletableFuture().isDone)
    }

    @Test fun cancelledQueuedBindMustActuallyDrainAndLateEndpointCannotTouchANewCohort() {
        val context = BindingContext(InstrumentationRegistry.getInstrumentation().targetContext)
        val binds = java.util.concurrent.atomic.AtomicInteger(); val unbinds = java.util.concurrent.atomic.AtomicInteger()
        context.bind = { binds.incrementAndGet(); false }; context.unbind = { unbinds.incrementAndGet(); Unit }
        val isolated = bindingClient(context)
        val blocked = CountDownLatch(1); val resume = CountDownLatch(1)
        pool(isolated, "binding").execute { blocked.countDown(); check(resume.await(5, TimeUnit.SECONDS)) }
        assertTrue(blocked.await(2, TimeUnit.SECONDS))
        val old = isolated.acquireForDiagnostics(scope.owner("queued-binding"), deadline(), scope.authority)
        val oldRecord = RemoteVisionClient::class.java.getDeclaredField("records").apply { isAccessible = true }
            .get(isolated).let { (it as Map<*, *>)[old.cohortId]!! }
        val oldConnection = oldRecord.javaClass.getDeclaredField("connection").apply { isAccessible = true }.get(oldRecord) as ServiceConnection
        try {
            isolated.requestExit(old, "cancel_before_sdk_bind")
            assertFalse(isolated.actualReleasedFuture(old.cohortId).toCompletableFuture().isDone)
            resume.countDown()
            isolated.whenAvailable(old.cohortId).toCompletableFuture().get(3, TimeUnit.SECONDS)
            assertEquals(0, binds.get()); assertEquals(0, unbinds.get())
            context.bind = { binds.incrementAndGet(); true }
            val next = isolated.acquireForDiagnostics(scope.owner("queued-binding-next"), deadline(), scope.authority)
            val hello = java.util.concurrent.atomic.AtomicInteger()
            oldConnection.onServiceConnected(null, object : Binder() {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    hello.incrementAndGet(); return true
                }
            })
            isolated.requestExit(old, "late_old_exit")
            assertEquals(0, hello.get())
            assertSame(next, isolated.peekLease("queued-binding-next"))
            isolated.requestExit(next, "new_binding_cleanup")
            isolated.whenAvailable(next.cohortId).toCompletableFuture().get(3, TimeUnit.SECONDS)
        } finally { resume.countDown() }
    }

    @Test fun noNativeStartCannotSkipAnAcceptedHelloThatHasNotReturned() {
        val context = BindingContext(InstrumentationRegistry.getInstrumentation().targetContext)
        val bound = CountDownLatch(1); val hello = CountDownLatch(1); val resume = CountDownLatch(1)
        context.bind = { bound.countDown(); true }
        val isolated = bindingClient(context)
        val current = isolated.acquireForDiagnostics(scope.owner("blocked-hello"), deadline(), scope.authority)
        assertTrue(bound.await(2, TimeUnit.SECONDS))
        val endpoint = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                hello.countDown(); check(resume.await(5, TimeUnit.SECONDS)); reply!!.writeNoException(); return true
            }
        }
        requireNotNull(context.boundConnection).onServiceConnected(null, endpoint)
        try {
            assertTrue(hello.await(2, TimeUnit.SECONDS))
            isolated.requestExit(current, "retire_blocked_hello")
            val unbindDrained = CompletableFuture<Unit>()
            pool(isolated, "unbinding").execute { unbindDrained.complete(Unit) }
            unbindDrained.get(2, TimeUnit.SECONDS)
            assertFalse(isolated.actualReleasedFuture(current.cohortId).toCompletableFuture().isDone)
            resume.countDown()
            assertEquals(VisionReleaseProof.NO_NATIVE_START,
                isolated.actualReleasedFuture(current.cohortId).toCompletableFuture().get(3, TimeUnit.SECONDS).releaseProof)
            isolated.whenAvailable(current.cohortId).toCompletableFuture().get(3, TimeUnit.SECONDS)
        } finally { resume.countDown() }
    }

    @Test fun queuedAdmissionMustActuallyDrainAfterItsCallerHasAlreadyObservedCancellation() {
        val context = BindingContext(InstrumentationRegistry.getInstrumentation().targetContext)
        context.bind = { true }
        val isolated = bindingClient(context)
        val entered = CountDownLatch(1); val resume = CountDownLatch(1)
        val lane = pool(isolated, "admission")
        lane.execute { entered.countDown(); check(resume.await(5, TimeUnit.SECONDS)) }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val current = isolated.acquireForDiagnostics(scope.owner("queued-admission"), deadline(), scope.authority)
        val result = CompletableFuture<VisionModelHandle>()
        val worker = Thread {
            try { result.complete(isolated.open(current, spec(), deadline())) }
            catch (error: Throwable) { result.completeExceptionally(error) }
        }.apply { start() }
        try {
            val until = deadline(2_000)
            while (lane.queue.isEmpty() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(5)
            assertEquals(1, lane.queue.size)
            isolated.requestExit(current, "cancel_queued_admission")
            assertThrows(java.util.concurrent.ExecutionException::class.java) { result.get(1, TimeUnit.SECONDS) }
            assertFalse(isolated.actualReleasedFuture(current.cohortId).toCompletableFuture().isDone)
            resume.countDown()
            isolated.whenAvailable(current.cohortId).toCompletableFuture().get(3, TimeUnit.SECONDS)
        } finally { resume.countDown(); worker.join(2_000) }
    }

    @Test fun aRejectedBindEnqueueAcknowledgesOnlyTheWrapperThatNeverEnteredTheSdk() {
        val context = BindingContext(InstrumentationRegistry.getInstrumentation().targetContext)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        context.bind = { calls.incrementAndGet(); false }; context.unbind = { calls.incrementAndGet(); Unit }
        val isolated = bindingClient(context)
        val entered = CountDownLatch(1); val resume = CountDownLatch(1)
        val lane = pool(isolated, "binding")
        lane.execute { entered.countDown(); check(resume.await(5, TimeUnit.SECONDS)) }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        lane.execute { check(resume.await(5, TimeUnit.SECONDS)) }
        try {
            val current = isolated.acquireForDiagnostics(scope.owner("rejected-bind"), deadline(), scope.authority)
            assertEquals(VisionReleaseProof.NO_NATIVE_START,
                isolated.actualReleasedFuture(current.cohortId).toCompletableFuture().get(2, TimeUnit.SECONDS).releaseProof)
            isolated.whenAvailable(current.cohortId).toCompletableFuture().get(2, TimeUnit.SECONDS)
            assertEquals(0, calls.get())
        } finally { resume.countDown() }
    }

    @Test fun physicalCleanupDoesNotWaitForJournalButAnotherAllocationMustWaitForItsAcknowledgement() {
        val current = acquire(); fake(current)
        val entered = CountDownLatch(1); val resume = CountDownLatch(1)
        client.debugBeforeJournalCleanup = { if (it === current) { entered.countDown(); check(resume.await(5, TimeUnit.SECONDS)) } }
        try {
            client.requestExit(current, "split_physical_and_journal")
            client.actualReleasedFuture(current.cohortId).toCompletableFuture().get(5, TimeUnit.SECONDS)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val snapshot = requireNotNull(client.retirementSnapshot("fault-test"))
            assertTrue(snapshot.actualReleased.toCompletableFuture().isDone)
            assertFalse(snapshot.allocationAvailable.toCompletableFuture().isDone)
            val nextOwner = scope.owner("journal-pending-next")
            val busy = assertThrows(VisionCohortBusyException::class.java) {
                client.acquireForDiagnostics(nextOwner, deadline(), scope.authority)
            }
            assertSame(snapshot.allocationAvailable, busy.availabilityReceipt)
            resume.countDown()
            busy.availabilityReceipt.toCompletableFuture().get(5, TimeUnit.SECONDS)
            val next = client.acquireForDiagnostics(nextOwner, deadline(), scope.authority).also { lease = it }
            assertNotEquals(current.cohortId, next.cohortId)
            invoke(fake(next))
        } finally { client.debugBeforeJournalCleanup = null; resume.countDown() }
    }
}
