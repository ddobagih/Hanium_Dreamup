package kr.co.hanium.dreamup.walksafe.navigation

import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kr.co.hanium.dreamup.walksafe.security.AeadKeyPolicy
import kr.co.hanium.dreamup.walksafe.security.AeadLimits
import kr.co.hanium.dreamup.walksafe.security.AeadOpenResult
import kr.co.hanium.dreamup.walksafe.security.AeadSealResult
import kr.co.hanium.dreamup.walksafe.security.LocalAeadKeyProvider
import kr.co.hanium.dreamup.walksafe.security.VersionedLocalAead
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * S07-41 (FP-023 '세션 종료와 24시간 중 먼저', FP-001 '재부팅 뒤 자동 재개 없음'): a wall clock set back
 * cannot extend the snapshot; within one boot the limit runs on the monotonic clock.
 */
class EncryptedRouteSnapshotClockTest {
    private val hour = 60L * 60L * 1_000L
    private var elapsedMs = 5 * hour
    private val storage = ClockTestSnapshotStorage()
    private val process = RouteSnapshotProcessCoordinator()
    private val owner = RouteSnapshotOwner("actor-a", "account:1")
    private val scope = RouteSnapshotScope(owner, "walk-1")
    private val keys = ClockTestKeyProvider()
    private var bootCount = UNKNOWN_BOOT_COUNT
    private fun store() = EncryptedRouteSnapshotStore(
        storage,
        aead(),
        testOnly = Unit,
        elapsedRealtimeMs = { elapsedMs },
        bootCount = { bootCount },
        processCoordinator = process,
    ).also { check(it.bindAccount(owner)); check(it.beginWalk(scope)) }
    private val wallAtSave = 1_000_000_000_000L

    @Test fun wallClockSetBackCannotKeepASnapshotPast24MonotonicHours() {
        assertNotNull(saveAndLoad())
        // 24 real hours later the user (or the network) set the wall clock back to the save time.
        elapsedMs += ROUTE_SNAPSHOT_TTL_MS
        assertNull(store().load(scope, nowEpochMs = wallAtSave + 1_000L))
        assertNull(storage.envelope)
    }

    @Test fun aSmallBackwardStepRightAfterSavingIsNotAFailure() {
        assertNotNull(saveAndLoad())
        elapsedMs += 2_000L
        val restored = store().load(scope, nowEpochMs = wallAtSave - 5_000L)
        assertEquals("walk-1", restored?.walkSessionId)
        assertNotNull(storage.envelope)
    }

    @Test fun aLargeWallClockChangeInTheSameBootEndsTheSnapshot() {
        assertNotNull(saveAndLoad())
        elapsedMs += 10L * 60L * 1_000L
        assertNull(store().load(scope, nowEpochMs = wallAtSave - hour))
        assertNull(storage.envelope)
    }

    @Test fun aRebootEndsTheSnapshotEvenWithAPlausibleWallClock() {
        assertNotNull(saveAndLoad())
        elapsedMs = 60_000L
        assertNull(store().load(scope, nowEpochMs = wallAtSave + hour))
        assertNull(storage.envelope)
    }

    @Test fun ordinaryUseWithinOneBootStillRestoresAndExpiresAtTheWallLimit() {
        assertNotNull(saveAndLoad())
        elapsedMs += 3 * hour
        assertEquals("walk-1", store().load(scope, nowEpochMs = wallAtSave + 3 * hour)?.walkSessionId)
        elapsedMs = 5 * hour + ROUTE_SNAPSHOT_TTL_MS - 1L
        assertNotNull(store().load(scope, nowEpochMs = wallAtSave + ROUTE_SNAPSHOT_TTL_MS - 1L))
        elapsedMs += 1L
        assertNull(store().load(scope, nowEpochMs = wallAtSave + ROUTE_SNAPSHOT_TTL_MS))
    }

    @Test fun aV1PayloadWithoutTheMonotonicSaveTimeIsDeleted() {
        assertNotNull(saveAndLoad())
        val payload = open()
        payload.put("schema_version", "walksafe.encrypted-route-snapshot.v1")
        payload.remove("saved_at_elapsed_realtime_ms")
        seal(payload)
        assertNull(store().load(scope, nowEpochMs = wallAtSave + 1_000L))
        assertNull(storage.envelope)
    }

    @Test fun theMonotonicSaveTimeIsStoredInsideTheCiphertext() {
        assertNotNull(saveAndLoad())
        assertEquals(5 * hour, open().getLong("saved_at_elapsed_realtime_ms"))
        assertEquals(5 * hour, store().load(scope, nowEpochMs = wallAtSave)?.savedAtElapsedRealtimeMs)
    }

    /**
     * S07-41 review: saved 2 minutes after boot, rebooted 10 s later, loaded 2.5 minutes into the new
     * boot. Elapsed time is ahead of the save and the boot reference moved only 130 s, so the
     * monotonic heuristic alone keeps it; the boot count ends it.
     */
    @Test fun aQuickRebootAfterAnEarlySaveIsCaughtByTheBootCount() {
        bootCount = 7
        elapsedMs = 2L * 60L * 1_000L
        assertNotNull(saveAndLoad())
        assertEquals(7, open().getInt("saved_boot_count"))
        bootCount = 8
        elapsedMs = 150_000L
        assertNull(store().load(scope, nowEpochMs = wallAtSave + 10_000L + 150_000L))
        assertNull(storage.envelope)
    }

