package kr.co.hanium.dreamup.walksafe.device

import kr.co.hanium.dreamup.walksafe.inference.composite.JointRuntimeChoiceCodec
import kr.co.hanium.dreamup.walksafe.inference.composite.ValidatedJointRuntimeChoice
import java.util.UUID

internal enum class DeviceCheckPublication { COMMITTED, NOT_PUBLISHED, INDETERMINATE }
internal interface DeviceCheckAuthorityPersistence {
    /** Fresh descriptor/decryption, never a SharedPreferences or last-read cache. */
    fun read(): String?
    fun publish(canonicalState: String): DeviceCheckPublication
}
internal data class DeviceCheckFrozenSuccess(val attempt: DeviceCheckAttemptRef, val acceptedAt: Long,
    val result: PersistedPostLoginDeviceCheckResult, val choice: ValidatedJointRuntimeChoice,
    val primary: DeviceCheckNativeModelRef, val auxiliary: DeviceCheckNativeModelRef)
internal class DeviceCheckRuntimeRetirement(val lease: DeviceCheckNativeLease, val attemptId: String,
    val reason: String, val interruptsAttempt: Boolean)
internal class DeviceCheckNativePrepareCapture(val source: DeviceCheckAuthoritySource, val owner: DeviceCheckOwnerRef,
    val lease: DeviceCheckNativeLease, val identity: Any, val commandId: String, val spec: DeviceCheckNativeSpec,
    val process: DeviceCheckNativeProcess, val deadlineMs: Long, val generation: Long, val authorityId: String)

