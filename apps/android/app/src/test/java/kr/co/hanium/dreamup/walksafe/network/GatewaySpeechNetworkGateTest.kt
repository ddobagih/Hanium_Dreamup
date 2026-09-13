package kr.co.hanium.dreamup.walksafe.network

import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewaySpeechNetworkGateTest {
    @Test
    fun cellularCaptureRequiresTheEffectiveConfirmedPreference() {
        var preference = MobileNetworkPreference.WIFI_ONLY
        val gate = GatewaySpeechNetworkGate { preference to ActiveNetworkTransport.CELLULAR }

        // An unsaved or pending grant leaves the effective preference at WIFI_ONLY.
        assertNull(gate.capture())
        preference = MobileNetworkPreference.ALLOW_CELLULAR
        assertNotNull(gate.capture())
    }

    @Test
    fun offlineIsBlockedAndWifiWorksWithCellularDisabled() {
        var transport = ActiveNetworkTransport.OFFLINE
        val gate = GatewaySpeechNetworkGate { MobileNetworkPreference.WIFI_ONLY to transport }

        assertNull(gate.capture())
        transport = ActiveNetworkTransport.WIFI
        assertNotNull(gate.capture())
    }

    @Test
    fun queuedExecutionRechecksTransportEvenBeforeTheObserverRuns() {
        var transport = ActiveNetworkTransport.WIFI
        val gate = GatewaySpeechNetworkGate { MobileNetworkPreference.WIFI_ONLY to transport }
        val ticket = checkNotNull(gate.capture())
        val executed = AtomicBoolean(false)
        val underlying = CancellableNetworkCall.blocking { executed.set(true) }
        val call = gate.bind(ticket, underlying)

        transport = ActiveNetworkTransport.CELLULAR

        assertThrows(CancellationException::class.java) { call.execute() }
        assertFalse(executed.get())
        assertTrue(underlying.isCancelled())
        assertFalse(gate.isCurrent(ticket))
    }

    @Test
    fun revokedGrantCannotResumeAQueuedCallAfterPermissionReturns() {
        var preference = MobileNetworkPreference.ALLOW_CELLULAR
        val gate = GatewaySpeechNetworkGate { preference to ActiveNetworkTransport.CELLULAR }
        val ticket = checkNotNull(gate.capture())
        val executed = AtomicBoolean(false)
        val call = gate.bind(ticket, CancellableNetworkCall.blocking { executed.set(true) })

        preference = MobileNetworkPreference.WIFI_ONLY
        gate.onNetworkPolicyChanged()
        preference = MobileNetworkPreference.ALLOW_CELLULAR
        gate.onNetworkPolicyChanged()

        assertThrows(CancellationException::class.java) { call.execute() }
        assertFalse(executed.get())
        assertFalse(gate.isCurrent(ticket))
        assertNotNull(gate.capture())
    }

    @Test
    fun allowedCellularExecutesTheRequest() {
        val gate = GatewaySpeechNetworkGate {
            MobileNetworkPreference.ALLOW_CELLULAR to ActiveNetworkTransport.CELLULAR
        }
        val ticket = checkNotNull(gate.capture())

        assertEquals("response", gate.bind(ticket, CancellableNetworkCall.blocking { "response" }).execute())
        assertTrue(gate.isCurrent(ticket))
    }

    @Test
    fun transportChangeCancelsInFlightAndInvalidatesEvenAnUncooperativeLateResult() {
        var transport = ActiveNetworkTransport.CELLULAR
        val gate = GatewaySpeechNetworkGate { MobileNetworkPreference.ALLOW_CELLULAR to transport }
        val ticket = checkNotNull(gate.capture())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancelled = AtomicBoolean(false)
        val call = gate.bind(
            ticket,
            CancellableNetworkCall(
                executeBlock = {
                    entered.countDown()
                    check(release.await(3, TimeUnit.SECONDS))
                    "late response"
                },
                cancelBlock = { cancelled.set(true) },
            ),
        )
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit<String> { call.execute() }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            transport = ActiveNetworkTransport.WIFI
            gate.onNetworkPolicyChanged()

            assertTrue(cancelled.get())
            assertTrue(call.isCancelled())
            release.countDown()
            assertEquals("late response", result.get(3, TimeUnit.SECONDS))
            assertFalse(gate.isCurrent(ticket))
        } finally {
            release.countDown()
            worker.shutdownNow()
        }
    }
}