    @Test fun theSameBootCountStillAppliesTheMonotonicLimits() {
        bootCount = 7
        assertNotNull(saveAndLoad())
        elapsedMs += 3 * hour
        assertEquals("walk-1", store().load(scope, nowEpochMs = wallAtSave + 3 * hour)?.walkSessionId)
        elapsedMs += ROUTE_SNAPSHOT_TTL_MS
        assertNull(store().load(scope, nowEpochMs = wallAtSave + 3 * hour + 1_000L))
        assertNull(storage.envelope)
    }

    @Test fun anUnknownBootCountOnEitherSideKeepsTheElapsedFallback() {
        // Unknown at save: a later known count is not a mismatch.
        bootCount = UNKNOWN_BOOT_COUNT
        assertNotNull(saveAndLoad())
        assertEquals(UNKNOWN_BOOT_COUNT, open().getInt("saved_boot_count"))
        bootCount = 9
        elapsedMs += 2_000L
        assertEquals("walk-1", store().load(scope, nowEpochMs = wallAtSave + 2_000L)?.walkSessionId)
        // Unknown now (the setting became unreadable): the elapsed heuristic still ends a reboot.
        bootCount = UNKNOWN_BOOT_COUNT
        elapsedMs = 60_000L
        assertNull(store().load(scope, nowEpochMs = wallAtSave + hour))
        assertNull(storage.envelope)
    }

    @Test fun aThrowingBootCountReaderIsTreatedAsUnknown() {
        var throwing = false
        val reader: () -> Int = { if (throwing) throw SecurityException("blocked") else 4 }
        val guarded = EncryptedRouteSnapshotStore(
            storage, aead(), testOnly = Unit, elapsedRealtimeMs = { elapsedMs }, bootCount = reader,
            processCoordinator = process,
        ).also { check(it.bindAccount(owner)); check(it.beginWalk(scope)) }
        check(guarded.saveFirstRoute(scope, route(), destination, nowEpochMs = wallAtSave))
        throwing = true
        elapsedMs += 1_000L
        assertEquals("walk-1", guarded.load(scope, nowEpochMs = wallAtSave + 1_000L)?.walkSessionId)
    }

    @Test fun aV2PayloadWithoutTheBootCountIsDeleted() {
        assertNotNull(saveAndLoad())
        val payload = open()
        payload.put("schema_version", "walksafe.encrypted-route-snapshot.v2")
        payload.remove("saved_boot_count")
        seal(payload)
        assertNull(store().load(scope, nowEpochMs = wallAtSave + 1_000L))
        assertNull(storage.envelope)
    }

    private fun saveAndLoad(): StoredRouteSnapshot? {
        check(store().saveFirstRoute(scope, route(), destination, nowEpochMs = wallAtSave))
        return store().load(scope, nowEpochMs = wallAtSave)
    }

    private fun aead() = VersionedLocalAead(
        AeadKeyPolicy("walksafe.test.route_snapshot_clock.aead.v", currentVersion = 1, readableVersions = setOf(1)),
        keys,
    )

    private fun open(): JSONObject {
        val result = aead().open(requireNotNull(storage.envelope), aad, limits)
        return JSONObject(String((result as AeadOpenResult.Opened).plaintext, Charsets.UTF_8))
    }

    private fun seal(payload: JSONObject) {
        val result = aead().seal(payload.toString().toByteArray(Charsets.UTF_8), aad, limits)
        storage.envelope = (result as AeadSealResult.Sealed).envelope
    }

    private val aad = "kr.co.hanium.dreamup.walksafe|USER|navigation|route-snapshot|schema=1".toByteArray(Charsets.UTF_8)
    private val limits = AeadLimits(2 * 1_024 * 1_024, 2 * 1_024 * 1_024 + 16, 3 * 1_024 * 1_024)
    private val destination = RoutePoint(37.001, 127.0, "목적지")

    private fun route() = WalkingRoute(
        priority = "STAIR_AVOID",
        summary = WalkingRouteSummary(distanceM = 111, durationS = 90),
        polyline = listOf(RoutePoint(37.0, 127.0), destination),
        guidePoints = listOf(
            WalkingRouteGuidePoint(0, RoutePoint(37.0005, 127.0), "직진", 56, 55, 0f, 11, "guide", 15),
        ),
        steps = listOf(
            WalkingRouteStep(0, 111, 90, listOf(RoutePoint(37.0, 127.0), destination), "직진", "테스트길", 11, 15),
        ),
        providerRouteId = "clock-route",
    )

    private class ClockTestSnapshotStorage : RouteSnapshotStorage {
        var envelope: String? = null
        override fun read(): String? = envelope
        override fun write(envelope: String): Boolean {
            this.envelope = envelope
            return true
        }
        override fun clear(): Boolean {
            envelope = null
            return true
        }
    }

    private class ClockTestKeyProvider : LocalAeadKeyProvider {
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
}
