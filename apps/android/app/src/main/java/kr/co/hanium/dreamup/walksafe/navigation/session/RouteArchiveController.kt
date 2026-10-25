package kr.co.hanium.dreamup.walksafe.navigation.session

import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kr.co.hanium.dreamup.walksafe.AccountDeletionResetCoordinator
import kr.co.hanium.dreamup.walksafe.navigation.DestinationCoordinateEvidence
import kr.co.hanium.dreamup.walksafe.navigation.EncryptedRouteSnapshotStore
import kr.co.hanium.dreamup.walksafe.navigation.ROUTE_SNAPSHOT_TTL_MS
import kr.co.hanium.dreamup.walksafe.navigation.RoutePoint
import kr.co.hanium.dreamup.walksafe.navigation.RouteSnapshotIoLane
import kr.co.hanium.dreamup.walksafe.navigation.RouteSnapshotOwner
import kr.co.hanium.dreamup.walksafe.navigation.RouteSnapshotOwnershipIoResult
import kr.co.hanium.dreamup.walksafe.navigation.RouteSnapshotScope
import kr.co.hanium.dreamup.walksafe.navigation.StoredRouteSnapshot
import kr.co.hanium.dreamup.walksafe.navigation.WalkingRoute
import kr.co.hanium.dreamup.walksafe.network.GatewayFieldSession
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionProcessSnapshot
import kr.co.hanium.dreamup.walksafe.session.AccountDeletionActivityLease
import kr.co.hanium.dreamup.walksafe.session.PermissionSessionPolicy
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import kr.co.hanium.dreamup.walksafe.session.WalkSessionLifecycle
import kr.co.hanium.dreamup.walksafe.session.WalkSessionSnapshot
import kr.co.hanium.dreamup.walksafe.session.WalkSessionState
import kr.co.hanium.dreamup.walksafe.session.WalkSessionTransition

