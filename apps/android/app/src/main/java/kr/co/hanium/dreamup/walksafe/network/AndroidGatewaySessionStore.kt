package kr.co.hanium.dreamup.walksafe.network

import android.content.SharedPreferences
import java.util.UUID
import kr.co.hanium.dreamup.walksafe.security.AeadKeyPolicy
import kr.co.hanium.dreamup.walksafe.security.AeadLimits
import kr.co.hanium.dreamup.walksafe.security.AeadOpenResult
import kr.co.hanium.dreamup.walksafe.security.AeadSealResult
import kr.co.hanium.dreamup.walksafe.security.AndroidKeyStoreAead
import kr.co.hanium.dreamup.walksafe.security.LocalAead
import kr.co.hanium.dreamup.walksafe.session.FirstRunAgeBand
import kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingEvidence
import kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingEvidenceVerifier
import kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingFlow
import kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingPolicy
import kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingSnapshot
import kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingStage
import kr.co.hanium.dreamup.walksafe.session.FirstRunOpaqueActorBinding
import kr.co.hanium.dreamup.walksafe.session.FirstRunOpaqueSubmissionHandle
import kr.co.hanium.dreamup.walksafe.session.FirstRunReceiptHash
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists one authenticated long-lived login state and one installation-scoped device ID.
 *
 * The state envelope is an exact-schema tagged union. ACTIVE and RENEWING contain both the
 * complete ordered FP-010 evidence and the Gateway lease. PENDING_REVOCATION contains only the
 * refresh proof required to retry remote logout. SharedPreferences never contains a raw
 * credential, actor ID, receipt, or device ID outside AES-GCM ciphertext.
 */
