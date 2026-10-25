package kr.co.hanium.dreamup.walksafe.positioneval

import java.io.File
import java.time.Instant
import java.util.Locale
import kr.co.hanium.dreamup.walksafe.positioneval.core.AnalysisCancellation
import kr.co.hanium.dreamup.walksafe.positioneval.core.AnalysisCancelledException
import kr.co.hanium.dreamup.walksafe.positioneval.core.BaseStation
import kr.co.hanium.dreamup.walksafe.positioneval.core.EvaluationStatus
import kr.co.hanium.dreamup.walksafe.positioneval.core.ImportedArtifact
import kr.co.hanium.dreamup.walksafe.positioneval.core.ManualBaseRinexImporter
import kr.co.hanium.dreamup.walksafe.positioneval.core.PositionChannel
import kr.co.hanium.dreamup.walksafe.positioneval.core.PositionEvaluator
import kr.co.hanium.dreamup.walksafe.positioneval.core.PpkPosParser
import kr.co.hanium.dreamup.walksafe.positioneval.core.PpkRequest
import kr.co.hanium.dreamup.walksafe.positioneval.core.ResultJson
import kr.co.hanium.dreamup.walksafe.positioneval.core.StrictHttpsTransport
import kr.co.hanium.dreamup.walksafe.positioneval.core.TraceSession
import kr.co.hanium.dreamup.walksafe.positioneval.core.DownloadedBaseData
import kr.co.hanium.dreamup.walksafe.positioneval.core.GnssLoggerRinexConverter
import kr.co.hanium.dreamup.walksafe.positioneval.core.PpkEngine
import kr.co.hanium.dreamup.walksafe.positioneval.core.PpkRunContext

internal data class AnalysisInputs(
    val trace: ImportedArtifact,
    val traceSession: TraceSession,
    val gnss: ImportedArtifact,
    val manualBase: List<ImportedArtifact> = emptyList(),
)

internal fun interface AnalysisBaseProvider {
    fun prepare(inputs: AnalysisInputs, outputDirectory: File, cancellation: AnalysisCancellation): DownloadedBaseData
}

internal interface AnalysisWorkspace {
    val directory: File
    fun cleanup()
}

internal class DirectoryAnalysisWorkspace(override val directory: File) : AnalysisWorkspace {
    override fun cleanup() {
        if (directory.exists() && !directory.deleteRecursively()) {
            throw IllegalStateException("PRIVATE_DIRECTORY_DELETE_FAILED")
        }
    }
}

internal data class AnalysisCompletion(val json: String, val message: String, val keyStored: Boolean? = null)
internal data class AnalysisPipelineResult(val completion: AnalysisCompletion?, val cleanupFailed: Boolean)

