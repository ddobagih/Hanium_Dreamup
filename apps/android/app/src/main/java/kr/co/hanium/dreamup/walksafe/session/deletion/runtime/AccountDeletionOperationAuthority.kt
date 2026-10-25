package kr.co.hanium.dreamup.walksafe.session.deletion.runtime

import kr.co.hanium.dreamup.walksafe.network.CancellableNetworkCall
import kr.co.hanium.dreamup.walksafe.session.AccountDeletionStatus

/** One admission slot shared by confirmation preparation and deletion network work. */
internal class AccountDeletionOperationAuthority {
    private val lock = Any()
    private var generation = 0L
    private var inFlight = false
    private var call: CancellableNetworkCall<AccountDeletionStatus>? = null

    // The former Main UI fact was read without taking the admission lock.
    val requestInFlight: Boolean get() = inFlight

    fun beginPreparation(): Long = synchronized(lock) {
        generation += 1L
        inFlight = true
        generation
    }

    fun isCurrentPreparation(expectedGeneration: Long): Boolean = synchronized(lock) {
        expectedGeneration == generation && inFlight && call == null
    }

    /** Publish adjunct facts only while this exact preparation still owns the admission slot. */
    fun updateCurrentPreparation(expectedGeneration: Long, update: () -> Unit): Boolean = synchronized(lock) {
        if (expectedGeneration != generation || !inFlight || call != null) false
        else { update(); true }
    }

    fun completePreparation(expectedGeneration: Long): Boolean = synchronized(lock) {
        if (expectedGeneration != generation || call != null) {
            false
        } else {
            inFlight = false
            true
        }
    }

    fun admitCall(currentCall: CancellableNetworkCall<AccountDeletionStatus>): Long = synchronized(lock) {
        call?.cancel()
        generation += 1L
        call = currentCall
        inFlight = true
        generation
    }

    fun completeCall(expectedGeneration: Long, currentCall: CancellableNetworkCall<AccountDeletionStatus>): Boolean =
        synchronized(lock) {
            if (expectedGeneration != generation || call !== currentCall) {
                false
            } else {
                call = null
                inFlight = false
                true
            }
        }

    fun destroy() = synchronized(lock) {
        generation += 1L
        call?.cancel()
        call = null
        inFlight = false
    }
}
