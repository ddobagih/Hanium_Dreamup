package kr.co.hanium.dreamup.walksafe.positioneval

import android.content.Context
import android.net.Uri
import java.io.File
import java.lang.ref.WeakReference
import java.time.Instant
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kr.co.hanium.dreamup.walksafe.positioneval.core.PpkRunContext
import java.util.concurrent.Future
import kr.co.hanium.dreamup.walksafe.positioneval.core.AnalysisCancellation
import kr.co.hanium.dreamup.walksafe.positioneval.core.AnalysisCancelledException
import kr.co.hanium.dreamup.walksafe.positioneval.core.AnalysisRunGeneration
import kr.co.hanium.dreamup.walksafe.positioneval.core.acceptsAnalysisCallback
import kr.co.hanium.dreamup.walksafe.positioneval.core.ExtractedEntry
import kr.co.hanium.dreamup.walksafe.positioneval.core.GnssLoggerConversionException
import kr.co.hanium.dreamup.walksafe.positioneval.core.GnssLoggerValidator
import kr.co.hanium.dreamup.walksafe.positioneval.core.ImportedArtifact
import kr.co.hanium.dreamup.walksafe.positioneval.core.ManualBaseRinexImporter
import kr.co.hanium.dreamup.walksafe.positioneval.core.NgiiException
import kr.co.hanium.dreamup.walksafe.positioneval.core.PpkEngineException
import kr.co.hanium.dreamup.walksafe.positioneval.core.PpkFormatException
import kr.co.hanium.dreamup.walksafe.positioneval.core.PrivateFileImporter
import kr.co.hanium.dreamup.walksafe.positioneval.core.RinexHeaderValidator
import kr.co.hanium.dreamup.walksafe.positioneval.core.TraceFormatException
import kr.co.hanium.dreamup.walksafe.positioneval.core.TraceSession
import kr.co.hanium.dreamup.walksafe.positioneval.core.TraceV2Parser

enum class ArtifactKind { TRACE, GNSS }

data class AnalysisSnapshot(
    val keyStored: Boolean = false,
    val traceSummary: String? = null,
    val gnssSummary: String? = null,
    val manualBaseSummary: String? = null,
    val manualBaseReady: Boolean = false,
    val status: String = "입력 파일을 선택해 주세요.",
    val busy: Boolean = false,
    val cancelling: Boolean = false,
    val analysisRunning: Boolean = false,
    val hasResult: Boolean = false,
    val inputsReady: Boolean = false,
) {
    val canAnalyze: Boolean get() = inputsReady && keyStored && !busy
    val canAnalyzeManual: Boolean get() = inputsReady && manualBaseReady && !busy
}

internal data class BaseRinexInputs(val observations: List<File>, val navigations: List<File>)

internal object BaseInputClassifier {
    private val navigationTypes = setOf('N', 'G', 'H', 'L', 'J', 'C', 'I', 'S')

    fun classify(entries: List<ExtractedEntry>, session: TraceSession): BaseRinexInputs {
        val observations = mutableListOf<File>()
        val observationHeaders = mutableListOf<kr.co.hanium.dreamup.walksafe.positioneval.core.RinexHeader>()
        val navigations = mutableListOf<File>()
        entries.sortedBy(ExtractedEntry::originalName).forEach { entry ->
            val header = RinexHeaderValidator.validate(entry.file, session.startUtcEpochMs, session.endUtcEpochMs)
            when (header.type) {
                'O' -> {
                    observations += entry.file
                    observationHeaders += header
                }
                in navigationTypes -> navigations += entry.file
            }
        }
        if (observations.isEmpty() || navigations.isEmpty()) {
            throw NgiiException("BASE_DATA_INVALID", "기준국 관측·항법 RINEX 구성이 완전하지 않습니다.")
        }
        BaseObservationSetPolicy.validate(observationHeaders, session.startUtcEpochMs, session.endUtcEpochMs)
        return BaseRinexInputs(observations, navigations)
    }
}

internal object BaseObservationSetPolicy {
    const val MAX_ALLOWED_GAP_MS = 2L * 60L * 1000L
    private const val MAX_STATION_POSITION_DELTA_M = 10.0

