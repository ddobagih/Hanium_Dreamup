package kr.co.hanium.dreamup.walksafe.runtime.vision.remote

import android.app.Application
import android.content.Context
import android.os.Process
import android.provider.Settings
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import kr.co.hanium.dreamup.walksafe.device.DeviceCheckNativeProcess
import kr.co.hanium.dreamup.walksafe.device.DeviceCheckProcessObservation
import kr.co.hanium.dreamup.walksafe.device.DeviceCheckProcessObserver
import java.io.File

/** Identity capture performs no inference and never sends a terminating signal to any process. */
object VisionProcessIdentity {
    private val BOOT_UUID = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")

    @JvmStatic fun captureSelf(context: Context): DeviceCheckNativeProcess? = capture(context, Process.myPid(), null)

    /** Parent re-reads the child's exact boot provider; cross-provider values cannot authenticate it. */
    @JvmStatic fun captureVerified(context: Context, claimed: DeviceCheckNativeProcess): DeviceCheckNativeProcess? {
        if (!validChild(context, claimed)) return null
        return capture(context, claimed.pid, provider(claimed.bootIdentity))?.takeIf { it == claimed }
    }

    internal fun capture(context: Context, pid: Int, bootProvider: String?): DeviceCheckNativeProcess? = runCatching {
        require(pid > 0)
        val ticks = requireNotNull(parseStartTicks(readLimited("/proc/$pid/stat", 4096), pid))
        val uid = if (pid == Process.myPid()) Process.myUid() else {
            val row = readLimited("/proc/$pid/status", 16384).lineSequence().first { it.startsWith("Uid:") }
            row.substringAfter(':').trim().split(Regex("\\s+")).first().toInt()
        }
        val name = if (pid == Process.myPid()) Application.getProcessName()
            else readLimited("/proc/$pid/cmdline", 1024).substringBefore('\u0000')
        require(name.isNotBlank() && name.length <= 256)
        DeviceCheckNativeProcess(pid, uid, name, ticks, requireNotNull(bootIdentity(context, bootProvider)))
    }.getOrNull()

    internal fun validChild(context: Context, saved: DeviceCheckNativeProcess) = saved.pid > 0 &&
        saved.pid != Process.myPid() && saved.uid == Process.myUid() && saved.startTicks > 0 &&
        saved.processName in setOf(context.packageName + VisionRuntimeProtocol.PROCESS_SUFFIX,
            context.packageName + ":metric_support") && validBoot(saved.bootIdentity)

    internal fun parseStartTicks(stat: String, expectedPid: Int): Long? = runCatching {
        require(stat.substringBefore(' ').toInt() == expectedPid)
        // comm (field 2) may contain whitespace and parentheses; fields after its last ')' are stable.
        val end = stat.lastIndexOf(')')
        require(end >= 0 && stat.indexOf('(') in 1 until end)
        val fields = stat.substring(end + 1).trim().split(Regex("\\s+"))
        fields[19].toLong().also { require(it > 0) } // stat field 22; suffix starts at field 3.
    }.getOrNull()

    internal fun provider(identity: String): String? = identity.substringBefore(':').takeIf {
        it == "boot-id" || it == "boot-count"
    }
    internal fun validBoot(identity: String): Boolean = when (provider(identity)) {
        "boot-id" -> BOOT_UUID.matches(identity.substringAfter(':'))
        "boot-count" -> identity.substringAfter(':').let { text ->
            text.toLongOrNull()?.let { it >= 0 && it.toString() == text } == true
        }
        else -> false
    }
    internal fun bootIdentity(context: Context, requestedProvider: String? = null): String? {
        if (requestedProvider == null || requestedProvider == "boot-id") {
            val id = runCatching { readLimited("/proc/sys/kernel/random/boot_id", 128).trim().lowercase() }.getOrNull()
            if (id != null && BOOT_UUID.matches(id)) return "boot-id:$id"
            if (requestedProvider == "boot-id") return null
        }
        if (requestedProvider == null || requestedProvider == "boot-count") {
            val count = runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1) }.getOrNull()
            if (count != null && count >= 0) return "boot-count:$count"
        }
        return null
    }
    internal fun provesDifferentBoot(saved: String, current: String?) = current != null &&
        validBoot(saved) && validBoot(current) && provider(saved) == provider(current) && saved != current

    internal fun comparePresent(saved: DeviceCheckNativeProcess, current: DeviceCheckNativeProcess?): DeviceCheckProcessObservation {
        if (current == null || !validBoot(saved.bootIdentity) || !validBoot(current.bootIdentity) ||
            saved.startTicks <= 0 || current.startTicks <= 0 || provider(saved.bootIdentity) != provider(current.bootIdentity) ||
            saved.pid != current.pid) return DeviceCheckProcessObservation.UNKNOWN
        if (saved.bootIdentity != current.bootIdentity || saved.startTicks != current.startTicks) return DeviceCheckProcessObservation.ABSENT
        return if (saved == current) DeviceCheckProcessObservation.SAME_PROCESS else DeviceCheckProcessObservation.UNKNOWN
    }
    private fun readLimited(path: String, maximum: Int): String = File(path).inputStream().use { input ->
        val bytes = ByteArray(maximum + 1)
        var used = 0
        while (used < bytes.size) { val count = input.read(bytes, used, bytes.size - used); if (count < 0) break; used += count }
        require(used in 1..maximum)
        String(bytes, 0, used, Charsets.UTF_8)
    }
}

/** Recovery is positive evidence of the recorded child's absence, never an inference from an I/O error. */
class AndroidVisionProcessObserver(context: Context) : DeviceCheckProcessObserver {
    private val app = context.applicationContext
    override fun observe(process: DeviceCheckNativeProcess): DeviceCheckProcessObservation {
        if (!VisionProcessIdentity.validChild(app, process)) return DeviceCheckProcessObservation.UNKNOWN
        val boot = VisionProcessIdentity.bootIdentity(app, VisionProcessIdentity.provider(process.bootIdentity))
        if (VisionProcessIdentity.provesDifferentBoot(process.bootIdentity, boot)) return DeviceCheckProcessObservation.ABSENT
        try {
            // kill(2), signal 0: existence/permission probe only. No process receives a terminating signal.
            Os.kill(process.pid, 0)
        } catch (error: ErrnoException) {
            return if (error.errno == OsConstants.ESRCH) DeviceCheckProcessObservation.ABSENT else DeviceCheckProcessObservation.UNKNOWN
        } catch (_: Throwable) { return DeviceCheckProcessObservation.UNKNOWN }
        return VisionProcessIdentity.comparePresent(process,
            VisionProcessIdentity.capture(app, process.pid, VisionProcessIdentity.provider(process.bootIdentity)))
    }
}