/** Disk methods run on one worker; small capability/fence methods may run concurrently. No lock spans I/O. */
internal class DeviceCheckAuthorityCore(
    private val persistence: DeviceCheckAuthorityPersistence,
    private val observer: DeviceCheckProcessObserver,
    private val now: () -> Long,
    private val processors: () -> Int,
    private val thermalSupported: Boolean,
    val processNonce: String = UUID.randomUUID().toString(),
    private val verifyProofOrigin: (DeviceCheckPairProof) -> Boolean = { false },
    private val requireSessionObservation: Boolean = false,
) {
    private val lock = Any()
    private val authorityId = UUID.randomUUID().toString()
    private var state: DeviceCheckAuthorityState? = null
    private var activeBinding: DeviceCheckAuthorityBinding? = null
    private var readFailure: String? = null
    private var running: String? = null
    private var unresolved: String? = null
    private var uncertaintyEpoch = 0L
    private var publishingState: DeviceCheckAuthorityState? = null
    private var pendingNewAttempt: String? = null
    private var sessionObserved = false
    private var currentSessionGeneration: Long? = null
    private val absentCohorts = mutableSetOf<String>()
    private val attempts = linkedMapOf<String, DeviceCheckAttemptRef>()
    private val bundles = linkedMapOf<Pair<String, Long>, DeviceCheckBundleRef>()
    private val issuedBundles = java.util.WeakHashMap<DeviceCheckBundleRef, Boolean>()
    private val owners = linkedMapOf<String, DeviceCheckOwnerRef>()
    private val cohortOwners = linkedMapOf<String, Pair<Any, DeviceCheckOwnerRef>>()
    private val retiredOwners = java.util.Collections.newSetFromMap(java.util.WeakHashMap<DeviceCheckOwnerRef, Boolean>())
    private val cancelledAttempts = mutableSetOf<String>()
    private val interruptedAttempts = mutableSetOf<String>()
    private val runtimeRetirements = linkedMapOf<String, DeviceCheckRuntimeRetirement>()
    private val reserved = linkedMapOf<String, DeviceCheckFrozenSuccess>()
    private val permits = linkedMapOf<String, DeviceCheckNativePermit>()
    private val models = linkedMapOf<Triple<String, Long, String>, DeviceCheckNativeModelRef>()
    private val issuedModels = java.util.WeakHashMap<DeviceCheckNativeModelRef, Boolean>()
    private val deferredModelCloses = linkedMapOf<String, Int>()
    private val deferredCohortReleases = linkedMapOf<String, Pair<Int, DeviceCheckNativeReleaseProof>>()
    private val nativeCleanupPending = mutableSetOf<String>()
    private var nativeAdmissionGeneration = 0L

    fun enterWork(id: String, recovery: Boolean = false): Boolean = synchronized(lock) {
        if (running != null || !recovery && unresolved != null) false else { running = id; true }
    }
    fun leaveWork(id: String) = synchronized(lock) { if (running == id) running = null }
    fun publicationUnconfirmed(id: String) = synchronized(lock) {
        uncertaintyEpoch++
        if (unresolved == null) unresolved = id
    }
    fun unsettledOperation(): String? = synchronized(lock) { unresolved }
    fun workRunning(): Boolean = synchronized(lock) { running != null }
    /** Selection happens only at API admission, never later on the disk worker. */
    fun admit(binding: DeviceCheckAuthorityBinding, expectedSession: Long? = null, beginId: String? = null): Boolean = synchronized(lock) {
        if (!sessionCurrent(expectedSession) || !DeviceCheckAuthorityValidation.binding(binding) ||
            beginId != null && !DeviceCheckAuthorityValidation.command(beginId)) false
        else {
            activeBinding = binding
            if (beginId != null) pendingNewAttempt = beginId
            true
        }
    }
    fun requestSessionCurrent(expectedSession: Long?): Boolean = synchronized(lock) { sessionCurrent(expectedSession) }
    fun currentBinding(): DeviceCheckAuthorityBinding? = synchronized(lock) { activeBinding }

    fun read(binding: DeviceCheckAuthorityBinding, expectedSession: Long? = null): DeviceCheckAuthorityRead {
        synchronized(lock) {
            if (!requestCurrent(binding, expectedSession)) return DeviceCheckAuthorityRead.Blocked("binding_or_session_superseded")
        }
        if (!DeviceCheckAuthorityValidation.binding(binding)) return DeviceCheckAuthorityRead.Blocked("invalid_binding")
        if (!loadIfNeeded()) return cachedSnapshot(binding)
        val old = synchronized(lock) { requireNotNull(state).cohorts.filter { it.release == null && it.processNonce != processNonce } }
        if (old.isNotEmpty()) {
            val absent = old.filter { runCatching { observer.observe(it.process) }.getOrDefault(DeviceCheckProcessObservation.UNKNOWN) == DeviceCheckProcessObservation.ABSENT }
            synchronized(lock) { absentCohorts += absent.map { it.lease.cohortId } }
        }
        return synchronized(lock) {
            if (!requestCurrent(binding, expectedSession)) DeviceCheckAuthorityRead.Blocked("binding_or_session_superseded")
            else cachedSnapshot(binding)
        }
    }

    fun cachedSnapshot(binding: DeviceCheckAuthorityBinding): DeviceCheckAuthorityRead = synchronized(lock) {
        unresolved?.let { return DeviceCheckAuthorityRead.Blocked("publication_unconfirmed", it) }
        if (activeBinding != binding) return DeviceCheckAuthorityRead.Blocked("binding_not_loaded")
        readFailure?.let { return DeviceCheckAuthorityRead.Blocked(it) }
        val current = state ?: return DeviceCheckAuthorityRead.Blocked("not_loaded")
        val dirty = current.cohorts.filter { it.release == null && it.processNonce != processNonce }
        if (dirty.isNotEmpty()) return if (dirty.all { it.lease.cohortId in absentCohorts })
            DeviceCheckAuthorityRead.NeedsCheck("RECOVERED_PROCESS_ABSENT")
            else DeviceCheckAuthorityRead.Blocked("native_recovery_required")
        if (pendingNewAttempt != null) return DeviceCheckAuthorityRead.NeedsCheck("attempt_begin_pending")
        val latest = current.latest
        val bundle = current.bundle
        if (latest?.id in cancelledAttempts) return DeviceCheckAuthorityRead.NeedsCheck("attempt_retired_in_process")
        if (latest?.phase != "COMMITTED" || bundle == null || latest.id != bundle.attemptId ||
            latest.binding != binding || bundle.binding != binding || !validChoice(bundle.choice, binding))
            return DeviceCheckAuthorityRead.NeedsCheck("missing_current_bundle")
        DeviceCheckAuthorityRead.Ready(bundleRef(bundle), bundle.result, bundle.choice)
    }

    fun beginAttempt(binding: DeviceCheckAuthorityBinding, commandId: String, started: Long,
        deadline: Long, expectedSession: Long? = null): DeviceCheckAuthorityOutcome<DeviceCheckAttemptRef> {
        synchronized(lock) {
            if (!requestCurrent(binding, expectedSession)) return reject("binding_or_session_superseded")
        }
        if (!DeviceCheckAuthorityValidation.binding(binding) || !DeviceCheckAuthorityValidation.command(commandId) ||
            started < 0 || deadline <= started) return reject("invalid_attempt")
        if (!loadIfNeeded()) return reject("storage_not_ready")
        val previous = synchronized(lock) {
            if (!requestCurrent(binding, expectedSession)) return reject("binding_or_session_superseded")
            requireNotNull(state)
        }
        val fingerprint = fingerprint("begin", commandId, binding, started, deadline)
        commandConflict(previous, commandId, fingerprint)?.let { return reject(it) }
        if (previous.latest?.id == commandId && previous.latest.phase == "CHECKING") {
            return synchronized(lock) {
                if (!requestCurrent(binding, expectedSession)) return reject("binding_or_session_superseded")
                if (pendingNewAttempt == commandId) pendingNewAttempt = null
                committed(attemptRef(previous.latest), previous, commandId)
            }
        }
        val recovered = mutableListOf<DeviceCheckCohortRecord>()
        for (cohort in previous.cohorts) {
            if (cohort.release != null) continue // Compact only durably retired rows.
            if (cohort.processNonce == processNonce) { recovered += cohort; continue }
            if (synchronized(lock) { cohort.lease.cohortId !in absentCohorts } &&
                runCatching { observer.observe(cohort.process) }.getOrDefault(DeviceCheckProcessObservation.UNKNOWN) != DeviceCheckProcessObservation.ABSENT)
                return reject("native_process_identity_unconfirmed")
            recovered += cohort.copy(release = "RECOVERED_PROCESS_ABSENT")
        }
        val latest = DeviceCheckAttemptRecord(commandId, binding, started, deadline, "CHECKING")
        val next = previous.copy(latest = latest, cohorts = recovered)
        return publish(next, commandId, fingerprint, binding, expectedSession) {
            if (pendingNewAttempt == commandId) pendingNewAttempt = null
            reserved.keys.filter { it != commandId }.toList().forEach(reserved::remove)
            attempts.keys.filter { it != commandId }.toList().forEach(attempts::remove)
            cancelledAttempts.retainAll(setOf(commandId))
            interruptedAttempts.retainAll(setOf(commandId))
            runtimeRetirements.keys.retainAll(requireNotNull(state).cohorts.filter { it.release == null }.map { it.lease.cohortId }.toSet())
            attemptRef(latest)
        }
    }

    /** Runs at API admission, before a queued cancellation write, and competes with freezeSuccess. */
    fun reserveCancellation(attempt: DeviceCheckAttemptRef): Boolean = synchronized(lock) {
        if (!issuedAttempt(attempt) || reserved.containsKey(attempt.attemptId)) false
        else { cancelledAttempts += attempt.attemptId; true }
    }
    fun cancelAttempt(attempt: DeviceCheckAttemptRef, commandId: String, reason: String): DeviceCheckAuthorityOutcome<Unit> {
        if (!DeviceCheckAuthorityValidation.command(commandId) || reason.length > 160) return reject("invalid_cancellation")
        val previous = synchronized(lock) { state } ?: return reject("not_loaded")
        if (!issuedAttempt(attempt) || previous.latest?.id != attempt.attemptId || reserved.containsKey(attempt.attemptId))
            return reject("stale_or_success_reserved")
        val hash = fingerprint("cancel", attempt.attemptId, reason)
        commandConflict(previous, commandId, hash)?.let { return reject(it) }
        return publish(previous.copy(latest = requireNotNull(previous.latest).copy(phase = "CANCELLED")), commandId, hash) { Unit }
    }

    fun registerOwner(ownerId: String, source: DeviceCheckAuthoritySource, session: Long): DeviceCheckOwnerRef? = synchronized(lock) {
        if (!DeviceCheckAuthorityValidation.key(ownerId) || ownerId == "foreground" || session < 0 ||
            !sessionCurrent(session) || !sourceCurrent(source)) return null
        owners[ownerId]?.let { if (it.source === source && it.sessionGeneration == session && it !in retiredOwners) return it }
        DeviceCheckOwnerRef(ownerId, source, session, authorityId).also { owners[ownerId] = it }
    }
    fun retireOwner(owner: DeviceCheckOwnerRef) = synchronized(lock) {
        if (owner.authorityId == authorityId) { retiredOwners += owner; if (owners[owner.ownerId] === owner) owners.remove(owner.ownerId) }
    }
    fun ownerRef(id: String): DeviceCheckOwnerRef? = synchronized(lock) { owners[id]?.takeIf(::ownerCurrent) }
    fun retireOwnersOutsideSession(session: Long?) = synchronized(lock) {
        if (!sessionObserved || currentSessionGeneration != session) pendingNewAttempt = null
        sessionObserved = true
        currentSessionGeneration = session
        owners.values.filter { session == null || it.sessionGeneration != session }.toList().forEach {
            retiredOwners += it
            if (owners[it.ownerId] === it) owners.remove(it.ownerId)
        }
    }
    fun checkAttempt(attempt: DeviceCheckAttemptRef, at: Long): Boolean = synchronized(lock) {
        sourceCurrent(attempt) && at >= attempt.startedAtMs && at < attempt.deadlineMs
    }

    /** Metadata first, including while another disk operation is uncertain. No external callbacks. */
    fun reserveRuntimeRetirement(owner: DeviceCheckOwnerRef, cohortId: String, identity: Any,
        reason: String): DeviceCheckRuntimeRetirement? = synchronized(lock) {
        if (reason.isBlank() || reason.length > 160 || owner.authorityId != authorityId || owners[owner.ownerId] !== owner ||
            owner in retiredOwners || !sessionCurrent(owner.sessionGeneration) ||
            pendingNewAttempt != null || owner.source.binding != activeBinding) return null
        val captured = cohortOwners[cohortId] ?: return null
        if (captured.first !== identity || captured.second !== owner) return null
        val current = state ?: return null
        val latest = current.latest ?: return null
        val cohort = current.cohorts.firstOrNull { it.lease.cohortId == cohortId && it.release == null } ?: return null
        val attemptId = sourceAttempt(owner.source)
        if (cohort.lease.ownerId != owner.ownerId || cohort.sourceAttemptId != attemptId || cohort.binding != owner.source.binding ||
            latest.id != attemptId || latest.phase !in setOf("CHECKING", "COMMITTED") ||
            attemptId in cancelledAttempts || runtimeRetirements.containsKey(cohortId)) return null
        val finalPairFailed = reserved[attemptId]?.let {
            it.primary.lease == cohort.lease || it.auxiliary.lease == cohort.lease
        } == true
        val interrupts = latest.phase == "COMMITTED" || finalPairFailed
        DeviceCheckRuntimeRetirement(cohort.lease, attemptId, reason, interrupts).also { retirement ->
            runtimeRetirements[cohortId] = retirement
            retiredOwners += owner
            owners.remove(owner.ownerId)
            models.keys.filter { it.first == cohortId }.toList().forEach(models::remove)
            permits.values.filter { it.lease.cohortId == cohortId }.forEach { it.revoked = true }
            if (interrupts) {
                cancelledAttempts += attemptId
                interruptedAttempts += attemptId
            }
        }
    }
    fun retireRuntime(retirement: DeviceCheckRuntimeRetirement, commandId: String): DeviceCheckAuthorityOutcome<Unit> {
        val previous = synchronized(lock) {
            if (runtimeRetirements[retirement.lease.cohortId] !== retirement || !DeviceCheckAuthorityValidation.command(commandId))
                return reject("runtime_retirement_not_reserved")
            state ?: return reject("not_loaded")
        }
        return publish(previous, commandId, fingerprint("runtime_retired", retirement.lease, retirement.attemptId,
            retirement.reason, retirement.interruptsAttempt)) { Unit }
    }

    fun captureNativePrepare(owner: DeviceCheckOwnerRef, lease: DeviceCheckNativeLease, identity: Any, commandId: String,
        spec: DeviceCheckNativeSpec, process: DeviceCheckNativeProcess, deadline: Long): DeviceCheckNativePrepareCapture? = synchronized(lock) {
        if (nativeCleanupPending.isNotEmpty() || nativeAdmissionGeneration == Long.MAX_VALUE || !ownerCurrent(owner) ||
            !sourceCurrent(owner.source) || lease.ownerId != owner.ownerId || lease.ownerEpoch <= 0 ||
            !DeviceCheckAuthorityValidation.key(lease.cohortId) || !DeviceCheckAuthorityValidation.command(commandId) ||
            !DeviceCheckAuthorityValidation.spec(spec) || !DeviceCheckAuthorityValidation.process(process) || deadline <= now()) return null
        DeviceCheckNativePrepareCapture(owner.source, owner, lease, identity, commandId, spec, process, deadline,
            nativeAdmissionGeneration, authorityId)
    }
    fun reserveNativeCleanup(capture: DeviceCheckNativePrepareCapture): Boolean = synchronized(lock) {
        if (capture.authorityId != authorityId || capture.owner.authorityId != authorityId) return false
        fenceNativeCleanup(capture.lease.cohortId)
        true
    }
    private fun fenceNativeCleanup(cohort: String) {
        if (nativeCleanupPending.add(cohort)) {
            if (nativeAdmissionGeneration < Long.MAX_VALUE) nativeAdmissionGeneration++
            permits.values.filter { !it.consumed }.forEach { it.revoked = true }
        }
    }
    fun prepareNativeOpen(owner: DeviceCheckOwnerRef, lease: DeviceCheckNativeLease, identity: Any, commandId: String,
        spec: DeviceCheckNativeSpec, process: DeviceCheckNativeProcess, deadline: Long,
        capture: DeviceCheckNativePrepareCapture? = null): DeviceCheckAuthorityOutcome<DeviceCheckNativePermit> {
        val previous: DeviceCheckAuthorityState
        synchronized(lock) {
            previous = state ?: return reject("not_loaded")
            if (nativeCleanupPending.isNotEmpty() || capture?.let { it.authorityId != authorityId || it.owner !== owner ||
                    it.source !== owner.source || it.generation != nativeAdmissionGeneration } == true ||
                !ownerCurrent(owner) || !sourceCurrent(owner.source) || runtimeRetirements.containsKey(lease.cohortId) ||
                lease.ownerId != owner.ownerId || lease.ownerEpoch <= 0 ||
                !DeviceCheckAuthorityValidation.key(lease.cohortId) || !DeviceCheckAuthorityValidation.command(commandId) ||
                !DeviceCheckAuthorityValidation.spec(spec) || !DeviceCheckAuthorityValidation.process(process) || deadline <= now())
                return reject("native_open_not_current")
            cohortOwners[lease.cohortId]?.let { (actual, captured) ->
                if (actual !== identity || captured !== owner) return reject("cohort_owner_capability_changed")
            }
            permits[commandId]?.let { existing ->
                return if (existing.leaseIdentity === identity && existing.lease == lease && existing.spec == spec)
                    committed(existing, previous, commandId) else reject("command_payload_changed")
            }
        }
        val sourceId = sourceAttempt(owner.source)
        val measurement = (owner.source as? DeviceCheckBundleRef)?.measurementId
        if (spec.role == "METRIC_SUPPORT") {
            if (spec != DeviceCheckMetricSupportContract.spec) return reject("metric_support_contract_changed")
        } else if (owner.source is DeviceCheckBundleRef) {
            val bundle = previous.bundle ?: return reject("bundle_missing")
            val allowed = previous.cohorts.flatMap { it.loads }.filter { it.id in bundle.pairLoadIds }.map { it.spec }
            if (spec !in allowed) return reject("restored_model_or_options_changed")
        }
        val existing = previous.cohorts.firstOrNull { it.lease.cohortId == lease.cohortId }
        if (previous.cohorts.any { it.release == null && it.lease.cohortId != lease.cohortId }) return reject("foreign_native_owner_pending")
        if (existing != null && (existing.release != null || existing.lease != lease || existing.process != process ||
                existing.binding != owner.source.binding || existing.sourceAttemptId != sourceId)) return reject("cohort_binding_changed")
        val hash = fingerprint("open", lease, sourceId, measurement, spec, process, deadline)
        commandConflict(previous, commandId, hash)?.let { return reject(it) }
        val load = DeviceCheckLoadRecord(commandId, spec)
        val cohort = existing?.copy(loads = existing.loads + load) ?: DeviceCheckCohortRecord(lease, sourceId, measurement,
            owner.source.binding, processNonce, process, listOf(load))
        if (cohort.loads.size > 32 || previous.cohorts.size >= 64 && existing == null) return reject("journal_capacity")
        val historicalLoads = previous.bundle?.pairLoadIds.orEmpty()
        val next = previous.copy(cohorts = previous.cohorts.filter {
            it.lease.cohortId != lease.cohortId && (it.release == null || it.loads.any { l -> l.id in historicalLoads })
        } + cohort)
        return publish(next, commandId, hash) {
            cohortOwners[lease.cohortId] = identity to owner
            DeviceCheckNativePermit(commandId, lease, spec, identity, owner, deadline, authorityId).also {
                it.revoked = nativeCleanupPending.isNotEmpty() || capture?.generation?.let { it != nativeAdmissionGeneration } == true ||
                    runtimeRetirements.containsKey(lease.cohortId) || !ownerCurrent(owner)
                permits[commandId] = it
            }
        }
    }
    fun consumeNativeOpen(permit: DeviceCheckNativePermit, identity: Any, at: Long): Boolean = synchronized(lock) {
        if (nativeCleanupPending.isNotEmpty() || permit.authorityId != authorityId || permits[permit.loadId] !== permit || permit.consumed || permit.revoked ||
            permit.leaseIdentity !== identity || !ownerCurrent(permit.owner) || !sourceCurrent(permit.owner.source) || at >= permit.deadlineMs ||
            (permit.owner.source as? DeviceCheckAttemptRef)?.let { at < it.startedAtMs || at >= it.deadlineMs } == true) false
        else { permit.consumed = true; true }
    }
    fun recordModelOpened(permit: DeviceCheckNativePermit, modelId: String, actualHash: String,
        commandId: String = UUID.randomUUID().toString()): DeviceCheckAuthorityOutcome<DeviceCheckNativeModelRef> {
        if (!DeviceCheckAuthorityValidation.key(modelId) || !DeviceCheckAuthorityValidation.hash(actualHash)) return reject("invalid_model_ack")
        val previous = synchronized(lock) { state } ?: return reject("not_loaded")
        if (permit.authorityId != authorityId || permits[permit.loadId] !== permit || !permit.consumed || permit.revoked)
            return reject("unissued_native_permit")
        val cohort = previous.cohorts.firstOrNull { it.lease == permit.lease && it.release == null } ?: return reject("cohort_retired")
        val load = cohort.loads.firstOrNull { it.id == permit.loadId } ?: return reject("load_missing")
        if (load.modelId != null && (load.modelId != modelId || load.actualHash != actualHash)) return reject("model_identity_changed")
        val updated = cohort.copy(loads = cohort.loads.map { if (it.id == load.id) it.copy(modelId = modelId, actualHash = actualHash) else it })
        val id = commandId
        return publish(previous.copy(cohorts = previous.cohorts.map { if (it === cohort) updated else it }), id,
            fingerprint("opened", permit.loadId, modelId, actualHash)) {
            DeviceCheckNativeModelRef(load.id, permit.lease, permit.spec, modelId, actualHash, authorityId).also {
                if (!permit.revoked && !runtimeRetirements.containsKey(permit.lease.cohortId))
                    models[Triple(permit.lease.cohortId, permit.lease.ownerEpoch, modelId)] = it
                issuedModels[it] = true
            }
        }
    }
    fun modelRef(cohort: String, epoch: Long, model: String): DeviceCheckNativeModelRef? = synchronized(lock) {
        if (unresolved != null || runtimeRetirements.containsKey(cohort)) null else models[Triple(cohort, epoch, model)]
    }

    fun recordModelCloseAck(model: DeviceCheckNativeModelRef, pid: Int, processDeath: Boolean,
        commandId: String = UUID.randomUUID().toString()): DeviceCheckAuthorityOutcome<Unit> {
        if (!noteModelClose(model, pid)) return reject("unissued_model_receipt")
        val previous = synchronized(lock) { state } ?: return reject("not_loaded")
        if (model.authorityId != authorityId || synchronized(lock) { issuedModels[model] != true })
            return reject("unissued_model_receipt")
        val cohort = previous.cohorts.firstOrNull { it.lease.cohortId == model.lease.cohortId } ?: return reject("cohort_missing")
        if (cohort.process.pid != pid) return reject("receipt_pid_changed")
        val updated = cohort.copy(loads = cohort.loads.map { if (it.id == model.loadId && it.modelId == model.modelId) it.copy(closed = true) else it })
        // Even processDeath=true is only this handle's receipt; it cannot retire the cohort.
        return publish(previous.copy(cohorts = previous.cohorts.map { if (it === cohort) updated else it }), commandId,
            fingerprint("model_closed", model.loadId, pid, processDeath)) {
            deferredModelCloses.remove(model.loadId)
            models.keys.filter { it.first == model.lease.cohortId && it.third == model.modelId }.toList().forEach(models::remove)
            Unit
        }
    }
    fun noteModelClose(model: DeviceCheckNativeModelRef, pid: Int): Boolean = synchronized(lock) {
        if (model.authorityId != authorityId || issuedModels[model] != true ||
            state?.cohorts?.none { it.lease.cohortId == model.lease.cohortId && it.process.pid == pid } != false) false
        else { deferredModelCloses[model.loadId] = pid; true }
    }
    fun noteCohortRelease(cohortId: String, pid: Int, proof: DeviceCheckNativeReleaseProof): Boolean = synchronized(lock) {
        val cohort = state?.cohorts?.firstOrNull { it.lease.cohortId == cohortId }
            ?: publishingState?.cohorts?.firstOrNull { it.lease.cohortId == cohortId } ?: return false
        if (proof == DeviceCheckNativeReleaseProof.CHILD_DEATH && pid != cohort.process.pid ||
            proof == DeviceCheckNativeReleaseProof.NO_NATIVE_START && pid != 0) false
        else { fenceNativeCleanup(cohortId); deferredCohortReleases[cohortId] = pid to proof; true }
    }
    fun recordCohortReleased(cohortId: String, pid: Int, proof: DeviceCheckNativeReleaseProof,
        commandId: String = UUID.randomUUID().toString()): DeviceCheckAuthorityOutcome<Unit> {
        if (!noteCohortRelease(cohortId, pid, proof)) return reject("release_proof_mismatch")
        val previous = synchronized(lock) { state } ?: return reject("not_loaded")
        val cohort = previous.cohorts.firstOrNull { it.lease.cohortId == cohortId } ?: return reject("cohort_missing")
        return applyCohortRelease(previous, cohort, proof.name, commandId)
    }
    /** Caller has joined the original writer; this method never reselects a current actor/source. */
    fun settleNativeOpenRelease(capture: DeviceCheckNativePrepareCapture, proof: DeviceCheckNativeReleaseProof,
        commandId: String): DeviceCheckAuthorityOutcome<DeviceCheckNativeReleaseSettlement> {
        synchronized(lock) {
            if (capture.authorityId != authorityId || capture.lease.cohortId !in nativeCleanupPending)
                return reject("native_cleanup_capture_unissued")
            unresolved?.let { return DeviceCheckAuthorityOutcome.Indeterminate(it) }
        }
        val decoded = try {
            persistence.read()?.let { DeviceCheckAuthorityCodec.decode(it) ?: throw IllegalStateException("invalid_record") }
                ?: DeviceCheckAuthorityState()
        } catch (_: Throwable) {
            publicationUnconfirmed(commandId)
            return DeviceCheckAuthorityOutcome.Indeterminate(requireNotNull(unsettledOperation()))
        }
        val cohort = synchronized(lock) {
            unresolved?.let { return DeviceCheckAuthorityOutcome.Indeterminate(it) }
            if (state != decoded) {
                publicationUnconfirmed(commandId)
                return DeviceCheckAuthorityOutcome.Indeterminate(requireNotNull(unresolved))
            }
            decoded.cohorts.firstOrNull { it.lease.cohortId == capture.lease.cohortId }
        }
        if (cohort == null) {
            if (proof != DeviceCheckNativeReleaseProof.NO_NATIVE_START) return reject("missing_cohort_requires_no_native_start")
            return acknowledgeNativeCleanupRead(capture, decoded, commandId,
                DeviceCheckNativeReleaseSettlement.ABSENT_AFTER_SETTLED_NO_NATIVE)
        }
        if (cohort.lease != capture.lease || cohort.process != capture.process || cohort.processNonce != processNonce ||
            cohort.binding != capture.source.binding || cohort.sourceAttemptId != sourceAttempt(capture.source) ||
            (capture.source as? DeviceCheckBundleRef)?.let { cohort.sourceMeasurementId != it.measurementId } == true ||
            cohort.loads.none { it.id == capture.commandId && it.spec == capture.spec }) return reject("native_cleanup_binding_mismatch")
        if (cohort.release != null) {
            return acknowledgeNativeCleanupRead(capture, decoded, commandId, DeviceCheckNativeReleaseSettlement.ALREADY_RELEASED)
        }
        val pid = if (proof == DeviceCheckNativeReleaseProof.NO_NATIVE_START) 0 else capture.process.pid
        if (!noteCohortRelease(capture.lease.cohortId, pid, proof)) return reject("native_cleanup_release_mismatch")
        return when (val result = applyCohortRelease(decoded, cohort, proof.name, commandId)) {
            is DeviceCheckAuthorityOutcome.Committed -> DeviceCheckAuthorityOutcome.Committed(
                DeviceCheckNativeReleaseSettlement.RECORDED_RELEASE, result.revision, result.commandId)
            is DeviceCheckAuthorityOutcome.Rejected -> result
            is DeviceCheckAuthorityOutcome.NotPublished -> result
            is DeviceCheckAuthorityOutcome.Indeterminate -> result
        }
    }
    private fun acknowledgeNativeCleanupRead(capture: DeviceCheckNativePrepareCapture, decoded: DeviceCheckAuthorityState,
        commandId: String, receipt: DeviceCheckNativeReleaseSettlement): DeviceCheckAuthorityOutcome<DeviceCheckNativeReleaseSettlement> = synchronized(lock) {
        unresolved?.let { return DeviceCheckAuthorityOutcome.Indeterminate(it) }
        if (state != decoded) {
            publicationUnconfirmed(commandId)
            return DeviceCheckAuthorityOutcome.Indeterminate(requireNotNull(unresolved))
        }
        nativeCleanupPending.remove(capture.lease.cohortId)
        committed(receipt, decoded, commandId)
    }
    private fun applyCohortRelease(previous: DeviceCheckAuthorityState, cohort: DeviceCheckCohortRecord, proof: String,
        commandId: String = UUID.randomUUID().toString()): DeviceCheckAuthorityOutcome<Unit> {
        val updated = cohort.copy(release = proof, loads = cohort.loads.map { it.copy(closed = true) })
        return publish(previous.copy(cohorts = previous.cohorts.map { if (it === cohort) updated else it }), commandId,
            fingerprint("cohort_closed", cohort.lease.cohortId, proof)) {
            deferredCohortReleases.remove(cohort.lease.cohortId)
            models.keys.filter { it.first == cohort.lease.cohortId }.toList().forEach(models::remove)
            permits.keys.filter { permits[it]?.lease?.cohortId == cohort.lease.cohortId }.toList().forEach(permits::remove)
            cohortOwners.remove(cohort.lease.cohortId)
            nativeCleanupPending.remove(cohort.lease.cohortId)
            Unit
        }
    }

    fun recordOwnerTransfer(source: DeviceCheckNativeLease, target: DeviceCheckNativeLease, targetOwner: DeviceCheckOwnerRef, identity: Any,
        commandId: String, hashes: Map<String, String>): DeviceCheckAuthorityOutcome<List<DeviceCheckNativeModelRef>> {
        val previous = synchronized(lock) { state } ?: return reject("not_loaded")
        if (synchronized(lock) { target.ownerId != targetOwner.ownerId || !ownerCurrent(targetOwner) }) return reject("target_owner_not_current")
        val cohort = previous.cohorts.firstOrNull { it.lease == source && it.release == null } ?: return reject("source_cohort_stale")
        if (synchronized(lock) { runtimeRetirements.containsKey(source.cohortId) || cohortOwners[source.cohortId]?.first === identity })
            return reject("transfer_lease_identity_not_fresh")
        val open = cohort.loads.filter { !it.closed }
        if (!DeviceCheckAuthorityValidation.command(commandId) || target.cohortId != source.cohortId ||
            source.ownerEpoch == Long.MAX_VALUE || target.ownerEpoch != source.ownerEpoch + 1 ||
            cohort.binding != targetOwner.source.binding || cohort.sourceAttemptId != sourceAttempt(targetOwner.source) ||
            open.any { it.modelId == null } || open.map { it.modelId }.toSet() != hashes.keys || !hashes.values.all(DeviceCheckAuthorityValidation::hash))
            return reject("transfer_binding_mismatch")
        val updated = cohort.copy(lease = target, loads = cohort.loads.map { l -> l.modelId?.let { hashes[it] }?.let { l.copy(actualHash = it) } ?: l })
        return publish(previous.copy(cohorts = previous.cohorts.map { if (it === cohort) updated else it }), commandId,
            fingerprint("transfer", source, target, hashes.toSortedMap())) {
            cohortOwners[target.cohortId] = identity to targetOwner
            models.keys.filter { it.first == source.cohortId }.toList().forEach(models::remove)
            permits.values.filter { it.lease.cohortId == source.cohortId }.forEach { it.consumed = true }
            // identity is retained by future OPEN permits; transferred handles get fresh opaque refs.
            updated.loads.filter { !it.closed }.map { l -> DeviceCheckNativeModelRef(l.id, target, l.spec,
                requireNotNull(l.modelId), requireNotNull(l.actualHash), authorityId).also {
                if (!runtimeRetirements.containsKey(target.cohortId)) models[Triple(target.cohortId, target.ownerEpoch, it.modelId)] = it
                issuedModels[it] = true
            } }
        }
    }

    fun freezeSuccess(attempt: DeviceCheckAttemptRef, accepted: Long, result: PersistedPostLoginDeviceCheckResult,
        choice: ValidatedJointRuntimeChoice, proof: DeviceCheckPairProof): DeviceCheckFrozenSuccess? {
        if (!runCatching { verifyProofOrigin(proof) }.getOrDefault(false)) return null
        synchronized(lock) {
            if (!sourceCurrent(attempt) || accepted < attempt.startedAtMs || accepted >= attempt.deadlineMs ||
                !DeviceCheckAuthorityValidation.result(result) || !validChoice(choice, attempt.binding) ||
                reserved.containsKey(attempt.attemptId) || proof.measurementId != choice.measurementId || !pairMatches(proof, choice)) return null
            state?.bundle?.let { prior ->
                if (prior.choice.measurementId == choice.measurementId && (prior.attemptId != attempt.attemptId ||
                        JointRuntimeChoiceCodec.encode(prior.choice) != JointRuntimeChoiceCodec.encode(choice))) return null
            }
        }
        if (!runCatching { proof.isCurrent() && proof.consume() }.getOrDefault(false)) return null
        val frozen = DeviceCheckFrozenSuccess(attempt, accepted, result.copy(disabledFeatures = result.disabledFeatures.toSet()),
            JointRuntimeChoiceCodec.decode(JointRuntimeChoiceCodec.encode(choice)) ?: return null, proof.primary, proof.auxiliary)
        return synchronized(lock) {
            if (!sourceCurrent(attempt) || !pairMatches(proof, choice)) null
            else frozen.also { reserved[attempt.attemptId] = it }
        }
    }
    fun commitSuccess(commandId: String, frozen: DeviceCheckFrozenSuccess): DeviceCheckAuthorityOutcome<DeviceCheckBundleRef> {
        val previous = synchronized(lock) { state } ?: return reject("not_loaded")
        val attempt = frozen.attempt
        if (!DeviceCheckAuthorityValidation.command(commandId) || reserved[attempt.attemptId] !== frozen ||
            synchronized(lock) { attempt.attemptId in interruptedAttempts } ||
            previous.latest?.id != attempt.attemptId || previous.latest.phase != "CHECKING") return reject("success_not_reserved")
        val hash = fingerprint("success", attempt.attemptId, frozen.acceptedAt, frozen.result,
            JointRuntimeChoiceCodec.encode(frozen.choice), frozen.primary.loadId, frozen.auxiliary.loadId)
        commandConflict(previous, commandId, hash)?.let { return reject(it) }
        val pairIds = listOf(frozen.primary.loadId, frozen.auxiliary.loadId)
        val bundle = DeviceCheckBundleRecord(attempt.attemptId, attempt.binding, previous.revision + 1,
            frozen.acceptedAt, frozen.result, frozen.choice, pairIds)
        val next = previous.copy(latest = previous.latest.copy(phase = "COMMITTED"), bundle = bundle,
            cohorts = previous.cohorts.map { c -> c.copy(sourceMeasurementId = if (c.loads.any { it.id in pairIds }) frozen.choice.measurementId else c.sourceMeasurementId,
                loads = c.loads.map { l ->
                if (l.id in pairIds) l.copy(verifiedAt = frozen.acceptedAt) else l }) })
        return publish(next, commandId, hash) {
            val promoted = bundleRef(bundle)
            owners.values.filter { it.source === attempt }.forEach { it.source = promoted }
            permits.values.filter { !it.consumed && it.owner.source === promoted }.forEach { it.revoked = true }
            promoted
        }
    }
    fun updateResult(bundleRef: DeviceCheckBundleRef, commandId: String, expected: PersistedPostLoginDeviceCheckResult,
        updated: PersistedPostLoginDeviceCheckResult): DeviceCheckAuthorityOutcome<DeviceCheckBundleRef> {
        val previous = synchronized(lock) { state } ?: return reject("not_loaded")
        val bundle = previous.bundle ?: return reject("bundle_missing")
        if (!sourceCurrent(bundleRef) || bundleRef.revision != bundle.revision || expected != bundle.result ||
            !DeviceCheckAuthorityValidation.command(commandId) || !DeviceCheckAuthorityValidation.result(updated) ||
            !validRecoveryDelta(expected, updated)) return reject("result_update_stale_or_not_recovery")
        val hash = fingerprint("update", bundleRef.attemptId, bundleRef.measurementId, bundleRef.revision, expected, updated)
        commandConflict(previous, commandId, hash)?.let { return reject(it) }
        val nextBundle = bundle.copy(revision = previous.revision + 1, result = updated.copy(disabledFeatures = updated.disabledFeatures.toSet()))
        return publish(previous.copy(bundle = nextBundle), commandId, hash) { this.bundleRef(nextBundle) }
    }
    fun retireChoice(choice: ValidatedJointRuntimeChoice, commandId: String): DeviceCheckAuthorityOutcome<Unit> {
        val previous = synchronized(lock) { state } ?: return reject("not_loaded")
        val bundle = previous.bundle
        if (bundle == null || bundle.choice.measurementId != choice.measurementId || previous.latest?.id != bundle.attemptId)
            return committed(Unit, previous, commandId)
        if (JointRuntimeChoiceCodec.encode(bundle.choice) != JointRuntimeChoiceCodec.encode(choice)) return reject("measurement_payload_changed")
        if (!DeviceCheckAuthorityValidation.command(commandId)) return reject("invalid_command")
        synchronized(lock) { cancelledAttempts += bundle.attemptId; interruptedAttempts += bundle.attemptId }
        return publish(previous.copy(latest = previous.latest?.copy(phase = "INTERRUPTED")), commandId,
            fingerprint("retire_choice", choice.measurementId)) { Unit }
    }
    fun reserveChoiceRetirement(choice: ValidatedJointRuntimeChoice): Boolean = synchronized(lock) {
        val bundle = state?.bundle ?: return false
        if (state?.latest?.id != bundle.attemptId || bundle.choice.measurementId != choice.measurementId ||
            JointRuntimeChoiceCodec.encode(bundle.choice) != JointRuntimeChoiceCodec.encode(choice)) false
        else { cancelledAttempts += bundle.attemptId; interruptedAttempts += bundle.attemptId; true }
    }

    fun reconcile(id: String): DeviceCheckAuthorityRead {
        val epoch = synchronized(lock) {
            if (unresolved != id) return DeviceCheckAuthorityRead.Blocked("unknown_operation", unresolved)
            uncertaintyEpoch
        }
        val text = try { persistence.read() } catch (_: Throwable) { return DeviceCheckAuthorityRead.Blocked("reconcile_read_failed", id) }
        var decoded = if (text == null) DeviceCheckAuthorityState() else DeviceCheckAuthorityCodec.decode(text)
            ?: return DeviceCheckAuthorityRead.Blocked("reconcile_invalid_record", id)
        while (true) {
            val pending = synchronized(lock) { deferredCohortReleases.toMap() to deferredModelCloses.toMap() }
            val repairedCohorts = decoded.cohorts.map { cohort ->
                val release = pending.first[cohort.lease.cohortId]?.let { (pid, proof) ->
                    proof.name.takeIf { proof == DeviceCheckNativeReleaseProof.CHILD_DEATH && pid == cohort.process.pid ||
                        proof == DeviceCheckNativeReleaseProof.NO_NATIVE_START && pid == 0 }
                } ?: cohort.release
                cohort.copy(release = release, loads = cohort.loads.map { l ->
                    if (release != null || pending.second[l.id] == cohort.process.pid) l.copy(closed = true) else l
                })
            }
            val repaired = synchronized(lock) {
                if (uncertaintyEpoch != epoch) return DeviceCheckAuthorityRead.Blocked("recovery_publication_unconfirmed", unresolved)
                mergePendingRetirements(decoded.copy(cohorts = repairedCohorts))
            }
            if (repaired != decoded) {
                if (decoded.revision == Long.MAX_VALUE) return DeviceCheckAuthorityRead.Blocked("revision_exhausted", id)
                val repairId = UUID.randomUUID().toString()
                val commands = LinkedHashMap(decoded.commands).apply { put(repairId, fingerprint("reconcile_cleanup_and_retirement", id)) }
                while (commands.size > 256) commands.remove(commands.keys.first())
                val next = repaired.copy(revision = decoded.revision + 1, commands = commands)
                val encoded = DeviceCheckAuthorityCodec.encode(next)
                    ?: return DeviceCheckAuthorityRead.Blocked("reconcile_encoding_failed", id)
                if (runCatching { persistence.publish(encoded) }.getOrDefault(DeviceCheckPublication.INDETERMINATE) != DeviceCheckPublication.COMMITTED)
                    return DeviceCheckAuthorityRead.Blocked("reconcile_cleanup_unconfirmed", id)
                decoded = next
                continue // A fault or cleanup receipt can arrive while the preceding write is in progress.
            }
            // Never clear the fence between a revoked COMMITTED read and its durable retirement repair.
            val settled = synchronized(lock) {
                if (uncertaintyEpoch != epoch) return DeviceCheckAuthorityRead.Blocked("recovery_publication_unconfirmed", unresolved)
                if (pending.first != deferredCohortReleases || pending.second != deferredModelCloses ||
                    mergePendingRetirements(decoded) != decoded) false
                else {
                    state = decoded; readFailure = null; unresolved = null; publishingState = null
                    pending.first.keys.forEach(deferredCohortReleases::remove)
                    pending.second.keys.forEach(deferredModelCloses::remove)
                    true
                }
            }
            if (settled) break
        }
        val binding = synchronized(lock) { activeBinding } ?: return DeviceCheckAuthorityRead.NeedsCheck("binding_not_loaded")
        return read(binding, synchronized(lock) { currentSessionGeneration })
    }

    private fun pairMatches(proof: DeviceCheckPairProof, choice: ValidatedJointRuntimeChoice): Boolean {
        val p = proof.primary; val a = proof.auxiliary
        if (p.authorityId != authorityId || a.authorityId != authorityId || p.lease != a.lease || p.loadId == a.loadId ||
            p.spec.role != "PRIMARY" || a.spec.role != "FASTSAM" || p.spec.delegate != choice.candidate.primary.backend.name.lowercase() ||
            a.spec.delegate != choice.candidate.auxiliary.backend.name.lowercase() || p.spec.numThreads != choice.candidate.primary.numThreads ||
            a.spec.numThreads != choice.candidate.auxiliary.numThreads) return false
        val cohort = state?.cohorts?.firstOrNull { it.lease == p.lease && it.release == null } ?: return false
        if (runtimeRetirements.containsKey(cohort.lease.cohortId)) return false
        val owner = owners[p.lease.ownerId] ?: return false
        return ownerCurrent(owner) && listOf(p, a).all { model ->
            models[Triple(model.lease.cohortId, model.lease.ownerEpoch, model.modelId)] === model &&
                cohort.loads.any { it.id == model.loadId && it.modelId == model.modelId && !it.closed && it.actualHash == model.actualRuntimeHash }
        }
    }
    private fun loadIfNeeded(): Boolean {
        synchronized(lock) { if (unresolved != null) return false; if (state != null) return true }
        val text = try { persistence.read() } catch (_: Throwable) {
            synchronized(lock) { readFailure = "storage_read_failed" }; return false
        }
        val decoded = if (text == null) DeviceCheckAuthorityState() else DeviceCheckAuthorityCodec.decode(text)
        synchronized(lock) {
            if (unresolved != null) return false
            if (decoded == null) { readFailure = "invalid_authority_record"; return false }
            state = decoded; readFailure = null
            return true
        }
    }
    private fun <T> publish(candidate: DeviceCheckAuthorityState, commandId: String, hash: String,
        expectedBinding: DeviceCheckAuthorityBinding? = null, expectedSession: Long? = null,
        value: () -> T): DeviceCheckAuthorityOutcome<T> {
        var next = synchronized(lock) {
            if (unresolved != null) return DeviceCheckAuthorityOutcome.Indeterminate(requireNotNull(unresolved))
            if (expectedBinding != null && !requestCurrent(expectedBinding, expectedSession)) return reject("binding_or_session_superseded")
            if (state?.revision == Long.MAX_VALUE) return reject("revision_exhausted")
            val commands = LinkedHashMap(candidate.commands).apply { put(commandId, hash) }
            while (commands.size > 256) commands.remove(commands.keys.first())
            mergePendingRetirements(candidate.copy(revision = requireNotNull(state).revision + 1, commands = commands))
                .also { publishingState = it }
        }
        var alreadyPublished = false
        while (true) {
            val encoded = DeviceCheckAuthorityCodec.encode(next)
            if (encoded == null) return synchronized(lock) {
                publishingState = null
                if (alreadyPublished) {
                    publicationUnconfirmed(commandId)
                    DeviceCheckAuthorityOutcome.Indeterminate(requireNotNull(unresolved))
                } else reject("state_capacity_or_encoding")
            }
            val publication = try { persistence.publish(encoded) } catch (_: Throwable) { DeviceCheckPublication.INDETERMINATE }
            synchronized(lock) {
                if (publication == DeviceCheckPublication.INDETERMINATE ||
                    alreadyPublished && publication == DeviceCheckPublication.NOT_PUBLISHED) publicationUnconfirmed(commandId)
                if (unresolved != null) return DeviceCheckAuthorityOutcome.Indeterminate(requireNotNull(unresolved))
                if (publication == DeviceCheckPublication.NOT_PUBLISHED) {
                    publishingState = null
                    return DeviceCheckAuthorityOutcome.NotPublished("publication_failed_before_rename")
                }
                state = next
                alreadyPublished = true
                val retired = mergePendingRetirements(next)
                if (retired == next) {
                    publishingState = null
                    return committed(value(), next, commandId)
                }
                if (next.revision == Long.MAX_VALUE) {
                    publicationUnconfirmed(commandId)
                    return DeviceCheckAuthorityOutcome.Indeterminate(requireNotNull(unresolved))
                }
                // A runtime fault arrived during I/O. A successful command cannot bypass its repair.
                next = retired.copy(revision = next.revision + 1)
                publishingState = next
            }
        }
    }
    /** Caller holds lock. Apply before every publication, including cleanup and fresh-FD recovery. */
    private fun mergePendingRetirements(candidate: DeviceCheckAuthorityState): DeviceCheckAuthorityState {
        val latest = candidate.latest ?: return candidate
        val interrupt = latest.id in interruptedAttempts && latest.phase in setOf("CHECKING", "COMMITTED") ||
            latest.id in cancelledAttempts && latest.phase == "COMMITTED"
        return if (interrupt) candidate.copy(latest = latest.copy(phase = "INTERRUPTED")) else candidate
    }
    private fun issuedAttempt(ref: DeviceCheckAttemptRef) = ref.authorityId == authorityId && attempts[ref.attemptId] === ref
    private fun sessionCurrent(expected: Long?): Boolean = (expected == null || expected >= 0) &&
        (if (!sessionObserved) !requireSessionObservation else currentSessionGeneration != null && currentSessionGeneration == expected)
    private fun requestCurrent(binding: DeviceCheckAuthorityBinding, expectedSession: Long?) =
        activeBinding == binding && sessionCurrent(expectedSession)
    private fun sourceCurrent(ref: DeviceCheckAuthoritySource): Boolean {
        if (unresolved != null || pendingNewAttempt != null || ref.binding != activeBinding) return false
        val current = state ?: return false
        return when (ref) {
            is DeviceCheckAttemptRef -> issuedAttempt(ref) && current.latest?.id == ref.attemptId && current.latest.phase == "CHECKING" &&
                ref.attemptId !in cancelledAttempts && !reserved.containsKey(ref.attemptId)
            is DeviceCheckBundleRef -> ref.authorityId == authorityId && issuedBundles[ref] == true &&
                current.latest?.phase == "COMMITTED" && current.latest.id == ref.attemptId && current.bundle?.attemptId == ref.attemptId &&
                ref.attemptId !in cancelledAttempts && current.bundle.choice.measurementId == ref.measurementId && current.bundle.binding == ref.binding
        }
    }
    private fun ownerCurrent(owner: DeviceCheckOwnerRef): Boolean {
        val source = owner.source
        val acceptedAttemptOwner = source is DeviceCheckAttemptRef && issuedAttempt(source) && unresolved == null && pendingNewAttempt == null &&
            source.attemptId !in cancelledAttempts && source.binding == activeBinding && state?.latest?.let { latest ->
                latest.id == source.attemptId && (latest.phase == "CHECKING" && reserved.containsKey(source.attemptId) ||
                    latest.phase == "COMMITTED" && state?.bundle?.let { it.attemptId == source.attemptId && it.binding == source.binding } == true)
            } == true
        return owner.authorityId == authorityId && owners[owner.ownerId] === owner && owner !in retiredOwners &&
            sessionCurrent(owner.sessionGeneration) &&
            (sourceCurrent(source) || acceptedAttemptOwner)
    }
    private fun sourceAttempt(source: DeviceCheckAuthoritySource) = when (source) {
        is DeviceCheckAttemptRef -> source.attemptId
        is DeviceCheckBundleRef -> source.attemptId
    }
    private fun attemptRef(record: DeviceCheckAttemptRecord) = attempts.getOrPut(record.id) {
        DeviceCheckAttemptRef(record.id, record.binding, record.started, record.deadline, authorityId)
    }
    private fun bundleRef(record: DeviceCheckBundleRecord): DeviceCheckBundleRef {
        val ref = bundles.getOrPut(record.attemptId to record.revision) {
            DeviceCheckBundleRef(record.attemptId, record.choice.measurementId, record.binding, record.revision, authorityId).also { issuedBundles[it] = true }
        }
        while (bundles.size > 32) bundles.remove(bundles.keys.first())
        return ref
    }
    private fun validChoice(choice: ValidatedJointRuntimeChoice, binding: DeviceCheckAuthorityBinding) =
        choice.isValid(binding.jointBindingHash, processors()) && (!thermalSupported || choice.evidence.thermalStatus >= 0)
    private fun validRecoveryDelta(old: PersistedPostLoginDeviceCheckResult, next: PersistedPostLoginDeviceCheckResult): Boolean {
        val added = next.disabledFeatures - old.disabledFeatures
        val removed = old.disabledFeatures - next.disabledFeatures
        val metric = PostLoginDeviceCheckFeature.METRIC_DISTANCE_GUIDANCE
        val voice = PostLoginDeviceCheckFeature.VOICE_GUIDANCE
        if (added.any { it != metric } || removed.any { it != metric && it != voice }) return false
        if (metric in added && next.metricDepthState != PostLoginMetricDepthState.EXPLICITLY_UNSUPPORTED) return false
        if (metric in removed && next.metricDepthState != PostLoginMetricDepthState.SUPPORTED) return false
        return true
    }
    private fun fingerprint(vararg fields: Any?) = DeviceCheckAuthorityValidation.digest(fields.joinToString("\u0000") {
        if (it is PersistedPostLoginDeviceCheckResult) DeviceCheckAuthorityCodec.resultText(it) else it.toString()
    })
    private fun commandConflict(s: DeviceCheckAuthorityState, id: String, hash: String): String? =
        s.commands[id]?.takeIf { it != hash }?.let { "command_payload_changed" }
    private fun <T> committed(value: T, state: DeviceCheckAuthorityState, id: String) =
        DeviceCheckAuthorityOutcome.Committed(value, state.revision, id)
    private fun reject(reason: String) = DeviceCheckAuthorityOutcome.Rejected(reason)
}
