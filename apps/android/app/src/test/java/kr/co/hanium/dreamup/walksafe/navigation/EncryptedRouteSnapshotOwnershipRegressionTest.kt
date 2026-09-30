package kr.co.hanium.dreamup.walksafe.navigation

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.concurrent.thread
import kr.co.hanium.dreamup.walksafe.AccountDeletionResetCoordinator
import kr.co.hanium.dreamup.walksafe.AccountDeletionResetJournal
import kr.co.hanium.dreamup.walksafe.AccountDeletionResetJournalState
import kr.co.hanium.dreamup.walksafe.AccountDeletionResetPhase
import kr.co.hanium.dreamup.walksafe.AccountDeletionResetResult
import kr.co.hanium.dreamup.walksafe.security.AeadKeyPolicy
import kr.co.hanium.dreamup.walksafe.security.AeadLimits
import kr.co.hanium.dreamup.walksafe.security.AeadOpenResult
import kr.co.hanium.dreamup.walksafe.security.AeadSealResult
import kr.co.hanium.dreamup.walksafe.security.LocalAeadKeyProvider
import kr.co.hanium.dreamup.walksafe.security.VersionedLocalAead
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** S07-40/44: the original three existing-API witnesses failed with assertions before the fix. */
class EncryptedRouteSnapshotOwnershipRegressionTest {
    private val ownerA = RouteSnapshotOwner("actor-a", "account:1")
    private val scopeA = RouteSnapshotScope(ownerA, "walk-a")
    private val scopeB = RouteSnapshotScope(ownerA, "walk-b")

    @Test
    fun anotherStoreCannotAssertThatALiveWalkEndedAndReplaceItsArchive() {
        val fixture = Fixture()
        val liveA = fixture.boundStore()
        val competingB = fixture.boundStore()
        assertTrue(liveA.beginWalk(scopeA))
        assertTrue(liveA.saveFirstRoute(scopeA, route("route-a"), destination, 1_000L))

        assertFalse(competingB.beginWalk(scopeB))
        assertFalse(competingB.saveFirstRoute(scopeB, route("route-b"), destination, 2_000L))
        assertEquals("walk-a", liveA.load(scopeA, 2_500L)?.walkSessionId)
    }

    @Test
    fun aLatePurgeFromThePreviousStoreCannotEraseTheNewWalk() {
        val fixture = Fixture()
        val oldA = fixture.boundStore()
        val newB = fixture.boundStore()
        assertTrue(oldA.beginWalk(scopeA))
        assertTrue(oldA.saveFirstRoute(scopeA, route("route-a"), destination, 1_000L))
        assertTrue(oldA.endWalk())
        assertTrue(newB.beginWalk(scopeB))
        assertTrue(newB.saveFirstRoute(scopeB, route("route-b"), destination, 2_000L))

        assertTrue(oldA.clearWalk(scopeA))
        assertTrue(oldA.endWalk())
        assertEquals("walk-b", newB.load(scopeB, 2_500L)?.walkSessionId)
    }

    @Test
    fun anUnboundStoreCannotReturnThePreviousAccountsDestination() {
        val fixture = Fixture()
        fixture.saveA()
        val unbound = fixture.store()

        assertNull(unbound.load(scopeA, 2_000L))
        assertNotNull(fixture.storage.envelope)
        assertEquals(scopeA, unbound.expiryMetadata(2_000L)?.scope)
    }

    @Test
    fun ownerAccountGenerationAndWalkMismatchesCannotReturnOrRemoveTheCurrentArchive() {
        val fixture = Fixture()
        val store = fixture.saveA()
        val original = fixture.storage.envelope
        listOf(
            scopeA.copy(owner = RouteSnapshotOwner("actor-b", "account:1")),
            scopeA.copy(owner = ownerA.copy(accountGeneration = "account:2")),
            scopeB,
        ).forEach { mismatch ->
            assertNull(store.load(mismatch, 2_000L))
            assertEquals(original, fixture.storage.envelope)
        }
        assertEquals("route-a", store.load(scopeA, 2_000L)?.route?.providerRouteId)
    }

    @Test
    fun anotherAccountLoginDeletesTheArchiveAndRejectsTheOldOwnersLateSave() {
        val fixture = Fixture()
        val oldA = fixture.saveA()
        val ownerB = RouteSnapshotOwner("actor-b", "account:1")
        val newB = fixture.store()
        assertTrue(newB.bindAccount(ownerB))
        assertNull(fixture.storage.envelope)
        val nextScope = RouteSnapshotScope(ownerB, "walk-b")
        assertTrue(newB.beginWalk(nextScope))
        assertTrue(newB.saveFirstRoute(nextScope, route("route-b"), destination, 2_000L))

        assertFalse(oldA.saveFirstRoute(scopeA, route("late-a"), destination, 2_500L))
        assertTrue(oldA.clearWalk(scopeA))
        assertNull(oldA.load(scopeA, 3_000L))
        assertEquals("route-b", newB.load(nextScope, 3_000L)?.route?.providerRouteId)
    }

