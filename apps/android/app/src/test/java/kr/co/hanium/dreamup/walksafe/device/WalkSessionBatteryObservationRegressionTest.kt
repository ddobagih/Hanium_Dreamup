package kr.co.hanium.dreamup.walksafe.device

import android.os.BatteryManager
import android.os.PowerManager
import java.io.File
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import kr.co.hanium.dreamup.walksafe.session.WalkSessionReadinessStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkSessionBatteryObservationRegressionTest {
    @Test
    fun legacyLowFlagReaderCollapsesDifferentValidQuantitativeInputs() {
        val inputs = listOf(Triple(false, 90, -320_000), Triple(false, 35, -820_000))
        assertNotEquals(inputs[0].second, inputs[1].second)
        assertNotEquals(inputs[0].third, inputs[1].third)
        val snapshots = inputs.map { input ->
            readWalkSessionDeviceResourceSnapshot(
                batteryNotLow = { !input.first },
                privateStorageAboveSystemLow = { true },
                thermalStatus = { PowerManager.THERMAL_STATUS_NONE },
            )
        }
        assertEquals(true, snapshots[0].batteryNotLow)
        assertEquals(true, snapshots[1].batteryNotLow)
        assertEquals(snapshots[0], snapshots[1])
        println("S14-40 legacy actual reader: same low=false; level90/35 current-320000/-820000 collapse=true")
    }

    @Test
    fun productionSourceConnectsRawBatteryInputsToTheSnapshot() {
        val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/device/AndroidWalkSessionResourceProbe.kt").readText()
        val snapshot = source.substringAfter("fun snapshot(): WalkSessionDeviceResourceSnapshot")
        listOf(
            "BatteryManager.EXTRA_LEVEL",
            "BatteryManager.EXTRA_SCALE",
            "BatteryManager.BATTERY_PROPERTY_CURRENT_NOW",
            "BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE",
            "readWalkSessionBatteryObservation(",
            "batteryObservation = { batteryObservation }",
        ).forEach { token -> assertTrue("SOURCE-only production input missing: $token", snapshot.contains(token)) }
    }

    @Test
    fun quantitativeProducerDistinguishesTheSameLowFlagInputsAndPreservesSignedValues() {
        val first = snapshot { observation(level = 90, now = { -320_000 }, average = { -300_000 }, receipt = { 101L }) }
        val second = snapshot { observation(level = 35, now = { -820_000 }, average = { -800_000 }, receipt = { 202L }) }

        assertEquals(first.batteryNotLow, second.batteryNotLow)
        assertNotEquals(first.batteryObservation, second.batteryObservation)
        assertEquals(90, first.batteryObservation.level)
        assertEquals(35, second.batteryObservation.level)
        assertEquals(100, first.batteryObservation.scale)
        assertEquals(-320_000, first.batteryObservation.currentNowMicroamps)
        assertEquals(-820_000, second.batteryObservation.currentNowMicroamps)
        assertEquals(-300_000, first.batteryObservation.currentAverageMicroamps)
        assertEquals(-800_000, second.batteryObservation.currentAverageMicroamps)
        assertEquals(WalkSessionBatteryObservationSource.ANDROID_SYSTEM, first.batteryObservation.source)
        assertEquals(101L, first.batteryObservation.queryReceivedAtElapsedRealtimeMs)
        assertEquals(202L, second.batteryObservation.queryReceivedAtElapsedRealtimeMs)
        assertNull(first.batteryObservation.measuredAtElapsedRealtimeMs)
        assertNull(second.batteryObservation.measuredAtElapsedRealtimeMs)
        assertEquals(WalkSessionReadinessStatus.READY, first.readinessStatus)
        assertEquals(WalkSessionReadinessStatus.READY, second.readinessStatus)
        println("S14-40 producer: low=false level90/35 scale100 current-320000/-820000 average-300000/-800000 queryReceipt101/202 measuredAt=null")
    }

    @Test
    fun chargingAndFullRemainRawStatusWithPositiveCurrent() {
        listOf(
            BatteryManager.BATTERY_STATUS_CHARGING to WalkSessionBatteryChargeStatus.CHARGING,
            BatteryManager.BATTERY_STATUS_FULL to WalkSessionBatteryChargeStatus.FULL,
            BatteryManager.BATTERY_STATUS_NOT_CHARGING to WalkSessionBatteryChargeStatus.NOT_CHARGING,
        ).forEach { (status, expected) ->
            val actual = observation(status = status, plugged = BatteryManager.BATTERY_PLUGGED_USB,
                now = { 150_000 }, average = { 120_000 })
            assertEquals(expected, actual.chargeStatus)
            assertEquals(true, actual.pluggedIn)
            assertEquals(150_000, actual.currentNowMicroamps)
            assertEquals(120_000, actual.currentAverageMicroamps)
            assertNull(actual.measuredAtElapsedRealtimeMs)
        }
    }

    @Test
    fun missingAndInvalidLevelScaleStayUnknownWithoutDiscardingCurrent() {
        listOf(null to 100, 10 to null, -1 to 100, 101 to 100, 1 to 0, 0 to -1, Int.MIN_VALUE to 100).forEach { (level, scale) ->
            val actual = observation(level = level, scale = scale)
            assertNull(actual.level)
            assertNull(actual.scale)
            assertTrue(WalkSessionBatteryUnknownReason.LEVEL_UNAVAILABLE in actual.unknownReasons)
            assertEquals(-320_000, actual.currentNowMicroamps)
        }
        val empty = observation(level = 0, scale = 100)
        assertEquals(0, empty.level)
        assertEquals(100, empty.scale)
    }

    @Test
    fun unsupportedSentinelsAndLegacyZeroDifferFromAReportedModernZero() {
        val unavailable = observation(now = { Int.MIN_VALUE }, average = { null })
        assertNull(unavailable.currentNowMicroamps)
        assertNull(unavailable.currentAverageMicroamps)
        assertTrue(WalkSessionBatteryUnknownReason.CURRENT_NOW_UNAVAILABLE in unavailable.unknownReasons)
        assertTrue(WalkSessionBatteryUnknownReason.CURRENT_AVERAGE_UNAVAILABLE in unavailable.unknownReasons)
        assertEquals(90, unavailable.level)

        val legacy = observation(now = { 0 }, average = { 0 }, targetSdkVersion = 27)
        assertNull(legacy.currentNowMicroamps)
        assertNull(legacy.currentAverageMicroamps)
        val modern = observation(now = { 0 }, average = { 0 }, targetSdkVersion = 36)
        assertEquals(0, modern.currentNowMicroamps)
        assertEquals(0, modern.currentAverageMicroamps)
        assertEquals(emptySet<WalkSessionBatteryUnknownReason>(), modern.unknownReasons)
        assertNull(modern.measuredAtElapsedRealtimeMs)
    }

    @Test
    fun eachCurrentPropertyFailurePreservesTheOtherFactAndTheKnownLowSignal() {
        val nowFailed = snapshot {
            observation(now = { throw SecurityException("must not retain private details") }, average = { -300_000 })
        }
        val averageFailed = snapshot {
            observation(now = { -320_000 }, average = { throw IllegalStateException("must not retain private details") })
        }
        assertNull(nowFailed.batteryObservation.currentNowMicroamps)
        assertEquals(-300_000, nowFailed.batteryObservation.currentAverageMicroamps)
        assertEquals(-320_000, averageFailed.batteryObservation.currentNowMicroamps)
        assertNull(averageFailed.batteryObservation.currentAverageMicroamps)
        listOf(nowFailed, averageFailed).forEach { actual ->
            assertEquals(true, actual.batteryNotLow)
            assertEquals(90, actual.batteryObservation.level)
            assertEquals(true, actual.privateStorageAboveSystemLow)
            assertEquals(true, actual.thermalBelowCritical)
            assertEquals(false, actual.thermalThrottled)
            assertEquals(emptySet<WalkSessionResourceQuery>(), actual.failedQueries)
            assertEquals(emptyMap<WalkSessionResourceQuery, String>(), actual.queryFailureReasons)
            assertEquals(WalkSessionReadinessStatus.READY, actual.readinessStatus)
            assertTrue(WalkSessionBatteryUnknownReason.QUERY_FAILED in actual.batteryObservation.unknownReasons)
        }
    }

    @Test
    fun optionalSupplierFailureAndDefaultsDoNotEraseKnownResourceFacts() {
        val actual = readWalkSessionDeviceResourceSnapshot(
            batteryNotLow = { true },
            privateStorageAboveSystemLow = { true },
            thermalStatus = { PowerManager.THERMAL_STATUS_NONE },
            batteryObservation = { throw SecurityException("quantitative query failed") },
        )
        assertEquals(true, actual.batteryNotLow)
        assertEquals(WalkSessionReadinessStatus.READY, actual.readinessStatus)
        assertEquals(WalkSessionBatteryObservationSource.UNKNOWN, actual.batteryObservation.source)
        assertEquals(setOf(WalkSessionBatteryUnknownReason.QUERY_FAILED), actual.batteryObservation.unknownReasons)
        val default = readWalkSessionDeviceResourceSnapshot({ true }, { true }, { PowerManager.THERMAL_STATUS_NONE })
        assertEquals(WalkSessionBatteryObservationSource.UNKNOWN, default.batteryObservation.source)
        assertNull(default.batteryObservation.queryReceivedAtElapsedRealtimeMs)
        assertNull(default.batteryObservation.measuredAtElapsedRealtimeMs)
    }

    @Test
    fun unknownLowBooleanAndItsQueryExceptionStillKeepReadinessPending() {
        val missing = snapshot(notLow = null) { observation() }
        assertEquals(WalkSessionReadinessStatus.PENDING, missing.readinessStatus)
        assertEquals("battery_state_pending", missing.reason)
        assertEquals(90, missing.batteryObservation.level)
        val failed = readWalkSessionDeviceResourceSnapshot(
            batteryNotLow = { throw SecurityException("low query unavailable") },
            privateStorageAboveSystemLow = { true },
            thermalStatus = { PowerManager.THERMAL_STATUS_NONE },
        )
        assertNull(failed.batteryNotLow)
        assertEquals(WalkSessionReadinessStatus.PENDING, failed.readinessStatus)
        assertEquals(setOf(WalkSessionResourceQuery.BATTERY), failed.failedQueries)
        assertEquals("battery_state_query_failed", failed.reason)
        assertEquals(WalkSessionBatteryObservationSource.UNKNOWN, failed.batteryObservation.source)
    }

    @Test
    fun repeatedStickyFactsAndRegressingQueryReceiptsNeverBecomeSampleTimes() {
        val observations = listOf(101L, 101L, 202L, 90L).map { time -> observation(receipt = { time }) }
        observations.zip(listOf(101L, 101L, 202L, 90L)).forEach { (actual, originalReceipt) ->
            assertEquals(originalReceipt, actual.queryReceivedAtElapsedRealtimeMs)
            assertNull(actual.measuredAtElapsedRealtimeMs)
            assertEquals(90, actual.level)
            assertEquals(-320_000, actual.currentNowMicroamps)
            assertEquals(-300_000, actual.currentAverageMicroamps)
        }
        assertEquals(observations[0], observations[1])
    }

    @Test
    fun invalidQueryClockDoesNotInventReceiptOrEraseIndependentlyReadRawFacts() {
        listOf<() -> Long>({ -1L }, { throw IllegalStateException("clock unavailable") }).forEach { clock ->
            val actual = observation(receipt = clock)
            assertNull(actual.queryReceivedAtElapsedRealtimeMs)
            assertNull(actual.measuredAtElapsedRealtimeMs)
            assertTrue(WalkSessionBatteryUnknownReason.RECEIPT_TIME_UNAVAILABLE in actual.unknownReasons)
            assertEquals(90, actual.level)
            assertEquals(-320_000, actual.currentNowMicroamps)
        }
    }

    @Test
    fun absentRawInputsKeepUnknownSourceAndEachMissingFact() {
        val actual = observation(level = null, scale = null, status = null, plugged = -1,
            now = { null }, average = { Int.MIN_VALUE })
        assertEquals(WalkSessionBatteryObservationSource.UNKNOWN, actual.source)
        assertEquals(WalkSessionBatteryChargeStatus.UNKNOWN, actual.chargeStatus)
        assertNull(actual.pluggedIn)
        assertNull(actual.level)
        assertNull(actual.scale)
        assertNull(actual.currentNowMicroamps)
        assertNull(actual.currentAverageMicroamps)
        assertTrue(actual.unknownReasons.containsAll(setOf(
            WalkSessionBatteryUnknownReason.LEVEL_UNAVAILABLE,
            WalkSessionBatteryUnknownReason.CURRENT_NOW_UNAVAILABLE,
            WalkSessionBatteryUnknownReason.CURRENT_AVERAGE_UNAVAILABLE,
            WalkSessionBatteryUnknownReason.CHARGE_STATUS_UNAVAILABLE,
            WalkSessionBatteryUnknownReason.POWER_CONNECTION_UNAVAILABLE,
        )))
        assertNull(actual.measuredAtElapsedRealtimeMs)
    }

    @Test
    fun receiptIsReadAfterIndependentCurrentQueriesExactlyOnce() {
        val calls = mutableListOf<String>()
        val actual = observation(
            now = { calls.add("current_now"); -320_000 },
            average = { calls.add("current_average"); -300_000 },
            receipt = { calls.add("query_receipt"); 303L },
        )
        assertEquals(listOf("current_now", "current_average", "query_receipt"), calls)
        assertEquals(303L, actual.queryReceivedAtElapsedRealtimeMs)
        assertNull(actual.measuredAtElapsedRealtimeMs)
    }

    @Test
    fun rawFactsCannotOverrideTheExistingCriticalStopOrItsSafetyEvidence() {
        val actual = snapshot(notLow = false) {
            observation(now = { throw IllegalStateException("current unavailable") })
        }
        assertEquals(WalkSessionReadinessStatus.UNAVAILABLE, actual.readinessStatus)
        assertEquals("battery_low", actual.reason)
        val safety = actual.toRuntimeSafetyObservation(
            WalkRuntimeEpoch("battery-direct", 7L), 301L, 302L,
        )
        assertEquals(true, safety.batteryCritical)
        assertEquals(false, safety.storageCritical)
        assertEquals(false, safety.thermalCritical)
        assertEquals(301L, safety.observedAtElapsedRealtimeMs)
        assertEquals(302L, safety.receivedAtElapsedRealtimeMs)
    }

    private fun snapshot(
        notLow: Boolean? = true,
        read: () -> WalkSessionBatteryObservation,
    ): WalkSessionDeviceResourceSnapshot {
        var observation = WalkSessionBatteryObservation()
        return readWalkSessionDeviceResourceSnapshot(
            batteryNotLow = { observation = read(); notLow },
            privateStorageAboveSystemLow = { true },
            thermalStatus = { PowerManager.THERMAL_STATUS_NONE },
            batteryObservation = { observation },
        )
    }

    private fun observation(
        level: Int? = 90,
        scale: Int? = 100,
        status: Int? = BatteryManager.BATTERY_STATUS_DISCHARGING,
        plugged: Int? = 0,
        now: () -> Int? = { -320_000 },
        average: () -> Int? = { -300_000 },
        targetSdkVersion: Int = 36,
        receipt: () -> Long = { 101L },
    ): WalkSessionBatteryObservation = readWalkSessionBatteryObservation(
        level, scale, status, plugged, now, average, targetSdkVersion, receipt,
    )
}
