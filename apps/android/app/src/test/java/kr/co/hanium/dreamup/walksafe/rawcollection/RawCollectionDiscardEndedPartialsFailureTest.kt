package kr.co.hanium.dreamup.walksafe.rawcollection

import java.util.Base64
import java.util.UUID
import kr.co.hanium.dreamup.walksafe.device.PostLoginDeviceCheckSnapshot
import kr.co.hanium.dreamup.walksafe.device.PostLoginDeviceCheckState
import kr.co.hanium.dreamup.walksafe.network.BackendAccountDeviceCookieBinding
import kr.co.hanium.dreamup.walksafe.network.GatewayFieldSession
import kr.co.hanium.dreamup.walksafe.security.AeadBlockReason
import kr.co.hanium.dreamup.walksafe.security.AeadLimits
import kr.co.hanium.dreamup.walksafe.security.AeadOpenResult
import kr.co.hanium.dreamup.walksafe.security.AeadSealResult
import kr.co.hanium.dreamup.walksafe.security.LocalAead
import kr.co.hanium.dreamup.walksafe.session.IntegratedConsentSession
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import kr.co.hanium.dreamup.walksafe.session.WalkSessionMode
import kr.co.hanium.dreamup.walksafe.session.WalkSessionSnapshot
import kr.co.hanium.dreamup.walksafe.session.WalkSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RawCollectionDiscardEndedPartialsFailureTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun unreadableListingReturnsFailureRatherThanAnEmptySuccessfulResult() {
        val fixture = fixture()
        val context = context()

        assertEquals(0, fixture.coordinator.discardEndedPartials(context))
        fixture.storage.failListing = true
        assertEquals(-1, fixture.coordinator.discardEndedPartials(context))
        assertEquals(0, fixture.storage.deleteCalls)
    }

    @Test
    fun failedListingPreservesStoredCiphertextAndAllowsALaterExplicitCleanup() {
        val fixture = fixture()
        seedPartial(fixture, COLLECTION_ID, WALK_ID)
        val manifest = fixture.storage.readManifest(COLLECTION_ID)
        assertTrue(!manifest.isNullOrBlank())
        fixture.storage.failListing = true

        assertEquals(-1, fixture.coordinator.discardEndedPartials(context()))
        assertEquals(0, fixture.storage.deleteCalls)
        assertEquals(manifest, fixture.storage.readManifest(COLLECTION_ID))
        assertEquals(CIPHERTEXT, fixture.storage.readChunk(COLLECTION_ID, 0))

        fixture.storage.failListing = false
        assertEquals(1, fixture.coordinator.discardEndedPartials(context()))
        assertNull(fixture.storage.readManifest(COLLECTION_ID))
        assertNull(fixture.storage.readChunk(COLLECTION_ID, 0))
    }

    @Test
    fun healthyCleanupStillDiscardsOnlyTheEndedWalksPartials() {
        val fixture = fixture()
        seedPartial(fixture, COLLECTION_ID, WALK_ID)
        seedPartial(fixture, OTHER_COLLECTION_ID, OTHER_WALK_ID)

        assertEquals(1, fixture.coordinator.discardEndedPartials(context()))
        assertEquals(setOf(OTHER_COLLECTION_ID), fixture.storage.listCollectionIds())
        assertEquals(CIPHERTEXT, fixture.storage.readChunk(OTHER_COLLECTION_ID, 0))
    }

    @Test
    fun unfinishedWalkAndMissingOwnerKeepTheirExistingGates() {
        val fixture = fixture()
        fixture.storage.failListing = true

        assertEquals(0, fixture.coordinator.discardEndedPartials(context(WalkSessionState.READY)))
        assertEquals(0, fixture.coordinator.discardEndedPartials(context().copy(session = null)))
        assertEquals(0, fixture.storage.deleteCalls)
    }

    private fun fixture(): CleanupFixture {
        val storage = CleanupListingStorage(FileRawCollectionStorage(temporary.newFolder()))
        val store = RawCollectionStore(storage, CleanupAead(), { NOW }, testOnly = Unit)
        return CleanupFixture(
            storage = storage,
            store = store,
            coordinator = RawCollectionRuntimeCoordinator(
                store = store,
                client = AndroidRawCollectionClient(),
                idFactory = { UUID.fromString(COLLECTION_ID) },
                elapsedRealtimeMs = { NOW },
                minimumCaptureIntervalMs = 30_000L,
                testOnly = Unit,
            ),
        )
    }

    private fun seedPartial(fixture: CleanupFixture, collectionId: String, walkId: String) {
        assertTrue(fixture.store.open(
            collectionId, OWNER, walkId, "c".repeat(64),
            rawConsentGranted = true, walkState = RawWalkState.ACTIVE,
        ))
        assertTrue(fixture.storage.writeChunkAtomically(collectionId, 0, CIPHERTEXT))
        assertTrue(fixture.storage.clearActiveCollectionId())
    }

    private fun context(state: WalkSessionState = WalkSessionState.ENDED): RawCollectionRuntimeContext {
        val expiry = System.currentTimeMillis() + 3_600_000L
        val session = GatewayFieldSession.backendAccountDeviceSession(
            gatewayBaseUrl = "http://127.0.0.1:8081",
            binding = BackendAccountDeviceCookieBinding(
                actorId = ACTOR_ID, accountGeneration = 1L, authEpoch = 1L,
                deviceId = DEVICE_ID, sessionId = "s".repeat(32), expiresAtEpochMs = expiry,
            ),
            cookiePair = "${GatewayFieldSession.COOKIE_NAME}=cleanup-test-cookie",
            expiresAtEpochMs = expiry,
        )
        return RawCollectionRuntimeContext(
            session = session,
            sessionGeneration = 7L,
            deviceCheck = PostLoginDeviceCheckSnapshot(
                state = PostLoginDeviceCheckState.FULL,
                actorId = ACTOR_ID, sessionGeneration = 7L, attemptGeneration = 1L,
            ),
            consentSession = IntegratedConsentSession(),
            walk = WalkSessionSnapshot(
                epoch = WalkRuntimeEpoch(WALK_ID, 0L), state = state,
                mode = WalkSessionMode.UNAVAILABLE, recoveryStage = null,
                isForeground = true, readiness = null,
            ),
            gatewayWalkLease = null,
        )
    }

    private companion object {
        const val NOW = 10_000L
        const val COLLECTION_ID = "123e4567-e89b-42d3-a456-426614174000"
        const val OTHER_COLLECTION_ID = "123e4567-e89b-42d3-a456-426614174004"
        const val WALK_ID = "123e4567-e89b-42d3-a456-426614174001"
        const val OTHER_WALK_ID = "123e4567-e89b-42d3-a456-426614174005"
        const val ACTOR_ID = "123e4567-e89b-42d3-a456-426614174002"
        const val DEVICE_ID = "device-installation-00000001"
        const val CIPHERTEXT = "retained-encrypted-payload"
        val OWNER = RawCollectionOwner(ACTOR_ID, 1L, DEVICE_ID)
    }
}

