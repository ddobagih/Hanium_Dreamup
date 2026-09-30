package kr.co.hanium.dreamup.walksafe.rawcollection

import kr.co.hanium.dreamup.walksafe.security.AeadLimits
import kr.co.hanium.dreamup.walksafe.security.AeadOpenResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * transfer-F8 (w24 review): a segment whose walk the Gateway will never accept (409
 * raw_collection_walk_not_uploadable) is marked WALK_REJECTED, apart from a refused consent
 * binding (CONSENT_REJECTED); both are never sent again and are deleted.
 */
class RawWalkRejectedDispositionTest {
    @Test
    fun theWalkRejectedMarkerRoundTripsAndIsNeverUploadReady() {
        val rig = PostWalkRawRig()
        assertEquals(RawMetadataCaptureResult.CAPTURED, rig.capture(NP_WALK_1))
        rig.coordinator.onWalkEnded(rig.after(NP_WALK_1))
        val sealed = rig.store.readyManifests(NP_OWNER).single()
        val rejected = requireNotNull(
            decodeRawManifest(encodeRawManifest(sealed.copy(uploadDisposition = RawUploadDisposition.WALK_REJECTED))),
        )
        assertEquals(RawUploadDisposition.WALK_REJECTED, rejected.uploadDisposition)
        assertFalse(rejected.uploadReady)
        assertNotEquals(RawUploadDisposition.CONSENT_REJECTED, rejected.uploadDisposition)
    }

    @Test
    fun aRefusedWalkKeepsItsOwnMarkerUntilTheNextCleanupDeletesIt() {
        val rig = PostWalkRawRig()
        assertEquals(RawMetadataCaptureResult.CAPTURED, rig.capture(NP_WALK_1))
        rig.coordinator.onWalkEnded(rig.after(NP_WALK_1))
        val collectionId = rig.store.readyManifests(NP_OWNER).single().collectionId
        rig.storage.failCollectionDeletes = true
        rig.client.failure = RawCollectionHttpException(
            409,
            """{"detail":{"code":"raw_collection_walk_not_uploadable","message":"no"}}""",
        )
        assertEquals(
            RawCollectionUploadOutcome.WALK_REJECTED,
            requireNotNull(rig.coordinator.startNextUpload { rig.after(NP_WALK_1) }).execute(),
        )
        assertEquals(1, rig.storage.collectionCount)
        assertTrue(rig.store.readyManifests(NP_OWNER).isEmpty())
        val envelope = requireNotNull(rig.storage.readManifest(collectionId))
        val opened = rig.aead.open(envelope, ByteArray(0), AeadLimits(1 shl 20, (1 shl 20) + 16, 2 shl 20))
            as AeadOpenResult.Opened
        assertEquals(
            RawUploadDisposition.WALK_REJECTED,
            requireNotNull(decodeRawManifest(opened.plaintext)).uploadDisposition,
        )
        rig.storage.failCollectionDeletes = false
        rig.client.failure = null
        assertEquals(RawMetadataCaptureResult.CAPTURED, rig.capture(NP_WALK_2))
        assertEquals(null, rig.storage.readManifest(collectionId))
    }
}