/** Owns one analysis workspace; the coordinator alone owns job and result publication. */
internal class AnalysisPipeline(
    private val converter: GnssLoggerRinexConverter,
    private val engine: PpkEngine,
    private val downloadedBase: AnalysisBaseProvider,
    private val workspaceFactory: () -> AnalysisWorkspace,
    private val manualBase: AnalysisBaseProvider = AnalysisBaseProvider { inputs, output, cancellation ->
        ManualBaseRinexImporter.prepare(inputs.manualBase, inputs.traceSession, output, cancellation)
    },
    private val clock: () -> Instant = Instant::now,
) {
    fun run(inputs: AnalysisInputs, run: PpkRunContext, progress: (String) -> Unit): AnalysisPipelineResult {
        val workspace = workspaceFactory()
        val workRoot = workspace.directory
        var completion: AnalysisCompletion? = null
        var cleanupFailed: Boolean
        var station: BaseStation? = null
        var downloads = emptyList<StrictHttpsTransport.Download>()
        try {
            run.cancellation.throwIfCancelled()
            progress("1/6 입력 무결성 확인 완료\n2/6 GnssLogger 원시 측정값을 RINEX로 변환하는 중입니다.")
            if (!converter.status.available) throw PipelineException(converter.status.code)
            if (!engine.status.available) throw PipelineException(engine.status.code)
            val conversion = converter.convert(inputs.gnss.file, File(workRoot, "rover"), run.cancellation)
            run.cancellation.throwIfCancelled()

            val provider = if (inputs.manualBase.isNotEmpty()) {
                progress("2/6 RINEX 변환 완료\n3/6 선택한 기준국 파일의 무결성·시간 범위를 다시 확인하는 중입니다.")
                manualBase
            } else {
                progress("2/6 RINEX 변환 완료\n3/6 가장 가까운 정상 기준국과 시간별 자료를 찾는 중입니다.")
                downloadedBase
            }
            val base = provider.prepare(inputs, File(workRoot, "base"), run.cancellation)
            station = base.station
            downloads = base.downloads
            val baseInputs = BaseInputClassifier.classify(base.rinexEntries, inputs.traceSession)
            run.cancellation.throwIfCancelled()

            progress(
                "3/6 기준국 ${base.station.name}(${base.station.code}) 자료 검증 완료\n" +
                    "4/6 RTKLIB로 PPK 참조 위치 후보를 계산하는 중입니다.",
            )
            val output = File(workRoot, "solution/same-phone-reference.pos")
            engine.run(
                PpkRequest(
                    roverObservation = conversion.observationFile,
                    roverNavigation = conversion.navigationFile,
                    baseObservations = baseInputs.observations,
                    baseNavigations = baseInputs.navigations,
                    outputPosition = output,
                ),
                run,
            )
            run.cancellation.throwIfCancelled()

            progress("4/6 PPK 계산 완료\n5/6 Q=1 FIX만 골라 WalkSafe 위치 오차를 계산하는 중입니다.")
            val parsed = PpkPosParser.parse(output)
            val outcome = PositionEvaluator.evaluate(inputs.traceSession, parsed)
            val json = ResultJson.evaluation(
                outcome,
                AnalysisFailurePolicy.redacted(inputs.trace),
                AnalysisFailurePolicy.redacted(inputs.gnss),
                base.station,
                downloads,
                parsed,
                clock(),
                manualBaseArtifacts = inputs.manualBase.map(AnalysisFailurePolicy::redacted),
            )
            val message = if (outcome.status == EvaluationStatus.TRUTH_INSUFFICIENT) {
                "정답 부족 · ${outcome.reasonCodes.joinToString(", ")}\n" +
                    "충분한 Q=1 FIX가 없어 오차 점수를 만들지 않았습니다."
            } else {
                buildSuccessMessage(outcome)
            }
            run.cancellation.throwIfCancelled()
            completion = AnalysisCompletion(json, message)
        } catch (error: Throwable) {
            if (error !is AnalysisCancelledException && !run.cancellation.isCancelled()) {
                val code = if (error is PipelineException) {
                    AnalysisFailurePolicy.sanitize(error.reasonCode)
                } else {
                    AnalysisFailurePolicy.reasonCode(error)
                }
                val truthInsufficient = AnalysisFailurePolicy.isTruthInsufficient(code)
                val json = ResultJson.processingFailure(
                    code,
                    AnalysisFailurePolicy.publicMessage(code),
                    AnalysisFailurePolicy.redacted(inputs.trace),
                    AnalysisFailurePolicy.redacted(inputs.gnss),
                    inputs.traceSession,
                    station,
                    downloads,
                    clock(),
                    truthInsufficient,
                    manualBaseArtifacts = inputs.manualBase.map(AnalysisFailurePolicy::redacted),
                )
                val category = if (truthInsufficient) "정답 부족" else "처리 실패"
                completion = AnalysisCompletion(
                    json,
                    "$category · $code\n${AnalysisFailurePolicy.publicMessage(code)}",
                    keyStored = if (code == "KEY_NOT_AVAILABLE") false else null,
                )
            }
        } finally {
            cleanupFailed = runCatching { workspace.cleanup() }.isFailure
        }
        return AnalysisPipelineResult(completion.takeUnless { run.cancellation.isCancelled() }, cleanupFailed)
    }

    private fun buildSuccessMessage(outcome: kr.co.hanium.dreamup.walksafe.positioneval.core.EvaluationOutcome): String {
        val filtered = outcome.metrics.firstOrNull { it.channel == PositionChannel.FILTERED }
        val p95 = filtered?.p95ErrorM?.let { String.format(Locale.KOREA, "%.2f m", it) } ?: "표본 부족"
        val within = filtered?.twoMeterCoverage?.let { String.format(Locale.KOREA, "%.1f%%", it * 100.0) } ?: "표본 부족"
        return "6/6 분석 완료\n필터 위치 P95 오차: $p95\n2m 이내 비율: $within\n" +
            "이 결과는 같은 휴대폰의 사후 PPK 기준이며 독립 측량 정답은 아닙니다."
    }

    private class PipelineException(val reasonCode: String) : IllegalStateException(reasonCode)
}