/** Owns route archive admission, preparation, account binding and expiry. Construction is inert. */
internal class RouteArchiveController(
    private val lane: RouteSnapshotIoLane,
    private val facts: Facts,
    private val presentation: RoutePresentation,
    private val callbacks: RouteCallbackScheduler,
    private val onPrepared: () -> Unit,
    private val wallTimeMs: () -> Long,
) {
    interface Facts {
        fun gatewaySnapshot(): GatewaySessionProcessSnapshot
        fun lifecycleOrNull(): WalkSessionLifecycle?
        fun activityCurrent(): Boolean
        fun deletionBlocked(): Boolean
        fun deletionCoordinator(): AccountDeletionResetCoordinator
        fun deletionActivityLease(): AccountDeletionActivityLease
        fun deletionIntentToken(): Any
        fun developmentBypass(): Boolean
        fun routeActive(): Boolean
    }
    @Volatile private lateinit var routeSnapshotStore: EncryptedRouteSnapshotStore
    private lateinit var routeSnapshotIoLease: RouteSnapshotIoLane.Lease
    @Volatile private var routeSnapshotPreparationPending = true
    private val routeSnapshotResetParticipant = AtomicReference<RouteSnapshotAccountCleanup?>()
    @Volatile private var routeSnapshotPurgeFailed = false
    @Volatile private var routeSnapshotAccountOwner: RouteSnapshotOwner? = null
    @Volatile private var routeSnapshotAccountBindingEstablished = false
    @Volatile private var routeSnapshotWalkScope: RouteSnapshotScope? = null
    @Volatile private var routeSnapshotOwnershipGateway: GatewaySessionProcessSnapshot? = null
    @Volatile private var routeSnapshotOwnershipEpoch: WalkRuntimeEpoch? = null
    private val routeSnapshotExpiryRunnable = AtomicReference<Runnable?>()

    val preparationPending: Boolean get() = routeSnapshotPreparationPending
    val purgeFailed: Boolean get() = routeSnapshotPurgeFailed
    val walkScope: RouteSnapshotScope? get() = routeSnapshotWalkScope
    val leaseOrNull: RouteSnapshotIoLane.Lease? get() = if (::routeSnapshotIoLease.isInitialized) routeSnapshotIoLease else null
    val resetParticipant: RouteSnapshotAccountCleanup? get() = routeSnapshotResetParticipant.get()
    fun markArchiveStored() { routeSnapshotPurgeFailed = false }
    fun detach() { if (::routeSnapshotIoLease.isInitialized) lane.detach(routeSnapshotIoLease) }

    fun initialize(factory: () -> EncryptedRouteSnapshotStore) {
        val (lease, initialization) = lane.attach { factory().also { routeSnapshotStore = it } }
        routeSnapshotIoLease = lease
        val operation = lease.operation.get()
        initialization.thenAccept { result ->
            callbacks.post(Runnable {
                val committed = lane.commit(lease, operation, { routeSnapshotActivityIsCurrent(lease) }) {
                    routeSnapshotPurgeFailed = result?.purged != true
                    routeSnapshotPreparationPending = false
                }
                if (!committed) return@Runnable
                result?.metadata?.let {
                    scheduleEncryptedRouteSnapshotExpiry(it.scope, it.expiresAtEpochMs, expectedOperation = operation)
                }
            })
        }
    }

    private fun post(action: () -> Unit) = callbacks.post(Runnable { action() })

    class RouteSnapshotAccountCleanup(
        val lease: RouteSnapshotIoLane.Lease, val operation: Any,
        val purge: CompletableFuture<Boolean>, val coordinator: AccountDeletionResetCoordinator,
        val activityLease: AccountDeletionActivityLease, val accountIntentToken: Any,
    ) {
        val verified = AtomicBoolean()
    }

    internal data class RouteSnapshotScopePurgeSubmission(
        val operation: Any, val future: CompletableFuture<Boolean>,
    )

    /** The response caller and direct tests consume this exact original save/load body. */
    internal fun routeSnapshotResponseArchive(
        store: EncryptedRouteSnapshotStore, scope: RouteSnapshotScope,
        route: WalkingRoute, destination: RoutePoint,
        destinationCoordinateEvidence: DestinationCoordinateEvidence?,
    ): StoredRouteSnapshot? = runCatching {
        val nowEpochMs = wallTimeMs()
        val saved = store.saveFirstRoute(scope = scope, route = route, destination = destination,
            nowEpochMs = nowEpochMs,
            destinationCoordinateEvidence = destinationCoordinateEvidence)
        if (!saved) null else store.load(scope, nowEpochMs = nowEpochMs)
    }.getOrNull()?.takeIf { it.walkSessionId == scope.walkSessionId }

    internal fun routeSnapshotNavigationPermissionCurrent(
        owner: PermissionSessionPolicy, actorId: String,
    ): Boolean = owner.isAuthenticatedFor(actorId)

    internal fun captureRouteSnapshotResponseRoute(route: WalkingRoute): WalkingRoute =
        route.copy(polyline = route.polyline.toList(), guidePoints = route.guidePoints.toList(),
            steps = route.steps.map { it.copy(points = it.points.toList()) })

    internal fun submitRouteSnapshotResponseStorage(
        lane: RouteSnapshotIoLane, lease: RouteSnapshotIoLane.Lease,
        current: () -> Boolean, scope: RouteSnapshotScope, route: WalkingRoute,
        destination: RoutePoint, evidence: DestinationCoordinateEvidence?,
    ): CompletableFuture<StoredRouteSnapshot?> {
        // Copy only immutable input lists here. JSON, validation, clocks and crypto stay on I/O.
        val captured = captureRouteSnapshotResponseRoute(route)
        val provenance = evidence?.copy(candidates = evidence.candidates.toList())
        return lane.submit<StoredRouteSnapshot?>(lease, null, current) { store ->
            routeSnapshotResponseArchive(store, scope, captured, destination, provenance)
        }
    }

    internal fun submitRouteSnapshotOwnershipStorage(
        lane: RouteSnapshotIoLane, lease: RouteSnapshotIoLane.Lease,
        owner: RouteSnapshotOwner?, scope: RouteSnapshotScope?, endingWalk: String?, current: () -> Boolean,
        onAdmission: () -> Unit = {},
    ): CompletableFuture<RouteSnapshotOwnershipIoResult?> =
        lane.submit<RouteSnapshotOwnershipIoResult?>(lease, null, current, mandatoryCleanup = true, onAdmission = onAdmission) { store ->
            // A delayed old end cannot release a newly claimed walk in this same Store.
            if (endingWalk != null && lease.walkScope?.walkSessionId == endingWalk) {
                val ended = runCatching { store.endWalk() }
                lease.purgeFailed = ended.getOrDefault(false).not()
                // Normal false still releases the domain lease. A thrown storage call does not
                // prove release; preserve the exact old scope and block a new claim.
                if (ended.isSuccess) lease.walkScope = null
            }
            if (!current()) return@submit null
            if (!lease.bound || lease.boundOwner != owner) {
                val bound = runCatching { store.bindAccount(owner) }.getOrDefault(false)
                lease.boundOwner = owner
                lease.bound = bound
                lease.walkScope = null
                lease.purgeFailed = !bound
            }
            val begun = !lease.purgeFailed && (scope == null || store.beginWalk(scope))
            if (begun) lease.walkScope = scope
            RouteSnapshotOwnershipIoResult(begun, if (begun) scope else null,
                if (begun && owner != null) store.expiryMetadata(wallTimeMs())?.takeIf { it.scope.owner == owner } else null)
        }

    internal fun submitRouteSnapshotScopePurge(
        lane: RouteSnapshotIoLane, lease: RouteSnapshotIoLane.Lease, scope: RouteSnapshotScope,
        onAdmission: () -> Unit = {},
    ): CompletableFuture<Boolean> = lane.submit(lease, false, { true }, mandatoryCleanup = true, onAdmission = onAdmission) { store ->
        runCatching { store.clearWalk(scope) }.getOrDefault(false).also { lease.purgeFailed = !it }
    }

    /** The expiry claim and scoped purge admission cannot supersede a newer preparation. */
    internal fun submitRouteSnapshotExpiryPurge(
        lane: RouteSnapshotIoLane, lease: RouteSnapshotIoLane.Lease, expiryOperation: Any,
        scope: RouteSnapshotScope, expiryClaimIsCurrent: () -> Boolean,
    ): RouteSnapshotScopePurgeSubmission? {
        var submission: RouteSnapshotScopePurgeSubmission? = null
        val admitted = lane.commit(lease, expiryOperation, expiryClaimIsCurrent) {
            lateinit var operation: Any
            val future = submitRouteSnapshotScopePurge(lane, lease, scope,
                onAdmission = { operation = beginRouteSnapshotPreparation(lane, lease) })
            submission = RouteSnapshotScopePurgeSubmission(operation, future)
        }
        return submission.takeIf { admitted }
    }

    internal fun submitRouteSnapshotAccountPurgeStorage(
        lane: RouteSnapshotIoLane, lease: RouteSnapshotIoLane.Lease, authorityIsCurrent: () -> Boolean,
        onAdmission: () -> Unit = {},
    ): CompletableFuture<Boolean> = lane.submit(lease, false, authorityIsCurrent, mandatoryCleanup = true, onAdmission = onAdmission) { store ->
        if (!authorityIsCurrent()) return@submit false
        val purged = runCatching { store.bindAccount(null) }.getOrDefault(false)
        lease.boundOwner = null
        lease.bound = purged
        lease.walkScope = null
        lease.purgeFailed = !purged
        purged
    }

    internal fun beginRouteSnapshotPreparation(lane: RouteSnapshotIoLane, lease: RouteSnapshotIoLane.Lease): Any =
        lane.invalidate(lease) { routeSnapshotPreparationPending = true }

    internal fun publishRouteSnapshotAccountPurgeResult(
        lane: RouteSnapshotIoLane, lease: RouteSnapshotIoLane.Lease, operation: Any,
        purged: Boolean, current: () -> Boolean, markVerified: () -> Unit,
    ): Boolean {
        return lane.commit(lease, operation, current) {
            markVerified()
            routeSnapshotPreparationPending = false
            routeSnapshotPurgeFailed = !purged
            routeSnapshotAccountBindingEstablished = purged
        }
    }

    fun routeSnapshotPurgeFenceAllowsRoute(): Boolean {
        // A failed cleanup remains recorded and retried; test guidance can run in memory.
        if (facts.developmentBypass()) return true
        if (routeSnapshotPreparationPending) {
            presentation.navigationStatus("navigation=route_snapshot_preparing")
            return false
        }
        if (!routeSnapshotPurgeFailed) return true
        val detail =
            "암호화 경로 기록을 삭제하지 못해 새 길안내를 시작하지 않습니다. 저장소를 확인하고 새 보행을 시작해 주세요."
        presentation.safetyStop("route_snapshot_purge_failed")
        presentation.status("길안내 저장소 오류 · 안전 중지", detail)
        presentation.navigationStatus("navigation=safety_stopped reason=route_snapshot_purge_failed")
        presentation.speak(detail)
        return false
    }

    fun routeSnapshotOwnerOrNull(session: GatewayFieldSession?): RouteSnapshotOwner? {
        session ?: return null
        val generation = session.backendAccountGeneration?.let { "account:$it" }
            ?: session.familyId?.let { "session-family:$it" } ?: return null
        return runCatching { RouteSnapshotOwner(session.actorId, generation) }.getOrNull()
    }

    fun invalidateRouteSnapshotResponseLease() {
        if (::routeSnapshotIoLease.isInitialized) lane.invalidateResponse(routeSnapshotIoLease)
    }

    fun routeSnapshotActivityIsCurrent(lease: RouteSnapshotIoLane.Lease): Boolean =
        ::routeSnapshotIoLease.isInitialized && routeSnapshotIoLease === lease && !lease.closed.get() &&
            facts.activityCurrent()

    fun reconcileRouteSnapshotAccount(snapshot: GatewaySessionProcessSnapshot): Boolean {
        if (!::routeSnapshotIoLease.isInitialized) return false
        if (facts.deletionBlocked()) {
            invalidateRouteSnapshotResponseLease()
            return false // The exact deletion/reset preparation owns bind(null), not a Gateway refresh.
        }
        val owner = routeSnapshotOwnerOrNull(snapshot.session)
        if (routeSnapshotAccountBindingEstablished && routeSnapshotAccountOwner == owner) return true
        submitRouteSnapshotOwnership(snapshot,
            if ((facts.lifecycleOrNull() != null)) requireNotNull(facts.lifecycleOrNull()).snapshot() else null)
        return false // Pending is not a verified bind failure or success.
    }

    fun updateRouteSnapshotWalkOwnership(transition: WalkSessionTransition) {
        if (!::routeSnapshotIoLease.isInitialized || !transition.changed) return
        val gateway = facts.gatewaySnapshot()
        val scope = routeSnapshotWalkScope
        val ownershipGateway = routeSnapshotOwnershipGateway
        val sameGateway = ownershipGateway != null &&
            ownershipGateway.generation == gateway.generation && ownershipGateway.session === gateway.session &&
            ownershipGateway.storageBlocked == gateway.storageBlocked &&
            ownershipGateway.deletionRecoveryOnly == gateway.deletionRecoveryOnly
        val sameOwner = routeSnapshotAccountOwner != null &&
            routeSnapshotAccountOwner == routeSnapshotOwnerOrNull(gateway.session)
        val retainsOwnership = (routeSnapshotAccountBindingEstablished && scope != null &&
            scope.owner == routeSnapshotAccountOwner && scope.walkSessionId == transition.current.epoch.walkSessionId) ||
            routeSnapshotPreparationPending
        // Fresh readiness is not a new archive owner. Keep the admitted walk's pending
        // response and purge leases while its state, epoch and verified account stay current.
        if (transition.previous.state == WalkSessionState.ACTIVE &&
            transition.current.state == WalkSessionState.ACTIVE &&
            transition.previous.epoch == transition.current.epoch &&
            sameGateway && sameOwner && retainsOwnership &&
            routeSnapshotOwnershipEpoch == transition.current.epoch &&
            !gateway.storageBlocked && !gateway.deletionRecoveryOnly
        ) return
        val endingWalk = transition.previous.epoch.walkSessionId.takeIf {
            it != transition.current.epoch.walkSessionId ||
                transition.current.state in setOf(WalkSessionState.ENDED, WalkSessionState.SAFE_STOP)
        }
        submitRouteSnapshotOwnership(gateway, transition.current, endingWalk)
    }

    fun submitRouteSnapshotOwnership(
        gateway: GatewaySessionProcessSnapshot, walk: WalkSessionSnapshot?, endingWalk: String? = null,
    ) {
        val lease = routeSnapshotIoLease
        lateinit var operation: Any
        val owner = routeSnapshotOwnerOrNull(gateway.session)
        val scope = if (owner != null && walk?.state in setOf(WalkSessionState.ACTIVE, WalkSessionState.PAUSED)) {
            RouteSnapshotScope(owner, requireNotNull(walk).epoch.walkSessionId)
        } else null
        cancelEncryptedRouteSnapshotExpirySchedule()
        fun current(): Boolean {
            val actual = facts.gatewaySnapshot()
            return lane.isCurrent(lease, operation) &&
                routeSnapshotActivityIsCurrent(lease) && actual.generation == gateway.generation &&
                actual.session === gateway.session && actual.storageBlocked == gateway.storageBlocked &&
                actual.deletionRecoveryOnly == gateway.deletionRecoveryOnly &&
                (walk == null || requireNotNull(facts.lifecycleOrNull()).snapshot().epoch == walk.epoch)
        }
        val future = submitRouteSnapshotOwnershipStorage(lane, lease,
            owner, scope, endingWalk, ::current, onAdmission = {
                operation = beginRouteSnapshotPreparation(lane, lease)
                routeSnapshotOwnershipGateway = gateway
                routeSnapshotOwnershipEpoch = walk?.epoch
                routeSnapshotAccountOwner = owner
                routeSnapshotWalkScope = null
                routeSnapshotAccountBindingEstablished = false
            })
        future.thenAccept { result ->
            post {
                val committed = lane.commit(lease, operation, ::current) {
                    routeSnapshotPreparationPending = false
                    routeSnapshotAccountBindingEstablished = result?.ready == true
                    routeSnapshotWalkScope = result?.scope
                    routeSnapshotPurgeFailed = result?.ready != true
                }
                if (!committed) return@post
                result?.metadata?.let { scheduleEncryptedRouteSnapshotExpiry(it.scope, it.expiresAtEpochMs, expectedOperation = operation) }
                if (result?.ready == true) onPrepared()
            }
        }
    }

    fun prepareRouteSnapshotAccountPurge(): RouteSnapshotAccountCleanup? {
        if (!::routeSnapshotIoLease.isInitialized) return null
        val lease = routeSnapshotIoLease
        cancelEncryptedRouteSnapshotExpirySchedule()
        lateinit var operation: Any
        val coordinator = facts.deletionCoordinator()
        val activityLease = facts.deletionActivityLease()
        val intent = facts.deletionIntentToken()
        val future = submitRouteSnapshotAccountPurgeStorage(lane, lease,
            authorityIsCurrent = {
                routeSnapshotActivityIsCurrent(lease) && facts.deletionCoordinator() === coordinator &&
                    facts.deletionActivityLease() === activityLease && facts.deletionIntentToken() === intent
            }, onAdmission = {
                operation = beginRouteSnapshotPreparation(lane, lease)
                routeSnapshotResetParticipant.set(null)
                routeSnapshotAccountOwner = null
                routeSnapshotAccountBindingEstablished = false
                routeSnapshotWalkScope = null
                routeSnapshotOwnershipGateway = null
                routeSnapshotOwnershipEpoch = null
            })
        return RouteSnapshotAccountCleanup(lease, operation, future, coordinator, activityLease, intent)
    }

    fun routeSnapshotResetPreparationIsCurrent(preparation: RouteSnapshotAccountCleanup): Boolean =
        routeSnapshotActivityIsCurrent(preparation.lease) &&
            lane.isCurrent(preparation.lease, preparation.operation) &&
            facts.deletionCoordinator() === preparation.coordinator &&
            facts.deletionActivityLease() === preparation.activityLease &&
            facts.deletionIntentToken() === preparation.accountIntentToken

    fun awaitRouteSnapshotAccountPurgeOnWorker(preparation: RouteSnapshotAccountCleanup?): Boolean {
        preparation ?: return false
        val purged = awaitRouteSnapshotIoPurge(preparation.purge)
        val committed = publishRouteSnapshotAccountPurgeResult(lane,
            preparation.lease, preparation.operation, purged,
            { routeSnapshotResetPreparationIsCurrent(preparation) }, {
                preparation.verified.set(purged)
                routeSnapshotResetParticipant.set(if (purged) preparation else null)
            })
        return committed && purged
    }

    internal fun awaitRouteSnapshotIoPurge(purge: CompletableFuture<Boolean>): Boolean = try {
        purge.get() == true // Existing cleanup worker only, outside privacy and Coordinator locks.
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    } catch (_: Exception) { false }

    fun purgeEncryptedRouteSnapshotForAccount(): Boolean =
        awaitRouteSnapshotAccountPurgeOnWorker(prepareRouteSnapshotAccountPurge())

    fun purgeEncryptedRouteSnapshot(
        expectedScope: RouteSnapshotScope? = routeSnapshotWalkScope,
        onCompleted: (Boolean) -> Unit = {},
    ) {
        cancelEncryptedRouteSnapshotExpirySchedule()
        if (!::routeSnapshotIoLease.isInitialized || expectedScope == null) return
        val lease = routeSnapshotIoLease
        lateinit var operation: Any
        val future = submitRouteSnapshotScopePurge(lane, lease, expectedScope,
            onAdmission = { operation = beginRouteSnapshotPreparation(lane, lease) })
        observeEncryptedRouteSnapshotPurge(lease, RouteSnapshotScopePurgeSubmission(operation, future), onCompleted)
    }

    fun observeEncryptedRouteSnapshotPurge(
        lease: RouteSnapshotIoLane.Lease, submission: RouteSnapshotScopePurgeSubmission,
        onCompleted: (Boolean) -> Unit,
    ) {
        submission.future.thenAccept { purged ->
            post {
                val committed = lane.commit(lease, submission.operation,
                    { routeSnapshotActivityIsCurrent(lease) }) {
                    routeSnapshotPreparationPending = false
                    routeSnapshotPurgeFailed = !purged
                }
                if (!committed) return@post
                onCompleted(purged)
            }
        }
    }

    fun cancelEncryptedRouteSnapshotExpirySchedule() {
        val previous = routeSnapshotExpiryRunnable.getAndSet(null)
        previous?.let(callbacks::remove)
    }

    fun scheduleEncryptedRouteSnapshotExpiry(
        scope: RouteSnapshotScope,
        expiresAtEpochMs: Long,
        expectedOperation: Any? = if (::routeSnapshotIoLease.isInitialized) routeSnapshotIoLease.operation.get() else null,
    ) {
        val lease = if (::routeSnapshotIoLease.isInitialized) routeSnapshotIoLease else return
        val operation = expectedOperation ?: return
        lateinit var expiry: Runnable
        expiry = Runnable {
            onEncryptedRouteSnapshotExpired(scope, lease, operation) {
                routeSnapshotExpiryRunnable.compareAndSet(expiry, null) &&
                    lane.isCurrent(lease, operation)
            }
        }
        var previous: Runnable? = null
        val committed = lane.commit(lease, operation,
            { routeSnapshotActivityIsCurrent(lease) }) {
            previous = routeSnapshotExpiryRunnable.getAndSet(expiry)
        }
        if (!committed) return
        previous?.let(callbacks::remove)
        val remainingMs = (expiresAtEpochMs - wallTimeMs())
            .coerceIn(0L, ROUTE_SNAPSHOT_TTL_MS)
        callbacks.postDelayed(expiry, remainingMs)
        // A cleanup worker can retire the operation while this SDK post runs. The exact old
        // callback can never erase a new schedule or submit a stale expiry purge.
        if (!lane.isCurrent(lease, operation) &&
            routeSnapshotExpiryRunnable.compareAndSet(expiry, null)
        ) callbacks.remove(expiry)
    }

    fun onEncryptedRouteSnapshotExpired(
        scope: RouteSnapshotScope, lease: RouteSnapshotIoLane.Lease, operation: Any,
        expiryClaimIsCurrent: () -> Boolean,
    ) {
        val activeSnapshotExpired = (facts.lifecycleOrNull() != null) &&
            requireNotNull(facts.lifecycleOrNull()).currentRuntimeEpochOrNull()?.walkSessionId == scope.walkSessionId &&
            routeSnapshotWalkScope == scope && facts.routeActive()
        val submission = submitRouteSnapshotExpiryPurge(lane, lease,
            operation, scope, expiryClaimIsCurrent) ?: return
        observeEncryptedRouteSnapshotPurge(lease, submission) { purged ->
            if (!purged) presentation.navigationStatus("navigation=route_snapshot_purge_failed")
        }
        if (activeSnapshotExpired && lane.isCurrent(lease, submission.operation) &&
            !facts.developmentBypass()
        ) {
            presentation.safetyStop("route_snapshot_expired")
            val detail = "암호화 경로 기록의 24시간 보관 시간이 끝나 길안내를 중지했습니다."
            presentation.status("길안내 안전 중지", detail)
            presentation.navigationStatus("navigation=safety_stopped reason=route_snapshot_expired purge=pending")
            presentation.speak(detail)
        }
    }
}
