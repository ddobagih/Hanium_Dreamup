/*
 * Measurement filtering and RINEX layout are adapted from android_rinex:
 * https://github.com/rtklibexplorer/android_rinex
 * commit b27fcd07bc085e5213ba29655ad6168b61d60b9e
 * Copyright (c) 2017, Rokubun. BSD-2-Clause license; see THIRD_PARTY_NOTICES.txt.
 */
package kr.co.hanium.dreamup.walksafe.positioneval.core

import java.io.File
import java.io.FileOutputStream
import java.io.Writer
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.round
import kotlin.math.roundToInt

data class RinexConversionSummary(
    val rawMeasurementCount: Int,
    val acceptedMeasurementCount: Int,
    val epochCount: Int,
    val satelliteCount: Int,
    val carrierPhaseCount: Int,
    val excludedByReason: Map<String, Int>,
)

class GnssLoggerConversionException(
    val reasonCode: String,
    message: String,
) : IllegalArgumentException(message)

object GnssLoggerRinex3Converter {
    private const val SPEED_OF_LIGHT_MPS = 299_792_458.0
    private const val GPS_WEEK_SECONDS = 604_800.0
    private const val GPS_WEEK_NANOS = 604_800_000_000_000L
    private const val DAY_SECONDS = 86_400.0
    private const val NS_TO_SECONDS = 1e-9
    private const val TIME_ADJUSTMENT_SECONDS = 1e-7
    private const val MAX_RECEIVED_TIME_UNCERTAINTY_NS = 500.0
    private const val MAX_BIAS_UNCERTAINTY_NS = 1e6
    private const val MAX_ADR_UNCERTAINTY_M = 0.1
    private const val MIN_CN0_DB_HZ = 20.0
    private const val MAX_RAW_RECORDS = 2_000_000
    private const val BDS_TO_GPS_SECONDS = 14.0
    private const val GLONASS_TO_UTC_SECONDS = 10_800.0
    private const val LEAP_VALIDATION_TOLERANCE_MS = 100L

    private const val STATE_CODE_LOCK = 0x00000001
    private const val STATE_TOW_DECODED = 0x00000008
    private const val STATE_MSEC_AMBIGUOUS = 0x00000010
    private const val STATE_GLO_TOD_DECODED = 0x00000080
    private const val STATE_GAL_E1BC_CODE_LOCK = 0x00000400
    private const val STATE_TOW_KNOWN = 0x00004000
    private const val STATE_GLO_TOD_KNOWN = 0x00008000

    private const val ADR_STATE_VALID = 0x01
    private const val ADR_STATE_RESET = 0x02
    private const val ADR_STATE_CYCLE_SLIP = 0x04
    private const val ADR_STATE_HALF_CYCLE_RESOLVED = 0x08

    private val gpsEpoch = LocalDateTime.of(1980, 1, 6, 0, 0)
    private val generatedAtFormatter = DateTimeFormatter.ofPattern("yyyyMMdd HHmmss 'UTC'", Locale.US)