    fun validate(
        headers: List<kr.co.hanium.dreamup.walksafe.positioneval.core.RinexHeader>,
        sessionStartUtcMs: Long,
        sessionEndUtcMs: Long,
    ) {
        if (headers.isEmpty() || sessionEndUtcMs < sessionStartUtcMs) invalid()
        val markers = headers.map { it.markerName?.trim()?.uppercase(Locale.ROOT).orEmpty() }.toSet()
        if (markers.size != 1 || markers.single().isBlank()) invalid()
        val reference = headers.first().approximatePosition ?: invalid()
        headers.forEach { header ->
            val position = header.approximatePosition ?: invalid()
            val distance = kotlin.math.sqrt(
                (position.xM - reference.xM) * (position.xM - reference.xM) +
                    (position.yM - reference.yM) * (position.yM - reference.yM) +
                    (position.zM - reference.zM) * (position.zM - reference.zM),
            )
            if (!distance.isFinite() || distance > MAX_STATION_POSITION_DELTA_M) invalid()
            if ((header.maxObservationGapMs ?: Long.MAX_VALUE) > MAX_ALLOWED_GAP_MS) invalid()
        }
        val intervals = headers.map { header ->
            val start = header.firstObservationUtcMs ?: invalid()
            val end = header.lastObservationUtcMs ?: invalid()
            if (end < start) invalid()
            start to end
        }.sortedBy { it.first }
        if (intervals.first().first > sessionStartUtcMs + MAX_ALLOWED_GAP_MS) invalid()
        var coveredUntil = intervals.first().second
        intervals.drop(1).forEach { (start, end) ->
            if (start > coveredUntil + MAX_ALLOWED_GAP_MS) invalid()
            if (end > coveredUntil) coveredUntil = end
        }
        if (coveredUntil < sessionEndUtcMs - MAX_ALLOWED_GAP_MS) invalid()
    }

    private fun invalid(): Nothing = throw NgiiException(
        "BASE_DATA_INVALID",
        "기준국 관측이 같은 관측소의 연속 시간 범위를 만족하지 않습니다.",
    )
}

internal object AnalysisFailurePolicy {
    private val truthInsufficientCodes = setOf(
        "BASE_DATA_UNAVAILABLE", "BASE_DATA_INVALID", "BASE_STATION_TOO_FAR", "INSUFFICIENT_RAW_MEASUREMENTS",
        "INSUFFICIENT_VALID_MEASUREMENTS", "INSUFFICIENT_REFERENCE", "EVALUATION_WINDOW_TOO_SHORT",
        "TIME_ALIGNMENT_FAILED", "PPK_QUALITY_OR_TIME_SYSTEM_INVALID", "PPK_FIXED_COUNT_MISMATCH",
        "EVALUATION_GRID_TOO_SHORT", "PPK_NO_FIXED_SOLUTION", "FIX_GRID_TOO_SPARSE", "FIX_COVERAGE_TOO_LOW",
    )
    private val messages = mapOf(
        "BASE_DATA_UNAVAILABLE" to "테스트 시간 전체를 덮는 기준국 자료를 찾지 못했습니다.",
        "BASE_DATA_INVALID" to "기준국 자료가 불완전하거나 형식 검증을 통과하지 못했습니다.",
        "BASE_STATION_TOO_FAR" to "30km 안에서 사용할 수 있는 기준국 자료를 찾지 못했습니다.",
        "INSUFFICIENT_RAW_MEASUREMENTS" to "원시 GNSS 측정값이 부족합니다.",
        "INSUFFICIENT_VALID_MEASUREMENTS" to "PPK에 사용할 수 있는 원시 GNSS 측정값이 부족합니다.",
        "INSUFFICIENT_REFERENCE" to "UTC에 연결된 WalkSafe 위치 기록이 부족합니다.",
        "RAW_TXT_CONVERTER_VALIDATION_PENDING" to "원시 GNSS 변환기의 안전 검증이 아직 완료되지 않았습니다.",
        "ENGINE_NOT_AVAILABLE" to "이 기기에서 고정된 RTKLIB 엔진을 불러오지 못했습니다.",
        "PPK_INPUT_INVALID" to "PPK 입력 자료 구성이 올바르지 않습니다.",
        "PPK_OUTPUT_INVALID" to "PPK 결과 형식 검증에 실패했습니다.",
        "PPK_PROCESSING_FAILED" to "PPK 참조 위치 계산에 실패했습니다.",
        "PPK_SESSION_TOO_LONG" to "PPK 분석 세션이 48시간 제한을 넘었습니다.",
        "KEY_NOT_AVAILABLE" to "저장된 다운로드 키를 사용할 수 없습니다. 키를 다시 등록해 주세요.",
        "ANALYSIS_FAILED" to "분석 처리 중 오류가 발생했습니다.",
    )
    private val allowedCodes = messages.keys + truthInsufficientCodes