private data class CleanupFixture(
    val storage: CleanupListingStorage,
    val store: RawCollectionStore,
    val coordinator: RawCollectionRuntimeCoordinator,
)

private class CleanupListingStorage(
    private val delegate: RawCollectionStorage,
) : RawCollectionStorage by delegate {
    var failListing = false
    var deleteCalls = 0

    override fun listCollectionIds(): Set<String> {
        if (failListing) throw RawCollectionStorageReadException("test listing")
        return delegate.listCollectionIds()
    }

    override fun deleteCollection(collectionId: String): Boolean {
        deleteCalls += 1
        return delegate.deleteCollection(collectionId)
    }
}

private class CleanupAead : LocalAead {
    override fun seal(plaintext: ByteArray, domainAad: ByteArray, limits: AeadLimits) =
        AeadSealResult.Sealed("aead:" + Base64.getEncoder().encodeToString(plaintext))

    override fun open(envelope: String, domainAad: ByteArray, limits: AeadLimits): AeadOpenResult =
        if (envelope.startsWith("aead:")) {
            AeadOpenResult.Opened(Base64.getDecoder().decode(envelope.removePrefix("aead:")), 1, false)
        } else {
            AeadOpenResult.Blocked(AeadBlockReason.MALFORMED_ENVELOPE)
        }

    override fun destroyVersion(version: Int) = true
    override fun destroyKnownVersions() = true
    override fun createFreshAfterVerifiedPurge() = true
}
