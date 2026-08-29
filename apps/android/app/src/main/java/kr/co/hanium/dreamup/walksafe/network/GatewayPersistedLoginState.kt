package kr.co.hanium.dreamup.walksafe.network

import java.util.Collections
import kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingEvidence
import kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingFlow
import kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingSnapshot

internal enum class GatewaySessionStoreResult {
    COMMITTED,
    ALREADY_COMMITTED,
    NOT_FOUND,
    STALE,
    BLOCKED,
    STORAGE_FAILURE,
}

internal class GatewayPersistedLoginBundle(
    val firstRunEpoch: Long,
    orderedEvidence: List<FirstRunOnboardingEvidence>,
    val session: GatewayFieldSessionPersistence,
    val firstRunFlow: FirstRunOnboardingFlow = FirstRunOnboardingFlow.LEGACY_PHONE_V3,
) {
    val orderedEvidence: List<FirstRunOnboardingEvidence> =
        Collections.unmodifiableList(orderedEvidence.toList())
}

internal data class GatewayPendingRevocation(
    val operationId: String,
    val version: GatewaySessionVersion,
    val refreshToken: String,
) {
    override fun toString(): String =
        "GatewayPendingRevocation(operationId=$operationId, version=$version, refreshToken=redacted)"
}

internal data class RestoredGatewayLoginBundle(
    val firstRunSnapshot: FirstRunOnboardingSnapshot,
    val session: GatewayFieldSession,
    val version: GatewaySessionVersion,
)

internal sealed interface GatewayPersistedLoginState {
    data class Active(
        val bundle: GatewayPersistedLoginBundle,
    ) : GatewayPersistedLoginState

    data class Renewing(
        val operationId: String,
        val bundle: GatewayPersistedLoginBundle,
    ) : GatewayPersistedLoginState

    data class PendingRevocation(
        val pending: GatewayPendingRevocation,
    ) : GatewayPersistedLoginState
}

internal data class GatewayPersistedStateTransition(
    val result: GatewaySessionStoreResult,
    val nextState: GatewayPersistedLoginState?,
)

/**
 * Pure compare-and-set policy for the one persisted long-lived login.
 *
 * A caller must durably store a COMMITTED transition before making the corresponding
 * network mutation. STALE and BLOCKED transitions preserve the current state exactly.
 */
internal object GatewayPersistedLoginStatePolicy {
    private val operationIdPattern = Regex("^[A-Za-z0-9_-]{16,128}$")

    fun saveInitialIfAbsent(
        current: GatewayPersistedLoginState?,
        bundle: GatewayPersistedLoginBundle,
    ): GatewayPersistedStateTransition = when (current) {
        null -> committed(GatewayPersistedLoginState.Active(bundle))
        is GatewayPersistedLoginState.Active ->
            if (sameBundle(current.bundle, bundle)) {
                unchanged(GatewaySessionStoreResult.ALREADY_COMMITTED, current)
            } else {
                unchanged(GatewaySessionStoreResult.STALE, current)
            }
        is GatewayPersistedLoginState.Renewing,
        is GatewayPersistedLoginState.PendingRevocation,
        -> unchanged(GatewaySessionStoreResult.BLOCKED, current)
    }

    fun reserveRenewal(
        current: GatewayPersistedLoginState?,
        expectedVersion: GatewaySessionVersion,
        operationId: String,
    ): GatewayPersistedStateTransition {
        if (!operationIdPattern.matches(operationId)) {
            return unchanged(GatewaySessionStoreResult.BLOCKED, current)
        }
        return when (current) {
            null -> unchanged(GatewaySessionStoreResult.NOT_FOUND, null)
            is GatewayPersistedLoginState.Active ->
                if (current.bundle.session.version() == expectedVersion) {
                    committed(
                        GatewayPersistedLoginState.Renewing(
                            operationId = operationId,
                            bundle = current.bundle,
                        ),
                    )
                } else {
                    unchanged(GatewaySessionStoreResult.STALE, current)
                }
            is GatewayPersistedLoginState.Renewing ->
                if (
                    current.operationId == operationId &&
                    current.bundle.session.version() == expectedVersion
                ) {
                    unchanged(GatewaySessionStoreResult.ALREADY_COMMITTED, current)
                } else {
                    unchanged(GatewaySessionStoreResult.BLOCKED, current)
                }
            is GatewayPersistedLoginState.PendingRevocation ->
                unchanged(GatewaySessionStoreResult.BLOCKED, current)
        }
    }

