package kr.co.hanium.dreamup.walksafe.feedback

import java.util.concurrent.CompletableFuture
import kr.co.hanium.dreamup.walksafe.AccountDeletionResetCoordinator
import kr.co.hanium.dreamup.walksafe.device.PostLoginDeviceCheckState
import kr.co.hanium.dreamup.walksafe.fieldlog.NoopFieldSessionLog
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.startup.PrivacyStartupInspectionController
import org.junit.Assert.*
import org.junit.Test

class SpeechOutputStorageCoordinatorTest {
    @Test fun constructorDoesNotObservePermissionsCreateStorageStartWorkOrSampleClocks() = SpeechStorageFixture().use { f ->
        assertNull(f.owner.collectionOrNull()); assertNull(f.owner.authorityOrNull())
        assertTrue(f.collections.isEmpty() && f.queryEvents.isEmpty() && f.worker.isEmpty() && f.ui.isEmpty())
        assertEquals(0,f.clockReads)
    }
    @Test fun exactPublicationUsesTheOriginalActuatorReceiptWindowAndCapturedPermissionOwners() = SpeechStorageFixture().use { f ->
        val collection=f.initialize(); f.owner.refreshBinding(f.actuator)
        val authority=requireNotNull(f.owner.authorityOrNull())
        assertSame(f.actuator,authority.binding.actor);assertSame(f.actuator,authority.binding.source)
        assertSame(f.receipt,authority.binding.rawConfirmation);assertSame(collection.window,authority.binding.window)
        assertSame(f.session,authority.binding.gatewaySession);assertEquals(f.gatewayGeneration,authority.binding.gatewayGeneration)
        assertTrue(f.owner.bindingIsCurrent(authority.binding));assertNotNull(f.actuator.speechOutputListener)
        assertEquals(listOf("observed"),f.queryEvents);assertEquals(0,f.clockReads)
    }
    @Test fun unchangedReadinessPublicationKeepsExactlyTheSameAuthorityAndListener() = SpeechStorageFixture().use { f ->
        f.initialize();f.owner.refreshBinding(f.actuator)
        val before=f.owner.authorityOrNull();val listener=f.actuator.speechOutputListener
        f.owner.refreshBinding(f.actuator)
        assertSame(before,f.owner.authorityOrNull());assertSame(listener,f.actuator.speechOutputListener)
        assertTrue(f.worker.isEmpty())
    }
    @Test fun missingObservedPermissionCannotPublishOrScheduleRecovery() = SpeechStorageFixture().use { f ->
        f.initialize();f.observed=ObservedPermissionSnapshot(true,false,true,false);f.owner.refreshBinding(f.actuator)
        assertNull(f.owner.authorityOrNull());assertNull(f.actuator.speechOutputListener);assertTrue(f.worker.isEmpty())
    }
    @Test fun memoryPermissionAndEpochChangesRejectOldAuthorityWithoutAnotherOsObservation() = SpeechStorageFixture().use { f ->
        f.initialize();f.owner.refreshBinding(f.actuator);val before=requireNotNull(f.owner.authorityOrNull())
        f.permission.setRawCollectionConsent(false)
        assertFalse(f.owner.bindingIsCurrent(before.binding));assertEquals(1,f.queryEvents.count { it=="observed" })
        f.permission.setRawCollectionConsent(true);f.lifecycle.handle(WalkSessionEvent.EnteredBackground)
        assertFalse(f.owner.bindingIsCurrent(before.binding))
    }
    @Test fun oldCapturedListenerCannotWriteThroughANewerAuthority() = SpeechStorageFixture().use { f ->
        val collection=f.initialize();f.owner.refreshBinding(f.actuator);val old=f.actuator.speechOutputListener!!
        val first=f.owner.authorityOrNull()!!;f.owner.invalidateBinding();f.feedbackGeneration++;f.owner.refreshBinding(f.actuator)
        assertNotSame(first,f.owner.authorityOrNull());assertTrue(first.binding.isInvalidated)
        old(SpeechOutputEvent(SpeechOutputKind.values().first(),SpeechOutputOutcome.values().first(),1_000L,null))
        assertEquals(1L,collection.store.droppedEventCount())
    }
    @Test fun accountDeletionIntentPermanentlyRetiresTheCurrentWindowAndFencesPublication() = SpeechStorageFixture().use { f ->
        val collection=f.initialize();f.owner.refreshBinding(f.actuator);val authority=f.owner.authorityOrNull()!!
        val token=f.owner.accountDeletionIntentToken();f.owner.blockForAccountDeletion()
        assertNotSame(token,f.owner.accountDeletionIntentToken());assertTrue(collection.retired.get());assertTrue(authority.binding.isInvalidated)
        f.owner.refreshBinding(f.actuator);assertNull(f.owner.authorityOrNull());assertTrue(f.worker.isEmpty())
    }
    @Test fun oneFailedDrainOrPurgeCannotRetryTheSameExactServerReceipt() = SpeechStorageFixture().use { f ->
        val collection=f.initialize();collection.retired.set(true);collection.purgeFuture.set(CompletableFuture.completedFuture(false))
        f.owner.refreshBinding(f.actuator);assertEquals(1,f.worker.size);f.runWorker()
        assertFalse(collection.recoveryInFlight.get());assertSame(f.receipt,collection.recoveryReceipt.get())
        repeat(3){f.owner.refreshBinding(f.actuator)}
        assertTrue(f.worker.isEmpty()&&f.ui.isEmpty());assertSame(collection,f.owner.collectionOrNull())
    }
    @Test fun newerExactServerReceiptCanAttemptItsOwnRecoveryAfterFailure() = SpeechStorageFixture().use { f ->
        val collection=f.initialize();collection.retired.set(true);collection.purgeFuture.set(CompletableFuture.completedFuture(false))
        f.owner.refreshBinding(f.actuator);f.runWorker();val next=f.confirmNext();f.owner.refreshBinding(f.actuator)
        assertEquals(1,f.worker.size);assertSame(next,collection.recoveryReceipt.get());f.runWorker()
        assertTrue(f.ui.isEmpty());assertSame(collection,f.owner.collectionOrNull())
    }
    @Test fun rejectedCleanupExecutorRemainsBlockedForTheSameReceipt() = SpeechStorageFixture().use { f ->
        val collection=f.initialize();collection.retired.set(true);f.rejecting=true;f.owner.refreshBinding(f.actuator)
        assertFalse(collection.recoveryInFlight.get());assertSame(f.receipt,collection.recoveryReceipt.get())
        f.rejecting=false;f.owner.refreshBinding(f.actuator);assertTrue(f.worker.isEmpty());assertNull(f.owner.authorityOrNull())
    }
    @Test fun successfulRecoveryPublishesANewProviderWindowOnlyAfterTheUiCurrentnessCheck() = SpeechStorageFixture().use { f ->
        val previous=f.initialize();previous.retired.set(true);f.owner.refreshBinding(f.actuator);f.runWorker()
        assertSame(previous,f.owner.collectionOrNull());assertEquals(2,f.collections.size);assertEquals(1,f.ui.size)
        f.runUi();val current=f.owner.collectionOrNull()!!
        assertNotSame(previous,current);assertNotSame(previous.provider,current.provider);assertNotSame(previous.window,current.window)
        assertTrue(previous.retired.get());assertFalse(current.retired.get());assertTrue(f.owner.bindingIsCurrent(f.owner.authorityOrNull()!!.binding))
    }
    @Test fun changedDeviceOrActuatorAfterWorkerPreparationClosesReplacementWithoutPublication() {
        for(changeDevice in listOf(true,false)) SpeechStorageFixture().use { f ->
            val previous=f.initialize();previous.retired.set(true);f.owner.refreshBinding(f.actuator);f.runWorker()
            if(changeDevice)f.device=f.device.copy(state=PostLoginDeviceCheckState.NOT_RUN) else f.currentActuator=null
            f.runUi();assertSame(previous,f.owner.collectionOrNull());assertNull(f.owner.authorityOrNull())
            assertTrue(f.collections.last().store.closeAndDrain().get());assertTrue(f.worker.isEmpty())
        }
    }
    @Test fun retiredAdmissionTokenAfterPreparationCannotInstallTheReplacement() = SpeechStorageFixture().use { f ->
        val previous=f.initialize();previous.retired.set(true);f.owner.refreshBinding(f.actuator);f.runWorker();f.owner.invalidateBinding();f.runUi()
        assertSame(previous,f.owner.collectionOrNull());assertNull(f.owner.authorityOrNull());assertTrue(f.collections.last().store.closeAndDrain().get())
    }
    @Test fun rejectedUiPublicationDrainsTheUnusedPreparedCollection() = SpeechStorageFixture().use { f ->
        val previous=f.initialize();previous.retired.set(true);f.postAccepted=false;f.owner.refreshBinding(f.actuator);f.runWorker()
        assertSame(previous,f.owner.collectionOrNull());assertTrue(f.ui.isEmpty());assertTrue(f.collections.last().store.closeAndDrain().get())
        assertFalse(previous.recoveryInFlight.get());assertNull(f.owner.authorityOrNull())
    }
    @Test fun accountIntentDuringRecoveryPreventsAnyProviderReopenOrPublication() = SpeechStorageFixture().use { f ->
        val previous=f.initialize();previous.retired.set(true);f.owner.refreshBinding(f.actuator);f.owner.blockForAccountDeletion();f.runWorker()
        assertSame(previous,f.owner.collectionOrNull());assertTrue(f.ui.isEmpty());assertEquals(1,f.collections.size)
    }
    @Test fun cleanupAlwaysAwaitsTheTwoOriginalAcknowledgementsOutsideEveryPrivacyAndCreationLock() = SpeechStorageFixture().use { f ->
        val locks = listOf(
            field(f.owner,"speechOutputCollectionCreationLock"),
            PrivacyStartupInspectionController::class.java.getDeclaredField("PRIVACY_STARTUP_PROCESS_LOCK").apply { isAccessible=true }.get(null),
            AccountDeletionResetCoordinator::class.java.getDeclaredField("PROCESS_LOCK").apply { isAccessible=true }.get(null),
        )
        for((purged,drained) in listOf(true to true,false to true,true to false)) {
            val events=mutableListOf<String>()
            fun ack(name:String,result:Boolean)=object:CompletableFuture<Boolean>() {
                override fun get():Boolean {
                    assertTrue(name,locks.none(Thread::holdsLock));events+=name;return result
                }
            }
            val purge=ack("purge",purged);val drain=ack("drain",drained)
            assertEquals(purged&&drained,f.owner.awaitCleanup(purge,drain,true))
            assertEquals(listOf("purge","drain"),events)
        }
        assertFalse(f.owner.awaitCleanup(null,CompletableFuture.completedFuture(true),true))
        assertTrue(f.owner.awaitCleanup(null,CompletableFuture.completedFuture(true),false))
    }
    @Test fun interruptedAcknowledgementFailsClosedAndRestoresTheInterruptedFlag() = SpeechStorageFixture().use { f ->
        val interrupted=object:CompletableFuture<Boolean>() { override fun get():Boolean = throw InterruptedException("test ACK interruption") }
        try {
            assertFalse(f.owner.awaitCleanup(interrupted,CompletableFuture.completedFuture(true),true))
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }
    @Test fun accountPreparationCapturesOneExactParticipantCoordinatorLeaseAndIntentTuple() = SpeechStorageFixture().use { f ->
        val collection=f.initialize();f.queryEvents.clear()
        // This input is used solely as an identity; no Noop purge/reset result is consumed.
        f.fieldParticipant=NoopFieldSessionLog()
        val prepared=f.owner.prepareAccountPurgeOnWorker()
        assertSame(collection,prepared.collection);assertSame(f.fieldParticipant,prepared.fieldParticipant)
        assertSame(f.resetOwner,prepared.coordinator);assertSame(f.lease,prepared.activityLease)
        assertSame(f.owner.accountDeletionIntentToken(),prepared.accountIntentToken)
        assertEquals(listOf("field-participant","reset-owner","activity-lease"),f.queryEvents)
        assertTrue(f.owner.resetPreparationIsCurrent(prepared));assertTrue(collection.retired.get())
    }
    @Test fun aChangedResetParticipantCoordinatorLeaseOrIntentRejectsTheCapturedPreparationBeforeFieldReset() {
        for(change in listOf("participant","coordinator","lease","intent","collection")) SpeechStorageFixture().use { f ->
            val prepared=f.owner.prepareAccountPurgeOnWorker()
            assertTrue(f.owner.awaitPreparedAccountPurge(prepared));assertTrue(f.owner.resetPreparationIsCurrent(prepared))
            val candidate=when(change) {
                "participant" -> { f.fieldParticipant=NoopFieldSessionLog();prepared }
                "coordinator" -> { f.resetOwner=f.createResetOwner();prepared }
                "lease" -> { f.lease=f.deletion.attachActivity();prepared }
                "intent" -> { f.owner.blockForAccountDeletion();prepared }
                else -> SpeechOutputAccountCleanup(f.owner.createCollection(f.root),prepared.purge,prepared.drain,
                    prepared.fieldParticipant,prepared.coordinator,prepared.activityLease,prepared.accountIntentToken)
            }
            assertFalse(change,f.owner.resetPreparationIsCurrent(candidate))
            if(change!="collection") assertFalse(change,f.owner.resetFieldStorageOnWorker())
        }
    }
    @Test fun failedAccountPurgeCannotPrepareOrPublishANewWindowDespiteDrainSuccess() = SpeechStorageFixture().use { f ->
        val prepared=f.owner.prepareAccountPurgeOnWorker()
        val failed=SpeechOutputAccountCleanup(prepared.collection,CompletableFuture.completedFuture(false),prepared.drain,
            prepared.fieldParticipant,prepared.coordinator,prepared.activityLease,prepared.accountIntentToken)
        assertFalse(f.owner.awaitPreparedAccountPurge(failed));assertNull(f.owner.prepareAfterVerifiedAccountReset())
        val replacement=f.owner.createCollection(f.root)
        assertFalse(f.owner.publishAfterVerifiedAccountReset(replacement));assertSame(prepared.collection,f.owner.collectionOrNull())
        assertTrue(replacement.store.closeAndDrain().get());assertFalse(f.owner.resetFieldStorageOnWorker())
    }
    @Test fun verifiedAccountPublicationInstallsOneFreshWindowAndRetiresTheExactPreparation() = SpeechStorageFixture().use { f ->
        val prepared=f.owner.prepareAccountPurgeOnWorker();assertTrue(f.owner.awaitPreparedAccountPurge(prepared))
        val replacement=requireNotNull(f.owner.prepareAfterVerifiedAccountReset())
        val oldToken=f.owner.accountDeletionIntentToken()
        assertTrue(f.owner.publishAfterVerifiedAccountReset(replacement));assertSame(replacement,f.owner.collectionOrNull())
        assertNotSame(prepared.collection.window,replacement.window);assertNotSame(oldToken,f.owner.accountDeletionIntentToken())
        assertNull(f.owner.prepareAfterVerifiedAccountReset());assertFalse(f.owner.resetPreparationIsCurrent(prepared))
        f.owner.refreshBinding(f.actuator);assertTrue(f.owner.bindingIsCurrent(f.owner.authorityOrNull()!!.binding))
    }
    private fun field(receiver:Any,name:String)=receiver.javaClass.getDeclaredField(name).apply { isAccessible=true }.get(receiver)
}
