package kr.co.hanium.dreamup.walksafe.inference.composite

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import kr.co.hanium.dreamup.walksafe.inference.RuntimeBackend
import kr.co.hanium.dreamup.walksafe.inference.RuntimeCandidate
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CompletableFuture

internal object FixtureRuntimeProtocol {
    const val VERSION = 1
    const val DESCRIPTOR = "walksafe.fixture.runtime.v1"
    const val CALLBACK_DESCRIPTOR = "walksafe.fixture.runtime.callback.v1"
    const val HELLO = 1
    const val START = 2
    const val EXIT = 3
    const val STAGE = 1
    const val RESULT = 2
    const val PROCESS_SUFFIX = ":fixture_runtime"
    const val MAX_JSON_BYTES = 65_536
    const val MAX_REASON_CHARS = 160
    const val HANDSHAKE_MS = 5_000L
    const val DEATH_ACK_MS = 5_000L
    val noncePattern = Regex("[a-f0-9]{64}")
    fun validNonce(value: String?) = value != null && noncePattern.matches(value)
    fun candidate(backend: String?, threads: Int): RuntimeCandidate {
        require(backend in setOf("CPU", "GPU") && threads in 1..4)
        return RuntimeCandidate(RuntimeBackend.valueOf(requireNotNull(backend)), threads)
    }
    fun validIdentity(expectedName: String, actualName: String?, parentPid: Int, workerPid: Int,
        expectedUid: Int, actualUid: Int): Boolean = actualName == expectedName &&
        expectedName.endsWith(PROCESS_SUFFIX) && parentPid > 0 && workerPid > 0 && workerPid != parentPid &&
        actualUid == expectedUid

    fun processName(context: Context): String? = if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else {
        context.getSystemService(ActivityManager::class.java)?.runningAppProcesses
            ?.firstOrNull { it.pid == Process.myPid() }?.processName ?: runCatching {
            File("/proc/self/cmdline").inputStream().use { input ->
                val bytes = ByteArray(512)
                val count = input.read(bytes)
                if (count <= 0) null else String(bytes, 0, count, Charsets.UTF_8).substringBefore('\u0000')
            }
        }.getOrNull()
    }
}

internal enum class FixtureRuntimeStage(val token: String, val budgetMs: Long) {
    REFERENCES("references", 45_000L), PRIMARY_LOAD("primary_load", 20_000L),
    AUXILIARY_LOAD("auxiliary_load", 20_000L), OUTPUT_CHECK("output_check", 30_000L),
    BENCHMARK("benchmark", 25_000L), CLOSING("closing", 5_000L);
    companion object { fun parse(value: String?) = entries.firstOrNull { it.token == value } }
}

/** Process-wide ownership survives Activity/check replacement and is released only after death ACK. */
internal object FixtureRuntimeSupervisor {
    private var owner: Any? = null
    @Synchronized fun acquire(value: Any): Boolean = if (owner == null) { owner = value; true } else false
    @Synchronized fun owns(value: Any): Boolean = owner === value
    @Synchronized fun release(value: Any, confirmed: Boolean): Boolean =
        if (confirmed && owner === value) { owner = null; true } else false
}

/** One process accepts one nonce and one candidate for its entire lifetime. */
internal class FixtureRuntimeAttemptGate {
    private var nonce: String? = null
    private val started = AtomicBoolean(false)
    @Synchronized fun hello(value: String): Boolean {
        if (!FixtureRuntimeProtocol.validNonce(value) || nonce != null && nonce != value) return false
        nonce = value
        return true
    }
    @Synchronized fun accepts(value: String) = nonce == value
    fun start(value: String): Boolean = accepts(value) && started.compareAndSet(false, true)
}

/** Evidence authority is separate from a timeout report, unbind and native-close acknowledgement. */
internal class FixtureRuntimeAuthority(private val nonce: String) {
    @Volatile var verified = false
        private set
    @Volatile var retired = false
        private set
    private var nativeClosed = false
    private var deathAcknowledged = false
    @Synchronized fun authenticate(value: String?, identityMatches: Boolean): Boolean {
        if (value != nonce || !FixtureRuntimeProtocol.validNonce(value) || !identityMatches) return false
        verified = true
        return true
    }
    @Synchronized fun accepts(value: String?) = verified && !retired && value == nonce
    @Synchronized fun recordResult(value: String?, closed: Boolean): Boolean {
        if (!accepts(value)) return false
        nativeClosed = closed
        return true
    }
    @Synchronized fun retire() { retired = true }
    @Synchronized fun observeDeath() { retired = true; deathAcknowledged = true }
    fun publishDeath(receipt: CompletableFuture<Unit>, beforePublication: () -> Unit) {
        observeDeath()
        beforePublication()
        receipt.complete(Unit)
    }
    // Before HELLO there cannot be native START. Actual endpoint death can recover that empty child.
    val releaseConfirmed: Boolean get() = synchronized(this) { deathAcknowledged }
    val canAdopt: Boolean get() = synchronized(this) { verified && nativeClosed && deathAcknowledged }
}
