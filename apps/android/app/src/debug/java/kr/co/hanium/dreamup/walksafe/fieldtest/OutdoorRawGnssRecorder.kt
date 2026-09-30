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
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicBoolean

/** One private, immutable-name session. Activity owns consent, lifecycle, and explicit export. */
class OutdoorRawGnssRecorder(
    context: Context,
    private val sessionFile: File,
    private val onStatus: (Status) -> Unit = {},
) {
    enum class State { IDLE, STARTING, RECORDING, STOPPING, STOPPED, ERROR }

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
        val closed: Boolean,
        val pendingEpochCount: Int,
        val pendingBytes: Int,
        val droppedMeasurementCount: Long,
    )

    private val applicationContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val locationManager = applicationContext.getSystemService(LocationManager::class.java)
    private var callback: GnssMeasurementsEvent.Callback? = null
    @Volatile private var registered = false
    private var startCompletion: ((Boolean) -> Unit)? = null
    private val stopCompletions = mutableListOf<(Boolean) -> Unit>()
    private val statusPending = AtomicBoolean(false)
    private val stagingFile = File(sessionFile.path + ".part")
    private val writer = BoundedRawGnssWriter(
        openSink = {
            val privateRoot = applicationContext.noBackupFilesDir.canonicalFile.toPath()
            val filePath = sessionFile.canonicalFile.toPath()
            val stagingPath = stagingFile.canonicalFile.toPath()
            require(filePath.startsWith(privateRoot) && filePath != privateRoot &&
                stagingPath.startsWith(privateRoot) && stagingPath != privateRoot) {
                "원시 기록은 앱 전용 noBackupFilesDir 안에만 저장합니다."
            }
            check(!Files.exists(filePath)) { "이미 존재하는 원시 기록은 덮어쓰지 않습니다." }
            Files.createDirectories(filePath.parent)
            BufferedOutputStream(Files.newOutputStream(stagingPath, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))
        },
        header = OutdoorRawGnssFormatter.header(Build.MODEL, Build.MANUFACTURER).toByteArray(Charsets.UTF_8),
        maxFileBytes = MAX_FILE_BYTES,
        // Only a successfully closed file receives the legacy exportable name. Failed .part
        // files remain private for diagnosis, including after Activity/process recreation.
        publishClosedSink = { Files.move(stagingFile.toPath(), sessionFile.toPath()); Unit },
        onChanged = ::publish,
        onClosed = { mainHandler.post { finishClose() } },
    )

    val sampleCount: Long get() = writer.snapshot.measurements
    val epochCount: Long get() = writer.snapshot.epochs
    val status: Status get() = snapshot()

    /** Main-thread control; true is delivered only after open/header flush AND registration. */
    fun start(onComplete: (Boolean) -> Unit) {
        if (writer.snapshot.state != BoundedRawGnssWriter.State.IDLE || startCompletion != null) {
            onComplete(false)
            return
        }
        startCompletion = onComplete
        try {
            if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                throw SecurityException("정확한 위치 권한이 필요합니다.")
            }
            checkNotNull(locationManager) { "GNSS 위치 서비스를 찾을 수 없습니다." }
            writer.start { opened, activeGeneration ->
                mainHandler.post { finishStart(opened, activeGeneration) }
            }
        } catch (failure: Exception) {
            writer.fail("원시 GNSS 기록 시작 실패: ${failure.message ?: failure.javaClass.simpleName}")
            completeStart(false)
        }
    }

    private fun finishStart(opened: Boolean, activeGeneration: Long) {
        if (!opened || !writer.accepts(activeGeneration)) {
            completeStart(false)
            return
        }
        try {
            val listener = object : GnssMeasurementsEvent.Callback() {
                override fun onGnssMeasurementsReceived(eventArgs: GnssMeasurementsEvent) {
                    record(eventArgs, activeGeneration)
                }

                @Suppress("DEPRECATION")
                override fun onStatusChanged(status: Int) {
                    if (!writer.accepts(activeGeneration)) return
                    when (status) {
                        STATUS_NOT_SUPPORTED -> writer.fail("이 기기에서 원시 GNSS 관측을 지원하지 않습니다.")
                        STATUS_LOCATION_DISABLED -> writer.fail("위치 서비스가 꺼져 원시 GNSS 기록을 중단했습니다.")
                    }
                }
            }
            callback = listener
            val manager = checkNotNull(locationManager)
            val accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                manager.registerGnssMeasurementsCallback(
                    GnssMeasurementRequest.Builder().setFullTracking(true).build(),
                    ContextCompat.getMainExecutor(applicationContext),
                    listener,
                )
            } else {
                manager.registerGnssMeasurementsCallback(listener, mainHandler)
            }
            if (!accepted) throw IllegalStateException("원시 GNSS 관측 등록이 거부되었습니다.")
            registered = true
            publish()
            completeStart(true)
        } catch (failure: Exception) {
            unregister()
            writer.fail("원시 GNSS 기록 시작 실패: ${failure.message ?: failure.javaClass.simpleName}")
            completeStart(false)
        }
    }

    /** Nonblocking main-thread control. Completion true still means flush/close succeeded. */
    fun stop(onComplete: (Boolean) -> Unit) {
        writer.stop() // Invalidate admission before unregistering or returning to the UI.
        unregister()
        completeStart(false)
        if (writer.snapshot.closed) onComplete(writer.snapshot.state == BoundedRawGnssWriter.State.STOPPED)
        else stopCompletions += onComplete
    }

    private fun completeStart(success: Boolean) {
        val completion = startCompletion
        startCompletion = null
        completion?.invoke(success)
    }

    private fun finishClose() {
        unregister()
        completeStart(false)
        val completions = stopCompletions.toList()
        stopCompletions.clear()
        val success = writer.snapshot.state == BoundedRawGnssWriter.State.STOPPED
        completions.forEach { it(success) }
        publish()
    }

    private fun record(event: GnssMeasurementsEvent, activeGeneration: Long) {
        val callbackElapsedNanos = SystemClock.elapsedRealtimeNanos()
        val callbackUtcMillis = System.currentTimeMillis()
            if (!writer.accepts(activeGeneration)) return
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
                val count = event.measurements.size.toLong()
                writer.append(activeGeneration, BoundedRawGnssWriter.Batch(
                    rows.toByteArray(Charsets.UTF_8), count, batchAdr,
                    if (clock.fullBiasNanos == null) count else 0L, batchMissingCodes,
                    if (clock.fullBiasNanos == null || clock.leapSecond == null) count else 0L,
                    callbackElapsedNanos,
                ))
            } catch (failure: Exception) {
                writer.fail("원시 GNSS 기록 실패: ${failure.message ?: failure.javaClass.simpleName}")
            }
    }

    private fun unregister() {
        val listener = callback
        callback = null
        registered = false
        if (listener != null) runCatching { locationManager?.unregisterGnssMeasurementsCallback(listener) }
    }

    private fun snapshot(): Status {
        val value = writer.snapshot
        val warning = when {
            value.error != null || value.droppedMeasurements > 0 -> "불완전 Raw: 누락 또는 저장 미확인 ${value.droppedMeasurements}개. PPK 연속성을 보장하지 않습니다."
            value.missingFullBias > 0 -> "FullBiasNanos가 없는 관측 ${value.missingFullBias}개: 현재 파일의 PPK 변환은 제한됩니다."
            value.measurements == 0L -> "원시 GNSS 관측 수신 대기 중입니다."
            value.adrValid == 0L -> "유효 ADR 반송파 관측이 아직 없습니다. PPK 가능 여부가 확인되지 않았습니다."
            value.missingCodeType > 0L -> "CodeType 미제공 관측 ${value.missingCodeType}개는 분석에서 제외될 수 있습니다."
            value.utcFallbacks > 0L -> "기기 UTC를 사용한 관측 ${value.utcFallbacks}개: GNSS 시간 정합성은 분석에서 확인합니다."
            else -> null
        }
        val state = if (value.state == BoundedRawGnssWriter.State.RECORDING && !registered) State.STARTING
            else State.valueOf(value.state.name)
        return Status(state, value.measurements, value.epochs, value.adrValid, value.lastEpochNanos,
            value.error, if (state == State.STOPPED) sessionFile else stagingFile,
            value.missingFullBias, value.missingCodeType, value.utcFallbacks, warning,
            value.closed, value.pendingBatches, value.pendingBytes, value.droppedMeasurements)
    }

    private fun publish() {
        if (!statusPending.compareAndSet(false, true)) return
        mainHandler.post {
            statusPending.set(false)
            val value = status
            if (value.state == State.STOPPING || value.closed) unregister()
            onStatus(value)
        }
    }

    companion object {
        const val MAX_FILE_BYTES = 64L * 1024L * 1024L
    }
}
