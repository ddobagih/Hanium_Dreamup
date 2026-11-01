package kr.co.hanium.dreamup.walksafe.runtime.vision.remote

import android.content.Context
import android.os.SystemClock
import kr.co.hanium.dreamup.walksafe.BuildConfig
import kr.co.hanium.dreamup.walksafe.device.AndroidDeviceCheckAuthority
import kr.co.hanium.dreamup.walksafe.device.DeviceCheckAuthorityBinding
import kr.co.hanium.dreamup.walksafe.device.DeviceCheckAuthorityOutcome
import kr.co.hanium.dreamup.walksafe.device.DeviceCheckOwnerRef
import kr.co.hanium.dreamup.walksafe.inference.TwoModelRuntimeConfig
import kr.co.hanium.dreamup.walksafe.inference.composite.AndroidJointRuntimeChoiceStore
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Explicit diagnostic realm with real APK/config identity; never reads or writes production PASS. */
class VisionDiagnosticScope(context: Context, namespace: String = "vision_" + UUID.randomUUID().toString()) {
    private val app = context.applicationContext
    val authority = AndroidDeviceCheckAuthority.forDiagnostics(app, namespace)
    private val owners = mutableMapOf<String, DeviceCheckOwnerRef>()
    private val binding = DeviceCheckAuthorityBinding(digest("diagnostic-actor:$namespace"),
        digest("diagnostic-result:$namespace:${apkHash(app)}"),
        requireNotNull(AndroidJointRuntimeChoiceStore.createBinding(app, TwoModelRuntimeConfig.load(app))), apkHash(app))
    private val started = SystemClock.elapsedRealtime()
    private val attempt = when (val result = authority.beginAttempt(binding, UUID.randomUUID().toString(), started,
        started + 300_000L).toCompletableFuture().get(20, TimeUnit.SECONDS)) {
        is DeviceCheckAuthorityOutcome.Committed -> result.value
        else -> error("Diagnostic attempt not committed: $result")
    }
    /** Explicit allocation-time capture; delayed producer closures retain the returned ref, never this lookup. */
    @Synchronized fun owner(id: String): DeviceCheckOwnerRef {
        owners[id]?.takeIf { authority.ownerRef(id) === it }?.let { return it }
        return requireNotNull(authority.registerOwner(id, attempt, 1L)) { "Diagnostic owner rejected" }
            .also { owners[id] = it }
    }
    fun context(id: String): VisionRuntimeContext = VisionRuntimeContext(app, owner(id), authority)

    companion object {
        private val apkHashes = mutableMapOf<String, String>()
        @Synchronized private fun apkHash(context: Context): String {
            check(BuildConfig.DEBUG)
            val file = File(context.applicationInfo.sourceDir)
            val key = "${file.absolutePath}:${file.length()}:${file.lastModified()}"
            return apkHashes.getOrPut(key) {
                val hash = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input -> val block = ByteArray(65536)
                    while (true) { val count = input.read(block); if (count < 0) break; hash.update(block, 0, count) } }
                hash.digest().joinToString("") { "%02x".format(it) }
            }
        }
        private fun digest(value: String) = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
