package kr.co.hanium.dreamup.walksafe.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayPersistedLoginStateTest {
    @Test
    fun sameSourceCanBeReservedOnceAndStaleSourceCannotOverwriteRenewedState() {
        val source = bundle(rotation = 0L)
        val active = committedState(
            GatewayPersistedLoginStatePolicy.saveInitialIfAbsent(
                current = null,
                bundle = source,
            ),
        )

        val reserved = GatewayPersistedLoginStatePolicy.reserveRenewal(
            current = active,
            expectedVersion = source.session.version(),
            operationId = OPERATION_ONE,
        )
        assertEquals(GatewaySessionStoreResult.COMMITTED, reserved.result)
        val renewing = reserved.nextState
        assertTrue(renewing is GatewayPersistedLoginState.Renewing)

        val competing = GatewayPersistedLoginStatePolicy.reserveRenewal(
            current = renewing,
            expectedVersion = source.session.version(),
            operationId = OPERATION_TWO,
        )
        assertEquals(GatewaySessionStoreResult.BLOCKED, competing.result)
        assertSame(renewing, competing.nextState)

        val renewed = bundle(rotation = 1L)
        val committed = GatewayPersistedLoginStatePolicy.commitRenewal(
            current = renewing,
            expectedVersion = source.session.version(),
            operationId = OPERATION_ONE,
            renewedBundle = renewed,
        )
        assertEquals(GatewaySessionStoreResult.COMMITTED, committed.result)
        val renewedActive = committed.nextState

        val staleReserve = GatewayPersistedLoginStatePolicy.reserveRenewal(
            current = renewedActive,
            expectedVersion = source.session.version(),
            operationId = OPERATION_TWO,
        )
        assertEquals(GatewaySessionStoreResult.STALE, staleReserve.result)
        assertSame(renewedActive, staleReserve.nextState)
        assertEquals(
            1L,
            (renewedActive as GatewayPersistedLoginState.Active)
                .bundle.session.rotation,
        )
    }

    @Test
    fun lateCommitCannotReplaceASecondSuccessfulRotation() {
        val rotationZero = bundle(rotation = 0L)
        val firstClaim = committedState(
            GatewayPersistedLoginStatePolicy.reserveRenewal(
                current = GatewayPersistedLoginState.Active(rotationZero),
                expectedVersion = rotationZero.session.version(),
                operationId = OPERATION_ONE,
            ),
        )
        val rotationOne = bundle(rotation = 1L)
        val firstCommit = committedState(
            GatewayPersistedLoginStatePolicy.commitRenewal(
                current = firstClaim,
                expectedVersion = rotationZero.session.version(),
                operationId = OPERATION_ONE,
                renewedBundle = rotationOne,
            ),
        )
        val secondClaim = committedState(
            GatewayPersistedLoginStatePolicy.reserveRenewal(
                current = firstCommit,
                expectedVersion = rotationOne.session.version(),
                operationId = OPERATION_TWO,
            ),
        )
        val rotationTwo = bundle(rotation = 2L)
        val secondCommit = committedState(
            GatewayPersistedLoginStatePolicy.commitRenewal(
                current = secondClaim,
                expectedVersion = rotationOne.session.version(),
                operationId = OPERATION_TWO,
                renewedBundle = rotationTwo,
            ),
        )

        val lateFirstCommit = GatewayPersistedLoginStatePolicy.commitRenewal(
            current = secondCommit,
            expectedVersion = rotationZero.session.version(),
            operationId = OPERATION_ONE,
            renewedBundle = rotationOne,
        )

        assertEquals(GatewaySessionStoreResult.STALE, lateFirstCommit.result)
        assertSame(secondCommit, lateFirstCommit.nextState)
        assertEquals(
            2L,
            (secondCommit as GatewayPersistedLoginState.Active)
                .bundle.session.rotation,
        )
    }

    @Test
    fun renewalCannotExtendTheAbsoluteExpiry() {
        val source = bundle(rotation = 0L)
        val renewing = committedState(
            GatewayPersistedLoginStatePolicy.reserveRenewal(
                current = GatewayPersistedLoginState.Active(source),
                expectedVersion = source.session.version(),
                operationId = OPERATION_ONE,
            ),
        )
        val extended = bundle(
            rotation = 1L,
            absoluteExpiresAtEpochMs = 4_001L,
        )

        val result = GatewayPersistedLoginStatePolicy.commitRenewal(
            current = renewing,
            expectedVersion = source.session.version(),
            operationId = OPERATION_ONE,
            renewedBundle = extended,
        )

        assertEquals(GatewaySessionStoreResult.BLOCKED, result.result)
        assertSame(renewing, result.nextState)
    }

    @Test
    fun abandonedOrRecoveredRenewalBecomesRedactedPendingRevocation() {
        val source = bundle(rotation = 4L)
        val renewing = GatewayPersistedLoginState.Renewing(
            operationId = OPERATION_ONE,
            bundle = source,
        )

        val abandoned = GatewayPersistedLoginStatePolicy.abandonRenewal(
            current = renewing,
            expectedVersion = source.session.version(),
            operationId = OPERATION_ONE,
        )
        val pending = committedState(abandoned)
            as GatewayPersistedLoginState.PendingRevocation
        assertEquals(source.session.version(), pending.pending.version)
        assertEquals(source.session.refreshToken, pending.pending.refreshToken)
        assertFalse(pending.pending.toString().contains(source.session.refreshToken))
        assertFalse(pending.pending.toString().contains(source.session.accessCookiePair))
        assertTrue(pending.pending.toString().contains("refreshToken=redacted"))

        val recovered = GatewayPersistedLoginStatePolicy
            .recoverRenewingToPendingRevocation(renewing)
        assertEquals(GatewaySessionStoreResult.COMMITTED, recovered.result)
        assertEquals(
            pending.pending,
            (recovered.nextState as GatewayPersistedLoginState.PendingRevocation).pending,
        )
    }

    @Test
    fun pendingRevocationIsCompletedOnlyByItsExactVersionAndOperation() {
        val source = bundle(rotation = 7L)
        val pending = committedState(
            GatewayPersistedLoginStatePolicy.moveActiveToPendingRevocation(
                current = GatewayPersistedLoginState.Active(source),
                expectedVersion = source.session.version(),
                operationId = OPERATION_ONE,
            ),
        )

        val staleOperation = GatewayPersistedLoginStatePolicy.completePendingRevocation(
            current = pending,
            expectedVersion = source.session.version(),
            operationId = OPERATION_TWO,
        )
        assertEquals(GatewaySessionStoreResult.STALE, staleOperation.result)
        assertSame(pending, staleOperation.nextState)

        val staleVersion = GatewayPersistedLoginStatePolicy.completePendingRevocation(
            current = pending,
            expectedVersion = bundle(rotation = 8L).session.version(),
            operationId = OPERATION_ONE,
        )
        assertEquals(GatewaySessionStoreResult.STALE, staleVersion.result)
        assertSame(pending, staleVersion.nextState)

        val completed = GatewayPersistedLoginStatePolicy.completePendingRevocation(
            current = pending,
            expectedVersion = source.session.version(),
            operationId = OPERATION_ONE,
        )
        assertEquals(GatewaySessionStoreResult.COMMITTED, completed.result)
        assertNull(completed.nextState)
    }

    @Test
    fun staleLogoutCannotClearANewerActiveRotation() {
        val newer = bundle(rotation = 3L)
        val active = GatewayPersistedLoginState.Active(newer)

        val stale = GatewayPersistedLoginStatePolicy.moveActiveToPendingRevocation(
            current = active,
            expectedVersion = bundle(rotation = 2L).session.version(),
            operationId = OPERATION_ONE,
        )

        assertEquals(GatewaySessionStoreResult.STALE, stale.result)
        assertSame(active, stale.nextState)
    }

    private fun committedState(
        transition: GatewayPersistedStateTransition,
    ): GatewayPersistedLoginState {
        assertEquals(GatewaySessionStoreResult.COMMITTED, transition.result)
        return requireNotNull(transition.nextState)
    }

    private fun bundle(
        rotation: Long,
        absoluteExpiresAtEpochMs: Long = 4_000L,
    ): GatewayPersistedLoginBundle =
        GatewayPersistedLoginBundle(
            firstRunEpoch = 12L,
            orderedEvidence = emptyList(),
            session = GatewayFieldSessionPersistence(
                gatewayBaseUrl = GATEWAY_ORIGIN,
                actorId = ACTOR_ID,
                accessCookiePair =
                    "${GatewayFieldSession.COOKIE_NAME}=access-token-for-rotation-$rotation",
                refreshToken = refreshToken(rotation),
                deviceId = DEVICE_ID,
                familyId = FAMILY_ID,
                rotation = rotation,
                accessExpiresAtEpochMs = 2_000L + rotation,
                idleExpiresAtEpochMs = 3_000L + rotation,
                absoluteExpiresAtEpochMs = absoluteExpiresAtEpochMs,
            ),
        )

    private fun refreshToken(rotation: Long): String =
        ('a'.code + rotation.toInt()).toChar().toString().repeat(64)

    private companion object {
        const val GATEWAY_ORIGIN = "https://gateway.example.test"
        const val ACTOR_ID = "actor-test"
        const val DEVICE_ID = "device-installation-00000001"
        val FAMILY_ID = "f".repeat(32)
        const val OPERATION_ONE = "renew_operation_0001"
        const val OPERATION_TWO = "renew_operation_0002"
    }
}