    fun commitRenewal(
        current: GatewayPersistedLoginState?,
        expectedVersion: GatewaySessionVersion,
        operationId: String,
        renewedBundle: GatewayPersistedLoginBundle,
    ): GatewayPersistedStateTransition {
        if (!operationIdPattern.matches(operationId)) {
            return unchanged(GatewaySessionStoreResult.BLOCKED, current)
        }
        val renewedVersion = renewedBundle.session.version()
        return when (current) {
            null -> unchanged(GatewaySessionStoreResult.NOT_FOUND, null)
            is GatewayPersistedLoginState.Renewing -> {
                val sourceMatches =
                    current.operationId == operationId &&
                        current.bundle.session.version() == expectedVersion
                if (!sourceMatches) {
                    unchanged(GatewaySessionStoreResult.STALE, current)
                } else if (!validRenewal(current.bundle, renewedBundle)) {
                    unchanged(GatewaySessionStoreResult.BLOCKED, current)
                } else {
                    committed(GatewayPersistedLoginState.Active(renewedBundle))
                }
            }
            is GatewayPersistedLoginState.Active ->
                if (
                    current.bundle.session.version() == renewedVersion &&
                    sameBundle(current.bundle, renewedBundle)
                ) {
                    unchanged(GatewaySessionStoreResult.ALREADY_COMMITTED, current)
                } else {
                    unchanged(GatewaySessionStoreResult.STALE, current)
                }
            is GatewayPersistedLoginState.PendingRevocation ->
                unchanged(GatewaySessionStoreResult.BLOCKED, current)
        }
    }

    fun abandonRenewal(
        current: GatewayPersistedLoginState?,
        expectedVersion: GatewaySessionVersion,
        operationId: String,
    ): GatewayPersistedStateTransition {
        if (!operationIdPattern.matches(operationId)) {
            return unchanged(GatewaySessionStoreResult.BLOCKED, current)
        }
        return when (current) {
            null -> unchanged(GatewaySessionStoreResult.NOT_FOUND, null)
            is GatewayPersistedLoginState.Renewing ->
                if (
                    current.operationId == operationId &&
                    current.bundle.session.version() == expectedVersion
                ) {
                    committed(
                        GatewayPersistedLoginState.PendingRevocation(
                            current.bundle.pendingRevocation(operationId),
                        ),
                    )
                } else {
                    unchanged(GatewaySessionStoreResult.STALE, current)
                }
            is GatewayPersistedLoginState.PendingRevocation ->
                if (
                    current.pending.operationId == operationId &&
                    current.pending.version == expectedVersion
                ) {
                    unchanged(GatewaySessionStoreResult.ALREADY_COMMITTED, current)
                } else {
                    unchanged(GatewaySessionStoreResult.STALE, current)
                }
            is GatewayPersistedLoginState.Active ->
                unchanged(GatewaySessionStoreResult.STALE, current)
        }
    }

    fun recoverRenewingToPendingRevocation(
        current: GatewayPersistedLoginState?,
    ): GatewayPersistedStateTransition = when (current) {
        null,
        is GatewayPersistedLoginState.Active,
        -> unchanged(GatewaySessionStoreResult.NOT_FOUND, current)
        is GatewayPersistedLoginState.Renewing ->
            committed(
                GatewayPersistedLoginState.PendingRevocation(
                    current.bundle.pendingRevocation(current.operationId),
                ),
            )
        is GatewayPersistedLoginState.PendingRevocation ->
            unchanged(GatewaySessionStoreResult.ALREADY_COMMITTED, current)
    }

    fun moveActiveToPendingRevocation(
        current: GatewayPersistedLoginState?,
        expectedVersion: GatewaySessionVersion,
        operationId: String,
    ): GatewayPersistedStateTransition {
        if (!operationIdPattern.matches(operationId)) {
            return unchanged(GatewaySessionStoreResult.BLOCKED, current)
        }
        return when (current) {
            null -> unchanged(GatewaySessionStoreResult.NOT_FOUND, null)
            is GatewayPersistedLoginState.Active ->
                if (current.bundle.session.version() == expectedVersion) {
                    committed(
                        GatewayPersistedLoginState.PendingRevocation(
                            current.bundle.pendingRevocation(operationId),
                        ),
                    )
                } else {
                    unchanged(GatewaySessionStoreResult.STALE, current)
                }
            is GatewayPersistedLoginState.PendingRevocation ->
                if (
                    current.pending.operationId == operationId &&
                    current.pending.version == expectedVersion
                ) {
                    unchanged(GatewaySessionStoreResult.ALREADY_COMMITTED, current)
                } else {
                    unchanged(GatewaySessionStoreResult.STALE, current)
                }
            is GatewayPersistedLoginState.Renewing ->
                unchanged(GatewaySessionStoreResult.BLOCKED, current)
        }
    }