internal class AndroidGatewaySessionStore(
    private val preferences: SharedPreferences,
    private val sessionAead: LocalAead = AndroidKeyStoreAead(SESSION_KEY_POLICY),
    private val installIdAead: LocalAead = AndroidKeyStoreAead(INSTALL_ID_KEY_POLICY),
    private val v3SessionAead: LocalAead = AndroidKeyStoreAead(V3_SESSION_KEY_POLICY),
    private val legacySessionAead: LocalAead = AndroidKeyStoreAead(LEGACY_SESSION_KEY_POLICY),
) {
    fun getOrCreateInstallDeviceId(): String? = synchronized(PROCESS_LOCK) {
        if (!reconcileKeyResetLocked()) return null
        if (processStorageBlocked || failClosedMarkerPresentLocked()) return null
        runCatching {
            readInstallDeviceIdLocked(createIfMissing = true)
        }.getOrElse {
            markFailClosedLocked()
            null
        }
    }

    fun saveInitialIfAbsent(
        session: GatewayFieldSession,
        firstRunSnapshot: FirstRunOnboardingSnapshot,
        expectedGatewayBaseUrl: String,
    ): GatewaySessionStoreResult = synchronized(PROCESS_LOCK) {
        val installDeviceId = runCatching {
            readInstallDeviceIdLocked(createIfMissing = false)
        }.getOrNull() ?: return GatewaySessionStoreResult.BLOCKED
        val bundle = bundleOrNull(
            session = session,
            firstRunSnapshot = firstRunSnapshot,
            expectedGatewayBaseUrl = expectedGatewayBaseUrl,
            installDeviceId = installDeviceId,
        ) ?: return GatewaySessionStoreResult.BLOCKED
        val current = loadStateOrBlockedLocked()
            ?: return GatewaySessionStoreResult.BLOCKED
        applyTransitionLocked(
            GatewayPersistedLoginStatePolicy.saveInitialIfAbsent(
                current = current.state,
                bundle = bundle,
            ),
        )
    }

    fun restoreActive(
        expectedGatewayBaseUrl: String,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): RestoredGatewayLoginBundle? = synchronized(PROCESS_LOCK) {
        val installDeviceId = runCatching {
            readInstallDeviceIdLocked(createIfMissing = false)
        }.getOrNull() ?: return null
        val loaded = loadStateOrBlockedLocked() ?: return null
        val active = loaded.state as? GatewayPersistedLoginState.Active ?: return null
        val bundle = active.bundle
        val firstRun = restoreAuthenticatedFirstRun(bundle) ?: run {
            markFailClosedLocked()
            return null
        }
        val version = bundle.session.version()
        if (
            version.gatewayBaseUrl != expectedGatewayBaseUrl ||
            version.deviceId != installDeviceId ||
            firstRun.reporterActorBinding?.value != version.actorId
        ) {
            markFailClosedLocked()
            return null
        }
        if (
            nowEpochMs >= bundle.session.idleExpiresAtEpochMs ||
            nowEpochMs >= bundle.session.absoluteExpiresAtEpochMs
        ) {
            val operationId = recoveryOperationId()
            applyTransitionLocked(
                GatewayPersistedLoginStatePolicy.moveActiveToPendingRevocation(
                    current = active,
                    expectedVersion = version,
                    operationId = operationId,
                ),
            )
            return null
        }
        val restored = GatewayFieldSession.restore(
            snapshot = bundle.session,
            expectedGatewayBaseUrl = expectedGatewayBaseUrl,
            expectedActorId = firstRun.reporterActorBinding?.value ?: return null,
            expectedDeviceId = installDeviceId,
            nowEpochMs = nowEpochMs,
        ) ?: run {
            markFailClosedLocked()
            return null
        }
        RestoredGatewayLoginBundle(
            firstRunSnapshot = firstRun,
            session = restored,
            version = version,
        )
    }

    fun reserveRenewal(
        expectedVersion: GatewaySessionVersion,
        operationId: String,
    ): GatewaySessionStoreResult = synchronized(PROCESS_LOCK) {
        val current = loadStateOrBlockedLocked()
            ?: return GatewaySessionStoreResult.BLOCKED
        applyTransitionLocked(
            GatewayPersistedLoginStatePolicy.reserveRenewal(
                current = current.state,
                expectedVersion = expectedVersion,
                operationId = operationId,
            ),
        )
    }

    fun commitRenewal(
        expectedVersion: GatewaySessionVersion,
        operationId: String,
        renewedSession: GatewayFieldSession,
        firstRunSnapshot: FirstRunOnboardingSnapshot,
    ): GatewaySessionStoreResult = synchronized(PROCESS_LOCK) {
        val installDeviceId = runCatching {
            readInstallDeviceIdLocked(createIfMissing = false)
        }.getOrNull() ?: return GatewaySessionStoreResult.BLOCKED
        val renewedBundle = bundleOrNull(
            session = renewedSession,
            firstRunSnapshot = firstRunSnapshot,
            expectedGatewayBaseUrl = expectedVersion.gatewayBaseUrl,
            installDeviceId = installDeviceId,
        ) ?: return GatewaySessionStoreResult.BLOCKED
        val current = loadStateOrBlockedLocked()
            ?: return GatewaySessionStoreResult.BLOCKED
        applyTransitionLocked(
            GatewayPersistedLoginStatePolicy.commitRenewal(
                current = current.state,
                expectedVersion = expectedVersion,
                operationId = operationId,
                renewedBundle = renewedBundle,
            ),
        )
    }

    fun abandonRenewal(
        expectedVersion: GatewaySessionVersion,
        operationId: String,
    ): GatewaySessionStoreResult = synchronized(PROCESS_LOCK) {
        val current = loadStateOrBlockedLocked()
            ?: return GatewaySessionStoreResult.BLOCKED
        applyTransitionLocked(
            GatewayPersistedLoginStatePolicy.abandonRenewal(
                current = current.state,
                expectedVersion = expectedVersion,
                operationId = operationId,
            ),
        )
    }

    fun recoverRenewingToPendingRevocation(): GatewaySessionStoreResult =
        synchronized(PROCESS_LOCK) {
            val current = loadStateOrBlockedLocked()
                ?: return GatewaySessionStoreResult.BLOCKED
            applyTransitionLocked(
                GatewayPersistedLoginStatePolicy.recoverRenewingToPendingRevocation(
                    current.state,
                ),
            )
        }

    fun moveActiveToPendingRevocation(
        expectedVersion: GatewaySessionVersion,
        operationId: String,
    ): GatewaySessionStoreResult = synchronized(PROCESS_LOCK) {
        val current = loadStateOrBlockedLocked()
            ?: return GatewaySessionStoreResult.BLOCKED
        applyTransitionLocked(
            GatewayPersistedLoginStatePolicy.moveActiveToPendingRevocation(
                current = current.state,
                expectedVersion = expectedVersion,
                operationId = operationId,
            ),
        )
    }

    fun restorePendingRevocation(
        expectedGatewayBaseUrl: String,
    ): GatewayPendingRevocation? = synchronized(PROCESS_LOCK) {
        val installDeviceId = runCatching {
            readInstallDeviceIdLocked(createIfMissing = false)
        }.getOrNull() ?: return null
        val current = loadStateOrBlockedLocked()?.state
            as? GatewayPersistedLoginState.PendingRevocation ?: return null
        current.pending.takeIf {
            it.version.gatewayBaseUrl == expectedGatewayBaseUrl &&
                it.version.deviceId == installDeviceId
        } ?: run {
            markFailClosedLocked()
            null
        }
    }

    fun completePendingRevocation(
        expectedVersion: GatewaySessionVersion,
        operationId: String,
    ): GatewaySessionStoreResult = synchronized(PROCESS_LOCK) {
        val current = loadStateOrBlockedLocked()
            ?: return GatewaySessionStoreResult.BLOCKED
        val transition = GatewayPersistedLoginStatePolicy.completePendingRevocation(
            current = current.state,
            expectedVersion = expectedVersion,
            operationId = operationId,
        )
        if (transition.result != GatewaySessionStoreResult.COMMITTED) return transition.result
        if (transition.nextState != null) {
            markFailClosedLocked()
            return GatewaySessionStoreResult.STORAGE_FAILURE
        }
        if (!stageKeyResetLocked(KEY_RESET_SCOPE_SESSION) || !reconcileKeyResetLocked()) {
            processStorageBlocked = true
            return GatewaySessionStoreResult.STORAGE_FAILURE
        }
        GatewaySessionStoreResult.COMMITTED
    }

    /**
     * Removes all long-lived state without contacting the network.
     *
     * This is the only unconditional clear and is reserved for the build-time default-OFF path.
     * The installation device ID is intentionally preserved across logout and feature toggles.
     */
    fun purgeDisabled(): Boolean = synchronized(PROCESS_LOCK) {
        val reset = stageKeyResetLocked(KEY_RESET_SCOPE_SESSION) && reconcileKeyResetLocked()
        processStorageBlocked = !reset
        reset
    }

    fun resetInstallationAfterConfirmedAccountDeletion(): Boolean =
        synchronized(PROCESS_LOCK) {
            val reset = stageKeyResetLocked(KEY_RESET_SCOPE_INSTALLATION) &&
                reconcileKeyResetLocked()
            processStorageBlocked = !reset
            reset
        }

    private fun bundleOrNull(
        session: GatewayFieldSession,
        firstRunSnapshot: FirstRunOnboardingSnapshot,
        expectedGatewayBaseUrl: String,
        installDeviceId: String,
    ): GatewayPersistedLoginBundle? {
        val persisted = session.persistenceSnapshotOrNull() ?: return null
        val evidence = completeOrderedEvidenceOrNull(firstRunSnapshot) ?: return null
        val actorId = firstRunSnapshot.reporterActorBinding?.value ?: return null
        if (
            persisted.gatewayBaseUrl != expectedGatewayBaseUrl ||
            persisted.actorId != actorId ||
            persisted.deviceId != installDeviceId
        ) {
            return null
        }
        return GatewayPersistedLoginBundle(
            firstRunEpoch = firstRunSnapshot.epoch,
            orderedEvidence = evidence,
            session = persisted,
            firstRunFlow = firstRunSnapshot.flow,
        )
    }

    private fun completeOrderedEvidenceOrNull(
        snapshot: FirstRunOnboardingSnapshot,
    ): List<FirstRunOnboardingEvidence>? {
        if (!snapshot.isComplete || !snapshot.mayEnterWalk || snapshot.pendingAttempt != null) {
            return null
        }
        val ageBand = snapshot.ageBand ?: return null
        if (ageBand == FirstRunAgeBand.UNDER_14) return null
        val receipts = snapshot.completedReceiptHashes
        fun receipt(stage: FirstRunOnboardingStage): FirstRunReceiptHash =
            receipts[stage] ?: throw IllegalArgumentException("missing first-run receipt")
        if (snapshot.flow == FirstRunOnboardingFlow.EMAIL_ACCOUNT_V4) {
            return runCatching {
                buildList {
                    if (FirstRunOnboardingStage.EMAIL_OTP_ENROLLMENT in receipts) {
                        add(
                            FirstRunOnboardingEvidence.EmailOtpEnrollment(
                                ageBand = FirstRunAgeBand.VERIFIED_14_PLUS,
                                receiptHash = receipt(
                                    FirstRunOnboardingStage.EMAIL_OTP_ENROLLMENT,
                                ),
                            ),
                        )
                        add(
                            FirstRunOnboardingEvidence.AccountCreated(
                                receipt(FirstRunOnboardingStage.ACCOUNT_CREATED),
                            ),
                        )
                    }
                    add(
                        FirstRunOnboardingEvidence.VerifiedLogin(
                            actorBinding = requireNotNull(snapshot.verifiedActorBinding),
                            receiptHash = receipt(FirstRunOnboardingStage.VERIFIED_LOGIN),
                        ),
                    )
                    add(
                        FirstRunOnboardingEvidence.PurposeAndSafety(
                            receipt(FirstRunOnboardingStage.PURPOSE_AND_SAFETY),
                        ),
                    )
                    add(
                        FirstRunOnboardingEvidence.Fp004Training(
                            receipt(FirstRunOnboardingStage.FP004_TRAINING),
                        ),
                    )
                }
            }.getOrNull()
        }
        return runCatching {
            buildList {
                add(
                    FirstRunOnboardingEvidence.PurposeAndSafety(
                        receipt(FirstRunOnboardingStage.PURPOSE_AND_SAFETY),
                    ),
                )
                add(
                    FirstRunOnboardingEvidence.AgeAndGuardianNeed(
                        ageBand = ageBand,
                        receiptHash = receipt(FirstRunOnboardingStage.AGE_AND_GUARDIAN_NEED),
                    ),
                )
                add(
                    FirstRunOnboardingEvidence.IntegratedConsent(
                        receipt(FirstRunOnboardingStage.INTEGRATED_CONSENT),
                    ),
                )
                add(
                    FirstRunOnboardingEvidence.LocalCredentialPhoneSubmission(
                        submissionHandle =
                            requireNotNull(snapshot.localCredentialPhoneSubmissionHandle),
                        receiptHash =
                            receipt(
                                FirstRunOnboardingStage.LOCAL_CREDENTIAL_PHONE_SUBMISSION,
                            ),
                    ),
                )
                add(
                    FirstRunOnboardingEvidence.VerifiedSms(
                        receipt(FirstRunOnboardingStage.VERIFIED_SMS),
                    ),
                )
                if (ageBand == FirstRunAgeBand.AGE_14_TO_17) {
                    add(
                        FirstRunOnboardingEvidence.GuardianApproval(
                            receipt(FirstRunOnboardingStage.GUARDIAN_APPROVAL),
                        ),
                    )
                }
                add(
                    FirstRunOnboardingEvidence.AccountActivation(
                        receipt(FirstRunOnboardingStage.ACCOUNT_ACTIVATION),
                    ),
                )
                add(
                    FirstRunOnboardingEvidence.VerifiedLogin(
                        actorBinding = requireNotNull(snapshot.verifiedActorBinding),
                        receiptHash = receipt(FirstRunOnboardingStage.VERIFIED_LOGIN),
                    ),
                )
                add(
                    FirstRunOnboardingEvidence.JitPermissionObservation(
                        receipt(FirstRunOnboardingStage.JIT_PERMISSION_OBSERVATION),
                    ),
                )
                add(
                    FirstRunOnboardingEvidence.DeviceCheck(
                        receipt(FirstRunOnboardingStage.DEVICE_CHECK),
                    ),
                )
                add(
                    FirstRunOnboardingEvidence.Fp004Training(
                        receipt(FirstRunOnboardingStage.FP004_TRAINING),
                    ),
                )
            }
        }.getOrNull()
    }

    private fun restoreAuthenticatedFirstRun(
        bundle: GatewayPersistedLoginBundle,
    ): FirstRunOnboardingSnapshot? {
        val restored = when (bundle.firstRunFlow) {
            FirstRunOnboardingFlow.LEGACY_PHONE_V3 ->
                FirstRunOnboardingPolicy.restoreVerifiedReceiptPrefix(
                    epoch = bundle.firstRunEpoch,
                    orderedEvidence = bundle.orderedEvidence,
                    verifier = AUTHENTICATED_ENVELOPE_EVIDENCE_VERIFIER,
                )
            FirstRunOnboardingFlow.EMAIL_ACCOUNT_V4 ->
                FirstRunOnboardingPolicy.restoreVerifiedEmailReceiptPrefix(
                    epoch = bundle.firstRunEpoch,
                    orderedEvidence = bundle.orderedEvidence,
                    verifier = AUTHENTICATED_ENVELOPE_EVIDENCE_VERIFIER,
                )
        }
        return restored.snapshot.takeIf {
            restored.fullyRestored &&
                restored.restoredEvidenceCount == bundle.orderedEvidence.size &&
                it.isComplete &&
                it.mayEnterWalk &&
                it.reporterActorBinding?.value == bundle.session.actorId
        }
    }

    private fun applyTransitionLocked(
        transition: GatewayPersistedStateTransition,
    ): GatewaySessionStoreResult {
        if (transition.result != GatewaySessionStoreResult.COMMITTED) {
            return transition.result
        }
        val saved = if (transition.nextState == null) {
            removeStateLocked()
        } else {
            saveStateLocked(transition.nextState)
        }
        return if (saved) {
            GatewaySessionStoreResult.COMMITTED
        } else {
            GatewaySessionStoreResult.STORAGE_FAILURE
        }
    }

    private fun loadStateOrBlockedLocked(): LoadedState? {
        if (!reconcileKeyResetLocked()) return null
        if (processStorageBlocked) return null
        val failClosed = runCatching {
            preferences.getBoolean(FAIL_CLOSED_PREF_KEY, false)
        }.getOrDefault(true)
        if (failClosed) {
            processStorageBlocked = true
            return null
        }
        val unsupportedLegacyPresent = runCatching {
            preferences.contains(V3_FAIL_CLOSED_PREF_KEY) ||
                preferences.contains(V2_STATE_PREF_KEY) ||
                preferences.contains(V1_STATE_PREF_KEY)
        }.getOrDefault(true)
        if (unsupportedLegacyPresent) {
            markFailClosedLocked()
            return null
        }
        val v4Present = runCatching { preferences.contains(STATE_PREF_KEY) }.getOrDefault(true)
        val v3Present = runCatching { preferences.contains(V3_STATE_PREF_KEY) }.getOrDefault(true)
        if (v4Present && v3Present) {
            markFailClosedLocked()
            return null
        }
        if (v3Present) return migrateCompletedV3StateLocked()
        val encoded = runCatching {
            preferences.getString(STATE_PREF_KEY, null)
        }.getOrElse {
            markFailClosedLocked()
            return null
        } ?: return LoadedState(null)
        val state = runCatching {
            val opened = sessionAead.open(encoded, STATE_AAD, STATE_LIMITS)
                as? AeadOpenResult.Opened
                ?: error("gateway state authentication failed")
            val payload = opened.plaintext
            try {
                if (opened.needsRewrap && !storeRewrappedStateLocked(payload)) {
                    error("gateway state key rotation failed")
                }
                decodeState(JSONObject(String(payload, Charsets.UTF_8)))
            } finally {
                payload.fill(0)
            }
        }.getOrElse {
            markFailClosedLocked()
            return null
        }
        return LoadedState(state)
    }

    /**
     * V3 had no flow discriminator. Only its exact, authenticated, completed legacy reducer
     * bundle may cross the migration boundary; it is always tagged LEGACY_PHONE_V3 in V4.
     */
    private fun migrateCompletedV3StateLocked(): LoadedState? {
        val state = runCatching {
            val encoded = preferences.getString(V3_STATE_PREF_KEY, null)
                ?: error("missing v3 gateway state")
            val opened = v3SessionAead.open(encoded, V3_STATE_AAD, STATE_LIMITS)
                as? AeadOpenResult.Opened
                ?: error("v3 gateway state authentication failed")
            val payload = opened.plaintext
            try {
                require(opened.keyVersion == 3 && !opened.needsRewrap)
                decodeV3State(JSONObject(String(payload, Charsets.UTF_8)))
            } finally {
                payload.fill(0)
            }
        }.getOrElse {
            markFailClosedLocked()
            return null
        }
        if (!saveStateLocked(state) || !v3SessionAead.destroyKnownVersions()) {
            markFailClosedLocked()
            return null
        }
        return LoadedState(state)
    }

    private fun saveStateLocked(state: GatewayPersistedLoginState): Boolean {
        val saved = runCatching {
            val payload = encodeState(state).toString().toByteArray(Charsets.UTF_8)
            try {
                require(payload.size <= MAX_STATE_PLAINTEXT_BYTES)
                val envelope = (sessionAead.seal(payload, STATE_AAD, STATE_LIMITS)
                    as? AeadSealResult.Sealed)?.envelope
                    ?: error("gateway state encryption failed")
                preferences.edit()
                    .putString(STATE_PREF_KEY, envelope)
                    .remove(V3_STATE_PREF_KEY)
                    .remove(V3_FAIL_CLOSED_PREF_KEY)
                    .remove(V2_STATE_PREF_KEY)
                    .remove(V1_STATE_PREF_KEY)
                    .remove(FAIL_CLOSED_PREF_KEY)
                    .commit()
            } finally {
                payload.fill(0)
            }
        }.getOrDefault(false)
        if (saved) {
            processStorageBlocked = false
        } else {
            markFailClosedLocked()
        }
        return saved
    }

    private fun removeStateLocked(): Boolean {
        val removed = runCatching {
            preferences.edit()
                .remove(STATE_PREF_KEY)
                .remove(V3_STATE_PREF_KEY)
                .remove(V3_FAIL_CLOSED_PREF_KEY)
                .remove(V2_STATE_PREF_KEY)
                .remove(V1_STATE_PREF_KEY)
                .remove(FAIL_CLOSED_PREF_KEY)
                .commit()
        }.getOrDefault(false)
        if (removed) {
            processStorageBlocked = false
        } else {
            markFailClosedLocked()
        }
        return removed
    }

    private fun storeRewrappedStateLocked(payload: ByteArray): Boolean {
        val envelope = (sessionAead.seal(payload, STATE_AAD, STATE_LIMITS)
            as? AeadSealResult.Sealed)?.envelope ?: return false
        return preferences.edit()
            .putString(STATE_PREF_KEY, envelope)
            .remove(FAIL_CLOSED_PREF_KEY)
            .commit()
    }

    private fun encodeState(state: GatewayPersistedLoginState): JSONObject = when (state) {
        is GatewayPersistedLoginState.Active -> JSONObject()
            .put("payload_version", STATE_FORMAT_VERSION)
            .put("state", STATE_ACTIVE)
            .put("first_run", encodeFirstRun(state.bundle))
            .put("session", encodeSession(state.bundle.session))
        is GatewayPersistedLoginState.Renewing -> JSONObject()
            .put("payload_version", STATE_FORMAT_VERSION)
            .put("state", STATE_RENEWING)
            .put("operation_id", state.operationId)
            .put("first_run", encodeFirstRun(state.bundle))
            .put("session", encodeSession(state.bundle.session))
        is GatewayPersistedLoginState.PendingRevocation -> JSONObject()
            .put("payload_version", STATE_FORMAT_VERSION)
            .put("state", STATE_PENDING_REVOCATION)
            .put("operation_id", state.pending.operationId)
            .put("gateway_base_url", state.pending.version.gatewayBaseUrl)
            .put("actor_id", state.pending.version.actorId)
            .put("device_id", state.pending.version.deviceId)
            .put("family_id", state.pending.version.familyId)
            .put("rotation", state.pending.version.rotation)
            .put("refresh_token", state.pending.refreshToken)
    }

    private fun decodeState(payload: JSONObject): GatewayPersistedLoginState {
        require(payload.getInt("payload_version") == STATE_FORMAT_VERSION)
        return when (payload.getString("state")) {
            STATE_ACTIVE -> {
                require(payload.jsonKeys() == ACTIVE_FIELDS)
                GatewayPersistedLoginState.Active(
                    decodeBundle(
                        firstRun = payload.getJSONObject("first_run"),
                        session = payload.getJSONObject("session"),
                    ),
                )
            }
            STATE_RENEWING -> {
                require(payload.jsonKeys() == RENEWING_FIELDS)
                val operationId = payload.getString("operation_id")
                val state = GatewayPersistedLoginState.Renewing(
                    operationId = operationId,
                    bundle = decodeBundle(
                        firstRun = payload.getJSONObject("first_run"),
                        session = payload.getJSONObject("session"),
                    ),
                )
                require(
                    GatewayPersistedLoginStatePolicy.reserveRenewal(
                        current = GatewayPersistedLoginState.Active(state.bundle),
                        expectedVersion = state.bundle.session.version(),
                        operationId = operationId,
                    ).result == GatewaySessionStoreResult.COMMITTED,
                )
                state
            }
            STATE_PENDING_REVOCATION -> {
                require(payload.jsonKeys() == PENDING_FIELDS)
                val pending = GatewayPendingRevocation(
                    operationId = payload.getString("operation_id"),
                    version = GatewaySessionVersion(
                        gatewayBaseUrl = payload.getString("gateway_base_url"),
                        actorId = payload.getString("actor_id"),
                        deviceId = payload.getString("device_id"),
                        familyId = payload.getString("family_id"),
                        rotation = payload.getLong("rotation"),
                    ),
                    refreshToken = payload.getString("refresh_token"),
                )
                validatePending(pending)
                GatewayPersistedLoginState.PendingRevocation(pending)
            }
            else -> throw IllegalArgumentException("unsupported gateway login state")
        }
    }

    private fun decodeV3State(payload: JSONObject): GatewayPersistedLoginState {
        require(payload.getInt("payload_version") == V3_STATE_FORMAT_VERSION)
        return when (payload.getString("state")) {
            STATE_ACTIVE -> {
                require(payload.jsonKeys() == ACTIVE_FIELDS)
                GatewayPersistedLoginState.Active(
                    decodeV3Bundle(
                        firstRun = payload.getJSONObject("first_run"),
                        session = payload.getJSONObject("session"),
                    ),
                )
            }
            STATE_RENEWING -> {
                require(payload.jsonKeys() == RENEWING_FIELDS)
                val operationId = payload.getString("operation_id")
                val state = GatewayPersistedLoginState.Renewing(
                    operationId = operationId,
                    bundle = decodeV3Bundle(
                        firstRun = payload.getJSONObject("first_run"),
                        session = payload.getJSONObject("session"),
                    ),
                )
                require(
                    GatewayPersistedLoginStatePolicy.reserveRenewal(
                        current = GatewayPersistedLoginState.Active(state.bundle),
                        expectedVersion = state.bundle.session.version(),
                        operationId = operationId,
                    ).result == GatewaySessionStoreResult.COMMITTED,
                )
                state
            }
            STATE_PENDING_REVOCATION -> {
                require(payload.jsonKeys() == PENDING_FIELDS)
                val pending = GatewayPendingRevocation(
                    operationId = payload.getString("operation_id"),
                    version = GatewaySessionVersion(
                        gatewayBaseUrl = payload.getString("gateway_base_url"),
                        actorId = payload.getString("actor_id"),
                        deviceId = payload.getString("device_id"),
                        familyId = payload.getString("family_id"),
                        rotation = payload.getLong("rotation"),
                    ),
                    refreshToken = payload.getString("refresh_token"),
                )
                validatePending(pending)
                GatewayPersistedLoginState.PendingRevocation(pending)
            }
            else -> throw IllegalArgumentException("unsupported v3 gateway login state")
        }
    }

    private fun encodeFirstRun(bundle: GatewayPersistedLoginBundle): JSONObject =
        JSONObject()
            .put("epoch", bundle.firstRunEpoch)
            .put("flow", bundle.firstRunFlow.name)
            .put(
                "ordered_evidence",
                JSONArray().also { array ->
                    bundle.orderedEvidence.forEach { array.put(encodeEvidence(it)) }
                },
            )

    private fun decodeBundle(
        firstRun: JSONObject,
        session: JSONObject,
    ): GatewayPersistedLoginBundle {
        require(firstRun.jsonKeys() == FIRST_RUN_FIELDS)
        val evidenceArray = firstRun.getJSONArray("ordered_evidence")
        require(evidenceArray.length() in MIN_COMPLETE_EVIDENCE_COUNT..MAX_COMPLETE_EVIDENCE_COUNT)
        val evidence = buildList {
            for (index in 0 until evidenceArray.length()) {
                add(decodeEvidence(evidenceArray.getJSONObject(index)))
            }
        }
        val bundle = GatewayPersistedLoginBundle(
            firstRunEpoch = firstRun.getLong("epoch"),
            orderedEvidence = evidence,
            session = decodeSession(session),
            firstRunFlow = FirstRunOnboardingFlow.valueOf(firstRun.getString("flow")),
        )
        require(restoreAuthenticatedFirstRun(bundle) != null)
        return bundle
    }

    private fun decodeV3Bundle(
        firstRun: JSONObject,
        session: JSONObject,
    ): GatewayPersistedLoginBundle {
        require(firstRun.jsonKeys() == V3_FIRST_RUN_FIELDS)
        val evidenceArray = firstRun.getJSONArray("ordered_evidence")
        require(
            evidenceArray.length() in
                V3_MIN_COMPLETE_EVIDENCE_COUNT..MAX_COMPLETE_EVIDENCE_COUNT,
        )
        val evidence = buildList {
            for (index in 0 until evidenceArray.length()) {
                add(decodeEvidence(evidenceArray.getJSONObject(index)))
            }
        }
        val bundle = GatewayPersistedLoginBundle(
            firstRunEpoch = firstRun.getLong("epoch"),
            orderedEvidence = evidence,
            session = decodeSession(session),
            firstRunFlow = FirstRunOnboardingFlow.LEGACY_PHONE_V3,
        )
        require(restoreAuthenticatedFirstRun(bundle) != null)
        return bundle
    }

    private fun encodeEvidence(evidence: FirstRunOnboardingEvidence): JSONObject {
        val encoded = JSONObject()
            .put("stage", evidence.stage.name)
            .put("receipt_sha256", evidence.receiptHash.value)
        when (evidence) {
            is FirstRunOnboardingEvidence.EmailOtpEnrollment ->
                encoded.put("age_band", evidence.ageBand.name)
            is FirstRunOnboardingEvidence.AgeAndGuardianNeed ->
                encoded.put("age_band", evidence.ageBand.name)
            is FirstRunOnboardingEvidence.LocalCredentialPhoneSubmission ->
                encoded.put("submission_handle", evidence.submissionHandle.value)
            is FirstRunOnboardingEvidence.VerifiedLogin ->
                encoded.put("actor_binding", evidence.actorBinding.value)
            else -> Unit
        }
        return encoded
    }

    private fun decodeEvidence(encoded: JSONObject): FirstRunOnboardingEvidence {
        val stage = FirstRunOnboardingStage.valueOf(encoded.getString("stage"))
        val receipt = FirstRunReceiptHash.fromSha256Hex(
            encoded.getString("receipt_sha256"),
        )
        return when (stage) {
            FirstRunOnboardingStage.EMAIL_OTP_ENROLLMENT -> {
                require(encoded.jsonKeys() == AGE_EVIDENCE_FIELDS)
                FirstRunOnboardingEvidence.EmailOtpEnrollment(
                    ageBand = FirstRunAgeBand.valueOf(encoded.getString("age_band")),
                    receiptHash = receipt,
                )
            }
            FirstRunOnboardingStage.ACCOUNT_CREATED -> {
                require(encoded.jsonKeys() == BASIC_EVIDENCE_FIELDS)
                FirstRunOnboardingEvidence.AccountCreated(receipt)
            }
            FirstRunOnboardingStage.PURPOSE_AND_SAFETY -> {
                require(encoded.jsonKeys() == BASIC_EVIDENCE_FIELDS)
                FirstRunOnboardingEvidence.PurposeAndSafety(receipt)
            }
            FirstRunOnboardingStage.AGE_AND_GUARDIAN_NEED -> {
                require(encoded.jsonKeys() == AGE_EVIDENCE_FIELDS)
                FirstRunOnboardingEvidence.AgeAndGuardianNeed(
                    ageBand = FirstRunAgeBand.valueOf(encoded.getString("age_band")),
                    receiptHash = receipt,
                )
            }
            FirstRunOnboardingStage.INTEGRATED_CONSENT -> {
                require(encoded.jsonKeys() == BASIC_EVIDENCE_FIELDS)
                FirstRunOnboardingEvidence.IntegratedConsent(receipt)
            }
            FirstRunOnboardingStage.LOCAL_CREDENTIAL_PHONE_SUBMISSION -> {
                require(encoded.jsonKeys() == SUBMISSION_EVIDENCE_FIELDS)
                FirstRunOnboardingEvidence.LocalCredentialPhoneSubmission(
                    submissionHandle = FirstRunOpaqueSubmissionHandle.fromProvider(
                        encoded.getString("submission_handle"),
                    ),
                    receiptHash = receipt,
                )
            }
            FirstRunOnboardingStage.VERIFIED_SMS -> {
                require(encoded.jsonKeys() == BASIC_EVIDENCE_FIELDS)
                FirstRunOnboardingEvidence.VerifiedSms(receipt)
            }
            FirstRunOnboardingStage.GUARDIAN_APPROVAL -> {
                require(encoded.jsonKeys() == BASIC_EVIDENCE_FIELDS)
                FirstRunOnboardingEvidence.GuardianApproval(receipt)
            }
            FirstRunOnboardingStage.ACCOUNT_ACTIVATION -> {
                require(encoded.jsonKeys() == BASIC_EVIDENCE_FIELDS)
                FirstRunOnboardingEvidence.AccountActivation(receipt)
            }
            FirstRunOnboardingStage.VERIFIED_LOGIN -> {
                require(encoded.jsonKeys() == ACTOR_EVIDENCE_FIELDS)
                FirstRunOnboardingEvidence.VerifiedLogin(
                    actorBinding = FirstRunOpaqueActorBinding.fromProvider(
                        encoded.getString("actor_binding"),
                    ),
                    receiptHash = receipt,
                )
            }
            FirstRunOnboardingStage.JIT_PERMISSION_OBSERVATION -> {
                require(encoded.jsonKeys() == BASIC_EVIDENCE_FIELDS)
                FirstRunOnboardingEvidence.JitPermissionObservation(receipt)
            }
            FirstRunOnboardingStage.DEVICE_CHECK -> {
                require(encoded.jsonKeys() == BASIC_EVIDENCE_FIELDS)
                FirstRunOnboardingEvidence.DeviceCheck(receipt)
            }
            FirstRunOnboardingStage.FP004_TRAINING -> {
                require(encoded.jsonKeys() == BASIC_EVIDENCE_FIELDS)
                FirstRunOnboardingEvidence.Fp004Training(receipt)
            }
            FirstRunOnboardingStage.COMPLETE,
            FirstRunOnboardingStage.BLOCKED_UNDER_14,
            -> throw IllegalArgumentException("terminal stages are not evidence")
        }
    }

    private fun encodeSession(session: GatewayFieldSessionPersistence): JSONObject =
        JSONObject()
            .put("gateway_base_url", session.gatewayBaseUrl)
            .put("actor_id", session.actorId)
            .put("access_cookie_pair", session.accessCookiePair)
            .put("refresh_token", session.refreshToken)
            .put("device_id", session.deviceId)
            .put("family_id", session.familyId)
            .put("rotation", session.rotation)
            .put("access_expires_at_epoch_ms", session.accessExpiresAtEpochMs)
            .put("idle_expires_at_epoch_ms", session.idleExpiresAtEpochMs)
            .put("absolute_expires_at_epoch_ms", session.absoluteExpiresAtEpochMs)

    private fun decodeSession(session: JSONObject): GatewayFieldSessionPersistence {
        require(session.jsonKeys() == SESSION_FIELDS)
        val persistence = GatewayFieldSessionPersistence(
            gatewayBaseUrl = session.getString("gateway_base_url"),
            actorId = session.getString("actor_id"),
            accessCookiePair = session.getString("access_cookie_pair"),
            refreshToken = session.getString("refresh_token"),
            deviceId = session.getString("device_id"),
            familyId = session.getString("family_id"),
            rotation = session.getLong("rotation"),
            accessExpiresAtEpochMs = session.getLong("access_expires_at_epoch_ms"),
            idleExpiresAtEpochMs = session.getLong("idle_expires_at_epoch_ms"),
            absoluteExpiresAtEpochMs = session.getLong("absolute_expires_at_epoch_ms"),
        )
        require(GatewayCredentialPolicy.normalizedActorIdOrNull(persistence.actorId) != null)
        require(
            GatewayCredentialPolicy.normalizedDeviceIdOrNull(persistence.deviceId) ==
                persistence.deviceId,
        )
        require(
            GatewayCredentialPolicy.normalizedFamilyIdOrNull(persistence.familyId) ==
                persistence.familyId,
        )
        require(
            GatewayCredentialPolicy.normalizedRefreshTokenOrNull(persistence.refreshToken) ==
                persistence.refreshToken,
        )
        require(persistence.rotation >= 0L)
        require(persistence.accessExpiresAtEpochMs <= persistence.idleExpiresAtEpochMs)
        require(persistence.idleExpiresAtEpochMs <= persistence.absoluteExpiresAtEpochMs)
        return persistence
    }

    private fun validatePending(pending: GatewayPendingRevocation) {
        require(pending.operationId.matches(OPERATION_ID_PATTERN))
        require(
            GatewayCredentialPolicy.normalizedActorIdOrNull(pending.version.actorId) ==
                pending.version.actorId,
        )
        require(
            GatewayCredentialPolicy.normalizedDeviceIdOrNull(pending.version.deviceId) ==
                pending.version.deviceId,
        )
        require(
            GatewayCredentialPolicy.normalizedFamilyIdOrNull(pending.version.familyId) ==
                pending.version.familyId,
        )
        require(pending.version.rotation >= 0L)
        require(
            GatewayCredentialPolicy.normalizedRefreshTokenOrNull(pending.refreshToken) ==
                pending.refreshToken,
        )
    }

    private fun readInstallDeviceIdLocked(createIfMissing: Boolean): String? {
        if (!reconcileKeyResetLocked()) return null
        if (failClosedMarkerPresentLocked()) return null
        val encoded = preferences.getString(INSTALL_ID_PREF_KEY, null)
        if (encoded != null) {
            val opened = installIdAead.open(encoded, INSTALL_ID_AAD, INSTALL_ID_LIMITS)
                as? AeadOpenResult.Opened
                ?: error("install id authentication failed")
            val plaintext = opened.plaintext
            try {
                val payload = JSONObject(String(plaintext, Charsets.UTF_8))
                require(payload.jsonKeys() == INSTALL_ID_FIELDS)
                require(payload.getInt("payload_version") == INSTALL_ID_FORMAT_VERSION)
                val deviceId = payload.getString("device_id").takeIf {
                    GatewayCredentialPolicy.normalizedDeviceIdOrNull(it) == it
                } ?: throw IllegalArgumentException("invalid install device id")
                if (opened.needsRewrap) {
                    val rewrapped = (
                        installIdAead.seal(plaintext, INSTALL_ID_AAD, INSTALL_ID_LIMITS)
                            as? AeadSealResult.Sealed
                        )?.envelope ?: error("install id key rotation failed")
                    check(preferences.edit().putString(INSTALL_ID_PREF_KEY, rewrapped).commit())
                }
                return deviceId
            } finally {
                plaintext.fill(0)
            }
        }
        if (!createIfMissing) return null
        val deviceId = GatewayCredentialPolicy.newDeviceId()
        val payload = JSONObject()
            .put("payload_version", INSTALL_ID_FORMAT_VERSION)
            .put("device_id", deviceId)
            .toString()
            .toByteArray(Charsets.UTF_8)
        try {
            val envelope = (installIdAead.seal(payload, INSTALL_ID_AAD, INSTALL_ID_LIMITS)
                as? AeadSealResult.Sealed)?.envelope
                ?: error("install id encryption failed")
            check(
                preferences.edit()
                    .putString(INSTALL_ID_PREF_KEY, envelope)
                    .commit(),
            )
            return deviceId
        } finally {
            payload.fill(0)
        }
    }

    private fun failClosedMarkerPresentLocked(): Boolean = runCatching {
        preferences.getBoolean(FAIL_CLOSED_PREF_KEY, false)
    }.getOrDefault(true).also { present ->
        if (present) processStorageBlocked = true
    }

    private fun markFailClosedLocked() {
        processStorageBlocked = true
        val markerStored = runCatching {
            preferences.edit()
                .remove(STATE_PREF_KEY)
                .remove(V3_STATE_PREF_KEY)
                .remove(V3_FAIL_CLOSED_PREF_KEY)
                .remove(V2_STATE_PREF_KEY)
                .remove(V1_STATE_PREF_KEY)
                .putBoolean(FAIL_CLOSED_PREF_KEY, true)
                .commit()
        }.getOrDefault(false)
        if (!markerStored) deleteCredentialKeysLocked()
    }

    private fun deleteCredentialKeysLocked(): Boolean {
        val sessionKeysDeleted = sessionAead.destroyKnownVersions()
        val v3SessionKeysDeleted = v3SessionAead.destroyKnownVersions()
        val legacyKeysDeleted = legacySessionAead.destroyKnownVersions()
        return sessionKeysDeleted && v3SessionKeysDeleted && legacyKeysDeleted
    }

    private fun stageKeyResetLocked(scope: String): Boolean = runCatching {
        require(scope == KEY_RESET_SCOPE_SESSION || scope == KEY_RESET_SCOPE_INSTALLATION)
        preferences.edit()
            .remove(STATE_PREF_KEY)
            .remove(V3_STATE_PREF_KEY)
            .remove(V3_FAIL_CLOSED_PREF_KEY)
            .remove(V2_STATE_PREF_KEY)
            .remove(V1_STATE_PREF_KEY)
            .remove(FAIL_CLOSED_PREF_KEY)
            .apply {
                if (scope == KEY_RESET_SCOPE_INSTALLATION) remove(INSTALL_ID_PREF_KEY)
            }
            .putString(KEY_RESET_PENDING_PREF_KEY, scope)
            .commit()
    }.getOrDefault(false)

    private fun reconcileKeyResetLocked(): Boolean {
        val pendingPresent = runCatching {
            preferences.contains(KEY_RESET_PENDING_PREF_KEY)
        }.getOrElse {
            processStorageBlocked = true
            return false
        }
        if (!pendingPresent) return true
        val scope = runCatching {
            preferences.all[KEY_RESET_PENDING_PREF_KEY] as? String
        }.getOrNull()
        if (scope != KEY_RESET_SCOPE_SESSION && scope != KEY_RESET_SCOPE_INSTALLATION) {
            processStorageBlocked = true
            return false
        }
        val storageIsClear = gatewayCredentialArtifactsAbsentLocked() &&
            (scope != KEY_RESET_SCOPE_INSTALLATION || !preferences.contains(INSTALL_ID_PREF_KEY))
        if (!storageIsClear) {
            processStorageBlocked = true
            return false
        }
        val sessionDeleted = sessionAead.destroyKnownVersions()
        val v3Deleted = v3SessionAead.destroyKnownVersions()
        val legacyDeleted = legacySessionAead.destroyKnownVersions()
        val installDeleted = scope != KEY_RESET_SCOPE_INSTALLATION ||
            installIdAead.destroyKnownVersions()
        val sessionCreated = sessionDeleted && v3Deleted && legacyDeleted && installDeleted &&
            sessionAead.createFreshAfterVerifiedPurge()
        val installCreated = scope != KEY_RESET_SCOPE_INSTALLATION ||
            (sessionCreated && installIdAead.createFreshAfterVerifiedPurge())
        val markerCleared = sessionCreated && installCreated && runCatching {
            preferences.edit().remove(KEY_RESET_PENDING_PREF_KEY).commit() &&
                !preferences.contains(KEY_RESET_PENDING_PREF_KEY)
        }.getOrDefault(false)
        processStorageBlocked = !markerCleared
        return markerCleared
    }

    private fun gatewayCredentialArtifactsAbsentLocked(): Boolean = runCatching {
        !preferences.contains(STATE_PREF_KEY) &&
            !preferences.contains(V3_STATE_PREF_KEY) &&
            !preferences.contains(V3_FAIL_CLOSED_PREF_KEY) &&
            !preferences.contains(V2_STATE_PREF_KEY) &&
            !preferences.contains(V1_STATE_PREF_KEY) &&
            !preferences.contains(FAIL_CLOSED_PREF_KEY)
    }.getOrDefault(false)

    private fun JSONObject.jsonKeys(): Set<String> {
        val result = mutableSetOf<String>()
        val iterator = keys()
        while (iterator.hasNext()) result += iterator.next()
        return result
    }

    private data class LoadedState(
        val state: GatewayPersistedLoginState?,
    )

    private companion object {
        val PROCESS_LOCK = Any()

        @Volatile
        var processStorageBlocked = false

        const val STATE_FORMAT_VERSION = 4
        const val V3_STATE_FORMAT_VERSION = 3
        const val INSTALL_ID_FORMAT_VERSION = 1
        const val STATE_PREF_KEY = "gateway_login_bundle_encrypted_v4"
        const val V3_STATE_PREF_KEY = "gateway_login_bundle_encrypted_v3"
        const val V3_FAIL_CLOSED_PREF_KEY = "gateway_login_bundle_fail_closed_v3"
        const val INSTALL_ID_PREF_KEY = "gateway_install_id_encrypted_v1"
        const val V2_STATE_PREF_KEY = "gateway_session_encrypted_v2"
        const val V1_STATE_PREF_KEY = "gateway_session_encrypted_v1"
        const val FAIL_CLOSED_PREF_KEY = "gateway_login_bundle_fail_closed_v4"
        const val KEY_RESET_PENDING_PREF_KEY = "gateway_key_reset_pending_v1"
        const val KEY_RESET_SCOPE_SESSION = "SESSION"
        const val KEY_RESET_SCOPE_INSTALLATION = "INSTALLATION"

        const val STATE_ACTIVE = "ACTIVE"
        const val STATE_RENEWING = "RENEWING"
        const val STATE_PENDING_REVOCATION = "PENDING_REVOCATION"

        const val MAX_STATE_ENVELOPE_CHARS = 32_768
        const val MAX_STATE_CIPHERTEXT_BYTES = 24_576
        const val MAX_STATE_PLAINTEXT_BYTES = 20_480
        const val MAX_INSTALL_ENVELOPE_CHARS = 2_048
        const val MAX_INSTALL_CIPHERTEXT_BYTES = 1_024
        const val MAX_INSTALL_PLAINTEXT_BYTES = 512
        const val MIN_COMPLETE_EVIDENCE_COUNT = 2
        const val V3_MIN_COMPLETE_EVIDENCE_COUNT = 10
        const val MAX_COMPLETE_EVIDENCE_COUNT = 11

        val STATE_AAD =
            "kr.co.hanium.dreamup.walksafe|USER|gateway-session|payload=4"
                .toByteArray(Charsets.UTF_8)
        val V3_STATE_AAD =
            "kr.co.hanium.dreamup.walksafe|USER|gateway-session|payload=3"
                .toByteArray(Charsets.UTF_8)
        val INSTALL_ID_AAD =
            "kr.co.hanium.dreamup.walksafe|USER|install-id|payload=1"
                .toByteArray(Charsets.UTF_8)
        val SESSION_KEY_POLICY = AeadKeyPolicy(
            aliasPrefix = "walksafe_gateway_login_bundle_v",
            currentVersion = 4,
            readableVersions = setOf(4),
        )
        val V3_SESSION_KEY_POLICY = AeadKeyPolicy(
            aliasPrefix = "walksafe_gateway_login_bundle_v",
            currentVersion = 3,
            readableVersions = setOf(3),
        )
        val INSTALL_ID_KEY_POLICY = AeadKeyPolicy(
            aliasPrefix = "walksafe_gateway_install_id_v",
            currentVersion = 1,
            readableVersions = setOf(1),
        )
        val LEGACY_SESSION_KEY_POLICY = AeadKeyPolicy(
            aliasPrefix = "walksafe_gateway_session_v",
            currentVersion = 2,
            readableVersions = setOf(2),
            compromisedVersions = setOf(1),
        )
        val STATE_LIMITS = AeadLimits(
            maxPlaintextBytes = MAX_STATE_PLAINTEXT_BYTES,
            maxCiphertextBytes = MAX_STATE_CIPHERTEXT_BYTES,
            maxEnvelopeChars = MAX_STATE_ENVELOPE_CHARS,
        )
        val INSTALL_ID_LIMITS = AeadLimits(
            maxPlaintextBytes = MAX_INSTALL_PLAINTEXT_BYTES,
            maxCiphertextBytes = MAX_INSTALL_CIPHERTEXT_BYTES,
            maxEnvelopeChars = MAX_INSTALL_ENVELOPE_CHARS,
        )
        val ACTIVE_FIELDS = setOf("payload_version", "state", "first_run", "session")
        val RENEWING_FIELDS =
            setOf("payload_version", "state", "operation_id", "first_run", "session")
        val PENDING_FIELDS = setOf(
            "payload_version",
            "state",
            "operation_id",
            "gateway_base_url",
            "actor_id",
            "device_id",
            "family_id",
            "rotation",
            "refresh_token",
        )
        val FIRST_RUN_FIELDS = setOf("epoch", "flow", "ordered_evidence")
        val V3_FIRST_RUN_FIELDS = setOf("epoch", "ordered_evidence")
        val BASIC_EVIDENCE_FIELDS = setOf("stage", "receipt_sha256")
        val AGE_EVIDENCE_FIELDS = BASIC_EVIDENCE_FIELDS + "age_band"
        val SUBMISSION_EVIDENCE_FIELDS = BASIC_EVIDENCE_FIELDS + "submission_handle"
        val ACTOR_EVIDENCE_FIELDS = BASIC_EVIDENCE_FIELDS + "actor_binding"
        val SESSION_FIELDS = setOf(
            "gateway_base_url",
            "actor_id",
            "access_cookie_pair",
            "refresh_token",
            "device_id",
            "family_id",
            "rotation",
            "access_expires_at_epoch_ms",
            "idle_expires_at_epoch_ms",
            "absolute_expires_at_epoch_ms",
        )
        val INSTALL_ID_FIELDS = setOf("payload_version", "device_id")
        val OPERATION_ID_PATTERN = Regex("^[A-Za-z0-9_-]{16,128}$")

        val AUTHENTICATED_ENVELOPE_EVIDENCE_VERIFIER =
            FirstRunOnboardingEvidenceVerifier { _, _ -> true }

        fun recoveryOperationId(): String =
            "recover_${UUID.randomUUID().toString().replace("-", "")}"
    }
}
