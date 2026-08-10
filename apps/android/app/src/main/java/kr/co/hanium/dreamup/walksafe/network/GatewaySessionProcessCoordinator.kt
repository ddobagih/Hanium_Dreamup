package kr.co.hanium.dreamup.walksafe.network

import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingSnapshot

@ConsistentCopyVisibility
internal data class GatewaySessionOperation internal constructor(
    val generation: Long,
    val operationId: String,
) {
    override fun toString(): String =
        "GatewaySessionOperation(generation=$generation, operationId=redacted)"
}

internal class GatewaySessionProcessSnapshot internal constructor(
    val session: GatewayFieldSession?,
    val restoredFirstRunSnapshot: FirstRunOnboardingSnapshot?,
    val generation: Long,
    val storageBlocked: Boolean,
    val inFlightOperationId: String?,
    val deletionRecoveryOnly: Boolean,
) {
    override fun toString(): String =
        "GatewaySessionProcessSnapshot(" +
            "session=${if (session == null) "none" else "present"}, " +
            "restoredFirstRunSnapshot=" +
            "${if (restoredFirstRunSnapshot == null) "none" else "present"}, " +
            "generation=$generation, storageBlocked=$storageBlocked, " +
            "deletionRecoveryOnly=$deletionRecoveryOnly, " +
            "inFlightOperation=${if (inFlightOperationId == null) "none" else "present"})"
}

/**
 * Owns the long-lived Gateway login for the lifetime of the application process.
 *
 * Owner callbacks run synchronously on the thread that changes the state. Android owners must
 * dispatch UI work to the main thread themselves. Detaching an owner only removes its callback;
 * it never clears the session or stops the process executor.
 */
