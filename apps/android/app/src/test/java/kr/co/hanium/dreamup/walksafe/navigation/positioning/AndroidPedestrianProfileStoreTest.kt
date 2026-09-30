package kr.co.hanium.dreamup.walksafe.navigation.positioning

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidPedestrianProfileStoreTest {
    @Test
    fun savesAndLoadsProfileUsingOnlyHashedActorKey() {
        val storage = FakePedestrianProfileStorage()
        val store = newStore(storage)
        val profile = profile(stepLengthM = 0.62, acceptedSampleCount = 12L)

        assertTrue(store.save("abc", profile))

        val expectedKey =
            "pedestrian_motion_profile_v1_" +
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        assertEquals(setOf(expectedKey), storage.values.keys)
        assertFalse(storage.values.getValue(expectedKey).contains("abc"))
        assertEquals(profile, store.load("abc"))
        assertNull(store.load("different-actor"))
    }

    @Test
    fun deleteRemovesOnlyTheRequestedActorProfile() {
        val storage = FakePedestrianProfileStorage()
        val store = newStore(storage)
        val first = profile(stepLengthM = 0.61, acceptedSampleCount = 8L)
        val second = profile(stepLengthM = 0.72, acceptedSampleCount = 9L)
        assertTrue(store.save("actor-one", first))
        assertTrue(store.save("actor-two", second))

        assertTrue(store.delete("actor-one"))

        assertNull(store.load("actor-one"))
        assertEquals(second, store.load("actor-two"))
        assertEquals(1, storage.values.size)
    }

    @Test
    fun rejectsBlankActorAndInvalidSnapshotWithoutWriting() {
        val storage = FakePedestrianProfileStorage()
        val store = newStore(storage)
        val invalid = profile(stepLengthM = Double.NaN, acceptedSampleCount = 12L)

        assertFalse(store.save(" ", profile()))
        assertFalse(store.save("actor", invalid))
        assertNull(store.load(" "))
        assertFalse(store.delete(" "))
        assertTrue(storage.values.isEmpty())
    }

    @Test
    fun invalidDecryptedRecordIsRemovedAloneAndPersonalizationRecovers() {
        // S09-56: a record that decrypted but fails validation no longer blocks every actor.
        val storage = FakePedestrianProfileStorage()
        val store = newStore(storage)
        assertTrue(store.save("actor", profile()))
        assertTrue(store.save("other", profile(stepLengthM = 0.7)))
        val key = storage.values.keys.first { store.keyFor("actor") == it }
        storage.values[key] =
            storage.values.getValue(key).replace("\"accepted_sample_count\":6", "\"accepted_sample_count\":1")

        val result = store.loadResult("actor")

        assertEquals(PedestrianProfileLoadStatus.RESET_INVALID_RECORD, result.status)
        assertNull(result.profile)
        assertFalse(storage.blocked)
        assertFalse(key in storage.values)
        assertEquals(profile(stepLengthM = 0.7), store.load("other"))
        assertTrue(store.save("actor", profile(stepLengthM = 0.66)))
        assertEquals(profile(stepLengthM = 0.66), store.load("actor"))
    }

    @Test
    fun unsupportedSchemaOrModelIsResetInsteadOfRestored() {
        val storage = FakePedestrianProfileStorage()
        val store = newStore(storage)
        assertTrue(store.save("actor", profile()))
        val key = storage.values.keys.single()
        val saved = storage.values.getValue(key)

        storage.values[key] = JSONObject(saved).put("model_version", "walksafe.pedestrian-motion-profile-model.0").toString()
        assertEquals(PedestrianProfileLoadStatus.RESET_INVALID_RECORD, store.loadResult("actor").status)

        storage.values[key] = JSONObject(saved).put("schema_version", "walksafe.pedestrian-motion-profile.v9").toString()
        assertEquals(PedestrianProfileLoadStatus.RESET_INVALID_RECORD, store.loadResult("actor").status)
        assertFalse(storage.blocked)
    }

    @Test
    fun removalFailureOfAnInvalidRecordStillFailsClosed() {
        val storage = FakePedestrianProfileStorage()
        val store = newStore(storage)
        assertTrue(store.save("actor", profile()))
        val key = storage.values.keys.single()
        storage.values[key] = "{\"schema_version\":1}"
        storage.failRemovals = true

        assertEquals(PedestrianProfileLoadStatus.STORE_BLOCKED, store.loadResult("actor").status)
        assertTrue(storage.blocked)
    }

    @Test
    fun blockedStorageFailsClosedForEveryOperation() {
        val storage = FakePedestrianProfileStorage(blocked = true)
        val store = newStore(storage)

        assertNull(store.load("actor"))
        assertEquals(PedestrianProfileLoadStatus.STORE_BLOCKED, store.loadResult("actor").status)
        assertFalse(store.save("actor", profile()))
        assertFalse(store.delete("actor"))
        assertTrue(storage.values.isEmpty())
    }

    @Test
    fun missingProfileAndBlockedStoreAreDistinguishable() {
        val empty = newStore(FakePedestrianProfileStorage())
        val blocked = newStore(FakePedestrianProfileStorage(blocked = true))

        assertEquals(PedestrianProfileLoadStatus.NO_PROFILE, empty.loadResult("actor").status)
        assertEquals(PedestrianProfileLoadStatus.STORE_BLOCKED, blocked.loadResult("actor").status)
    }

    @Test
    fun explicitResetClearsABlockedStoreAndAllowsNewCalibration() {
        val storage = FakePedestrianProfileStorage()
        val store = newStore(storage)
        assertTrue(store.save("actor", profile()))
        storage.blocked = true
        assertFalse(store.save("actor", profile(stepLengthM = 0.7)))

        assertTrue(store.resetAllProfiles())

        assertFalse(storage.blocked)
        assertTrue(storage.values.isEmpty())
        assertEquals(PedestrianProfileLoadStatus.NO_PROFILE, store.loadResult("actor").status)
        assertTrue(store.save("actor", profile(stepLengthM = 0.7)))
    }

    @Test
    fun savedRecordCarriesCalibrationTimeAndModelVersion() {
        // S09-43: last calibration time and profile model version are stored with the values.
        val storage = FakePedestrianProfileStorage()
        val store = newStore(storage, nowEpochMs = 1_700_000_000_000L)
        assertTrue(store.save("actor", profile()))

        val stored = JSONObject(storage.values.values.single())
        val loaded = store.loadResult("actor")

        // S09-43: v3 adds the calibration usage condition (enum names) to v2.
        assertEquals("walksafe.pedestrian-motion-profile.v3", stored.getString("schema_version"))
        assertEquals("walksafe.pedestrian-motion-profile-model.1", stored.getString("model_version"))
        assertEquals(1_700_000_000_000L, stored.getLong("last_calibrated_at_epoch_ms"))
        assertEquals(PedestrianProfileLoadStatus.LOADED, loaded.status)
        assertEquals(1_700_000_000_000L, loaded.lastCalibratedAtEpochMs)
        assertTrue(requireNotNull(loaded.profile).calibrationConditionKnown)
    }

    @Test
    fun savingAnUnknownConditionProfileDoesNotInventACalibrationTime() {
        // S09-40/S09-43 review: a restored v1 profile saved again (e.g. after a warm-up sample that
        // did not recalibrate) must stay "unknown" instead of gaining the save time as calibration time.
        val storage = FakePedestrianProfileStorage()
        val store = newStore(storage, nowEpochMs = 1_800_000_000_000L)

        assertTrue(store.save("actor", profile().copy(calibrationConditionKnown = false)))

        val stored = JSONObject(storage.values.values.single())
        val reloaded = store.loadResult("actor")
        assertTrue(stored.isNull("last_calibrated_at_epoch_ms"))
        assertEquals(PedestrianProfileLoadStatus.LOADED, reloaded.status)
        assertNull(reloaded.lastCalibratedAtEpochMs)
        assertFalse(requireNotNull(reloaded.profile).calibrationConditionKnown)
        assertEquals(profile().copy(calibrationConditionKnown = false), reloaded.profile)
    }

    @Test
    fun v1RecordMigratesWithUnknownCalibrationTime() {
        val storage = FakePedestrianProfileStorage()
        val store = newStore(storage)
        val key = store.keyFor("actor")
        storage.values[key] = JSONObject()
            .put("schema_version", "walksafe.pedestrian-motion-profile.v1")
            .put("step_length_m", 0.64)
            .put("mean_walking_speed_mps", 1.1)
            .put("speed_variance_mps2", 0.08)
            .put("accepted_sample_count", 6)
            .toString()

        val migrated = store.loadResult("actor")
        val reloaded = store.loadResult("actor")

        assertEquals(PedestrianProfileLoadStatus.MIGRATED, migrated.status)
        assertEquals(profile().copy(calibrationConditionKnown = false), migrated.profile)
        assertNull(migrated.lastCalibratedAtEpochMs)
        // S09-43: migrations write the current schema (v3; usage condition unknown).
        assertEquals("walksafe.pedestrian-motion-profile.v3", JSONObject(storage.values.getValue(key)).getString("schema_version"))
        assertTrue(JSONObject(storage.values.getValue(key)).isNull("last_calibrated_at_epoch_ms"))
        assertEquals(PedestrianProfileLoadStatus.LOADED, reloaded.status)
        assertFalse(requireNotNull(reloaded.profile).calibrationConditionKnown)
        assertFalse(storage.blocked)
    }

    private fun newStore(storage: FakePedestrianProfileStorage, nowEpochMs: Long = 1_000L) =
        AndroidPedestrianProfileStore(storage, Unit) { nowEpochMs }

    private fun AndroidPedestrianProfileStore.keyFor(actorId: String): String {
        val probe = FakePedestrianProfileStorage()
        AndroidPedestrianProfileStore(probe, Unit) { 0L }.save(actorId, profile())
        return probe.values.keys.single()
    }

    private fun profile(
        stepLengthM: Double = 0.64,
        acceptedSampleCount: Long = 6L,
    ) = PersistedPedestrianMotionProfile(
        stepLengthM = stepLengthM,
        meanWalkingSpeedMps = 1.1,
        speedVarianceMps2 = 0.08,
        acceptedSampleCount = acceptedSampleCount,
    )
}

private class FakePedestrianProfileStorage(
    var blocked: Boolean = false,
) : PedestrianProfileStorage {
    val values = mutableMapOf<String, String>()
    var failRemovals = false

    override fun isBlocked(): Boolean = blocked

    override fun contains(key: String): Boolean = !blocked && key in values

    override fun getString(key: String): String? = if (blocked) null else values[key]

    override fun putString(key: String, value: String): Boolean {
        if (blocked) return false
        values[key] = value
        return true
    }

    override fun remove(key: String): Boolean {
        if (blocked || failRemovals) return false
        values.remove(key)
        return true
    }

    override fun failClosed() {
        blocked = true
    }

    override fun resetAll(): Boolean {
        values.clear()
        blocked = false
        return true
    }
}
