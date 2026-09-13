package kr.co.hanium.dreamup.walksafe.fieldtest

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssMeasurementRequest
import android.location.GnssMeasurementsEvent
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardOpenOption

/** One private, immutable-name session. Activity owns consent, lifecycle, and explicit export. */
class OutdoorRawGnssRecorder(
    context: Context,
    private val sessionFile: File,
    private val onStatus: (Status) -> Unit = {},
) {
    enum class State { IDLE, RECORDING, STOPPED, ERROR }

    data class Status(
        val state: State,
        val rawMeasurementCount: Long,
        val epochCount: Long,
        val adrValidCount: Long,
        val lastEpochElapsedRealtimeNanos: Long?,
        val errorMessage: String?,
        val file: File,
        val missingFullBiasCount: Long,
        val missingCodeTypeCount: Long,
        val deviceUtcFallbackCount: Long,
        val warningMessage: String?,
    )

    private val applicationContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val locationManager = applicationContext.getSystemService(LocationManager::class.java)
    private val lock = Any()
    private var currentState = State.IDLE
    private var generation = 0L
    private var callback: GnssMeasurementsEvent.Callback? = null
    private var output: BufferedOutputStream? = null
    private var bytesWritten = 0L
    private var measurementsWritten = 0L
    private var epochsWritten = 0L
    private var adrValidWritten = 0L
    private var fullBiasMissing = 0L
    private var codeTypeMissing = 0L
    private var utcFallbacks = 0L
    private var lastEpochNanos: Long? = null
    private var error: String? = null

    val sampleCount: Long get() = synchronized(lock) { measurementsWritten }
    val epochCount: Long get() = synchronized(lock) { epochsWritten }
    val status: Status get() = synchronized(lock) { snapshotLocked() }

    fun start(): Boolean = synchronized(lock) {
        if (currentState != State.IDLE) return false
        try {
            if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                throw SecurityException("정확한 위치 권한이 필요합니다.")
            }
            checkNotNull(locationManager) { "GNSS 위치 서비스를 찾을 수 없습니다." }
            val privateRoot = applicationContext.noBackupFilesDir.canonicalFile.toPath()
            val filePath = sessionFile.canonicalFile.toPath()
            require(filePath.startsWith(privateRoot) && filePath != privateRoot) { "원시 기록은 앱 전용 noBackupFilesDir 안에만 저장합니다." }
            Files.createDirectories(filePath.parent)
            output = BufferedOutputStream(Files.newOutputStream(filePath, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))
            writeLocked(OutdoorRawGnssFormatter.header(Build.MODEL, Build.MANUFACTURER).toByteArray(Charsets.UTF_8))
            output?.flush()
            currentState = State.RECORDING
            val activeGeneration = ++generation
            val listener = object : GnssMeasurementsEvent.Callback() {
                override fun onGnssMeasurementsReceived(eventArgs: GnssMeasurementsEvent) {
                    record(eventArgs, activeGeneration)
                }

                @Suppress("DEPRECATION")
                override fun onStatusChanged(status: Int) {
                    synchronized(lock) {
                        if (generation != activeGeneration || currentState != State.RECORDING) return
                        when (status) {
                            STATUS_NOT_SUPPORTED -> failLocked("이 기기에서 원시 GNSS 관측을 지원하지 않습니다.")
                            STATUS_LOCATION_DISABLED -> failLocked("위치 서비스가 꺼져 원시 GNSS 기록을 중단했습니다.")
                        }
                    }
                }
            }
            callback = listener
            val registered = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                locationManager.registerGnssMeasurementsCallback(
                    GnssMeasurementRequest.Builder().setFullTracking(true).build(),
                    ContextCompat.getMainExecutor(applicationContext),
                    listener,
                )
            } else {
                locationManager.registerGnssMeasurementsCallback(listener, mainHandler)
            }
            if (!registered) throw IllegalStateException("원시 GNSS 관측 등록이 거부되었습니다.")
            publishLocked()
            true
        } catch (failure: Exception) {
            failLocked("원시 GNSS 기록 시작 실패: ${failure.message ?: failure.javaClass.simpleName}")
            false
        }
    }

    /** A true result means flushing and closing succeeded, not that observations support PPK. */
    fun stop(): Boolean = synchronized(lock) {
        if (currentState == State.STOPPED) return true
        if (currentState == State.ERROR) return false
        if (currentState != State.RECORDING) return false
        ++generation
        unregisterLocked()
        val failure = closeLocked()
        if (failure != null) {
            currentState = State.ERROR
            error = "원시 GNSS 파일 닫기 실패: ${failure.message}"
        } else {
            currentState = State.STOPPED
        }
        publishLocked()
        failure == null
    }

    private fun record(event: GnssMeasurementsEvent, activeGeneration: Long) {
        val callbackElapsedNanos = SystemClock.elapsedRealtimeNanos()
        val callbackUtcMillis = System.currentTimeMillis()
        synchronized(lock) {
            if (generation != activeGeneration || currentState != State.RECORDING) return
            try {
                val receiver = event.clock
                val clock = OutdoorRawGnssClock(
                    receiver.timeNanos,
                    if (receiver.hasLeapSecond()) receiver.leapSecond else null,
                    if (receiver.hasTimeUncertaintyNanos()) receiver.timeUncertaintyNanos else null,
                    if (receiver.hasFullBiasNanos()) receiver.fullBiasNanos else null,
                    if (receiver.hasBiasNanos()) receiver.biasNanos else null,
                    if (receiver.hasBiasUncertaintyNanos()) receiver.biasUncertaintyNanos else null,
                    receiver.hardwareClockDiscontinuityCount,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && receiver.hasElapsedRealtimeNanos()) receiver.elapsedRealtimeNanos else null,
                )
                var batchAdr = 0L
                var batchMissingCodes = 0L
                val rows = buildString {
                    for (raw in event.measurements) {
                        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && raw.hasCodeType()) raw.codeType else null
                        if (code.isNullOrBlank() || code == "UNKNOWN") batchMissingCodes++
                        if ((raw.accumulatedDeltaRangeState and 1) != 0) batchAdr++
                        val measurement = OutdoorRawGnssMeasurement(
                            raw.svid, raw.timeOffsetNanos, raw.state, raw.receivedSvTimeNanos,
                            raw.receivedSvTimeUncertaintyNanos, raw.cn0DbHz,
                            raw.pseudorangeRateMetersPerSecond, raw.pseudorangeRateUncertaintyMetersPerSecond,
                            raw.accumulatedDeltaRangeState, raw.accumulatedDeltaRangeMeters,
                            raw.accumulatedDeltaRangeUncertaintyMeters,
                            if (raw.hasCarrierFrequencyHz()) raw.carrierFrequencyHz else null,
                            raw.multipathIndicator, raw.constellationType, code,
                        )
                        append(OutdoorRawGnssFormatter.rawRow(clock, measurement, callbackUtcMillis, callbackElapsedNanos))
                    }
                }
                if (event.measurements.isEmpty()) return
                writeLocked(rows.toByteArray(Charsets.UTF_8))
                // A GNSS epoch is small; keep one bounded epoch write and flush before publishing.
                output?.flush()
                val count = event.measurements.size.toLong()
                measurementsWritten += count
                epochsWritten++
                adrValidWritten += batchAdr
                codeTypeMissing += batchMissingCodes
                if (clock.fullBiasNanos == null) fullBiasMissing += count
                if (clock.fullBiasNanos == null || clock.leapSecond == null) utcFallbacks += count
                lastEpochNanos = callbackElapsedNanos
                publishLocked()
            } catch (failure: Exception) {
                failLocked("원시 GNSS 기록 실패: ${failure.message ?: failure.javaClass.simpleName}")
            }
        }
    }

    private fun writeLocked(bytes: ByteArray) {
        if (bytes.size.toLong() > MAX_FILE_BYTES - bytesWritten) throw IOException("원시 기록의 64 MiB 한도에 도달했습니다.")
        val stream = output ?: throw IOException("원시 기록 파일이 닫혀 있습니다.")
        stream.write(bytes)
        bytesWritten += bytes.size
    }

    private fun failLocked(message: String) {
        ++generation
        currentState = State.ERROR
        error = message
        unregisterLocked()
        closeLocked()?.let { error = "$message / 닫기 실패: ${it.message}" }
        publishLocked()
    }

    private fun unregisterLocked() {
        val listener = callback
        callback = null
        if (listener != null) runCatching { locationManager?.unregisterGnssMeasurementsCallback(listener) }
    }

    private fun closeLocked(): Exception? {
        val stream = output ?: return null
        output = null
        var failure: Exception? = null
        try { stream.flush() } catch (caught: Exception) { failure = caught }
        try { stream.close() } catch (caught: Exception) { if (failure == null) failure = caught }
        return failure
    }

    private fun snapshotLocked(): Status {
        val warning = when {
            fullBiasMissing > 0 -> "FullBiasNanos가 없는 관측 ${fullBiasMissing}개: 현재 파일의 PPK 변환은 제한됩니다."
            measurementsWritten == 0L -> "원시 GNSS 관측 수신 대기 중입니다."
            adrValidWritten == 0L -> "유효 ADR 반송파 관측이 아직 없습니다. PPK 가능 여부가 확인되지 않았습니다."
            codeTypeMissing > 0L -> "CodeType 미제공 관측 ${codeTypeMissing}개는 분석에서 제외될 수 있습니다."
            utcFallbacks > 0L -> "기기 UTC를 사용한 관측 ${utcFallbacks}개: GNSS 시간 정합성은 분석에서 확인합니다."
            else -> null
        }
        return Status(currentState, measurementsWritten, epochsWritten, adrValidWritten, lastEpochNanos,
            error, sessionFile, fullBiasMissing, codeTypeMissing, utcFallbacks, warning)
    }

    private fun publishLocked() {
        val snapshot = snapshotLocked()
        mainHandler.post { if (status == snapshot) onStatus(snapshot) }
    }

    companion object {
        const val MAX_FILE_BYTES = 64L * 1024L * 1024L
    }
}
