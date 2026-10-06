package kr.co.hanium.dreamup.walksafe.account

import android.content.Context
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyStore
import kr.co.hanium.dreamup.walksafe.network.EmailOtpRequestRetryPolicy
import kr.co.hanium.dreamup.walksafe.security.AeadKeyPolicy
import kr.co.hanium.dreamup.walksafe.security.AndroidKeyStoreAead
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue

/** Two separate Android processes; isolated synthetic enrollment metadata, no SMTP or real account. */
class EnrollmentRestartDeviceTest {
    @Test fun pendingOtpAndUnconfirmedCreationSurviveProcessDeath() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("enrollmentProbePhase") in setOf("write", "read"))
        val phase = requireNotNull(args.getString("enrollmentProbePhase"))
        require(phase in setOf("write", "read"))
        val id = requireNotNull(args.getString("enrollmentProbeId"))
        require(id.matches(Regex("[A-Za-z0-9_-]{1,32}")))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "enrollment-probe-$id"
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val policy = AeadKeyPolicy("walksafe.instrumentation.enrollment.$id.v", 1, setOf(1))
        val aead = AndroidKeyStoreAead(policy)
        val store = AndroidEmailEnrollmentStore(prefs, aead)
        val payload = EmailOtpRequestRetryPolicy.Payload("http://127.0.0.1:8081", "a".repeat(64),
            "process-${Process.myPid()}", "synthetic-enrollment@example.com", "2000-01-01")
        val retry = EmailOtpRequestRetryPolicy({ android.os.SystemClock.elapsedRealtime() }, {
            check(phase == "write") { "Restart must reuse the original request id" }
            "synthetic-request-0123456789"
        }, { store })
        var keep = false
        try {
            if (phase == "write") {
                assertTrue("Probe must use a fresh id", prefs.all.isEmpty())
                assertTrue(store.markCreationPending())
                assertNotNull(retry.begin(payload))
                assertTrue(prefs.edit().putInt("probe_pid", Process.myPid()).commit())
                keep = true
            } else {
                assertNotEquals(prefs.getInt("probe_pid", -1), Process.myPid())
                assertTrue(store.hasUnconfirmedCreation())
                assertEquals("synthetic-request-0123456789", requireNotNull(store.loadPendingOtpRequest()).requestId)
                assertEquals("synthetic-request-0123456789", requireNotNull(retry.begin(payload)).requestId)
                assertTrue(store.clear())
                assertTrue("OTP cleanup must not erase uncertain account creation", store.hasUnconfirmedCreation())
                assertTrue(store.clear(creationConfirmed = true))
                assertFalse(store.hasUnconfirmedCreation())
            }
        } finally {
            if (!keep) {
                context.deleteSharedPreferences(name)
                aead.destroyKnownVersions()
                KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(policy.alias(1) + ".generation") }
            }
        }
    }
}