    fun reasonCode(error: Throwable): String {
        val raw = when (error) {
            is NgiiException -> error.reasonCode
            is GnssLoggerConversionException -> error.reasonCode
            is PpkEngineException -> error.reasonCode
            is PpkFormatException -> "PPK_OUTPUT_INVALID"
            is TraceFormatException -> error.reasonCode
            else -> "ANALYSIS_FAILED"
        }
        return sanitize(raw)
    }

    fun sanitize(reasonCode: String): String = reasonCode.takeIf(allowedCodes::contains) ?: "ANALYSIS_FAILED"

    fun isTruthInsufficient(reasonCode: String): Boolean = reasonCode in truthInsufficientCodes

    fun publicMessage(reasonCode: String): String = messages[reasonCode] ?: "분석 처리 중 오류가 발생했습니다."

    fun redacted(artifact: ImportedArtifact): ImportedArtifact = artifact.copy(displayName = "omitted")
}

internal object AnalysisStatePolicy {
    fun begin(current: AnalysisSnapshot, message: String, clearResult: Boolean): AnalysisSnapshot = current.copy(
        busy = true,
        status = message,
        hasResult = if (clearResult) false else current.hasResult,
    )

}

internal class AnalysisCoordinator(
    private val storage: AnalysisStorage,
    private val pipeline: AnalysisPipeline,
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "walksafe-position-evaluator").apply { isDaemon = true }
    },
) {
    companion object {
        private var processInstance: AnalysisCoordinator? = null

        @Synchronized
        fun get(context: Context): AnalysisCoordinator = processInstance ?: run {
            val storage = AndroidAnalysisStorage(context.applicationContext)
            AnalysisCoordinator(storage, storage.pipeline()).also { processInstance = it }
        }
    }

    private data class AnalysisRun(
        val generation: Long,
        val cancellation: AnalysisCancellation,
        @Volatile var future: Future<*>? = null,
    )

    private val guard = Any()
    private var initialized = false
    private var observer = WeakReference<((AnalysisSnapshot) -> Unit)>(null)
    private var snapshot = AnalysisSnapshot()
    private var traceArtifact: ImportedArtifact? = null
    private var traceSession: TraceSession? = null
    private var gnssArtifact: ImportedArtifact? = null
    private var manualBaseArtifacts = emptyList<ImportedArtifact>()
    private var lastJson: String? = null
    private var activeRun: AnalysisRun? = null
    private val cancellingRuns = mutableSetOf<Long>()

    fun initialize() {
        val shouldClean: Boolean
        synchronized(guard) {
            shouldClean = !initialized
            if (!initialized) {
                initialized = true
                snapshot = snapshot.copy(keyStored = storage.isKeyStored())
            }
        }
        if (shouldClean) worker.execute { cleanOrphans() }
    }

    fun attach(listener: (AnalysisSnapshot) -> Unit) {
        val current = synchronized(guard) {
            observer = WeakReference(listener)
            snapshot
        }
        listener(current)
    }

    fun detach(listener: (AnalysisSnapshot) -> Unit) {
        synchronized(guard) { if (observer.get() === listener) observer.clear() }
    }

    fun saveKey(value: CharArray) {
        if (!begin("다운로드 키를 기기 보안 저장소에 저장하는 중입니다.")) {
            value.fill('\u0000')
            return
        }
        worker.execute {
            runCatching { storage.saveKey(value) }
                .onSuccess { finish("다운로드 키를 안전하게 저장했습니다.", keyStored = true) }
                .onFailure {
                    value.fill('\u0000')
                    finish("입력 확인 필요\n다운로드 키를 저장하지 못했습니다.", keyStored = false)
                }
        }
    }

    fun importDocument(uri: Uri, kind: ArtifactKind) = importDocument(kind) {
        storage.import(uri, PrivateFileImporter.MAX_INPUT_BYTES, null)
    }

    internal fun importDocument(kind: ArtifactKind, source: () -> ImportedArtifact) {
        if (!begin("선택한 파일을 앱 비공개 공간으로 복사하고 검사하는 중입니다.", clearResult = true)) return
        worker.execute {
            var imported: ImportedArtifact? = null
            try {
                imported = source()
                when (kind) {
                    ArtifactKind.TRACE -> commitTrace(imported, TraceV2Parser.parse(imported.file))
                    ArtifactKind.GNSS -> commitGnss(imported, GnssLoggerValidator.validate(imported.file).rawMeasurementCount)
                }
            } catch (error: Throwable) {
                val cleanupFailed = runCatching { imported?.file?.let(::deleteFileChecked) }.isFailure
                val prefix = if (kind == ArtifactKind.TRACE) "WalkSafe" else "GnssLogger"
                val code = AnalysisFailurePolicy.reasonCode(error)
                val category = if (AnalysisFailurePolicy.isTruthInsufficient(code)) "정답 부족" else "입력 확인 필요"
                val cleanupWarning = if (cleanupFailed) "\n주의: 가져온 사본을 삭제하지 못했습니다." else ""
                finish("$category\n$prefix 파일을 사용할 수 없습니다. ${AnalysisFailurePolicy.publicMessage(code)}$cleanupWarning")
            }
        }
    }

    fun importBaseDocuments(uris: List<Uri>) = importBaseDocuments(
        uris.size,
        uris.distinct().map { uri ->
            { maxBytes: Long, cancellation: AnalysisCancellation -> storage.import(uri, maxBytes, cancellation) }
        },
    )

    internal fun importBaseDocuments(
        selectionCount: Int,
        sources: List<(Long, AnalysisCancellation) -> ImportedArtifact>,
    ) {
        val session: TraceSession
        val old: List<ImportedArtifact>
        val run: AnalysisRun
        synchronized(guard) {
            if (snapshot.busy || snapshot.cancelling) return
            session = traceSession ?: return
            old = manualBaseArtifacts
            lastJson = null
            run = AnalysisRun(AnalysisRunGeneration.next(), AnalysisCancellation())
            activeRun = run
        }
        change {
            it.copy(
                busy = true, analysisRunning = true, hasResult = false, manualBaseReady = false, manualBaseSummary = null,
                status = "기준국 파일을 복사하고 관측소·시간·관측·항법 자료를 확인하는 중입니다.",
            )
        }
        val future = worker.submit {
            val imported = mutableListOf<ImportedArtifact>()
            val staging = File(storage.root, "work/manual-check-${UUID.randomUUID()}")
            var committed = false
            var failureMessage: String? = null
            try {
                retireManualBaseCopies(old)
                run.cancellation.throwIfCancelled()
                if (selectionCount !in 1..ManualBaseRinexImporter.MAX_INPUT_FILES) {
                    throw NgiiException("BASE_DATA_INVALID", "기준국 파일은 1~32개를 선택해 주세요.")
                }
                sources.forEach { source ->
                    val remaining = ManualBaseRinexImporter.MAX_INPUT_BYTES - imported.sumOf { it.byteCount }
                    if (remaining <= 0L) throw NgiiException("BASE_DATA_INVALID", "기준국 입력 전체 크기가 제한을 넘습니다.")
                    imported += source(
                        minOf(PrivateFileImporter.MAX_INPUT_BYTES, remaining), run.cancellation,
                    )
                }
                val prepared = ManualBaseRinexImporter.prepare(imported, session, staging, run.cancellation)
                run.cancellation.throwIfCancelled()
                committed = finishBaseImport(
                    run, imported.toList(),
                    "기준국 ${prepared.station.code} · 선택 파일 ${imported.size}개\n" +
                        "관측·항법 ${prepared.rinexEntries.size}개 확인 · 키 없이 분석 가능",
                    "수동 기준국 자료 확인 완료\n같은 테스트의 GnssLogger TXT를 선택한 뒤 선택 파일로 분석하세요.",
                )
            } catch (error: Throwable) {
                if (error !is AnalysisCancelledException && !run.cancellation.isCancelled()) {
                    val code = if (error is NgiiException) error.reasonCode else "BASE_DATA_INVALID"
                    failureMessage = "입력 확인 필요 · $code\n${AnalysisFailurePolicy.publicMessage(code)}"
                }
            } finally {
                val cleanupFailed = runCatching {
                    deleteTreeChecked(staging)
                    if (!committed) imported.forEach { deleteFileChecked(it.file) }
                }.isFailure
                if (!committed) {
                    change {
                        // Retain any copy whose cleanup failed before accepting another selection.
                        manualBaseArtifacts = (manualBaseArtifacts + imported).distinctBy { artifact -> artifact.file }
                        reconcileInputs(it)
                    }
                }
                if (run.cancellation.isCancelled()) {
                    finishCancellation(run, if (cleanupFailed) "\n임시 사본 삭제 상태를 확인해 주세요." else "")
                } else if (failureMessage != null) {
                    val warning = if (cleanupFailed) "\n주의: 임시 사본을 완전히 삭제하지 못했습니다." else ""
                    finishBaseImport(run, null, null, failureMessage + warning)
                } else if (cleanupFailed) {
                    change { it.copy(status = it.status + "\n주의: 임시 사본을 완전히 삭제하지 못했습니다.") }
                }
            }
        }
        run.future = future
        if (run.cancellation.isCancelled()) future.cancel(true)
        // A queued Future may be cancelled before its body/finally starts; retire old copies anyway.
        worker.execute {
            val failed = runCatching { retireManualBaseCopies(old) }.isFailure
            if (run.cancellation.isCancelled()) {
                finishCancellation(run, if (failed) "\n이전 기준국 사본을 완전히 삭제하지 못했습니다." else "")
            }
        }
    }

    private fun finishBaseImport(
        run: AnalysisRun, artifacts: List<ImportedArtifact>?, summary: String?, message: String,
    ): Boolean {
        val next: AnalysisSnapshot
        val callback: ((AnalysisSnapshot) -> Unit)?
        synchronized(guard) {
            if (!acceptsAnalysisCallback(activeRun?.generation, run.generation, run.cancellation.isCancelled())) return false
            if (artifacts != null) manualBaseArtifacts = artifacts
            val reconciled = reconcileInputs(snapshot)
            activeRun = null
            next = reconciled.copy(
                busy = false, analysisRunning = false,
                manualBaseReady = artifacts != null && artifacts.isNotEmpty() && manualBaseArtifacts.size == artifacts.size,
                manualBaseSummary = if (artifacts != null) summary else reconciled.manualBaseSummary,
                status = message,
            )
            snapshot = next
            callback = observer.get()
        }
        callback?.invoke(next)
        return true
    }

    private fun retireManualBaseCopies(artifacts: List<ImportedArtifact>) {
        try {
            artifacts.forEach { deleteFileChecked(it.file) }
        } finally {
            // Reconcile current ownership instead of restoring an old generation's list.
            change { reconcileInputs(it) }
        }
    }

    fun startAnalysis(useManualBase: Boolean = false) {
        val inputs: AnalysisInputs
        val run: AnalysisRun
        val firstState: AnalysisSnapshot
        val callback: ((AnalysisSnapshot) -> Unit)?
        synchronized(guard) {
            val trace = traceArtifact
            val session = traceSession
            val gnss = gnssArtifact
            if (snapshot.busy || trace == null || session == null || gnss == null) return
            if (if (useManualBase) !snapshot.manualBaseReady else !snapshot.keyStored) return
            inputs = AnalysisInputs(trace, session, gnss, if (useManualBase) manualBaseArtifacts.toList() else emptyList())
            val precedingCancellation = activeRun?.cancellation?.isCancelled() == true
            run = AnalysisRun(AnalysisRunGeneration.next(), AnalysisCancellation())
            activeRun = run
            lastJson = null
            firstState = snapshot.copy(
                busy = true,
                cancelling = false,
                analysisRunning = true,
                hasResult = false,
                status = if (precedingCancellation) {
                    "이전 분석을 중단·정리하는 중입니다.\n새 분석은 정리가 끝나면 순서대로 시작합니다."
                } else {
                    "1/6 입력 무결성 확인 완료\n2/6 GnssLogger 원시 측정값을 RINEX로 변환하는 중입니다."
                },
            )
            snapshot = firstState
            callback = observer.get()
        }
        callback?.invoke(firstState)
        val future = worker.submit { analyze(inputs, run) }
        run.future = future
        if (run.cancellation.isCancelled()) future.cancel(true)
    }

    fun cancelAnalysis() {
        val run: AnalysisRun
        val next: AnalysisSnapshot
        val callback: ((AnalysisSnapshot) -> Unit)?
        synchronized(guard) {
            run = activeRun ?: return
            if (run.cancellation.isCancelled()) return
            cancellingRuns += run.generation
            lastJson = null
            next = snapshot.copy(
                status = "취소 요청됨\n실행을 중단하고 임시 파일을 정리하는 중입니다. 새 분석을 누르면 정리 뒤 순서대로 시작합니다.",
                busy = false,
                cancelling = true,
                analysisRunning = false,
                hasResult = false,
                inputsReady = traceArtifact != null && gnssArtifact != null,
            )
            snapshot = next
            callback = observer.get()
        }
        callback?.invoke(next)
        run.cancellation.cancel()
        run.future?.cancel(true)
        worker.execute { finishCancellation(run) }
    }

    fun exportResult(uri: Uri) {
        val json = synchronized(guard) { lastJson } ?: return
        if (!begin("결과 JSON을 선택한 문서에 저장하는 중입니다.")) return
        worker.execute {
            runCatching {
                storage.export(uri, json)
            }.onSuccess { finish("결과 JSON 저장 완료") }
                .onFailure { finish("처리 실패\n결과 JSON을 저장하지 못했습니다.") }
        }
    }

    fun clearPrivateData() {
        if (!begin("가져온 위치 기록과 다운로드 키를 삭제하는 중입니다.")) return
        worker.execute {
            runCatching {
                val artifacts = synchronized(guard) { listOfNotNull(traceArtifact, gnssArtifact) + manualBaseArtifacts }
                artifacts.map(ImportedArtifact::file).distinct().forEach(::deleteFileChecked)
                storage.clearKey()
                deleteTreeChecked(storage.root.resolve("imports"))
                deleteTreeChecked(storage.root.resolve("work"))
                deleteFileChecked(storage.root.resolve("ngii-key.v1.part"))
                synchronized(guard) {
                    traceArtifact = null
                    traceSession = null
                    gnssArtifact = null
                    manualBaseArtifacts = emptyList()
                    lastJson = null
                }
            }.onSuccess {
                change { AnalysisSnapshot(status = "가져온 기록과 다운로드 키를 삭제했습니다.") }
            }.onFailure {
                change {
                    val tracePresent = traceArtifact?.file?.isFile == true
                    val gnssPresent = gnssArtifact?.file?.isFile == true
                    if (!tracePresent) {
                        traceArtifact = null
                        traceSession = null
                    }
                    if (!gnssPresent) gnssArtifact = null
                    manualBaseArtifacts = manualBaseArtifacts.filter { artifact -> artifact.file.isFile }
                    lastJson = null
                    AnalysisSnapshot(
                        keyStored = storage.isKeyStored(),
                        traceSummary = it.traceSummary.takeIf { tracePresent },
                        gnssSummary = it.gnssSummary.takeIf { gnssPresent },
                        manualBaseReady = false,
                        manualBaseSummary = "일부 삭제 후에는 기준국 파일을 다시 선택해 주세요.".takeIf { manualBaseArtifacts.isNotEmpty() },
                        status = "처리 실패\n일부 비공개 데이터를 삭제하지 못했습니다.",
                        inputsReady = tracePresent && gnssPresent,
                    )
                }
            }
        }
    }

    fun resultFileName(): String =
        "walksafe-evaluation-${Instant.now().toString().replace(Regex("[:.-]"), "")}.json"

    private fun analyze(inputs: AnalysisInputs, run: AnalysisRun) {
        val result = pipeline.run(inputs, PpkRunContext(run.generation, run.cancellation)) { message ->
            progress(run, message)
        }
        val warning = if (result.cleanupFailed) "\n주의: 임시 작업 파일을 완전히 삭제하지 못했습니다." else ""
        if (run.cancellation.isCancelled()) {
            finishCancellation(run, warning)
        } else {
            result.completion?.let { complete(run, it.json, it.message + warning, it.keyStored) }
        }
    }

    private fun begin(message: String, clearResult: Boolean = false): Boolean {
        val next: AnalysisSnapshot
        val callback: ((AnalysisSnapshot) -> Unit)?
        synchronized(guard) {
            if (snapshot.busy || snapshot.cancelling) return false
            if (clearResult) lastJson = null
            next = AnalysisStatePolicy.begin(snapshot, message, clearResult)
            snapshot = next
            callback = observer.get()
        }
        callback?.invoke(next)
        return true
    }

    private fun progress(run: AnalysisRun, message: String) = changeIfCurrent(run) { it.copy(status = message) }

    private fun finish(message: String, keyStored: Boolean? = null) = change {
        reconcileInputs(it).copy(
            keyStored = keyStored ?: it.keyStored,
            status = message,
            busy = false,
        )
    }

    /** Called with guard held so retained copies and their readiness are published together. */
    private fun reconcileInputs(current: AnalysisSnapshot): AnalysisSnapshot {
        if (traceArtifact?.file?.isFile != true) {
            traceArtifact = null
            traceSession = null
        }
        if (gnssArtifact?.file?.isFile != true) gnssArtifact = null
        val retainedBase = manualBaseArtifacts.filter { it.file.isFile }
        val baseReady = current.manualBaseReady && traceSession != null &&
            retainedBase.isNotEmpty() && retainedBase.size == manualBaseArtifacts.size
        manualBaseArtifacts = retainedBase
        return current.copy(
            traceSummary = current.traceSummary.takeIf { traceArtifact != null },
            gnssSummary = current.gnssSummary.takeIf { gnssArtifact != null },
            inputsReady = traceArtifact != null && gnssArtifact != null,
            manualBaseReady = baseReady,
            manualBaseSummary = when {
                retainedBase.isEmpty() -> null
                baseReady -> current.manualBaseSummary
                else -> "일부 삭제 후에는 기준국 파일을 다시 선택해 주세요."
            },
        )
    }

    private fun complete(run: AnalysisRun, json: String, message: String, keyStored: Boolean? = null) {
        val next: AnalysisSnapshot
        val callback: ((AnalysisSnapshot) -> Unit)?
        synchronized(guard) {
            if (!acceptsAnalysisCallback(activeRun?.generation, run.generation, run.cancellation.isCancelled())) return
            lastJson = json
            activeRun = null
            next = snapshot.copy(
                keyStored = keyStored ?: snapshot.keyStored,
                status = message,
                busy = false,
                cancelling = false,
                analysisRunning = false,
                hasResult = true,
                inputsReady = true,
            )
            snapshot = next
            callback = observer.get()
        }
        callback?.invoke(next)
    }

    private fun changeIfCurrent(run: AnalysisRun, transform: (AnalysisSnapshot) -> AnalysisSnapshot) {
        val next: AnalysisSnapshot
        val callback: ((AnalysisSnapshot) -> Unit)?
        synchronized(guard) {
            if (!acceptsAnalysisCallback(activeRun?.generation, run.generation, run.cancellation.isCancelled())) return
            next = transform(snapshot)
            snapshot = next
            callback = observer.get()
        }
        callback?.invoke(next)
    }

    private fun finishCancellation(run: AnalysisRun, cleanupWarning: String = "") {
        val next: AnalysisSnapshot
        val callback: ((AnalysisSnapshot) -> Unit)?
        synchronized(guard) {
            if (!cancellingRuns.remove(run.generation)) return
            if (activeRun === run) activeRun = null
            if (activeRun != null || cancellingRuns.isNotEmpty() || snapshot.busy || snapshot.hasResult) return
            next = snapshot.copy(
                status = "분석 취소 완료\n임시 파일 정리가 끝났습니다. 다시 분석할 수 있습니다.$cleanupWarning",
                busy = false,
                cancelling = false,
                analysisRunning = false,
                hasResult = false,
                inputsReady = traceArtifact != null && gnssArtifact != null,
            )
            snapshot = next
            callback = observer.get()
        }
        callback?.invoke(next)
    }

    private fun commitTrace(artifact: ImportedArtifact, session: TraceSession) {
        val previousBase = synchronized(guard) { manualBaseArtifacts }
        previousBase.forEach { deleteFileChecked(it.file) }
        synchronized(guard) { manualBaseArtifacts = emptyList() }
        val old = synchronized(guard) { traceArtifact }
        if (old?.file != null && old.file != artifact.file) deleteFileChecked(old.file)
        synchronized(guard) {
            traceArtifact = artifact
            traceSession = session
            lastJson = null
        }
        val summary = "WalkSafe 기록: ${artifact.displayName}\n${session.samples.size}개 UTC 위치 · " +
            "${Instant.ofEpochMilli(session.startUtcEpochMs)} ~ ${Instant.ofEpochMilli(session.endUtcEpochMs)}\n" +
            "SHA-256 ${artifact.sha256.take(12)}…"
        change {
            it.copy(
                traceSummary = summary, status = "WalkSafe 파일 확인 완료", busy = false, hasResult = false,
                manualBaseReady = false, manualBaseSummary = null,
                inputsReady = traceArtifact != null && gnssArtifact != null,
            )
        }
    }

    private fun commitGnss(artifact: ImportedArtifact, rawMeasurementCount: Int) {
        val old = synchronized(guard) { gnssArtifact }
        if (old?.file != null && old.file != artifact.file) deleteFileChecked(old.file)
        synchronized(guard) {
            gnssArtifact = artifact
            lastJson = null
        }
        val summary = "GnssLogger 기록: ${artifact.displayName}\nRaw 측정 ${rawMeasurementCount}개\n" +
            "SHA-256 ${artifact.sha256.take(12)}…"
        change {
            it.copy(
                gnssSummary = summary, status = "GnssLogger 파일 확인 완료", busy = false, hasResult = false,
                inputsReady = traceArtifact != null && gnssArtifact != null,
            )
        }
    }

    private fun change(transform: (AnalysisSnapshot) -> AnalysisSnapshot) {
        val next: AnalysisSnapshot
        val callback: ((AnalysisSnapshot) -> Unit)?
        synchronized(guard) {
            next = transform(snapshot)
            snapshot = next
            callback = observer.get()
        }
        callback?.invoke(next)
    }

    private fun cleanOrphans() {
        runCatching {
            deleteTreeChecked(storage.root.resolve("work"))
            deleteTreeChecked(storage.root.resolve("imports"))
            deleteFileChecked(storage.root.resolve("ngii-key.v1.part"))
        }.onFailure {
            change { state -> state.copy(status = "주의: 이전 임시 위치 파일을 완전히 삭제하지 못했습니다.") }
        }
    }

    private fun deleteFileChecked(file: File) {
        if (file.exists() && !file.delete()) throw IllegalStateException("PRIVATE_FILE_DELETE_FAILED")
    }

    private fun deleteTreeChecked(directory: File) {
        if (directory.exists() && !directory.deleteRecursively()) {
            throw IllegalStateException("PRIVATE_DIRECTORY_DELETE_FAILED")
        }
    }

}