    fun completePendingRevocation(
        current: GatewayPersistedLoginState?,
        expectedVersion: GatewaySessionVersion,
        operationId: String,
    ): GatewayPersistedStateTransition {
        if (!operationIdPattern.matches(operationId)) {
            return unchanged(GatewaySessionStoreResult.BLOCKED, current)
        }
        return when (current) {
            null -> unchanged(GatewaySessionStoreResult.NOT_FOUND, null)
            is GatewayPersistedLoginState.PendingRevocation ->
                if (
                    current.pending.operationId == operationId &&
                    current.pending.version == expectedVersion
                ) {
                    committed(null)
                } else {
                    unchanged(GatewaySessionStoreResult.STALE, current)
                }
            is GatewayPersistedLoginState.Active,
            is GatewayPersistedLoginState.Renewing,
            -> unchanged(GatewaySessionStoreResult.BLOCKED, current)
        }
    }

    private fun validRenewal(
        source: GatewayPersistedLoginBundle,
        renewed: GatewayPersistedLoginBundle,
    ): Boolean {
        val sourceVersion = source.session.version()
        val renewedVersion = renewed.session.version()
        return source.firstRunEpoch == renewed.firstRunEpoch &&
            source.firstRunFlow == renewed.firstRunFlow &&
            source.orderedEvidence == renewed.orderedEvidence &&
            sourceVersion.gatewayBaseUrl == renewedVersion.gatewayBaseUrl &&
            sourceVersion.actorId == renewedVersion.actorId &&
            sourceVersion.deviceId == renewedVersion.deviceId &&
            sourceVersion.familyId == renewedVersion.familyId &&
            sourceVersion.rotation < Long.MAX_VALUE &&
            renewedVersion.rotation == sourceVersion.rotation + 1L &&
            source.session.absoluteExpiresAtEpochMs ==
            renewed.session.absoluteExpiresAtEpochMs &&
            source.session.accessCookiePair != renewed.session.accessCookiePair &&
            source.session.refreshToken != renewed.session.refreshToken
    }

    private fun sameBundle(
        first: GatewayPersistedLoginBundle,
        second: GatewayPersistedLoginBundle,
    ): Boolean = first.firstRunEpoch == second.firstRunEpoch &&
        first.firstRunFlow == second.firstRunFlow &&
        first.orderedEvidence == second.orderedEvidence &&
        sameSession(first.session, second.session)

    private fun sameSession(
        first: GatewayFieldSessionPersistence,
        second: GatewayFieldSessionPersistence,
    ): Boolean =
        first.gatewayBaseUrl == second.gatewayBaseUrl &&
            first.actorId == second.actorId &&
            first.accessCookiePair == second.accessCookiePair &&
            first.refreshToken == second.refreshToken &&
            first.deviceId == second.deviceId &&
            first.familyId == second.familyId &&
            first.rotation == second.rotation &&
            first.accessExpiresAtEpochMs == second.accessExpiresAtEpochMs &&
            first.idleExpiresAtEpochMs == second.idleExpiresAtEpochMs &&
            first.absoluteExpiresAtEpochMs == second.absoluteExpiresAtEpochMs

    private fun GatewayPersistedLoginBundle.pendingRevocation(
        operationId: String,
    ): GatewayPendingRevocation = GatewayPendingRevocation(
        operationId = operationId,
        version = session.version(),
        refreshToken = session.refreshToken,
    )

    private fun committed(
        state: GatewayPersistedLoginState?,
    ) = GatewayPersistedStateTransition(
        result = GatewaySessionStoreResult.COMMITTED,
        nextState = state,
    )

    private fun unchanged(
        result: GatewaySessionStoreResult,
        state: GatewayPersistedLoginState?,
    ) = GatewayPersistedStateTransition(result = result, nextState = state)
}

internal fun GatewayFieldSessionPersistence.version(): GatewaySessionVersion =
    GatewaySessionVersion(
        gatewayBaseUrl = gatewayBaseUrl,
        actorId = actorId,
        deviceId = deviceId,
        familyId = familyId,
        rotation = rotation,
    )
