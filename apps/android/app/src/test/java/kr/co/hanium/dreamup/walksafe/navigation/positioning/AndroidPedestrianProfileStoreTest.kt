package kr.co.hanium.dreamup.walksafe.navigation.positioning

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
    fun corruptSnapshotFailsClosedAndBlocksFurtherMutation() {
        val storage = FakePedestrianProfileStorage()
        val store = newStore(storage)
        assertTrue(store.save("actor", profile()))
        val key = storage.values.keys.single()
        storage.values[key] =
            storage.values.getValue(key).replace("\"accepted_sample_count\":6", "\"accepted_sample_count\":1")

        assertNull(store.load("actor"))

        assertTrue(storage.blocked)
        assertFalse(store.save("another", profile()))
        assertFalse(store.delete("actor"))
    }

    @Test
    fun blockedStorageFailsClosedForEveryOperation() {
        val storage = FakePedestrianProfileStorage(blocked = true)
        val store = newStore(storage)

        assertNull(store.load("actor"))
        assertFalse(store.save("actor", profile()))
        assertFalse(store.delete("actor"))
        assertTrue(storage.values.isEmpty())
    }

    private fun newStore(storage: FakePedestrianProfileStorage) =
        AndroidPedestrianProfileStore(storage, Unit)

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

    override fun isBlocked(): Boolean = blocked

    override fun contains(key: String): Boolean = !blocked && key in values

    override fun getString(key: String): String? = if (blocked) null else values[key]

    override fun putString(key: String, value: String): Boolean {
        if (blocked) return false
        values[key] = value
        return true
    }

    override fun remove(key: String): Boolean {
        if (blocked) return false
        values.remove(key)
        return true
    }

    override fun failClosed() {
        blocked = true
    }
}
