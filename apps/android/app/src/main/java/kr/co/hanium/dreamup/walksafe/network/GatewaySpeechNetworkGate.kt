package kr.co.hanium.dreamup.walksafe.network

import java.util.concurrent.CancellationException

internal class GatewaySpeechNetworkGate(
    private val stateProvider: () -> Pair<MobileNetworkPreference, ActiveNetworkTransport>,
) {
    internal data class Ticket(
        val generation: Long,
        val state: Pair<MobileNetworkPreference, ActiveNetworkTransport>,
    )

    private val lock = Any()
    private var generation = 0L
    private val calls = mutableSetOf<CancellableNetworkCall<*>>()

    fun capture(): Ticket? = synchronized(lock) {
        val state = stateProvider()
        if (!AndroidNetworkTransferPolicy.isAllowed(state.first, state.second)) return@synchronized null
        Ticket(generation, state)
    }

    fun isCurrent(ticket: Ticket): Boolean = synchronized(lock) {
        ticket.generation == generation &&
            ticket.state == stateProvider() &&
            AndroidNetworkTransferPolicy.isAllowed(ticket.state.first, ticket.state.second)
    }

    fun onNetworkPolicyChanged() = synchronized(lock) {
        generation += 1L
        calls.toList().forEach(CancellableNetworkCall<*>::cancel)
    }

    fun <T> bind(ticket: Ticket, call: CancellableNetworkCall<T>): CancellableNetworkCall<T> {
        lateinit var guarded: CancellableNetworkCall<T>
        guarded = CancellableNetworkCall(
            executeBlock = {
                try {
                    synchronized(lock) {
                        if (!isCurrent(ticket)) {
                            call.cancel()
                            throw CancellationException("speech network policy changed")
                        }
                    }
                    call.execute()
                } finally {
                    synchronized(lock) { calls.remove(guarded) }
                }
            },
            cancelBlock = {
                call.cancel()
                synchronized(lock) { calls.remove(guarded) }
            },
        )
        synchronized(lock) {
            if (isCurrent(ticket)) calls += guarded else guarded.cancel()
        }
        return guarded
    }
}
