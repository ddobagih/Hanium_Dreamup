package kr.co.hanium.dreamup.walksafe.device

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kr.co.hanium.dreamup.walksafe.BuildConfig
import kr.co.hanium.dreamup.walksafe.security.AeadKeyPolicy
import kr.co.hanium.dreamup.walksafe.security.LocalAeadKeyProvider
import kr.co.hanium.dreamup.walksafe.security.VersionedLocalAead
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/** Explicit emulator/diagnostic opt-in. No production preference, Keystore alias or native model is used. */
@RunWith(AndroidJUnit4::class)
class DeviceCheckAuthorityPublicationFaultDeviceTest {
    @Test(timeout = 30_000L)
    fun faultsBeforeAndAfterRenameNeverTurnAnUncertainPublicationIntoAnAck() {
        assumeTrue(BuildConfig.DEBUG && InstrumentationRegistry.getArguments()
            .getString("allowDeviceCheckAuthorityFaults") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        for (phase in DeviceCheckFilePhase.entries) {
            val namespace = UUID.randomUUID().toString()
            val directory = File(context.noBackupFilesDir, "device-check-diagnostics/file-fault-$namespace")
            val key = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
            val crypto = VersionedLocalAead(AeadKeyPolicy("diagnostic.authority.$namespace.", 1, setOf(1)), object : LocalAeadKeyProvider {
                override fun getOrCreate(alias: String): SecretKey = key
                override fun getExisting(alias: String): SecretKey = key
                override fun delete(alias: String) = true
                override fun createFreshAfterVerifiedPurge(alias: String): SecretKey = key
            })
            try {
                val storage = AndroidDeviceCheckAuthorityStorage(directory, crypto, namespace)
                val original = state(1, "CHECKING")
                assertEquals(DeviceCheckPublication.COMMITTED, storage.publish(original))
                val replacement = state(2, "CANCELLED")
                val faulty = AndroidDeviceCheckAuthorityStorage(directory, crypto, namespace) {
                    if (it == phase) throw IOException("explicit_test_phase_$phase")
                }
                val mayHavePublished = phase in setOf(DeviceCheckFilePhase.AFTER_RENAME,
                    DeviceCheckFilePhase.AFTER_DIRECTORY_SYNC, DeviceCheckFilePhase.AFTER_READBACK)
                assertEquals(if (mayHavePublished) DeviceCheckPublication.INDETERMINATE else DeviceCheckPublication.NOT_PUBLISHED,
                    faulty.publish(replacement))
                assertEquals(if (mayHavePublished) replacement else original,
                    AndroidDeviceCheckAuthorityStorage(directory, crypto, namespace).read())
            } finally {
                // Only this newly allocated diagnostic namespace is removed.
                File(directory, "state.aead.pending").delete()
                File(directory, "state.aead").delete()
                directory.delete()
            }
        }
    }
    private fun state(revision: Long, phase: String): String {
        val id = UUID.randomUUID().toString()
        val binding = DeviceCheckAuthorityBinding("a".repeat(64), "b".repeat(64), "c".repeat(64), "d".repeat(64))
        return requireNotNull(DeviceCheckAuthorityCodec.encode(DeviceCheckAuthorityState(revision,
            DeviceCheckAttemptRecord(id, binding, 1000, 301000, phase), commands = mapOf(id to "e".repeat(64)))))
    }
}