internal object GatewaySessionProcessCoordinator {
    private val lock = Any()
    private val subscriptions = IdentityHashMap<Any, OwnerSubscription>()
    private val processExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "walksafe-gateway-session").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
    }

    val executor: Executor = Executor { command -> processExecutor.execute(command) }

    private var currentSession: GatewayFieldSession? = null
    private var restoredFirstRunSnapshot: FirstRunOnboardingSnapshot? = null
    private var generation = 0L
    private var storageBlocked = false
    private var deletionRecoveryOnly = false
    private var inFlightOperation: GatewaySessionOperation? = null

    fun attach(
        owner: Any,
        subscriber: (GatewaySessionProcessSnapshot) -> Unit,
    ): GatewaySessionProcessSnapshot {
        val subscription = OwnerSubscription(subscriber)
        val previous: OwnerSubscription?
        val current: GatewaySessionProcessSnapshot
        synchronized(lock) {
            previous = subscriptions.put(owner, subscription)
            current = snapshotLocked()
        }
        previous?.deactivate()
        subscription.deliver(current)
        return current
    }

    fun detach(owner: Any) {
        val removed = synchronized(lock) {
            subscriptions.remove(owner)
        }
        removed?.deactivate()
    }

    fun snapshot(): GatewaySessionProcessSnapshot = synchronized(lock) {
        snapshotLocked()
    }

    fun beginOperation(): GatewaySessionOperation? {
        val operation: GatewaySessionOperation
        val notification: Notification
        synchronized(lock) {
            if (inFlightOperation != null) return null
            operation = GatewaySessionOperation(
                generation = advanceGenerationLocked(),
                operationId = UUID.randomUUID().toString(),
            )
            inFlightOperation = operation
            notification = notificationLocked()
        }
        notification.deliver()
        return operation
    }

    /**
     * Publishes a restored FP-010/session pair without ending the operation.
     *
     * The returned token has the same operation ID and the new generation required by the
     * following verification publish.
     */
    fun publishRestoredUnverified(
        operation: GatewaySessionOperation,
        session: GatewayFieldSession,
        firstRunSnapshot: FirstRunOnboardingSnapshot,
    ): GatewaySessionOperation? {
        if (
            session.verificationState != GatewaySessionVerificationState.RESTORED_UNVERIFIED ||
            session.sessionScope != GatewaySessionScope.GENERAL
        ) {
            return null
        }
        val continuedOperation: GatewaySessionOperation
        val notification: Notification
        synchronized(lock) {
            if (inFlightOperation != operation) return null
            replaceSessionLocked(session)
            restoredFirstRunSnapshot = firstRunSnapshot
            storageBlocked = false
            deletionRecoveryOnly = false
            continuedOperation = GatewaySessionOperation(
                generation = advanceGenerationLocked(),
                operationId = operation.operationId,
            )
            inFlightOperation = continuedOperation
            notification = notificationLocked()
        }
        notification.deliver()
        return continuedOperation
    }

    fun publishVerified(
        operation: GatewaySessionOperation,
        session: GatewayFieldSession,
        firstRunSnapshot: FirstRunOnboardingSnapshot,
    ): Boolean {
        if (
            session.verificationState != GatewaySessionVerificationState.VERIFIED ||
            session.sessionScope != GatewaySessionScope.GENERAL
        ) return false
        val notification: Notification
        synchronized(lock) {
            if (inFlightOperation != operation) return false
            replaceSessionLocked(session)
            restoredFirstRunSnapshot = firstRunSnapshot
            storageBlocked = false
            deletionRecoveryOnly = false
            advanceGenerationLocked()
            inFlightOperation = null
            notification = notificationLocked()
        }
        notification.deliver()
        return true
    }

    fun publishDeletionRecoveryVerified(
        operation: GatewaySessionOperation,
        session: GatewayFieldSession,
        expectedActorId: String,
        expectedGatewayBaseUrl: String,
    ): Boolean {
        if (
            session.verificationState != GatewaySessionVerificationState.VERIFIED ||
            session.sessionScope != GatewaySessionScope.ACCOUNT_DELETION_RECOVERY ||
            !session.isUsableFor(expectedActorId) ||
            session.actorId != expectedActorId ||
            session.gatewayBaseUrl != expectedGatewayBaseUrl
        ) return false
        val notification: Notification
        synchronized(lock) {
            if (inFlightOperation != operation) return false
            replaceSessionLocked(session)
            restoredFirstRunSnapshot = null
            storageBlocked = false
            deletionRecoveryOnly = true
            advanceGenerationLocked()
            inFlightOperation = null
            notification = notificationLocked()
        }
        notification.deliver()
        return true
    }

    /**
     * Removes the process credential after a durable pending-revocation record exists.
     *
     * The returned token keeps the same operation ID so only that logout attempt can complete
     * or fail the pending network action.
     */
    fun publishPendingRevocation(
        operation: GatewaySessionOperation,
    ): GatewaySessionOperation? {
        val continuedOperation: GatewaySessionOperation
        val notification: Notification
        synchronized(lock) {
            if (inFlightOperation != operation) return null
            currentSession?.invalidate()
            currentSession = null
            restoredFirstRunSnapshot = null
            storageBlocked = false
            deletionRecoveryOnly = false
            continuedOperation = GatewaySessionOperation(
                generation = advanceGenerationLocked(),
                operationId = operation.operationId,
            )
            inFlightOperation = continuedOperation
            notification = notificationLocked()
        }
        notification.deliver()
        return continuedOperation
    }

    fun markStorageBlocked(operation: GatewaySessionOperation): Boolean {
        val notification: Notification
        synchronized(lock) {
            if (inFlightOperation != operation) return false
            clearStateLocked(blocked = true)
            advanceGenerationLocked()
            notification = notificationLocked()
        }
        notification.deliver()
        return true
    }

    fun markStorageBlocked(expectedGeneration: Long): Boolean {
        val notification: Notification
        synchronized(lock) {
            if (generation != expectedGeneration) return false
            clearStateLocked(blocked = true)
            advanceGenerationLocked()
            notification = notificationLocked()
        }
        notification.deliver()
        return true
    }

    fun clear(operation: GatewaySessionOperation): GatewaySessionProcessSnapshot? {
        val cleared: GatewaySessionProcessSnapshot
        val notification: Notification
        synchronized(lock) {
            if (inFlightOperation != operation) return null
            clearStateLocked(blocked = false)
            advanceGenerationLocked()
            cleared = snapshotLocked()
            notification = notificationLocked(cleared)
        }
        notification.deliver()
        return cleared
    }

    fun clear(expectedGeneration: Long): GatewaySessionProcessSnapshot? {
        val cleared: GatewaySessionProcessSnapshot
        val notification: Notification
        synchronized(lock) {
            if (generation != expectedGeneration) return null
            clearStateLocked(blocked = false)
            advanceGenerationLocked()
            cleared = snapshotLocked()
            notification = notificationLocked(cleared)
        }
        notification.deliver()
        return cleared
    }

    private fun replaceSessionLocked(session: GatewayFieldSession) {
        currentSession?.takeIf { it !== session }?.invalidate()
        currentSession = session
    }

    private fun clearStateLocked(blocked: Boolean) {
        currentSession?.invalidate()
        currentSession = null
        restoredFirstRunSnapshot = null
        storageBlocked = blocked
        deletionRecoveryOnly = false
        inFlightOperation = null
    }

    private fun advanceGenerationLocked(): Long {
        check(generation < Long.MAX_VALUE) { "gateway session generation exhausted" }
        generation += 1L
        return generation
    }

    private fun snapshotLocked(): GatewaySessionProcessSnapshot =
        GatewaySessionProcessSnapshot(
            session = currentSession,
            restoredFirstRunSnapshot = restoredFirstRunSnapshot,
            generation = generation,
            storageBlocked = storageBlocked,
            inFlightOperationId = inFlightOperation?.operationId,
            deletionRecoveryOnly = deletionRecoveryOnly,
        )

    private fun notificationLocked(
        snapshot: GatewaySessionProcessSnapshot = snapshotLocked(),
    ): Notification = Notification(
        snapshot = snapshot,
        subscriptions = subscriptions.values.toList(),
    )

    private class Notification(
        private val snapshot: GatewaySessionProcessSnapshot,
        private val subscriptions: List<OwnerSubscription>,
    ) {
        fun deliver() {
            subscriptions.forEach { it.deliver(snapshot) }
        }
    }

    private class OwnerSubscription(
        private val subscriber: (GatewaySessionProcessSnapshot) -> Unit,
    ) {
        private val deliveryLock = Any()
        private var active = true
        private var lastDeliveredGeneration = -1L

        fun deliver(snapshot: GatewaySessionProcessSnapshot) {
            synchronized(deliveryLock) {
                if (!active || snapshot.generation <= lastDeliveredGeneration) return
                lastDeliveredGeneration = snapshot.generation
                runCatching { subscriber(snapshot) }
            }
        }

        fun deactivate() {
            synchronized(deliveryLock) {
                active = false
            }
        }
    }
}