    @Test
    fun theSameActorWithANewAccountGenerationPurgesItsOldArchive() {
        val fixture = Fixture()
        val oldA = fixture.saveA()
        val next = fixture.store()
        val newOwner = ownerA.copy(accountGeneration = "account:2")
        assertTrue(next.bindAccount(newOwner))
        assertNull(fixture.storage.envelope)
        assertNull(oldA.load(scopeA, 2_000L))
        assertFalse(oldA.beginWalk(scopeA))
    }

    @Test
    fun sameOwnerTokenRenewalKeepsTheFirstRouteAndTheExistingWalkLease() {
        val fixture = Fixture()
        val store = fixture.saveA()
        val envelope = fixture.storage.envelope
        assertTrue(store.bindAccount(ownerA))
        assertTrue(store.saveFirstRoute(scopeA, route("reroute"), destination, 2_000L))
        assertEquals(envelope, fixture.storage.envelope)
        assertEquals("route-a", store.load(scopeA, 3_000L)?.route?.providerRouteId)
    }

    @Test
    fun logoutAtAnyWalkStateDeletesCiphertextAndInvalidatesTheLease() {
        // Store admission is independent of Android ACTIVE/PAUSED/SAFE_STOP/ENDED UI state.
        listOf("ACTIVE", "PAUSED", "SAFE_STOP", "ENDED").forEach { state ->
            val fixture = Fixture()
            val store = fixture.saveA()
            assertTrue(state, store.bindAccount(null))
            assertNull(state, fixture.storage.envelope)
            assertNull(state, store.load(scopeA, 2_000L))
            assertFalse(state, store.saveFirstRoute(scopeA, route("late"), destination, 2_000L))
        }
    }

    @Test
    fun normalEndDeletesItsArchiveAndAllowsTheNextWalk() {
        val fixture = Fixture()
        val first = fixture.saveA()
        assertTrue(first.endWalk())
        assertNull(fixture.storage.envelope)
        assertFalse(first.beginWalk(scopeA))
        assertFalse(first.saveFirstRoute(scopeA, route("late"), destination, 2_000L))
        val next = fixture.boundStore()
        assertTrue(next.beginWalk(scopeB))
        assertTrue(next.saveFirstRoute(scopeB, route("next"), destination, 3_000L))
        assertEquals("next", next.load(scopeB, 4_000L)?.route?.providerRouteId)
    }

    @Test
    fun aDelayedExpiryForWalkACannotDeleteWalkB() {
        val fixture = Fixture()
        val oldA = fixture.saveA()
        val expiry = requireNotNull(oldA.expiryMetadata(1_000L))
        assertTrue(oldA.endWalk())
        val newB = fixture.boundStore()
        assertTrue(newB.beginWalk(scopeB))
        assertTrue(newB.saveFirstRoute(scopeB, route("route-b"), destination, 2_000L))
        assertTrue(oldA.clearWalk(expiry.scope))
        assertEquals("route-b", newB.load(scopeB, 3_000L)?.route?.providerRouteId)
    }

    @Test
    fun anOldScopesPurgeDoesNotDestroyAnUnreadableNewArchive() {
        val fixture = Fixture()
        val oldA = fixture.saveA()
        assertTrue(oldA.endWalk())
        val newB = fixture.boundStore()
        assertTrue(newB.beginWalk(scopeB))
        assertTrue(newB.saveFirstRoute(scopeB, route("route-b"), destination, 2_000L))
        fixture.storage.envelope = "temporarily unreadable new archive"
        assertFalse(oldA.clearWalk(scopeA))
        assertEquals("temporarily unreadable new archive", fixture.storage.envelope)
    }

    @Test
    fun twoConcurrentStoresCanAdmitOnlyOneLiveWalk() {
        val fixture = Fixture()
        val a = fixture.boundStore()
        val b = fixture.boundStore()
        val start = CountDownLatch(1)
        val finished = CountDownLatch(2)
        val admitted = AtomicInteger()
        val workers = listOf(a to scopeA, b to scopeB).map { (store, scope) ->
            thread {
                try {
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    if (store.beginWalk(scope)) {
                        assertTrue(store.saveFirstRoute(scope, route(scope.walkSessionId), destination, 1_000L))
                        admitted.incrementAndGet()
                    }
                } finally { finished.countDown() }
            }
        }
        start.countDown()
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        workers.forEach { it.join(100L) }
        assertEquals(1, admitted.get())
        val saved = requireNotNull(a.expiryMetadata(2_000L))
        assertTrue(saved.scope == scopeA || saved.scope == scopeB)
    }

    @Test
    fun ownerlessV3PayloadIsDeletedAndNeverReturned() {
        val fixture = Fixture()
        val store = fixture.saveA()
        val payload = fixture.open()
        payload.put("schema_version", "walksafe.encrypted-route-snapshot.v3")
        payload.remove("owner_actor_id")
        payload.remove("owner_account_generation")
        fixture.seal(payload)

        assertNull(store.load(scopeA, 2_000L))
        assertNull(fixture.storage.envelope)
    }

