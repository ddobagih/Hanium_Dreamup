package kr.co.hanium.dreamup.walksafe.navigation

import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kr.co.hanium.dreamup.walksafe.security.AeadKeyPolicy
import kr.co.hanium.dreamup.walksafe.security.LocalAeadKeyProvider
import kr.co.hanium.dreamup.walksafe.security.VersionedLocalAead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 07.txt #40: only a retired walk or a previous process's archive may be replaced. */
class EncryptedRouteSnapshotEndedWalkTest {
    private val owner = RouteSnapshotOwner("actor-a", "account:1")
    private fun scope(walk: String) = RouteSnapshotScope(owner, walk)

    @Test
    fun leftoverSnapshotOfAKilledProcessIsReplacedAfterTheNewProcessAdmitsItsWalk() {
        val storage = EndedWalkSnapshotStorage()
        val keys = EndedWalkKeyProvider()
        val firstProcess = newStore(storage, keys, RouteSnapshotProcessCoordinator())
        assertTrue(firstProcess.beginWalk(scope("walk-1")))
        assertTrue(firstProcess.saveFirstRoute(scope("walk-1"), route("first"), destination, 1_000L))

        // A new process has no live walk. It still cannot save until it admits the new walk.
        val nextProcess = newStore(storage, keys, RouteSnapshotProcessCoordinator())
        assertFalse(nextProcess.saveFirstRoute(scope("walk-2"), route("second"), destination, 2_000L))
        assertTrue(nextProcess.beginWalk(scope("walk-2")))
        assertTrue(nextProcess.saveFirstRoute(scope("walk-2"), route("second"), destination, 3_000L))
        val stored = requireNotNull(nextProcess.load(scope("walk-2"), 4_000L))
        assertEquals("walk-2", stored.walkSessionId)
        assertEquals("second", stored.route.providerRouteId)
        assertEquals(3_000L, stored.savedAtEpochMs)
    }

    @Test
    fun theLiveWalkStillKeepsItsFirstRouteAfterReroute() {
        val store = newStore(EndedWalkSnapshotStorage(), EndedWalkKeyProvider(), RouteSnapshotProcessCoordinator())
        assertTrue(store.beginWalk(scope("walk-1")))
        assertTrue(store.saveFirstRoute(scope("walk-1"), route("first"), destination, 1_000L))
        assertTrue(store.saveFirstRoute(scope("walk-1"), route("reroute"), destination, 2_000L))
        assertEquals("first", store.load(scope("walk-1"), 3_000L)?.route?.providerRouteId)
    }

    @Test
    fun failedDeletionOfTheEndedWalkFailsClosedWithoutOverwritingIt() {
        val storage = EndedWalkSnapshotStorage()
        val keys = EndedWalkKeyProvider()
        val store = newStore(storage, keys, RouteSnapshotProcessCoordinator())
        assertTrue(store.beginWalk(scope("walk-1")))
        assertTrue(store.saveFirstRoute(scope("walk-1"), route("first"), destination, 1_000L))
        val original = requireNotNull(storage.envelope)
        storage.clearSucceeds = false
        assertFalse(store.endWalk())
        assertTrue(store.beginWalk(scope("walk-2")))
        assertFalse(store.saveFirstRoute(scope("walk-2"), route("second"), destination, 2_000L))
        assertEquals(original, storage.envelope)
        assertNotNull(storage.envelope)
        storage.clearSucceeds = true
        assertTrue(store.saveFirstRoute(scope("walk-2"), route("second"), destination, 3_000L))
        assertEquals("second", store.load(scope("walk-2"), 4_000L)?.route?.providerRouteId)
    }

    @Test fun endWalkReadExceptionStillRetiresTheOldWalk() = checkUnexpectedEndFailure("read")

    @Test fun endWalkClearExceptionStillRetiresTheOldWalk() = checkUnexpectedEndFailure("clear")

    @Test fun endWalkReadbackExceptionStillRetiresTheOldWalk() = checkUnexpectedEndFailure("readback")

    private fun checkUnexpectedEndFailure(stage: String) {
        val storage = EndedWalkSnapshotStorage()
        val store = newStore(storage, EndedWalkKeyProvider(), RouteSnapshotProcessCoordinator())
        assertTrue(store.beginWalk(scope("walk-1")))
        assertTrue(store.saveFirstRoute(scope("walk-1"), route("first"), destination, 1_000L))
        when (stage) {
            "read" -> storage.readFailure = IllegalStateException("read unavailable")
            "clear" -> storage.clearFailure = IllegalStateException("clear unavailable")
            "readback" -> storage.failReadAfterClear = true
        }
        assertFalse(store.endWalk())
        storage.readFailure = null
        storage.clearFailure = null
        storage.failReadAfterClear = false
        // A late result from the ended walk must not recreate or read its archive.
        assertFalse(store.saveFirstRoute(scope("walk-1"), route("late"), destination, 2_000L))
        assertTrue(store.load(scope("walk-1"), 2_000L) == null)
        assertFalse(store.beginWalk(scope("walk-1")))
        assertTrue(store.endWalk())
        assertTrue(storage.envelope == null)
        assertTrue(store.beginWalk(scope("walk-2")))
        assertTrue(store.saveFirstRoute(scope("walk-2"), route("second"), destination, 3_000L))
        assertEquals("second", store.load(scope("walk-2"), 4_000L)?.route?.providerRouteId)
    }

    private fun newStore(storage: EndedWalkSnapshotStorage, keys: EndedWalkKeyProvider,
        process: RouteSnapshotProcessCoordinator) = EncryptedRouteSnapshotStore(
        storage = storage,
        aead = VersionedLocalAead(AeadKeyPolicy("walksafe.test.route_snapshot_ended_walk.aead.v", 1, setOf(1)), keys),
        testOnly = Unit,
        processCoordinator = process,
    ).also { check(it.bindAccount(owner)) }

    private fun route(providerRouteId: String) = WalkingRoute(
        priority = "STAIR_AVOID",
        summary = WalkingRouteSummary(distanceM = 111, durationS = 90),
        polyline = listOf(RoutePoint(37.0, 127.0), destination),
        guidePoints = listOf(
            WalkingRouteGuidePoint(0, RoutePoint(37.0005, 127.0), "직진", 56, 55, bearingDeg = 0f, turnType = 11),
        ),
        providerRouteId = providerRouteId,
    )

    private val destination = RoutePoint(37.001, 127.0, "목적지")
}

private class EndedWalkSnapshotStorage : RouteSnapshotStorage {
    var envelope: String? = null
    var clearSucceeds = true
    var readFailure: Exception? = null
    var clearFailure: Exception? = null
    var failReadAfterClear = false

    override fun read(): String? { readFailure?.let { throw it }; return envelope }

    override fun write(envelope: String): Boolean {
        this.envelope = envelope
        return true
    }

    override fun clear(): Boolean {
        clearFailure?.let { throw it }
        if (clearSucceeds) envelope = null
        if (failReadAfterClear) readFailure = IllegalStateException("readback unavailable")
        return clearSucceeds
    }
}

private class EndedWalkKeyProvider : LocalAeadKeyProvider {
    private val keys = mutableMapOf<String, SecretKey>()

    override fun getOrCreate(alias: String): SecretKey = keys.getOrPut(alias, ::newKey)

    override fun getExisting(alias: String): SecretKey? = keys[alias]

    override fun delete(alias: String): Boolean {
        keys.remove(alias)
        return true
    }

    override fun createFreshAfterVerifiedPurge(alias: String): SecretKey = newKey().also { keys[alias] = it }

    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
}
