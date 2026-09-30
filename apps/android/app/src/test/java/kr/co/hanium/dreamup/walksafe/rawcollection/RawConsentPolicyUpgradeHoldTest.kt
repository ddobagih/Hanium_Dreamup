package kr.co.hanium.dreamup.walksafe.rawcollection

import kr.co.hanium.dreamup.walksafe.session.INTEGRATED_CONSENT_POLICY_VERSION
import kr.co.hanium.dreamup.walksafe.session.IntegratedConsentSession
import kr.co.hanium.dreamup.walksafe.session.WalkSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * transfer-F1 / Root decision R3: segments kept under an earlier consent policy version are held,
 * not sent, until the user consents again, and even then a continuity recorded before the 1.2.0
 * marker is not carried over (the server ends RAW continuity at any version change). Nothing is
 * deleted by the alignment; the ordinary cleanup and the 30-day expiry apply.
 */
class RawConsentPolicyUpgradeHoldTest {
    private val earlier = NP_CONSENT_SHA
    private val current = "d".repeat(64)

    /** A segment under [earlier] with its continuity into [current], recorded before the marker. */
    private fun fixtureWithEarlierPolicySegment(): NoProfileRawFixture {
        val fixture = NoProfileRawFixture()
        assertEquals(RawMetadataCaptureResult.CAPTURED, fixture.capture(1))
        fixture.endWalk()
        assertEquals(1, fixture.store.readyManifests(NP_OWNER).size)
        check(fixture.store.onConsentContinued(earlier, current))
        fixture.supersede(current, revision = 2L)
        // Model the previous build: its storage has the continuity but no 1.2.0 marker.
        fixture.storage.removeFenceForTest(
            requireNotNull(rawConsentPolicyFence(INTEGRATED_CONSENT_POLICY_VERSION)),
        )
        return fixture
    }

    @Test
    fun withoutAConfirmationTheEarlierSegmentIsHeldAndKept() {
        val fixture = fixtureWithEarlierPolicySegment()
        val noConsent = fixture.context(WalkSessionState.ENDED).copy(consentSession = IntegratedConsentSession())
        assertEquals(
            RawReadyStatus.GateBlocked(RawCollectionGateReason.CONSENT),
            fixture.coordinator.readyStatus(noConsent),
        )
        assertNull(fixture.coordinator.startNextUpload { noConsent })
        assertEquals(1, fixture.storage.collectionCount)
    }

    @Test
    fun afterReconsentAContinuityFromBeforeThePolicyMarkerIsNotSentButStaysUntilExpiry() {
        val fixture = fixtureWithEarlierPolicySegment()
        val ended = fixture.context(WalkSessionState.ENDED)
        assertEquals(RawReadyStatus.Count(0), fixture.coordinator.readyStatus(ended))
        assertNull(fixture.coordinator.startNextUpload { ended })
        assertTrue(fixture.store.continuedReceipts().isEmpty())
        // Held: the alignment deletes nothing.
        assertEquals(1, fixture.storage.collectionCount)
        fixture.nowEpochMs += RAW_COLLECTION_TTL_MS + 60_000L
        assertEquals(1, fixture.store.pruneExpired())
        assertEquals(0, fixture.storage.collectionCount)
    }

    @Test
    fun aContinuityRecordedUnderTheCurrentPolicyStaysSelectable() {
        val fixture = NoProfileRawFixture()
        assertEquals(RawMetadataCaptureResult.CAPTURED, fixture.capture(1))
        fixture.endWalk()
        assertTrue(fixture.coordinator.onConsentContinued(earlier, current))
        fixture.supersede(current, revision = 2L)
        assertEquals(setOf(earlier), fixture.store.continuedReceipts())
        assertEquals(RawReadyStatus.Count(1), fixture.coordinator.readyStatus(fixture.context(WalkSessionState.ENDED)))
        assertTrue(fixture.store.alignConsentPolicy(INTEGRATED_CONSENT_POLICY_VERSION))
        assertEquals(setOf(earlier), fixture.store.continuedReceipts())
    }

    @Test
    fun thePolicyMarkerIsABoundedFenceName() {
        assertEquals("consent-policy-fp-013-1-2-0", rawConsentPolicyFence("FP-013-1.2.0"))
        assertNull(rawConsentPolicyFence(""))
    }
}