    @Test
    fun failedAccountPurgeKeepsCiphertextAndInvalidatesTheOldLeaseUntilRetrySucceeds() {
        val fixture = Fixture()
        val store = fixture.saveA()
        fixture.storage.clearSucceeds = false
        assertFalse(store.bindAccount(null))
        assertNotNull(fixture.storage.envelope)
        assertNull(store.load(scopeA, 2_000L))
        assertFalse(store.saveFirstRoute(scopeA, route("late"), destination, 2_000L))
        fixture.storage.clearSucceeds = true
        assertTrue(store.bindAccount(null))
        assertNull(fixture.storage.envelope)
    }

    @Test
    fun aSuccessfulClearReturnWithoutActualDeletionIsStillAFailure() {
        val fixture = Fixture()
        val store = fixture.saveA()
        fixture.storage.pretendClear = true
        assertFalse(store.bindAccount(null))
        assertNotNull(fixture.storage.envelope)
    }

    @Test
    fun accountResetBlocksOnRealSnapshotDeletionFailureAndCompletesOnRetry() {
        val fixture = Fixture()
        val store = fixture.saveA()
        var state: AccountDeletionResetJournalState = AccountDeletionResetJournalState.Absent
        val journal = object : AccountDeletionResetJournal {
            override fun read() = state
            override fun write(phase: AccountDeletionResetPhase): Boolean {
                state = AccountDeletionResetJournalState.Pending(phase); return true
            }
            override fun clear(): Boolean { state = AccountDeletionResetJournalState.Absent; return true }
        }
        val reset = AccountDeletionResetCoordinator(journal, { true }, { true }, { true },
            resetPlainPreferences = { store.bindAccount(null) }, resetFieldStorage = { true },
            clearDeletionIntentFence = { true })
        fixture.storage.clearSucceeds = false
        assertEquals(AccountDeletionResetResult.BLOCKED, reset.beginAndReconcile())
        assertNotNull(fixture.storage.envelope)
        assertTrue(state is AccountDeletionResetJournalState.Pending)
        fixture.storage.clearSucceeds = true
        assertEquals(AccountDeletionResetResult.COMPLETED, reset.reconcilePending())
        assertNull(fixture.storage.envelope)
        assertEquals(AccountDeletionResetJournalState.Absent, state)
    }

    private inner class Fixture {
        val storage = OwnershipStorage()
        val keys = OwnershipKeys()
        val process = RouteSnapshotProcessCoordinator()
        private fun aead() = VersionedLocalAead(AeadKeyPolicy("test.snapshot.ownership.v", 1, setOf(1)), keys)
        fun store() = EncryptedRouteSnapshotStore(storage, aead(), testOnly = Unit, processCoordinator = process)
        fun boundStore() = store().also { check(it.bindAccount(ownerA)) }
        fun saveA() = boundStore().also {
            check(it.beginWalk(scopeA))
            check(it.saveFirstRoute(scopeA, route("route-a"), destination, 1_000L))
        }
        fun open(): JSONObject = JSONObject(String((aead().open(requireNotNull(storage.envelope), aad, limits)
            as AeadOpenResult.Opened).plaintext, Charsets.UTF_8))
        fun seal(payload: JSONObject) {
            storage.envelope = (aead().seal(payload.toString().toByteArray(Charsets.UTF_8), aad, limits)
                as AeadSealResult.Sealed).envelope
        }
        private val aad = "kr.co.hanium.dreamup.walksafe|USER|navigation|route-snapshot|schema=1".toByteArray(Charsets.UTF_8)
        private val limits = AeadLimits(2 * 1_024 * 1_024, 2 * 1_024 * 1_024 + 16, 3 * 1_024 * 1_024)
    }
    private val destination = RoutePoint(37.001, 127.0, "목적지")
    private fun route(id: String) = WalkingRoute(
        priority = "STAIR_AVOID", summary = WalkingRouteSummary(111, 90),
        polyline = listOf(RoutePoint(37.0, 127.0), destination),
        guidePoints = listOf(WalkingRouteGuidePoint(0, RoutePoint(37.0005, 127.0), "직진", 56, 55, 0f, 11)),
        providerRouteId = id,
    )
}

private class OwnershipStorage : RouteSnapshotStorage {
    var envelope: String? = null
    var clearSucceeds = true
    var pretendClear = false
    override fun read(): String? = envelope
    override fun write(envelope: String): Boolean { this.envelope = envelope; return true }
    override fun clear(): Boolean {
        if (clearSucceeds && !pretendClear) envelope = null
        return clearSucceeds
    }
}
private class OwnershipKeys : LocalAeadKeyProvider {
    private val keys = mutableMapOf<String, SecretKey>()
    override fun getOrCreate(alias: String): SecretKey = keys.getOrPut(alias, ::newKey)
    override fun getExisting(alias: String): SecretKey? = keys[alias]
    override fun delete(alias: String): Boolean { keys.remove(alias); return true }
    override fun createFreshAfterVerifiedPurge(alias: String): SecretKey = newKey().also { keys[alias] = it }
    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
}
