package kr.co.hanium.dreamup.walksafe.runtime.vision.remote

import kr.co.hanium.dreamup.walksafe.device.DeviceCheckNativeProcess
import kr.co.hanium.dreamup.walksafe.device.DeviceCheckProcessObservation
import org.junit.Assert.*
import org.junit.Test

class VisionProcessIdentityTest {
    private val boot = "boot-id:00000000-0000-0000-0000-000000000001"
    private val saved = DeviceCheckNativeProcess(42, 10001, "app:vision_runtime", 100, boot)
    private fun stat(pid: Int, command: String, ticks: String) = "$pid ($command) " +
        (listOf("S") + List(18) { "0" } + ticks).joinToString(" ")

    @Test fun statParserDoesNotConfuseSpacesOrParenthesesWithFieldBoundaries() {
        assertEquals(100L, VisionProcessIdentity.parseStartTicks(stat(42, "worker ) with ( parentheses", "100"), 42))
        assertNull(VisionProcessIdentity.parseStartTicks(stat(43, "worker", "100"), 42))
        assertNull(VisionProcessIdentity.parseStartTicks(stat(42, "worker", "-1"), 42))
        assertNull(VisionProcessIdentity.parseStartTicks(stat(42, "worker", "9223372036854775808"), 42))
        assertNull(VisionProcessIdentity.parseStartTicks("42 (truncated)", 42))
    }
    @Test fun onlySameProviderCanProveThatTheOldBootEnded() {
        assertTrue(VisionProcessIdentity.provesDifferentBoot(boot, "boot-id:00000000-0000-0000-0000-000000000002"))
        assertFalse(VisionProcessIdentity.provesDifferentBoot(boot, "boot-count:2"))
        assertFalse(VisionProcessIdentity.provesDifferentBoot(boot, null))
        assertFalse(VisionProcessIdentity.provesDifferentBoot("boot-id:malformed", boot))
    }
    @Test fun unreadableOrDifferentProviderFactsAreUnknownAndNeverAbsent() {
        assertEquals(DeviceCheckProcessObservation.UNKNOWN, VisionProcessIdentity.comparePresent(saved, null))
        assertEquals(DeviceCheckProcessObservation.UNKNOWN, VisionProcessIdentity.comparePresent(saved, saved.copy(bootIdentity = "boot-count:1")))
        assertEquals(DeviceCheckProcessObservation.UNKNOWN, VisionProcessIdentity.comparePresent(saved, saved.copy(processName = "app:vosk_decoder")))
        assertEquals(DeviceCheckProcessObservation.UNKNOWN, VisionProcessIdentity.comparePresent(saved, saved.copy(uid = 10002)))
    }
    @Test fun sameBootPidReuseProvesOnlyTheRecordedProcessAbsent() {
        assertEquals(DeviceCheckProcessObservation.SAME_PROCESS, VisionProcessIdentity.comparePresent(saved, saved.copy()))
        assertEquals(DeviceCheckProcessObservation.ABSENT, VisionProcessIdentity.comparePresent(saved, saved.copy(startTicks = 101)))
        assertEquals(DeviceCheckProcessObservation.UNKNOWN, VisionProcessIdentity.comparePresent(saved, saved.copy(pid = 43)))
    }
}
