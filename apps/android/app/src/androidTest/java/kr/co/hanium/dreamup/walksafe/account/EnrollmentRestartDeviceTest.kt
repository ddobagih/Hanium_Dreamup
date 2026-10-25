package kr.co.hanium.dreamup.walksafe.account

import android.content.Context
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import kr.co.hanium.dreamup.walksafe.network.EmailOtpRequestRetryPolicy
import kr.co.hanium.dreamup.walksafe.security.AeadKeyPolicy
import kr.co.hanium.dreamup.walksafe.security.AndroidKeyStoreAead
import kr.co.hanium.dreamup.walksafe.testing.IsolatedBoundaryContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Writer/read instrumentation invocations, actual Android encrypted persistence, no SMTP/account. */
@RunWith(AndroidJUnit4::class)
class EnrollmentRestartDeviceTest {
    @Test
    fun pendingOtpAndUnconfirmedCreationSurviveProcessDeath() {
        val args = InstrumentationRegistry.getArguments()
        val phase = requireNotNull(args.getString("enrollmentProbePhase"))
        require(phase in setOf("write", "read"))
        val run = requireNotNull(args.getString("enrollmentProbeId"))
        val context = IsolatedBoundaryContext.create("enrollment", run, fresh = phase == "write")
        val name = "walksafe_native_enrollment_$run"
        val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val policy = AeadKeyPolicy("walksafe.instrumentation.enrollment.$run.v", 1, setOf(1))
        val aead = AndroidKeyStoreAead(policy)
        val store = AndroidEmailEnrollmentStore(preferences, aead)
        val owner = "a".repeat(64)
        val otherOwner = "e".repeat(64)
        val wireId = "synthetic-enrollment-$run"
        val payload = EmailOtpRequestRetryPolicy.Payload("http://127.0.0.1:39647", owner,
            "synthetic-attempt-$run", "synthetic-enrollment@example.com", "2000-01-01")
        val retry = EmailOtpRequestRetryPolicy(SystemClock::elapsedRealtime, {
            check(phase == "write") { "A new process must restore the original wire request id" }
            wireId
        }, { store })
        val marker = File(context.root, "writer.json")
        var retain = false
        try {
            if (phase == "write") {
                assertTrue("A fresh probe must preserve unrelated preferences", preferences.all.isEmpty())
                assertTrue(store.markCreationPending(owner))
                assertNotNull(retry.begin(payload))
                assertTrue(store.hasUnconfirmedCreation(owner))
                assertFalse(store.hasUnconfirmedCreation(otherOwner))
                val xml = File(context.applicationInfo.dataDir, "shared_prefs/$name.xml").readText()
                listOf(owner, wireId, payload.email).forEach { assertFalse("Enrollment metadata must be encrypted", xml.contains(it)) }
                marker.writeText(JSONObject().put("pid", Process.myPid()).toString())
                retain = true
            } else {
                val writerPid = JSONObject(marker.readText()).getInt("pid")
                assertTrue(writerPid > 0)
                assertNotEquals("Read must run in a new Android process", writerPid, Process.myPid())
                assertTrue(store.hasUnconfirmedCreation(owner))
                assertFalse(store.hasUnconfirmedCreation(otherOwner))
                assertEquals(wireId, requireNotNull(store.loadPendingOtpRequest()).requestId)
                assertEquals(wireId, requireNotNull(retry.begin(payload)).requestId)
                assertTrue(store.clear())
                assertTrue("Partial form cleanup must retain uncertain creation", store.hasUnconfirmedCreation(owner))
                assertFalse(store.clearUnconfirmedCreation(otherOwner))
                assertTrue(store.hasUnconfirmedCreation(owner))
                assertTrue(store.clearUnconfirmedCreation(owner))
                assertFalse(store.hasUnconfirmedCreation(owner))
                val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(policy.alias(1), null)
                assertNotNull("Actual Android KeyStore alias must survive process death", key)
                assertNull("Key material cannot be exported", key.encoded)
            }
        } finally {
            if (!retain) {
                assertTrue(context.deleteSharedPreferences(name))
                assertTrue(aead.destroyKnownVersions())
                KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(policy.alias(1) + ".generation") }
                context.dispose()
            }
        }
    }
}