    fun convert(
        input: File,
        output: File,
        generatedAtUtc: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC),
    ): RinexConversionSummary {
        if (!input.isFile) fail("INPUT_NOT_FOUND", "GnssLogger TXT 파일을 찾을 수 없습니다.")
        if (output.exists()) fail("OUTPUT_ALREADY_EXISTS", "기존 RINEX 출력 파일을 덮어쓰지 않습니다.")
        val scan = scan(input)
        output.parentFile?.let { parent ->
            if (!parent.isDirectory && !parent.mkdirs()) fail("OUTPUT_DIRECTORY_UNAVAILABLE", "RINEX 출력 폴더를 만들 수 없습니다.")
        }
        val part = File(output.parentFile, "${output.name}.part")
        if (part.exists() && !part.delete()) fail("OUTPUT_STAGING_UNAVAILABLE", "RINEX 임시 파일을 준비할 수 없습니다.")
        return try {
            var body = BodySummary(0, 0, 0, 0)
            FileOutputStream(part).use { stream ->
                val writer = stream.bufferedWriter(Charsets.US_ASCII)
                writeHeader(writer, scan, generatedAtUtc)
                body = writeBody(input, scan, writer)
                writer.flush()
                stream.fd.sync()
            }
            if (body.epochCount < 2 || body.satelliteCount < 4 || body.acceptedCount < 8 || body.carrierPhaseCount < 4) {
                fail("INSUFFICIENT_VALID_MEASUREMENTS", "PPK에 필요한 2 epoch, 4 위성, 8 코드, 4 반송파 관측값을 충족하지 못했습니다.")
            }
            if (body.acceptedCount != scan.acceptedCount || body.carrierPhaseCount != scan.carrierPhaseCount) {
                fail("INTERNAL_CONVERSION_MISMATCH", "RINEX 사전 검사와 본문 변환 결과가 일치하지 않습니다.")
            }
            if (!part.renameTo(output)) fail("OUTPUT_COMMIT_FAILED", "RINEX 출력 파일을 확정하지 못했습니다.")
            RinexConversionSummary(
                scan.rawCount,
                body.acceptedCount,
                body.epochCount,
                body.satelliteCount,
                body.carrierPhaseCount,
                scan.excludedByReason,
            )
        } catch (error: Throwable) {
            part.delete()
            throw error
        }
    }

    private fun scan(input: File): ScanResult {
        var schema: RawSchema? = null
        var model = "unknown"
        var rawCount = 0
        var acceptedCount = 0
        var firstEpoch: GpsEpoch? = null
        var lastEpoch: GpsEpoch? = null
        var effectiveLeapSecond: Int? = null
        var currentBatch: BatchKey? = null
        var currentBatchEpoch: GpsEpoch? = null
        var currentBatchHasAccepted = false
        var currentBatchSuppressCarrierPhase = false
        var previousAcceptedEpoch: GpsEpoch? = null
        var usableEpochCount = 0
        var carrierPhaseCount = 0
        val currentObservables = mutableSetOf<String>()
        val currentBatchSatellites = mutableSetOf<String>()
        val satellites = mutableSetOf<String>()
        val observationCodes = linkedMapOf<Char, MutableSet<String>>()
        val glonassChannels = linkedMapOf<String, Int>()
        val excludedByReason = linkedMapOf<String, Int>()
        val clock = ClockTracker()

        fun exclude(reason: String) {
            excludedByReason[reason] = excludedByReason.getOrDefault(reason, 0) + 1
        }

        fun finishBatch() {
            val epoch = currentBatchEpoch
            if (epoch != null && currentBatchHasAccepted) {
                val previous = previousAcceptedEpoch
                if (previous != null && epoch.totalGpsSeconds <= previous.totalGpsSeconds + 5e-8) {
                    fail("NON_MONOTONIC_CORRECTED_EPOCH", "보정된 GNSS epoch 시각이 증가하지 않습니다.")
                }
                if (firstEpoch == null) firstEpoch = epoch
                lastEpoch = epoch
                previousAcceptedEpoch = epoch
                if (currentBatchSatellites.size >= 4) usableEpochCount++
            }
            currentBatch = null
            currentBatchEpoch = null
            currentBatchHasAccepted = false
            currentBatchSuppressCarrierPhase = false
            currentObservables.clear()
            currentBatchSatellites.clear()
        }

        input.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEachIndexed { zeroBasedLine, rawLine ->
                val lineNumber = zeroBasedLine + 1
                val line = if (lineNumber == 1) rawLine.removePrefix("\uFEFF") else rawLine
                when {
                    line.startsWith("# Version:") -> model = line.substringAfter(" Model:", "unknown").trim().ifBlank { "unknown" }
                    rawHeaderText(line) != null -> {
                        val candidate = RawSchema.parse(rawHeaderText(line)!!, lineNumber)
                        if (schema != null && schema != candidate) fail("RAW_SCHEMA_CHANGED", "GnssLogger TXT 중간에 Raw 필드 구성이 바뀌었습니다. ($lineNumber 행)")
                        schema = candidate
                    }
                    line.startsWith("Raw,") -> {
                        val activeSchema = schema ?: fail("RAW_HEADER_MISSING", "Raw 헤더보다 측정값이 먼저 나왔습니다. ($lineNumber 행)")
                        rawCount++
                        if (rawCount > MAX_RAW_RECORDS) fail("RAW_RECORD_LIMIT_EXCEEDED", "Raw 측정값이 허용 개수를 넘습니다.")
                        val measurement = activeSchema.measurement(line, lineNumber)
                        validateMeasurementContract(measurement)
                        val key = BatchKey(measurement.timeNanos, measurement.hardwareClockDiscontinuityCount)
                        if (currentBatch != key) {
                            if (currentBatch != null) finishBatch()
                            currentBatch = key
                        }
                        val clockContext = clock.resolve(measurement)
                        currentBatchSuppressCarrierPhase = currentBatchSuppressCarrierPhase || clockContext.segmentStarted
                        val time = receiverTime(measurement, clockContext.fullBiasNanos)
                        validateBatchEpoch(currentBatchEpoch, time.epoch, measurement.lineNumber)
                        currentBatchEpoch = currentBatchEpoch ?: time.epoch
                        val leap = validateLeapSecond(measurement, time.epoch)
                        if (effectiveLeapSecond != null && leap != effectiveLeapSecond) fail("LEAP_SECOND_CHANGED", "한 로그 안에서 LeapSecond 값이 바뀌었습니다. ($lineNumber 행)")
                        effectiveLeapSecond = leap
                        measurement.qualityExclusionReason()?.let {
                            exclude(it)
                            return@forEachIndexed
                        }
                        val selected = signalFor(measurement)
                        val signal = selected.signal ?: run {
                            exclude(selected.exclusionReason!!)
                            return@forEachIndexed
                        }
                        val processed = process(measurement, signal, clockContext.fullBiasNanos, currentBatchSuppressCarrierPhase, 0) ?: run {
                            exclude("INVALID_PSEUDORANGE")
                            return@forEachIndexed
                        }
                        val observableKey = "${signal.satellite}/${signal.observationCode}"
                        if (!currentObservables.add(observableKey)) fail("DUPLICATE_OBSERVABLE", "한 epoch에 $observableKey 관측값이 중복됐습니다. ($lineNumber 행)")
                        acceptedCount++
                        currentBatchSatellites.add(signal.satellite)
                        satellites.add(signal.satellite)
                        if (processed.observations["L${signal.observationCode}"]?.value != null) carrierPhaseCount++
                        currentBatchHasAccepted = true
                        observationCodes.getOrPut(signal.system) { linkedSetOf() }.add(signal.observationCode)
                        signal.glonassFrequencyChannel?.let { glonassChannels[signal.satellite] = it }
                        validateBatchEpoch(currentBatchEpoch, processed.epoch, measurement.lineNumber)
                    }
                }
            }
        }
        if (currentBatch != null) finishBatch()
        if (schema == null) fail("RAW_HEADER_MISSING", "GnssLogger TXT에 '# Raw,...' 헤더가 없습니다.")
        if (rawCount < 4) fail("INSUFFICIENT_RAW_MEASUREMENTS", "GnssLogger Raw 측정값이 4개 미만입니다.")
        if (acceptedCount < 8 || usableEpochCount < 2 || satellites.size < 4 || carrierPhaseCount < 4 || firstEpoch == null || lastEpoch == null || effectiveLeapSecond == null) {
            fail("INSUFFICIENT_VALID_MEASUREMENTS", "PPK에 필요한 2 epoch, 4 위성, 8 코드, 4 반송파 관측값을 충족하지 못했습니다. 제외: ${excludedByReason.toSortedMap()}")
        }
        return ScanResult(
            schema!!,
            model,
            rawCount,
            firstEpoch!!,
            lastEpoch!!,
            effectiveLeapSecond!!,
            observationCodes.mapValues { (_, values) -> values.sortedWith(compareBy(::observationCodeSortKey)) },
            glonassChannels,
            acceptedCount,
            carrierPhaseCount,
            excludedByReason.toSortedMap(),
        )
    }

    private fun writeBody(input: File, scan: ScanResult, writer: Writer): BodySummary {
        var currentBatch: BatchKey? = null
        var currentEpoch: GpsEpoch? = null
        var suppressCarrierPhase = false
        var batch = mutableListOf<Pair<RawMeasurement, ClockContext>>()
        var epochs = 0
        var accepted = 0
        var previousWrittenEpoch: GpsEpoch? = null
        var carrierPhases = 0
        val satellitesSeen = mutableSetOf<String>()
        val trackedSignals = mutableSetOf<String>()
        val phaseBreakPending = mutableSetOf<String>()
        val clock = ClockTracker()

        fun flushBatch() {
            if (batch.isEmpty()) return
            val observations = linkedMapOf<String, MutableMap<String, ObservationValue>>()
            val encounteredSignals = mutableSetOf<String>()
            batch.forEach { (measurement, clockContext) ->
                val selected = signalFor(measurement)
                val signal = selected.signal ?: return@forEach
                val phaseKey = "${signal.satellite}/${signal.observationCode}"
                encounteredSignals.add(phaseKey)
                if (measurement.qualityExclusionReason() != null) {
                    trackedSignals.add(phaseKey)
                    phaseBreakPending.add(phaseKey)
                    return@forEach
                }
                val lossOfLock = if (phaseKey in phaseBreakPending) 1 else 0
                val processed = process(measurement, signal, clockContext.fullBiasNanos, suppressCarrierPhase, lossOfLock) ?: run {
                    trackedSignals.add(phaseKey)
                    phaseBreakPending.add(phaseKey)
                    return@forEach
                }
                validateBatchEpoch(currentEpoch, processed.epoch, measurement.lineNumber)
                val satelliteValues = observations.getOrPut(signal.satellite) { linkedMapOf() }
                if (satelliteValues.keys.any { it.drop(1) == signal.observationCode }) {
                    fail("DUPLICATE_OBSERVABLE", "한 epoch에 ${signal.satellite}/${signal.observationCode} 관측값이 중복됐습니다. (${measurement.lineNumber} 행)")
                }
                satelliteValues.putAll(processed.observations)
                accepted++
                satellitesSeen.add(signal.satellite)
                val hasPhase = processed.observations["L${signal.observationCode}"]?.value != null
                if (hasPhase) {
                    carrierPhases++
                    phaseBreakPending.remove(phaseKey)
                    trackedSignals.add(phaseKey)
                } else {
                    phaseBreakPending.add(phaseKey)
                    trackedSignals.add(phaseKey)
                }
            }
            phaseBreakPending.addAll(trackedSignals - encounteredSignals)
            val epoch = currentEpoch
            if (epoch != null && observations.isNotEmpty()) {
                val previous = previousWrittenEpoch
                if (previous != null && epoch.totalGpsSeconds <= previous.totalGpsSeconds + 5e-8) fail("NON_MONOTONIC_CORRECTED_EPOCH", "보정된 GNSS epoch 시각이 증가하지 않습니다.")
                writeEpoch(writer, epoch, observations, scan.observationCodes)
                previousWrittenEpoch = epoch
                epochs++
            }
            batch = mutableListOf()
            currentEpoch = null
            suppressCarrierPhase = false
        }

        input.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEachIndexed { zeroBasedLine, line ->
                if (!line.startsWith("Raw,")) return@forEachIndexed
                val measurement = scan.schema.measurement(line, zeroBasedLine + 1)
                validateMeasurementContract(measurement)
                val key = BatchKey(measurement.timeNanos, measurement.hardwareClockDiscontinuityCount)
                if (currentBatch != key) {
                    if (currentBatch != null) flushBatch()
                    currentBatch = key
                }
                val clockContext = clock.resolve(measurement)
                val epoch = receiverTime(measurement, clockContext.fullBiasNanos).epoch
                validateBatchEpoch(currentEpoch, epoch, measurement.lineNumber)
                currentEpoch = currentEpoch ?: epoch
                suppressCarrierPhase = suppressCarrierPhase || clockContext.segmentStarted
                batch.add(measurement to clockContext)
            }
        }
        flushBatch()
        return BodySummary(epochs, accepted, satellitesSeen.size, carrierPhases)
    }

    private fun process(measurement: RawMeasurement, signal: Signal, stabilizedFullBiasNanos: Long, suppressCarrierPhase: Boolean, lossOfLock: Int): ProcessedMeasurement? {
        val receiverTime = receiverTime(measurement, stabilizedFullBiasNanos)
        val transmitSeconds = when (measurement.constellationType) {
            3 -> glonassToGpsSeconds(
                receiverTime.epoch.dateTime,
                measurement.receivedSvTimeNanos * NS_TO_SECONDS,
                validateLeapSecond(measurement, receiverTime.epoch).toDouble(),
            )
            5 -> measurement.receivedSvTimeNanos * NS_TO_SECONDS + BDS_TO_GPS_SECONDS
            else -> measurement.receivedSvTimeNanos * NS_TO_SECONDS
        }
        val travelSeconds = weekCrossover(receiverTime.unadjustedSecondsOfWeek, transmitSeconds)
        if (!travelSeconds.isFinite() || travelSeconds <= 0.0 || travelSeconds > 0.2) return null
        val pseudorange = travelSeconds * SPEED_OF_LIGHT_MPS - receiverTime.clockCorrectionSeconds * SPEED_OF_LIGHT_MPS
        if (!pseudorange.isFinite() || pseudorange <= 0.0 || pseudorange > 100_000_000.0) return null
        val wavelength = SPEED_OF_LIGHT_MPS / signal.frequencyHz
        val adrState = measurement.accumulatedDeltaRangeState
        val adrUsable = !suppressCarrierPhase &&
            (adrState and ADR_STATE_VALID) != 0 &&
            (adrState and ADR_STATE_RESET) == 0 &&
            (adrState and ADR_STATE_CYCLE_SLIP) == 0 &&
            (adrState and ADR_STATE_HALF_CYCLE_RESOLVED) != 0 &&
            measurement.accumulatedDeltaRangeM != null &&
            measurement.accumulatedDeltaRangeUncertaintyM != null &&
            measurement.accumulatedDeltaRangeUncertaintyM < MAX_ADR_UNCERTAINTY_M
        val carrierPhase = if (adrUsable) {
            measurement.accumulatedDeltaRangeM!! / wavelength - receiverTime.clockCorrectionSeconds * signal.frequencyHz
        } else null
        val doppler = -measurement.pseudorangeRateMps / wavelength
        val signalStrength = floor(measurement.cn0DbHz / 6.0).toInt().coerceIn(1, 9)
        val suffix = signal.observationCode
        return ProcessedMeasurement(
            receiverTime.epoch,
            mapOf(
                "C$suffix" to ObservationValue(pseudorange),
                "L$suffix" to ObservationValue(carrierPhase?.takeIf(Double::isFinite), lossOfLock = lossOfLock, signalStrength = signalStrength),
                "D$suffix" to ObservationValue(doppler),
                "S$suffix" to ObservationValue(measurement.cn0DbHz),
            ),
        )
    }

    private fun receiverTime(measurement: RawMeasurement, stabilizedFullBiasNanos: Long): ReceiverTime {
        val gpsWeek = Math.floorDiv(Math.negateExact(stabilizedFullBiasNanos), GPS_WEEK_NANOS)
        if (gpsWeek !in 1L..10_000L) fail("INVALID_GNSS_CLOCK", "FullBiasNanos에서 유효한 GPS week를 계산할 수 없습니다. (${measurement.lineNumber} 행)")
        val integerSecondsOfWeekNanos = try {
            Math.subtractExact(
                Math.subtractExact(measurement.timeNanos, stabilizedFullBiasNanos),
                Math.multiplyExact(gpsWeek, GPS_WEEK_NANOS),
            )
        } catch (error: ArithmeticException) {
            fail("INVALID_GNSS_CLOCK", "GnssClock 계산 중 정수 범위를 벗어났습니다. (${measurement.lineNumber} 행)")
        }
        val fractionalNanos = measurement.timeOffsetNanos - measurement.biasNanos
        val secondsOfWeek = integerSecondsOfWeekNanos * NS_TO_SECONDS + fractionalNanos * NS_TO_SECONDS
        if (!secondsOfWeek.isFinite() || secondsOfWeek !in -1.0..(GPS_WEEK_SECONDS + 1.0)) fail("INVALID_GNSS_CLOCK", "GnssClock 수신 시각이 GPS week 범위를 벗어납니다. (${measurement.lineNumber} 행)")
        val correction = (secondsOfWeek / TIME_ADJUSTMENT_SECONDS - floor(secondsOfWeek / TIME_ADJUSTMENT_SECONDS + 0.5)) * TIME_ADJUSTMENT_SECONDS
        return ReceiverTime(secondsOfWeek, correction, GpsEpoch(gpsWeek, secondsOfWeek - correction))
    }

    private fun validateMeasurementContract(measurement: RawMeasurement) {
        if (measurement.utcTimeMillis <= 0L) fail("INVALID_UTC_TIME", "utcTimeMillis가 유효하지 않습니다. (${measurement.lineNumber} 행)")
        if (measurement.timeNanos < 0L || measurement.fullBiasNanos >= 0L) fail("INVALID_GNSS_CLOCK", "TimeNanos 또는 FullBiasNanos가 유효하지 않습니다. (${measurement.lineNumber} 행)")
        if (abs(measurement.timeOffsetNanos) > 1e9) fail("INVALID_TIME_OFFSET", "TimeOffsetNanos 절댓값이 1초를 넘습니다. (${measurement.lineNumber} 행)")
        if (measurement.hardwareClockDiscontinuityCount < 0) fail("INVALID_CLOCK_DISCONTINUITY", "hardware clock discontinuity 값이 음수입니다. (${measurement.lineNumber} 행)")
        if (measurement.receivedSvTimeNanos < 0L) fail("INVALID_SV_TIME", "ReceivedSvTimeNanos가 음수입니다. (${measurement.lineNumber} 행)")
        val maxSvTimeNanos = if (measurement.constellationType == 3) 86_400_000_000_000L else 604_800_000_000_000L
        if (measurement.receivedSvTimeNanos >= maxSvTimeNanos) fail("INVALID_SV_TIME", "ReceivedSvTimeNanos가 별자리 시간 범위를 벗어납니다. (${measurement.lineNumber} 행)")
        val uncertainties = listOfNotNull(
            measurement.timeUncertaintyNanos,
            measurement.biasUncertaintyNanos,
            measurement.receivedSvTimeUncertaintyNanos,
            measurement.pseudorangeRateUncertaintyMps,
            measurement.accumulatedDeltaRangeUncertaintyM,
        )
        if (uncertainties.any { it < 0.0 }) fail("NEGATIVE_UNCERTAINTY", "GNSS uncertainty 값은 음수일 수 없습니다. (${measurement.lineNumber} 행)")
        if (measurement.cn0DbHz !in 0.0..63.0) fail("INVALID_CN0", "Cn0DbHz가 Android 계약 범위를 벗어납니다. (${measurement.lineNumber} 행)")
        if (measurement.multipathIndicator !in 0..2) fail("INVALID_MULTIPATH_INDICATOR", "MultipathIndicator 값이 Android 계약 범위 밖입니다. (${measurement.lineNumber} 행)")
        if (measurement.leapSecond != null && measurement.leapSecond !in 0..64) fail("INVALID_LEAP_SECOND", "LeapSecond 값이 0..64 범위 밖입니다. (${measurement.lineNumber} 행)")
    }

    private fun validateLeapSecond(measurement: RawMeasurement, epoch: GpsEpoch): Int {
        val gpsEpochMillis = epoch.dateTime.toInstant(ZoneOffset.UTC).toEpochMilli()
        val deltaMillis = gpsEpochMillis - measurement.utcTimeMillis
        val inferred = (deltaMillis / 1_000.0).roundToInt()
        if (inferred !in 0..64 || abs(deltaMillis - inferred * 1_000L) > LEAP_VALIDATION_TOLERANCE_MS) fail("UTC_GPS_TIME_MISMATCH", "utcTimeMillis와 GNSS clock의 LeapSecond 관계가 맞지 않습니다. (${measurement.lineNumber} 행)")
        val reported = measurement.leapSecond
        if (reported != null && reported != inferred) fail("LEAP_SECOND_MISMATCH", "기록된 LeapSecond와 GNSS clock에서 계산한 값이 다릅니다. (${measurement.lineNumber} 행)")
        return reported ?: inferred
    }

    private fun validateBatchEpoch(expected: GpsEpoch?, actual: GpsEpoch, lineNumber: Int) {
        if (expected != null && abs(expected.totalGpsSeconds - actual.totalGpsSeconds) > 5e-8) fail("INCONSISTENT_EPOCH", "한 GnssLogger epoch 안의 측정 시각이 100ns 정렬 후에도 서로 다릅니다. ($lineNumber 행)")
    }

    private fun RawMeasurement.qualityExclusionReason(): String? {
        if ((state and STATE_MSEC_AMBIGUOUS) != 0) return "MSEC_AMBIGUOUS"
        val codeLocked = (state and (STATE_CODE_LOCK or STATE_GAL_E1BC_CODE_LOCK)) != 0
        val timeKnown = if (constellationType == 3) (state and (STATE_GLO_TOD_DECODED or STATE_GLO_TOD_KNOWN)) != 0 else (state and (STATE_TOW_DECODED or STATE_TOW_KNOWN)) != 0
        if (!codeLocked) return "CODE_NOT_LOCKED"
        if (!timeKnown) return "SV_TIME_NOT_KNOWN"
        if (cn0DbHz < MIN_CN0_DB_HZ) return "LOW_CN0"
        if (receivedSvTimeUncertaintyNanos > MAX_RECEIVED_TIME_UNCERTAINTY_NS) return "SV_TIME_UNCERTAINTY_HIGH"
        if (biasUncertaintyNanos != null && biasUncertaintyNanos >= MAX_BIAS_UNCERTAINTY_NS) return "CLOCK_BIAS_UNCERTAINTY_HIGH"
        if (multipathIndicator == 1) return "MULTIPATH_DETECTED"
        return null
    }

    private fun signalFor(measurement: RawMeasurement): SignalSelection {
        val system = when (measurement.constellationType) {
            1 -> 'G'; 2 -> 'S'; 3 -> 'R'; 4 -> 'J'; 5 -> 'C'; 6 -> 'E'
            else -> return SignalSelection(exclusionReason = "UNSUPPORTED_CONSTELLATION")
        }
        val satelliteNumber = when (system) {
            'G' -> measurement.svid.takeIf { it in 1..32 }
            'S' -> (measurement.svid - 100).takeIf { measurement.svid in 120..158 }
            'R' -> measurement.svid.takeIf { it in 1..50 }
            'J' -> (measurement.svid - 192).takeIf { measurement.svid in 193..202 }
            'C' -> measurement.svid.takeIf { it in 1..63 }
            'E' -> measurement.svid.takeIf { it in 1..36 }
            else -> null
        } ?: return SignalSelection(exclusionReason = "UNSUPPORTED_SVID")
        val frequency = measurement.carrierFrequencyHz ?: return SignalSelection(exclusionReason = "MISSING_CARRIER_FREQUENCY")
        if (frequency <= 0.0) return SignalSelection(exclusionReason = "INVALID_CARRIER_FREQUENCY")
        val band = when {
            system == 'R' && frequency in 1.597e9..1.609e9 -> 1
            system != 'C' && system != 'R' && abs(frequency - 1.57542e9) <= 1.0e6 -> 1
            system != 'C' && system != 'R' && abs(frequency - 1.17645e9) <= 1.0e6 -> 5
            system == 'C' && abs(frequency - 1.561098e9) <= 1.0e6 -> 2
            else -> return SignalSelection(exclusionReason = "UNSUPPORTED_SIGNAL_FREQUENCY")
        }
        val codeType = measurement.codeType
        val allowed = when (system to band) {
            'G' to 1 -> setOf('C', 'S', 'L', 'X', 'P', 'W', 'Y', 'M', 'N')
            'G' to 5 -> setOf('I', 'Q', 'X')
            'R' to 1 -> setOf('C', 'P')
            'E' to 1 -> setOf('A', 'B', 'C', 'X', 'Z')
            'E' to 5 -> setOf('I', 'Q', 'X')
            'S' to 1 -> setOf('C')
            'S' to 5 -> setOf('I', 'Q', 'X')
            'J' to 1 -> setOf('C', 'S', 'L', 'X', 'Z')
            'J' to 5 -> setOf('I', 'Q', 'X')
            'C' to 2 -> setOf('I', 'Q', 'X')
            else -> emptySet()
        }
        if (codeType !in allowed) return SignalSelection(exclusionReason = "UNSUPPORTED_CODE_TYPE")
        val glonassChannel = if (system == 'R') {
            round((frequency - 1.602e9) / 562_500.0).toInt().also {
                if (it !in -7..12) return SignalSelection(exclusionReason = "UNSUPPORTED_GLONASS_CHANNEL")
            }
        } else null
        val nominalFrequency = when {
            system == 'R' -> 1.602e9 + glonassChannel!! * 562_500.0
            system == 'C' -> 1.561098e9
            band == 5 -> 1.17645e9
            else -> 1.57542e9
        }
        return SignalSelection(Signal(system, "%c%02d".format(Locale.US, system, satelliteNumber), "$band$codeType", nominalFrequency, glonassChannel))
    }

    private fun writeHeader(writer: Writer, scan: ScanResult, generatedAtUtc: LocalDateTime) {
        writer.write("     3.03           OBSERVATION DATA    M                   RINEX VERSION / TYPE\n")
        val generatedAt = generatedAtUtc.format(generatedAtFormatter)
        writer.write(headerLine(String.format(Locale.US, "%-20s%-20s%-20s", "WalkSafeEval", "Hanium", generatedAt), "PGM / RUN BY / DATE"))
        writer.write(headerLine("WALKSAFE", "MARKER NAME"))
        writer.write(headerLine("SMARTPHONE", "MARKER TYPE"))
        writer.write(headerLine(String.format(Locale.US, "%-20s%-40s", "unknown", "Hanium"), "OBSERVER / AGENCY"))
        writer.write(headerLine(String.format(Locale.US, "%-20s%-20s%-20s", "unknown", scan.model.take(20), "Android"), "REC # / TYPE / VERS"))
        writer.write(headerLine(String.format(Locale.US, "%-20s%-40s", "unknown", "internal"), "ANT # / TYPE"))
        writer.write(headerLine(String.format(Locale.US, "%14.4f%14.4f%14.4f", 0.0, 0.0, 0.0), "APPROX POSITION XYZ"))
        writer.write(headerLine(String.format(Locale.US, "%14.4f%14.4f%14.4f", 0.0, 0.0, 0.0), "ANTENNA: DELTA H/E/N"))
        scan.observationCodes.toSortedMap().forEach { (system, suffixes) ->
            val types = suffixes.flatMap { suffix -> listOf("C$suffix", "L$suffix", "D$suffix", "S$suffix") }
            types.chunked(13).forEachIndexed { index, chunk ->
                val prefix = if (index == 0) String.format(Locale.US, "%c  %3d", system, types.size) else "      "
                writer.write(headerLine(prefix + chunk.joinToString(separator = " ", prefix = " "), "SYS / # / OBS TYPES"))
            }
        }
        writer.write(headerLine("DBHZ", "SIGNAL STRENGTH UNIT"))
        writer.write(headerLine("     1", "RCV CLOCK OFFS APPL"))
        writer.write(headerLine(headerEpoch(scan.firstEpoch), "TIME OF FIRST OBS"))
        writer.write(headerLine(headerEpoch(scan.lastEpoch), "TIME OF LAST OBS"))
        writer.write(headerLine(String.format(Locale.US, "%6d", scan.effectiveLeapSecond), "LEAP SECONDS"))
        if (scan.glonassChannels.isNotEmpty()) {
            val pairs = scan.glonassChannels.toSortedMap().entries.map { "${it.key} ${String.format(Locale.US, "%2d", it.value)}" }
            pairs.chunked(8).forEachIndexed { index, chunk ->
                val prefix = if (index == 0) String.format(Locale.US, "%3d ", pairs.size) else "    "
                writer.write(headerLine(prefix + chunk.joinToString(" "), "GLONASS SLOT / FRQ #"))
            }
            writer.write(headerLine("", "GLONASS COD/PHS/BIS"))
        }
        writer.write(headerLine("", "END OF HEADER"))
    }

    private fun writeEpoch(writer: Writer, epoch: GpsEpoch, satellites: Map<String, Map<String, ObservationValue>>, observationCodes: Map<Char, List<String>>) {
        val dateTime = epoch.dateTime
        val seconds = dateTime.second + dateTime.nano / 1e9
        writer.write(String.format(Locale.US, "> %04d %02d %02d %02d %02d %11.7f %1d%3d\n", dateTime.year, dateTime.monthValue, dateTime.dayOfMonth, dateTime.hour, dateTime.minute, seconds, 0, satellites.size))
        satellites.toSortedMap().forEach { (satellite, values) ->
            writer.write(satellite)
            val suffixes = observationCodes[satellite.first()] ?: fail("INTERNAL_OBSERVATION_SCHEMA", "RINEX 관측 타입을 찾지 못했습니다.")
            suffixes.flatMap { suffix -> listOf("C$suffix", "L$suffix", "D$suffix", "S$suffix") }.forEach { type ->
                val value = values[type]
                when {
                    value?.value == null || !value.value.isFinite() -> writer.write("                ")
                    type.first() == 'L' -> writer.write(String.format(Locale.US, "%14.3f%d%d", value.value, value.lossOfLock, value.signalStrength))
                    else -> writer.write(String.format(Locale.US, "%14.3f  ", value.value))
                }
            }
            writer.write("\n")
        }
    }

    private fun headerEpoch(epoch: GpsEpoch): String {
        val dateTime = epoch.dateTime
        val seconds = dateTime.second + dateTime.nano / 1e9
        return String.format(Locale.US, "%6d%6d%6d%6d%6d%13.7f     GPS", dateTime.year, dateTime.monthValue, dateTime.dayOfMonth, dateTime.hour, dateTime.minute, seconds)
    }

    private fun headerLine(content: String, label: String): String = content.take(60).padEnd(60) + label + "\n"

    private fun rawHeaderText(line: String): String? {
        if (!line.startsWith("#")) return null
        return line.removePrefix("#").trimStart().takeIf { it.startsWith("Raw,") }
    }

    private fun glonassToGpsSeconds(gpsDateTime: LocalDateTime, timeOfDaySeconds: Double, leapSeconds: Double): Double {
        val glonassDate = gpsDateTime.plusSeconds((GLONASS_TO_UTC_SECONDS - leapSeconds).toLong())
        return glonassDate.dayOfWeek.value * DAY_SECONDS + timeOfDaySeconds - GLONASS_TO_UTC_SECONDS + leapSeconds
    }

    private fun weekCrossover(receiveSeconds: Double, transmitSeconds: Double): Double {
        var travel = receiveSeconds - transmitSeconds
        if (abs(travel) > GPS_WEEK_SECONDS / 2.0) {
            travel -= round(travel / GPS_WEEK_SECONDS) * GPS_WEEK_SECONDS
            if (travel > 10.0) return 0.0
        }
        return travel
    }

    private fun observationCodeSortKey(code: String): Int = code.first().digitToInt() * 100 + code.last().code
    private fun fail(reasonCode: String, message: String): Nothing = throw GnssLoggerConversionException(reasonCode, message)

    private class ClockTracker {
        private var discontinuity: Int? = null
        private var fullBiasNanos: Long? = null
        private var lastTimeNanos: Long? = null

        fun resolve(measurement: RawMeasurement): ClockContext {
            val current = discontinuity
            if (current == null || measurement.hardwareClockDiscontinuityCount > current) {
                val isDiscontinuity = current != null
                discontinuity = measurement.hardwareClockDiscontinuityCount
                fullBiasNanos = measurement.fullBiasNanos
                lastTimeNanos = measurement.timeNanos
                return ClockContext(measurement.fullBiasNanos, isDiscontinuity)
            }
            if (measurement.hardwareClockDiscontinuityCount < current) fail("CLOCK_DISCONTINUITY_REGRESSION", "hardware clock discontinuity count가 감소했습니다. (${measurement.lineNumber} 행)")
            if (measurement.fullBiasNanos != fullBiasNanos) fail("CLOCK_BIAS_CHANGED_WITHOUT_DISCONTINUITY", "동일 clock segment에서 FullBiasNanos가 바뀌었습니다. (${measurement.lineNumber} 행)")
            val previousTime = lastTimeNanos!!
            if (measurement.timeNanos < previousTime) fail("RAW_TIME_REGRESSION_IN_SEGMENT", "동일 clock segment에서 TimeNanos가 감소했습니다. (${measurement.lineNumber} 행)")
            if (measurement.timeNanos > previousTime) lastTimeNanos = measurement.timeNanos
            return ClockContext(fullBiasNanos!!, false)
        }
    }

    private data class ScanResult(
        val schema: RawSchema,
        val model: String,
        val rawCount: Int,
        val firstEpoch: GpsEpoch,
        val lastEpoch: GpsEpoch,
        val effectiveLeapSecond: Int,
        val observationCodes: Map<Char, List<String>>,
        val glonassChannels: Map<String, Int>,
        val acceptedCount: Int,
        val carrierPhaseCount: Int,
        val excludedByReason: Map<String, Int>,
    )

    private data class BodySummary(val epochCount: Int, val acceptedCount: Int, val satelliteCount: Int, val carrierPhaseCount: Int)
    private data class BatchKey(val timeNanos: Long, val discontinuity: Int)
    private data class ClockContext(val fullBiasNanos: Long, val segmentStarted: Boolean)
    private data class ReceiverTime(val unadjustedSecondsOfWeek: Double, val clockCorrectionSeconds: Double, val epoch: GpsEpoch)
    private data class Signal(val system: Char, val satellite: String, val observationCode: String, val frequencyHz: Double, val glonassFrequencyChannel: Int?)
    private data class SignalSelection(val signal: Signal? = null, val exclusionReason: String? = null)
    private data class ObservationValue(val value: Double?, val lossOfLock: Int = 0, val signalStrength: Int = 0)
    private data class ProcessedMeasurement(val epoch: GpsEpoch, val observations: Map<String, ObservationValue>)

    private data class GpsEpoch(val week: Long, val secondsOfWeek: Double) {
        val totalGpsSeconds: Double get() = week * GPS_WEEK_SECONDS + secondsOfWeek
        val dateTime: LocalDateTime
            get() {
                val wholeSeconds = floor(totalGpsSeconds).toLong()
                var nanos = round((totalGpsSeconds - wholeSeconds) * 1e9).toLong()
                var normalizedSeconds = wholeSeconds
                if (nanos >= 1_000_000_000L) {
                    normalizedSeconds++
                    nanos -= 1_000_000_000L
                }
                return gpsEpoch.plusSeconds(normalizedSeconds).plusNanos(nanos)
            }
    }

    private data class RawMeasurement(
        val lineNumber: Int,
        val utcTimeMillis: Long,
        val timeNanos: Long,
        val leapSecond: Int?,
        val timeUncertaintyNanos: Double?,
        val fullBiasNanos: Long,
        val biasNanos: Double,
        val biasUncertaintyNanos: Double?,
        val hardwareClockDiscontinuityCount: Int,
        val svid: Int,
        val timeOffsetNanos: Double,
        val state: Int,
        val receivedSvTimeNanos: Long,
        val receivedSvTimeUncertaintyNanos: Double,
        val cn0DbHz: Double,
        val pseudorangeRateMps: Double,
        val pseudorangeRateUncertaintyMps: Double,
        val accumulatedDeltaRangeState: Int,
        val accumulatedDeltaRangeM: Double?,
        val accumulatedDeltaRangeUncertaintyM: Double?,
        val carrierFrequencyHz: Double?,
        val multipathIndicator: Int,
        val constellationType: Int,
        val codeTypeText: String,
    ) {
        val codeType: Char? get() = codeTypeText.singleOrNull()?.takeIf { it in 'A'..'Z' }
    }

    private data class RawSchema(val fields: List<String>, val indexByName: Map<String, Int>) {
        fun measurement(line: String, lineNumber: Int): RawMeasurement {
            val values = splitCsv(line)
            if (values.firstOrNull() != "Raw") fail("MALFORMED_RAW_RECORD", "Raw 측정 행 형식이 잘못되었습니다. ($lineNumber 행)")
            fun text(name: String): String {
                val index = indexByName[name] ?: fail("RAW_FIELD_MISSING", "필수 Raw 필드 '$name'이 헤더에 없습니다.")
                return values.getOrNull(index + 1)?.trim() ?: ""
            }
            fun long(name: String): Long = text(name).toLongOrNull() ?: fail("MALFORMED_RAW_VALUE", "$name 값이 정수가 아닙니다. ($lineNumber 행)")
            fun int(name: String): Int = text(name).toIntOrNull() ?: fail("MALFORMED_RAW_VALUE", "$name 값이 정수가 아닙니다. ($lineNumber 행)")
            fun double(name: String): Double {
                val value = text(name).toDoubleOrNull()
                if (value == null || !value.isFinite()) fail("MALFORMED_RAW_VALUE", "$name 값이 유한수가 아닙니다. ($lineNumber 행)")
                return value
            }
            fun optionalDouble(name: String): Double? {
                val raw = text(name)
                if (raw.isEmpty()) return null
                val value = raw.toDoubleOrNull()
                if (value == null || !value.isFinite()) fail("MALFORMED_RAW_VALUE", "$name 값이 유한수가 아닙니다. ($lineNumber 행)")
                return value
            }
            fun optionalInt(name: String): Int? {
                val raw = text(name)
                if (raw.isEmpty()) return null
                return raw.toIntOrNull() ?: fail("MALFORMED_RAW_VALUE", "$name 값이 정수가 아닙니다. ($lineNumber 행)")
            }
            return RawMeasurement(
                lineNumber,
                long("utcTimeMillis"),
                long("TimeNanos"),
                optionalInt("LeapSecond"),
                optionalDouble("TimeUncertaintyNanos"),
                long("FullBiasNanos"),
                optionalDouble("BiasNanos") ?: 0.0,
                optionalDouble("BiasUncertaintyNanos"),
                int("HardwareClockDiscontinuityCount"),
                int("Svid"),
                optionalDouble("TimeOffsetNanos") ?: 0.0,
                int("State"),
                long("ReceivedSvTimeNanos"),
                double("ReceivedSvTimeUncertaintyNanos"),
                double("Cn0DbHz"),
                double("PseudorangeRateMetersPerSecond"),
                double("PseudorangeRateUncertaintyMetersPerSecond"),
                int("AccumulatedDeltaRangeState"),
                optionalDouble("AccumulatedDeltaRangeMeters"),
                optionalDouble("AccumulatedDeltaRangeUncertaintyMeters"),
                optionalDouble("CarrierFrequencyHz"),
                int("MultipathIndicator"),
                int("ConstellationType"),
                text("CodeType"),
            )
        }

        companion object {
            private val requiredFields = setOf(
                "utcTimeMillis", "TimeNanos", "LeapSecond", "TimeUncertaintyNanos", "FullBiasNanos", "BiasNanos",
                "BiasUncertaintyNanos", "HardwareClockDiscontinuityCount", "Svid", "TimeOffsetNanos", "State",
                "ReceivedSvTimeNanos", "ReceivedSvTimeUncertaintyNanos", "Cn0DbHz", "PseudorangeRateMetersPerSecond",
                "PseudorangeRateUncertaintyMetersPerSecond", "AccumulatedDeltaRangeState", "AccumulatedDeltaRangeMeters",
                "AccumulatedDeltaRangeUncertaintyMeters", "CarrierFrequencyHz", "MultipathIndicator", "ConstellationType", "CodeType",
            )

            fun parse(text: String, lineNumber: Int): RawSchema {
                val parts = splitCsv(text)
                if (parts.firstOrNull() != "Raw") fail("RAW_HEADER_MALFORMED", "Raw 헤더가 잘못되었습니다. ($lineNumber 행)")
                val fields = parts.drop(1).map(String::trim)
                if (fields.any(String::isEmpty) || fields.toSet().size != fields.size) fail("RAW_HEADER_MALFORMED", "Raw 헤더에 빈 필드나 중복 필드가 있습니다. ($lineNumber 행)")
                val missing = requiredFields - fields.toSet()
                if (missing.isNotEmpty()) fail("RAW_FIELD_MISSING", "필수 Raw 필드가 없습니다: ${missing.sorted().joinToString()}")
                return RawSchema(fields, fields.withIndex().associate { it.value to it.index })
            }
        }
    }

    private fun splitCsv(line: String): List<String> {
        if ('"' in line) fail("QUOTED_CSV_UNSUPPORTED", "따옴표가 포함된 GnssLogger CSV는 지원하지 않습니다.")
        val values = ArrayList<String>()
        var start = 0
        line.forEachIndexed { index, character ->
            if (character == ',') {
                values.add(line.substring(start, index))
                start = index + 1
            }
        }
        values.add(line.substring(start))
        return values
    }
}
