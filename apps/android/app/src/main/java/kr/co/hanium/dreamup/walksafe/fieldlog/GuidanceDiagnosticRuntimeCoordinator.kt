package kr.co.hanium.dreamup.walksafe.fieldlog

import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kr.co.hanium.dreamup.walksafe.network.CancellableNetworkCall
import kr.co.hanium.dreamup.walksafe.network.GatewayFieldSession
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionScope
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionVerificationState

/** Main publishes immutable authority; this worker never reads an Activity or Android service. */
internal class GuidanceDiagnosticRuntimeCoordinator(
    private val debugUserApproved: Boolean,
    private val buildId: String,
    private val queueFactory: () -> GuidanceDiagnosticQueue,
    private val transport: GuidanceDiagnosticTransport,
    private val preference: (GatewayFieldSession) -> CancellableNetworkCall<GuidanceDiagnosticPreference>,
    private val authorizeIfAbsent: (GatewayFieldSession) -> CancellableNetworkCall<GuidanceDiagnosticPreference>,
    private val setPreference: (GatewayFieldSession, Boolean, Long) -> CancellableNetworkCall<GuidanceDiagnosticPreference>,
    private val wallTimeMs: () -> Long,
    private val elapsedRealtimeMs: () -> Long,
    private val onState: (State) -> Unit = {},
    private val worker: Executor = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, LinkedBlockingQueue(),
        { Thread(it, "walksafe-guidance-diagnostic").apply { isDaemon = true } }).apply { allowCoreThreadTimeOut(true) },
    private val ownsWorker: Boolean = true,
    private val preferenceTtlMs: Long = 60_000,
) : AutoCloseable {
    data class Authority(
        val session: GatewayFieldSession?,
        val gatewayGeneration: Long,
        val accountGeneration: Long?,
        val admitted: Boolean,
        val networkAllowed: Boolean,
        val localEnabled: Boolean,
        val deletionBlocked: Boolean = false,
        val storageBlocked: Boolean = false,
    )
    data class State(val enabled: Boolean, val authorized: Boolean, val pending: Int, val error: String?, val sessionId: String?,
        val requestedLocalEnabled: Boolean = false)
    private data class Scope(val actor: String, val generation: Long)
    private data class Record(val scope: Scope, val name: String, val fields: Map<String, Any>, val wall: Long, val elapsed: Long)
    private val lock = Any()
    private var authority = Authority(null, 0, null, false, false, false)
    private var epoch = 0L
    private var workVersion = 0L
    private var scheduled = false
    private var closed = false
    private var deletionFuture: CompletableFuture<Boolean>? = null
    private var needsRetire = false
    private var newSession = false
    private var sessionId: String? = null
    private var desiredPreference: Boolean? = null
    private var verifiedPreference: GuidanceDiagnosticPreference? = null
    private var knownPreference: GuidanceDiagnosticPreference? = null
    private var preferenceVerifiedAtMs: Long? = null
    private var revalidateWithGet = false
    private var preferenceConflict = false
    private var authorizationRejected = false
    private var trustedScope: Scope? = null
    private var deferredOffScope: Scope? = null
    private var pendingEnableScope: Scope? = null
    private var binding: GuidanceDiagnosticBinding? = null
    private var activeCall: CancellableNetworkCall<*>? = null
    private var lastDrainAtMs: Long? = null // worker only; server permits 12 batches/minute
    private var queued = 0
    private var error: String? = null
    private val records = ArrayDeque<Record>()
    private val purgeScopes = linkedSetOf<Scope>()
    private var queue: GuidanceDiagnosticQueue? = null // worker only
    private var log: GuidanceDiagnosticLog? = null // worker only
    private var writingRecord: Record? = null // worker only; retain original observation time

    init { require(Regex("[a-f0-9]{7,64}").matches(buildId)); require(preferenceTtlMs in 1..60_000) }

    private fun scope(value: Authority) = scope(value.session, value.accountGeneration)
    private fun scope(session: GatewayFieldSession?, generation: Long?): Scope? {
        if (session == null || generation == null || generation <= 0 || session.backendAccountGeneration != generation) return null
        return Scope(session.actorId, generation)
    }
    private fun requestOwner(value: Authority) = scope(value).takeIf { value.session?.sessionScope == GatewaySessionScope.GENERAL }
    private fun admitted(value: Authority) = debugUserApproved && !closed && !authorizationRejected && value.admitted &&
        !value.deletionBlocked && !value.storageBlocked && scope(value) != null &&
        value.session?.sessionScope == GatewaySessionScope.GENERAL &&
        value.session.verificationState == GatewaySessionVerificationState.VERIFIED
    private fun enabled(value: Authority) = admitted(value) && value.localEnabled && !preferenceConflict &&
        synchronized(lock) { scope(value) !in purgeScopes }

    fun state(): State = synchronized(lock) { State(enabled(authority), binding != null, queued + records.size, error, sessionId, authority.localEnabled) }

    /** Main retains persisted OFF while this current-owner override is only an explicit request. */
    fun authorityLocalEnabled(session: GatewayFieldSession?, accountGeneration: Long?, persistedEnabled: Boolean): Boolean =
        synchronized(lock) {
            persistedEnabled || (!closed && session?.sessionScope == GatewaySessionScope.GENERAL &&
                pendingEnableScope != null && pendingEnableScope == scope(session, accountGeneration))
        }

    /** Main calls after fresh authority publication, then persists ON only when this proof succeeds. */
    fun consumePendingEnableCommit(session: GatewayFieldSession?, gatewayGeneration: Long, accountGeneration: Long?): Boolean {
        val now = try { elapsedRealtimeMs() } catch (_: Exception) { return false }
        return synchronized(lock) {
            val owner = scope(session, accountGeneration)
            val current = binding
            val checkedAt = preferenceVerifiedAtMs
            if (owner == null || pendingEnableScope != owner || current == null || !enabled(authority) ||
                current.session !== session || authority.session !== session ||
                current.gatewayGeneration != gatewayGeneration || authority.gatewayGeneration != gatewayGeneration ||
                current.accountGeneration != accountGeneration || owner in purgeScopes || deferredOffScope == owner ||
                verifiedPreference?.enabled != true || checkedAt == null || now < checkedAt || now - checkedAt >= preferenceTtlMs) false
            else { pendingEnableScope = null; true }
        }
    }

    /** No queue/log lock or filesystem operation occurs on the publishing thread. */
    fun publishAuthority(value: Authority) {
        val cancel = synchronized(lock) {
            if (closed || value == authority) return
            val old = authority
            val changedScope = scope(old) != scope(value)
            val retire = changedScope || old.session !== value.session || old.gatewayGeneration != value.gatewayGeneration ||
                old.admitted != value.admitted || old.localEnabled != value.localEnabled ||
                old.deletionBlocked != value.deletionBlocked || old.storageBlocked != value.storageBlocked
            if (changedScope || value.deletionBlocked || (old.localEnabled && !value.localEnabled)) {
                if (debugUserApproved) {
                    scope(old)?.takeIf { it == trustedScope }?.let(purgeScopes::add)
                    if (value.deletionBlocked) scope(value)?.let(purgeScopes::add)
                }
                records.clear()
                sessionId = null
                if (changedScope) {
                    desiredPreference = null; knownPreference = null; preferenceConflict = false
                    pendingEnableScope = null; deferredOffScope = null; trustedScope = null
                }
            }
            if (value.deletionBlocked || !value.localEnabled) pendingEnableScope = null
            if (old.localEnabled && !value.localEnabled) desiredPreference = false
            if (!value.localEnabled) requestOwner(value)?.let { deferredOffScope = it }
            val recoveredNetwork = !old.networkAllowed && value.networkAllowed
            if (retire || recoveredNetwork) {
                binding = null; verifiedPreference = null; preferenceVerifiedAtMs = null
                revalidateWithGet = knownPreference != null
                needsRetire = true; queued = 0
            }
            if (changedScope || old.session !== value.session || old.gatewayGeneration != value.gatewayGeneration ||
                (!old.admitted && value.admitted)) authorizationRejected = false
            if (admitted(value)) {
                trustedScope = scope(value)
                if (deferredOffScope == trustedScope) trustedScope?.let(purgeScopes::add)
            }
            authority = value
            epoch++
            workVersion++
            activeCall.also { activeCall = null }
        }
        runCatching { cancel?.cancel() }
        requestWork()
    }

    /** Failed Main snapshot acquisition closes authority atomically until a fresh publication. */
    fun suspendAuthority() {
        val cancel = synchronized(lock) {
            if (closed) return
            authority = authority.copy(admitted = false, networkAllowed = false, storageBlocked = true)
            binding = null; verifiedPreference = null; preferenceVerifiedAtMs = null
            revalidateWithGet = knownPreference != null
            needsRetire = true; epoch++; workVersion++
            activeCall.also { activeCall = null }
        }
        runCatching { cancel?.cancel() }
        requestWork()
    }

    /** A new guidance UUID; Gateway renewal by itself keeps this UUID and old queued batches. */
    fun startGuidanceSession() {
        val cancel = synchronized(lock) {
            if (!enabled(authority)) return
            newSession = true
            verifiedPreference = null
            preferenceVerifiedAtMs = null
            revalidateWithGet = knownPreference != null
            binding = null
            needsRetire = true
            epoch++
            workVersion++
            activeCall.also { activeCall = null }
        }
        runCatching { cancel?.cancel() }
        requestWork()
        record("navigation_start")
    }

    /** Only this explicit action may PUT true; automatic admission uses POST-if-absent. */
    fun setLocalEnabled(value: Boolean, explicitUserAction: Boolean = false) {
        val change = synchronized(lock) {
            if (closed || (value && !explicitUserAction)) return
            val owner = requestOwner(authority)
            if (value && (owner == null || authority.deletionBlocked)) { error = "actor_unavailable"; return }
            if (value) {
                pendingEnableScope = owner
                if (!authority.localEnabled) deferredOffScope = owner
            } else {
                pendingEnableScope = null; deferredOffScope = owner
                if (debugUserApproved && owner != null && owner == trustedScope) purgeScopes.add(owner)
            }
            desiredPreference = value
            binding = null; needsRetire = true; epoch++
            Pair(authority.copy(localEnabled = value), activeCall.also { activeCall = null })
        }
        runCatching { change.second?.cancel() }
        publishAuthority(change.first)
        tick()
    }

    fun record(name: String, fields: Map<String, Any?> = emptyMap()): Boolean {
        val safe = GuidanceDiagnosticPolicy.sanitize(name, fields) ?: return false
        val recordingScope = synchronized(lock) {
            if (!enabled(authority) || knownPreference?.enabled == false || records.size >= 100) return false
            requireNotNull(scope(authority))
        }
        val observed = try { Pair(wallTimeMs(), elapsedRealtimeMs()) } catch (_: Exception) { return false }
        if (observed.first < 0 || observed.second < 0) return false
        synchronized(lock) {
            if (scope(authority) != recordingScope || !enabled(authority) || knownPreference?.enabled == false || records.size >= 100) return false
            records.addLast(Record(recordingScope, name, safe, observed.first, observed.second))
            workVersion++
        }
        requestWork()
        return true
    }
    fun tick() { synchronized(lock) { if (closed) return; workVersion++ }; requestWork() }

    private fun requestWork() {
        synchronized(lock) { if (scheduled) return; scheduled = true }
        try { worker.execute(::pump) }
        catch (_: Exception) { synchronized(lock) { scheduled = false; error = "worker_unavailable" }; notifyState() }
    }

    private fun pump() {
        val version = synchronized(lock) { workVersion }
        try {
            val initial = synchronized(lock) { authority }
            if (!initial.storageBlocked) {
                val retired = synchronized(lock) { needsRetire.also { needsRetire = false } }
                if (retired) log?.retireBinding()
                val purges = synchronized(lock) { purgeScopes.toList() }
                for (retiredScope in purges) {
                    if (synchronized(lock) { authority.storageBlocked }) break
                    try {
                        val owner = MessageDigest.getInstance("SHA-256")
                            .digest((retiredScope.actor + "\n" + retiredScope.generation).toByteArray(Charsets.UTF_8))
                            .joinToString("") { "%02x".format(it) }
                        store().purge(owner)
                        synchronized(lock) {
                            purgeScopes.remove(retiredScope)
                            if (deferredOffScope == retiredScope) deferredOffScope = null
                        }
                    } catch (_: Exception) { synchronized(lock) { error = "storage_unavailable" } }
                }
                if (admitted(initial)) {
                    reconcilePreference()
                    flushRecords()
                    val now = elapsedRealtimeMs()
                    val previous = lastDrainAtMs
                    if (previous == null || (now >= previous && now - previous >= 5_500)) {
                        if (log?.drainIfAdmitted() == true) lastDrainAtMs = now
                    }
                }
            }
        } catch (_: Exception) { synchronized(lock) { error = "runtime_unavailable" } }
        finally {
            log?.state()?.let { snapshot -> synchronized(lock) { queued = snapshot.queued; if (snapshot.outcome == "storage_unavailable") error = snapshot.outcome } }
            notifyState()
            val again = synchronized(lock) { scheduled = false; !closed && version != workVersion }
            if (again) requestWork()
        }
    }

    private fun reconcilePreference() {
        val now = elapsedRealtimeMs()
        val captured = synchronized(lock) {
            val checkedAt = preferenceVerifiedAtMs
            if (verifiedPreference != null && (checkedAt == null || now < checkedAt || now - checkedAt >= preferenceTtlMs)) {
                binding = null; verifiedPreference = null; revalidateWithGet = knownPreference != null
                needsRetire = true
            }
            Triple(authority, epoch, desiredPreference)
        }
        val value = captured.first
        if (!admitted(value)) return
        if (synchronized(lock) { needsRetire.also { needsRetire = false } }) log?.retireBinding()
        val session = requireNotNull(value.session)
        var result = synchronized(lock) { verifiedPreference }
        if (value.networkAllowed && (result == null || captured.third != null)) {
            val get = synchronized(lock) { revalidateWithGet }
            val call = if (get || captured.third != null || !value.localEnabled) preference(session) else authorizeIfAbsent(session)
            result = executeCall(captured.second, call) ?: return
            if (!validPreference(result)) {
                synchronized(lock) { if (epoch == captured.second) error = "invalid_preference" }
                return
            }
            val requested = captured.third ?: if (!value.localEnabled && result.present && result.enabled) false else null
            if (requested != null) result = executeCall(captured.second, setPreference(session, requested, result.revision)) ?: return
            if (!validPreference(result)) {
                synchronized(lock) { if (epoch == captured.second) error = "invalid_preference" }
                return
            }
            synchronized(lock) {
                if (epoch != captured.second) return
                val prior = knownPreference
                if (!result.enabled || (prior?.present == true && prior.revision != result.revision)) {
                    scope(value)?.let(purgeScopes::add)
                    records.clear(); binding = null; needsRetire = true; queued = 0
                    preferenceConflict = result.enabled
                    workVersion++
                }
                verifiedPreference = result; knownPreference = result
                preferenceVerifiedAtMs = elapsedRealtimeMs(); revalidateWithGet = false; desiredPreference = null
                error = if (result.enabled) null else "server_disabled"
            }
        }
        if (result?.present != true || !result.enabled || !value.localEnabled) return
        val candidate = synchronized(lock) {
            if (epoch != captured.second || !admitted(authority) || !authority.localEnabled || scope(authority) in purgeScopes) return
            if (binding != null) return
            if (newSession || sessionId == null) { sessionId = UUID.randomUUID().toString(); newSession = false }
            GuidanceDiagnosticBinding(session, value.gatewayGeneration, requireNotNull(value.accountGeneration),
                result.revision, requireNotNull(sessionId), buildId)
        }
        val discarded = try { store().bindPreference(candidate.ownerKey, candidate.preferenceRevision) }
        catch (_: Exception) {
            synchronized(lock) {
                if (epoch == captured.second) {
                    scope(value)?.let(purgeScopes::add); preferenceConflict = true; error = "storage_unavailable"
                }
            }
            return
        }
        synchronized(lock) {
            if (epoch != captured.second || !admitted(authority) || !authority.localEnabled || scope(authority) in purgeScopes) return
            if (discarded) queued = 0
            preferenceConflict = false
            binding = candidate
        }
    }

    private fun validPreference(value: GuidanceDiagnosticPreference) = value.revision >= 0 &&
        (!value.present || value.revision > 0) && (value.present || !value.enabled)

    private fun <T> executeCall(expected: Long, call: CancellableNetworkCall<T>, uploading: Boolean = false): T? {
        synchronized(lock) {
            if (epoch != expected || !admitted(authority) || !authority.networkAllowed) { call.cancel(); return null }
            activeCall = call
        }
        return try {
            val result = call.execute()
            synchronized(lock) { if (epoch == expected && !call.isCancelled() && admitted(authority) && authority.networkAllowed) result else null }
        } catch (failure: Exception) {
            var recheck = false
            synchronized(lock) {
                if (epoch == expected) {
                    val rejected = (failure as? GuidanceDiagnosticHttpFailure)?.status
                    if (uploading && rejected in setOf(401, 403, 409)) {
                        scope(authority)?.let(purgeScopes::add)
                        records.clear(); binding = null; verifiedPreference = null; preferenceVerifiedAtMs = null
                        revalidateWithGet = true; preferenceConflict = true; needsRetire = true; queued = 0
                        authorizationRejected = rejected == 401 || rejected == 403
                        epoch++; workVersion++; recheck = true
                        error = if (authorizationRejected) "authorization_lost" else "preference_changed"
                    } else error = "network_failed"
                }
            }
            if (recheck) requestWork()
            null
        }
        finally { synchronized(lock) { if (activeCall === call) activeCall = null } }
    }

    private fun store(): GuidanceDiagnosticQueue = queue ?: queueFactory().also { queue = it }

    private fun flushRecords() {
        val current = synchronized(lock) { binding } ?: return
        if (log == null) {
            val store = store()
            val admission = object : GuidanceDiagnosticAdmission {
                override fun currentBinding() = synchronized(lock) { binding.takeIf { enabled(authority) } }
                override fun sameBindingCurrent(binding: GuidanceDiagnosticBinding) = synchronized(lock) {
                    this@GuidanceDiagnosticRuntimeCoordinator.binding === binding && enabled(authority)
                }
                override fun uploadAllowed(binding: GuidanceDiagnosticBinding) = synchronized(lock) {
                    sameBindingCurrent(binding) && authority.networkAllowed
                }
            }
            val trackedTransport = object : GuidanceDiagnosticTransport {
                override fun upload(binding: GuidanceDiagnosticBinding, batch: GuidanceDiagnosticBatch): CancellableNetworkCall<GuidanceDiagnosticAck> {
                    val expected = synchronized(lock) { epoch }
                    val delegate = transport.upload(binding, batch)
                    return CancellableNetworkCall(executeBlock = {
                        executeCall(expected, delegate, uploading = true) ?: error("diagnostic call retired")
                    }, cancelBlock = delegate::cancel, rejectLateResult = true)
                }
            }
            log = GuidanceDiagnosticLog(admission, store, trackedTransport, Executor { task ->
                worker.execute {
                    task.run()
                    log?.state()?.let { synchronized(lock) {
                        queued = it.queued
                        if (error == null) error = it.outcome?.takeUnless { it in setOf("uploaded", "binding_retired", "admission_retired") }
                    } }
                    notifyState()
                }
            }, { writingRecord?.wall ?: wallTimeMs() }, { writingRecord?.elapsed ?: elapsedRealtimeMs() })
        }
        while (true) {
            val record = synchronized(lock) {
                if (binding !== current || !enabled(authority) || records.isEmpty()) null else records.removeFirst()
            } ?: break
            if (record.scope == scope(synchronized(lock) { authority })) {
                writingRecord = record
                try { log?.record(record.name, record.fields) } finally { writingRecord = null }
            }
        }
    }

    private fun notifyState() { runCatching { onState(state()) } }

    /** Root's privacy worker waits on this future; Main never waits. Also valid after close(). */
    fun purgeForAccountDeletion(purgeStore: () -> Boolean): CompletableFuture<Boolean> {
        val request = synchronized(lock) {
            deletionFuture?.let { return it }
            val future = CompletableFuture<Boolean>().also { deletionFuture = it }
            closed = true; pendingEnableScope = null; binding = null; records.clear(); queued = 0; epoch++; workVersion++
            Pair(future, activeCall.also { activeCall = null })
        }
        runCatching { request.second?.cancel() }
        try {
            worker.execute {
                val purged = runCatching { log?.blockForDeletion(); purgeStore() }.getOrDefault(false)
                request.first.complete(purged)
                notifyState()
                if (ownsWorker) (worker as? ThreadPoolExecutor)?.shutdown()
            }
        } catch (_: Exception) { request.first.complete(false) }
        return request.first
    }

    override fun close() {
        val cancel = synchronized(lock) {
            if (closed) return
            closed = true
            pendingEnableScope = null
            binding = null
            records.clear()
            needsRetire = true
            epoch++
            workVersion++
            activeCall.also { activeCall = null }
        }
        runCatching { cancel?.cancel() }
        requestWork()
    }
}
