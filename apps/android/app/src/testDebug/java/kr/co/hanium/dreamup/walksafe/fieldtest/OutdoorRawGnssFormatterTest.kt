package kr.co.hanium.dreamup.walksafe.fieldtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutdoorRawGnssFormatterTest {
    @Test
    fun preservesExactIntegerClockAndReceiverAttributesInNamedColumns() {
        val row = rowMap(clock(), measurement())
        assertEquals("-1312395720123456789", row["FullBiasNanos"])
        assertEquals("5000000000", row["TimeNanos"])
        assertEquals("259180000000000", row["ReceivedSvTimeNanos"])
        assertEquals("Q", row["CodeType"])
        assertEquals("9", row["AccumulatedDeltaRangeState"])
        assertEquals("GNSS_CLOCK", row["UtcTimeSource"])
        assertEquals(OutdoorRawGnssFormatter.fields.size, row.size)
    }

    @Test
    fun missingLeapCodeFrequencyAndFullBiasStayEmptyWhileObservedDeviceUtcIsPreserved() {
        val row = rowMap(clock().copy(fullBiasNanos = null, leapSecond = null), measurement().copy(codeType = null, carrierFrequencyHz = null))
        for (field in listOf("FullBiasNanos", "LeapSecond", "CodeType", "CarrierFrequencyHz")) assertEquals("", row[field])
        assertEquals("1999877", row["utcTimeMillis"])
        assertEquals("DEVICE_UTC_PROJECTED", row["UtcTimeSource"])
        assertEquals("2000000", row["CallbackUtcTimeMillis"])
    }

    @Test
    fun computesUtcWithSubMillisecondPrecisionBeforeRounding() {
        val clock = clock().copy(timeNanos = 5_000_999_900, fullBiasNanos = -1_000_000_000_000_000_000, biasNanos = 100.0)
        val result = OutdoorRawGnssFormatter.utcStamp(clock, 300.0, 0, 0)
        assertEquals(1_315_964_787_001L, result.millis)
        assertEquals("GNSS_CLOCK", result.source)
    }

    @Test
    fun neverTurnsBootElapsedTimeIntoUnixUtcWhenReceiverTimestampIsUnavailable() {
        val clock = clock().copy(leapSecond = null, elapsedRealtimeNanos = null)
        assertEquals(OutdoorRawGnssFormatter.UtcStamp(2_000_000, "DEVICE_UTC_CALLBACK"),
            OutdoorRawGnssFormatter.utcStamp(clock, 0.0, 2_000_000, 9_123_000_000))
    }

    @Test
    fun producesUniqueParserHeaderAndSanitizesMetadataWithoutAddingRawRows() {
        val header = OutdoorRawGnssFormatter.header("S20\nRaw,bogus", "Sam,sung")
        assertEquals(1, header.lineSequence().count { it.startsWith("# Raw,") })
        assertFalse(header.lineSequence().any { it.startsWith("Raw,") })
        assertEquals(OutdoorRawGnssFormatter.fields.size, OutdoorRawGnssFormatter.fields.toSet().size)
        assertTrue(OutdoorRawGnssFormatter.fields.containsAll(listOf("utcTimeMillis", "FullBiasNanos", "CodeType", "CarrierFrequencyHz")))
    }

    private fun rowMap(clock: OutdoorRawGnssClock, measurement: OutdoorRawGnssMeasurement): Map<String, String> {
        val row = OutdoorRawGnssFormatter.rawRow(clock, measurement, 2_000_000, 9_123_000_000).trimEnd().split(',')
        assertEquals("Raw", row.first())
        assertEquals(OutdoorRawGnssFormatter.fields.size + 1, row.size)
        return OutdoorRawGnssFormatter.fields.zip(row.drop(1)).toMap()
    }

    private fun clock() = OutdoorRawGnssClock(5_000_000_000, 18, null, -1_312_395_720_123_456_789,
        0.25, 0.0, 3, 9_000_000_000)

    private fun measurement() = OutdoorRawGnssMeasurement(11, 0.0, 9, 259_180_000_000_000, 10,
        35.0, -3.0, 0.03, 9, -12_345.5, 0.005, 1_176_450_000f, 0, 1, "Q")
}
