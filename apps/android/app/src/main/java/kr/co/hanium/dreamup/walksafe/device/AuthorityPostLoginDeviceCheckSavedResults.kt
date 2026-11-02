package kr.co.hanium.dreamup.walksafe.device

import kr.co.hanium.dreamup.walksafe.inference.composite.ValidatedJointRuntimeChoice
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/** The current runtime is process-local; a decoded choice never supplies this value. */
internal data class DeviceCheckReadyRuntime(val binding: PostLoginDeviceCheckBinding,
    val choice: ValidatedJointRuntimeChoice, val proof: DeviceCheckPairProof)

/** UI-facing metadata and asynchronous publication. This adapter performs no disk/Keystore I/O. */
internal class AuthorityPostLoginDeviceCheckSavedResults(
    private val authority: DeviceCheckAuthority,
    private val bindingFor: (PostLoginDeviceCheckResultBinding) -> DeviceCheckAuthorityBinding?,
    private val readyRuntime: () -> DeviceCheckReadyRuntime?,
    private val isCurrent: (PostLoginDeviceCheckBinding) -> Boolean,
    private val sessionIsCurrent: (String, Long) -> Boolean,
    private val isIssuedProof: (DeviceCheckPairProof) -> Boolean,
    private val nowMs: () -> Long,
    private val onChanged: () -> Unit = {},
) : PostLoginDeviceCheckSavedResults {
    private class Attempt(val binding: PostLoginDeviceCheckBinding, val command: String) {
        var ref: DeviceCheckAttemptRef? = null
        var cancelled: String? = null
    }
    private class ReadRequest(val actor: String, val session: Long, val binding: DeviceCheckAuthorityBinding,
        var notifyChanged: Boolean) {
        val result = CompletableFuture<DeviceCheckAuthorityRead>()
    }
    private val lock = Any()
    private var attempt: Attempt? = null
    private var reading: ReadRequest? = null
    private var binding: DeviceCheckAuthorityBinding? = null
    private var confirmedRead: DeviceCheckAuthorityBinding? = null
    private var resultActor: String? = null
    private var boundActor: String? = null
    private var boundSession: Long? = null
    private var displayed: DeviceCheckAuthorityRead.Ready? = null
    private val writing = linkedMapOf<String, PostLoginDeviceCheckBinding>()
    private val uncertain = linkedSetOf<String>()
    private val reconciling = linkedSetOf<String>()

    override fun bindSession(actorId: String?, sessionGeneration: Long?) {
        authority.retireOwnersOutsideSession(sessionGeneration)
        val oldAttempt = synchronized(lock) {
            if (boundActor == actorId && boundSession == sessionGeneration) return
            boundActor = actorId; boundSession = sessionGeneration; displayed = null
            binding = null; confirmedRead = null; resultActor = null; reading = null
            attempt?.takeIf { it.binding.actorId != actorId || it.binding.sessionGeneration != sessionGeneration }
        }
        oldAttempt?.let { attemptCancelled(it.binding, "session_changed") }
    }

    /** Called at UI reservation, before the immutable context is passed to a worker. */
    fun registerRuntimeOwner(expected: PostLoginDeviceCheckBinding, ownerId: String): DeviceCheckOwnerRef? {
        val source = sourceFor(expected) ?: return null
        if (!isCurrent(expected)) return null
        return authority.registerOwner(ownerId, source, expected.sessionGeneration)
    }

    fun refresh(resultBinding: PostLoginDeviceCheckResultBinding): CompletionStage<DeviceCheckAuthorityRead> =
        requestRead(resultBinding, explicitRetry = false)

    /** A user retry may recover an initial read failure, but cannot clear any publication/native fence. */
    fun prepareExplicitRetry(resultBinding: PostLoginDeviceCheckResultBinding): CompletionStage<DeviceCheckAuthorityRead> =
        requestRead(resultBinding, explicitRetry = true)

    private fun requestRead(resultBinding: PostLoginDeviceCheckResultBinding, explicitRetry: Boolean):
        CompletionStage<DeviceCheckAuthorityRead> {
        val session = synchronized(lock) { boundSession.takeIf { boundActor == resultBinding.actorId } }
            ?: return CompletableFuture.completedFuture(DeviceCheckAuthorityRead.Blocked("stale_session"))
        if (!sessionCurrent(resultBinding.actorId, session)) return CompletableFuture.completedFuture(
            DeviceCheckAuthorityRead.Blocked("stale_session"))
        val captured = bindingFor(resultBinding) ?: return CompletableFuture.completedFuture(
            DeviceCheckAuthorityRead.Blocked("binding_unavailable"))
        if (!sessionCurrent(resultBinding.actorId, session)) return CompletableFuture.completedFuture(
            DeviceCheckAuthorityRead.Blocked("stale_session"))
        val requested = synchronized(lock) {
            if (boundActor != resultBinding.actorId || boundSession != session)
                return CompletableFuture.completedFuture(DeviceCheckAuthorityRead.Blocked("stale_session"))
            if (binding != captured) confirmedRead = null
            binding = captured; resultActor = resultBinding.actorId
            reading?.takeIf { it.actor == resultBinding.actorId && it.session == session && it.binding == captured }?.let {
                // The captured user continuation owns this completion instead of automatic historical restoration.
                if (explicitRetry) it.notifyChanged = false
                return it.result
            }
            if (explicitRetry) {
                val cached = authority.cachedSnapshot(captured)
                if (cached is DeviceCheckAuthorityRead.Blocked && (cached.operationId != null ||
                    cached.reason !in setOf("storage_read_failed", "not_loaded", "binding_not_loaded")))
                    return CompletableFuture.completedFuture(cached)
                if (writing.isNotEmpty() || uncertain.isNotEmpty())
                    return CompletableFuture.completedFuture(DeviceCheckAuthorityRead.Blocked("authority_work_pending"))
                if (cached !is DeviceCheckAuthorityRead.Blocked && confirmedRead == captured)
                    return CompletableFuture.completedFuture(cached)
            }
            confirmedRead = null
            ReadRequest(resultBinding.actorId, session, captured, notifyChanged = !explicitRetry).also { reading = it }
        }
        val owner = WeakReference(this)
        val future: CompletionStage<DeviceCheckAuthorityRead> = try { authority.read(captured, session) } catch (_: Throwable) {
            CompletableFuture.completedFuture(DeviceCheckAuthorityRead.Blocked("authority_read_failed"))
        }
        future.whenComplete { read, error ->
            val current = owner.get()
            if (current == null) requested.result.complete(DeviceCheckAuthorityRead.Blocked("adapter_retired")) else {
                val completed = when {
                    !current.sessionCurrent(requested.actor, requested.session) -> DeviceCheckAuthorityRead.Blocked("stale_session")
                    synchronized(current.lock) { current.binding != requested.binding } -> DeviceCheckAuthorityRead.Blocked("binding_superseded")
                    error != null || read == null -> DeviceCheckAuthorityRead.Blocked("authority_read_failed")
                    else -> read
                }
                val notify = synchronized(current.lock) {
                    val selected = current.reading === requested
                    if (selected) {
                        current.reading = null
                        if (completed is DeviceCheckAuthorityRead.Ready || completed is DeviceCheckAuthorityRead.NeedsCheck)
                            current.confirmedRead = requested.binding
                    }
                    selected && requested.notifyChanged && current.boundActor == requested.actor && current.boundSession == requested.session &&
                        current.binding == requested.binding
                } && current.sessionCurrent(requested.actor, requested.session)
                if (read is DeviceCheckAuthorityRead.Blocked && read.operationId != null)
                    current.reconcile(read.operationId, notifyChanged = notify)
                try { if (notify) current.onChanged() } finally { requested.result.complete(completed) }
            }
        }
        return requested.result
    }

    fun sourceFor(expected: PostLoginDeviceCheckBinding): DeviceCheckAuthoritySource? {
        if (!isCurrent(expected)) return null
        synchronized(lock) { attempt?.takeIf { it.binding == expected && it.cancelled == null }?.ref }
            ?.takeIf { authority.checkAttempt(it, nowMs()) }?.let { return it }
        val read = currentRead() as? DeviceCheckAuthorityRead.Ready ?: return null
        return read.bundleRef.takeIf { expected.actorId == synchronized(lock) { resultActor } }
    }

    fun historical(): DeviceCheckAuthorityRead.Ready? = currentRead() as? DeviceCheckAuthorityRead.Ready
    override fun candidatePresent() = historical() != null
    override fun mayBeginAttempt(): Boolean {
        val captured = synchronized(lock) {
            if (reading != null || writing.isNotEmpty() || uncertain.isNotEmpty()) return false
            val selected = binding ?: return false
            if (confirmedRead != selected) return false
            val actor = resultActor ?: return false
            val session = boundSession ?: return false
            if (boundActor != actor) return false
            Triple(selected, actor, session)
        }
        if (!sessionCurrent(captured.second, captured.third)) return false
        return when (authority.cachedSnapshot(captured.first)) {
            is DeviceCheckAuthorityRead.Ready, is DeviceCheckAuthorityRead.NeedsCheck -> true
            is DeviceCheckAuthorityRead.Blocked -> false
        }
    }

    override fun restore(binding: PostLoginDeviceCheckResultBinding): PersistedPostLoginDeviceCheckResult? {
        val captured = bindingFor(binding) ?: return null
        val read = authority.cachedSnapshot(captured) as? DeviceCheckAuthorityRead.Ready ?: return null
        val runtime = readyRuntime() ?: return null
        return read.result.takeIf { runtime.binding.actorId == binding.actorId &&
            sessionIsCurrent(runtime.binding.actorId, runtime.binding.sessionGeneration) && matches(runtime, read) }
    }

    override fun retainedResult(binding: PostLoginDeviceCheckResultBinding): PersistedPostLoginDeviceCheckResult? {
        val session = synchronized(lock) { boundSession.takeIf { boundActor == binding.actorId } } ?: return null
        if (!sessionCurrent(binding.actorId, session)) return null
        val captured = bindingFor(binding) ?: return null
        val read = authority.cachedSnapshot(captured) as? DeviceCheckAuthorityRead.Ready ?: return null
        return read.result.takeIf { sessionCurrent(binding.actorId, session) }
    }

    override fun runtimeReady(binding: PostLoginDeviceCheckBinding): Boolean {
        if (!isCurrent(binding)) return false
        val runtime = readyRuntime()?.takeIf { it.binding == binding } ?: return false
        if (!validRuntime(runtime)) return false
        return when (val read = currentRead()) {
            is DeviceCheckAuthorityRead.Ready -> matches(runtime, read)
            is DeviceCheckAuthorityRead.NeedsCheck -> synchronized(lock) {
                attempt?.takeIf { it.binding == binding && it.cancelled == null }?.ref
            }?.let { authority.checkAttempt(it, nowMs()) } == true
            else -> false
        }
    }

    override fun acceptsPublishedSnapshot(snapshot: PostLoginDeviceCheckSnapshot): Boolean {
        val read = historical() ?: return false
        return snapshot.passesFeatureGate && snapshot.state == read.result.state &&
            snapshot.disabledFeatures == read.result.disabledFeatures
    }

    override fun didPublish(snapshot: PostLoginDeviceCheckSnapshot) {
        val read = historical() ?: return
        if (snapshot.bindingOrNull?.let(::runtimeReady) == true && acceptsPublishedSnapshot(snapshot))
            synchronized(lock) { displayed = read }
    }

    override fun beginAttemptAsync(binding: PostLoginDeviceCheckBinding,
        resultBinding: PostLoginDeviceCheckResultBinding, startedAtMs: Long, deadlineMs: Long):
        CompletionStage<DeviceCheckAuthorityOutcome<Unit>> {
        if (binding.actorId != resultBinding.actorId || !isCurrent(binding) ||
            !sessionCurrent(binding.actorId, binding.sessionGeneration)) return rejected("stale_begin")
        val captured = bindingFor(resultBinding) ?: return rejected("binding_unavailable")
        if (!isCurrent(binding) || !sessionCurrent(binding.actorId, binding.sessionGeneration)) return rejected("stale_begin")
        val requested = Attempt(binding, UUID.randomUUID().toString())
        synchronized(lock) { this.binding = captured; resultActor = resultBinding.actorId; attempt = requested; displayed = null }
        return track(requested.command,
            binding, authority.beginAttempt(captured, requested.command, startedAtMs, deadlineMs, binding.sessionGeneration)) { current, ref ->
            val cancelled = synchronized(current.lock) {
                val reason = if (current.attempt !== requested) "superseded_begin" else requested.cancelled
                if (reason == null) requested.ref = ref
                reason
            }
            if (cancelled != null) {
                current.authority.cancelAttempt(ref, UUID.randomUUID().toString(), cancelled)
                false
            } else {
                true
            }
        }
    }

    override fun attemptCancelled(binding: PostLoginDeviceCheckBinding, reason: String) {
        val ref = synchronized(lock) {
            attempt?.takeIf { it.binding == binding }?.also { it.cancelled = reason.take(160) }?.ref
        } ?: return
        val id = UUID.randomUUID().toString()
        track(id, binding, authority.cancelAttempt(ref, id, reason.take(160))) { _, _ -> true }
    }

    override fun save(snapshot: PostLoginDeviceCheckSnapshot, binding: PostLoginDeviceCheckResultBinding,
        cameraDependentChecksDeferred: Boolean, metricDepthState: PostLoginMetricDepthState) = false

    override fun saveAsync(snapshot: PostLoginDeviceCheckSnapshot, binding: PostLoginDeviceCheckResultBinding,
        cameraDependentChecksDeferred: Boolean, metricDepthState: PostLoginMetricDepthState, acceptedAtMs: Long):
        CompletionStage<DeviceCheckAuthorityOutcome<Unit>> {
        val expected = snapshot.bindingOrNull ?: return rejected("missing_attempt")
        val ref = synchronized(lock) { attempt?.takeIf { it.binding == expected && it.cancelled == null }?.ref }
            ?: return rejected("missing_attempt")
        val captured = bindingFor(binding) ?: return rejected("binding_unavailable")
        val runtime = readyRuntime()?.takeIf { it.binding == expected } ?: return rejected("missing_actual_pair")
        if (captured != ref.binding || !isCurrent(expected) || !validRuntime(runtime)) return rejected("stale_actual_pair")
        val id = UUID.randomUUID().toString()
        return track(id, expected, authority.commitSuccess(ref, id, acceptedAtMs,
            persisted(snapshot, cameraDependentChecksDeferred, metricDepthState), runtime.choice, runtime.proof)) { _, _ -> true }
    }

    override fun updateAsync(expected: PostLoginDeviceCheckSnapshot, snapshot: PostLoginDeviceCheckSnapshot,
        binding: PostLoginDeviceCheckResultBinding, cameraDependentChecksDeferred: Boolean,
        metricDepthState: PostLoginMetricDepthState): CompletionStage<DeviceCheckAuthorityOutcome<Unit>> {
        val captured = bindingFor(binding) ?: return rejected("binding_unavailable")
        val previous = synchronized(lock) { displayed } ?: return rejected("result_not_displayed")
        val read = authority.cachedSnapshot(captured) as? DeviceCheckAuthorityRead.Ready ?: return rejected("result_not_current")
        if (previous.bundleRef !== read.bundleRef || expected.state != previous.result.state ||
            expected.disabledFeatures != previous.result.disabledFeatures || expected.bindingOrNull?.let(::runtimeReady) != true)
            return rejected("result_revision_changed")
        val id = UUID.randomUUID().toString()
        return track(id, expected.bindingOrNull!!, authority.updateResult(previous.bundleRef, id, previous.result,
            persisted(snapshot, cameraDependentChecksDeferred, metricDepthState))) { _, _ -> true }
    }

    override fun publicationUnconfirmed(binding: PostLoginDeviceCheckBinding) {
        val operations = synchronized(lock) { writing.filterValues { it == binding }.keys.toList() }
        operations.forEach { id -> authority.publicationUnconfirmed(id); reconcile(id) }
    }

    private fun currentRead(): DeviceCheckAuthorityRead = synchronized(lock) { binding }?.let(authority::cachedSnapshot)
        ?: DeviceCheckAuthorityRead.Blocked("binding_not_loaded")
    private fun sessionCurrent(actor: String, session: Long): Boolean =
        synchronized(lock) { boundActor == actor && boundSession == session } && sessionIsCurrent(actor, session)
    private fun validRuntime(runtime: DeviceCheckReadyRuntime) = runCatching {
        isIssuedProof(runtime.proof) && runtime.proof.measurementId == runtime.choice.measurementId && runtime.proof.isCurrent()
    }.getOrDefault(false)
    private fun matches(runtime: DeviceCheckReadyRuntime, read: DeviceCheckAuthorityRead.Ready) =
        runtime.choice == read.choice && runtime.proof.measurementId == read.bundleRef.measurementId && validRuntime(runtime)
    private fun persisted(snapshot: PostLoginDeviceCheckSnapshot, deferred: Boolean, depth: PostLoginMetricDepthState) =
        PersistedPostLoginDeviceCheckResult(snapshot.state, snapshot.disabledFeatures.toSet(), deferred, true, depth)

    private fun <T> track(id: String, binding: PostLoginDeviceCheckBinding, future: CompletionStage<DeviceCheckAuthorityOutcome<T>>,
        accept: (AuthorityPostLoginDeviceCheckSavedResults, T) -> Boolean): CompletionStage<DeviceCheckAuthorityOutcome<Unit>> {
        synchronized(lock) { writing[id] = binding }
        val mapped = CompletableFuture<DeviceCheckAuthorityOutcome<Unit>>()
        val owner = WeakReference(this)
        future.whenComplete { outcome, error ->
            val current = owner.get()
            if (current == null) mapped.complete(DeviceCheckAuthorityOutcome.Rejected("adapter_retired")) else {
                synchronized(current.lock) { current.writing.remove(id) }
                val result: DeviceCheckAuthorityOutcome<Unit> = when {
                    error != null -> DeviceCheckAuthorityOutcome.Indeterminate(id)
                    outcome is DeviceCheckAuthorityOutcome.Committed -> if (runCatching { accept(current, outcome.value) }.getOrDefault(false))
                        DeviceCheckAuthorityOutcome.Committed(Unit, outcome.revision, outcome.commandId)
                        else DeviceCheckAuthorityOutcome.Rejected("attempt_retired")
                    outcome is DeviceCheckAuthorityOutcome.Indeterminate -> outcome
                    outcome is DeviceCheckAuthorityOutcome.NotPublished -> outcome
                    outcome is DeviceCheckAuthorityOutcome.Rejected -> outcome
                    else -> DeviceCheckAuthorityOutcome.Indeterminate(id)
                }
                if (result is DeviceCheckAuthorityOutcome.Indeterminate) {
                    current.authority.publicationUnconfirmed(result.operationId)
                    current.reconcile(result.operationId)
                }
                mapped.complete(result)
            }
        }
        return mapped
    }

    private fun reconcile(id: String, notifyChanged: Boolean = true) {
        synchronized(lock) { uncertain += id; if (!reconciling.add(id)) return }
        val owner = WeakReference(this)
        authority.reconcileAfterSettled(id).whenComplete { read, error -> owner.get()?.let { current ->
            synchronized(current.lock) {
                current.reconciling.remove(id)
                if (error == null && !(read is DeviceCheckAuthorityRead.Blocked && read.operationId != null))
                    current.uncertain.remove(id)
            }
            if (notifyChanged) current.onChanged()
        } }
    }
    private fun rejected(reason: String): CompletionStage<DeviceCheckAuthorityOutcome<Unit>> =
        CompletableFuture.completedFuture(DeviceCheckAuthorityOutcome.Rejected(reason))
}
