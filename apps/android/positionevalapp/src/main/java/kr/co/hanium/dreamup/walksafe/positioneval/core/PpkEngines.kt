package kr.co.hanium.dreamup.walksafe.positioneval.core

import java.io.File

data class ComponentStatus(val available: Boolean, val code: String, val detail: String)

data class ConversionOutput(
    val observationFile: File,
    val navigationFile: File?,
    val converterName: String,
    val converterCommit: String,
)

interface GnssLoggerRinexConverter {
    val status: ComponentStatus
    fun convert(rawGnssLogger: File, outputDirectory: File): ConversionOutput
}

class PinnedAndroidRinexConverter : GnssLoggerRinexConverter {
    companion object {
        const val UPSTREAM = "https://github.com/rtklibexplorer/android_rinex"
        const val COMMIT = "b27fcd07bc085e5213ba29655ad6168b61d60b9e"
        const val LICENSE = "BSD-2-Clause"
    }
    override val status = ComponentStatus(
        true,
        "RAW_TXT_CONVERTER_AVAILABLE",
        "RINEX 반송파 위상·LLI·행 단위 제외를 적용하고 RTKLIB readrnxt() round-trip을 검증했습니다.",
    )

    override fun convert(rawGnssLogger: File, outputDirectory: File): ConversionOutput {
        check(status.available) { status.code }
        GnssLoggerValidator.validate(rawGnssLogger)
        val observationFile = File(outputDirectory, "rover.obs")
        GnssLoggerRinex3Converter.convert(rawGnssLogger, observationFile)
        return ConversionOutput(
            observationFile = observationFile,
            navigationFile = null,
            converterName = "WalkSafe Kotlin android_rinex port",
            converterCommit = COMMIT,
        )
    }
}

data class PpkRequest(
    val roverObservation: File,
    val roverNavigation: File?,
    val baseObservations: List<File>,
    val baseNavigations: List<File>,
    val outputPosition: File,
) {
    constructor(
        roverObservation: File,
        roverNavigation: File?,
        baseObservation: File,
        baseNavigation: File,
        outputPosition: File,
    ) : this(
        roverObservation = roverObservation,
        roverNavigation = roverNavigation,
        baseObservations = listOf(baseObservation),
        baseNavigations = listOf(baseNavigation),
        outputPosition = outputPosition,
    )
}

class PpkEngineException(val reasonCode: String, message: String) : IllegalStateException(message)

interface PpkEngine {
    val status: ComponentStatus
    val name: String
    val sourceCommit: String
    fun run(request: PpkRequest)
}

data class NativeRinexObservationSummary(
    val epochCount: Int,
    val satelliteCount: Int,
    val pseudorangeCount: Int,
    val carrierPhaseCount: Int,
)

class Demo5JniPpkEngine : PpkEngine {
    companion object {
        const val UPSTREAM = "https://github.com/rtklibexplorer/RTKLIB"
        const val COMMIT = "62d4677ed8425a4e2748c6d390b500d1afb493fc"
        const val VERSION = "2.5.1"
        const val LICENSE = "BSD-2-Clause"
        const val NATIVE_VERSION = "RTKLIB-EX/2.5.1@62d4677ed8425a4e2748c6d390b500d1afb493fc"
        private const val MAX_BASE_OBSERVATION_FILES = 49
        private const val MAX_BASE_NAVIGATION_FILES = 392
        private val processLock = Any()
    }
    override val name = "RTKLIB-EX demo5"
    override val sourceCommit = COMMIT
    override val status: ComponentStatus
        get() {
            val nativeVersion = Demo5NativeBridge.versionOrNull
            return if (nativeVersion == NATIVE_VERSION) {
                ComponentStatus(
                    true,
                    "AVAILABLE",
                    "$name $VERSION JNI · UTC LLH · kinematic combined",
                )
            } else {
                ComponentStatus(
                    false,
                    "ENGINE_NOT_AVAILABLE",
                    Demo5NativeBridge.loadFailureOrNull?.message
                        ?: "고정한 RTKLIB JNI 버전이 일치하지 않습니다.",
                )
            }
        }

    override fun run(request: PpkRequest) = run(
        request,
        AnalysisRunGeneration.next(),
        AnalysisCancellation(),
    )

    fun run(request: PpkRequest, generation: Long, cancellation: AnalysisCancellation) {
        require(generation > 0L)
        val nativeToken = Demo5NativeBridge.createRunToken(generation)
        val cancellationRegistration = cancellation.register(nativeToken::cancel)
        try {
            synchronized(processLock) {
            cancellation.throwIfCancelled()
            val currentStatus = status
            if (!currentStatus.available) {
                throw PpkEngineException(currentStatus.code, currentStatus.detail)
            }

            val rover = request.roverObservation.canonicalFile
            val bases = request.baseObservations
                .map { it.canonicalFile }
                .distinctBy { it.path }
            val navigation = (listOfNotNull(request.roverNavigation) + request.baseNavigations)
                .map { it.canonicalFile }
                .distinctBy { it.path }
            if (bases.isEmpty() || navigation.isEmpty()) {
                throw PpkEngineException("PPK_INPUT_INVALID", "로버·기준국 관측과 항법 RINEX가 모두 필요합니다.")
            }
            if (bases.size > MAX_BASE_OBSERVATION_FILES ||
                request.baseNavigations.distinctBy { it.canonicalPath }.size > MAX_BASE_NAVIGATION_FILES
            ) {
                throw PpkEngineException(
                    "PPK_SESSION_TOO_LONG",
                    "PPK 세션은 최대 48시간(49개 hourly 관측, 392개 기준국 항법 파일)까지 처리합니다.",
                )
            }
            val inputs = listOf(rover) + bases + navigation
            if (inputs.distinctBy { it.path }.size != inputs.size) {
                throw PpkEngineException("PPK_INPUT_INVALID", "관측·항법 RINEX 파일은 서로 달라야 합니다.")
            }
            inputs.forEach(::requireReadableInput)

            val output = request.outputPosition.canonicalFile
            if (inputs.any { it.canonicalFile == output }) {
                throw PpkEngineException("PPK_OUTPUT_INVALID", "PPK 출력은 입력 파일과 달라야 합니다.")
            }
            val eventOutput = eventOutputFor(output).canonicalFile
            if (inputs.any { it.canonicalFile == eventOutput }) {
                throw PpkEngineException(
                    "PPK_OUTPUT_INVALID",
                    "RTKLIB 이벤트 출력은 입력 파일과 달라야 합니다.",
                )
            }
            val parent = output.parentFile
                ?: throw PpkEngineException("PPK_OUTPUT_INVALID", "PPK 출력 폴더가 없습니다.")
            if ((!parent.exists() && !parent.mkdirs()) || !parent.isDirectory) {
                throw PpkEngineException("PPK_OUTPUT_INVALID", "PPK 출력 폴더를 만들 수 없습니다.")
            }
            if (output.exists() && !output.delete()) {
                throw PpkEngineException("PPK_OUTPUT_INVALID", "이전 PPK 출력을 지우지 못했습니다.")
            }
            if (eventOutput.exists() && !eventOutput.delete()) {
                throw PpkEngineException("PPK_OUTPUT_INVALID", "이전 RTKLIB 이벤트 출력을 지우지 못했습니다.")
            }

            val nativeResult = Demo5NativeBridge.run(
                inputs.map { it.path }.toTypedArray(),
                bases.size,
                output.path,
                nativeToken,
            )
            if (nativeResult == 4) {
                output.delete()
                eventOutput.delete()
                throw AnalysisCancelledException()
            }
            cancellation.throwIfCancelled()
            val eventCleanupFailed = eventOutput.exists() && !eventOutput.delete()
            if (nativeResult != 0 || !output.isFile || eventCleanupFailed) {
                output.delete()
                throw PpkEngineException(
                    "PPK_PROCESSING_FAILED",
                    "RTKLIB postpos 계산이 유효한 결과를 만들지 못했습니다. native_code=$nativeResult",
                )
            }

            runCatching {
                PpkOutputContract.validate(output)
            }.getOrElse { error ->
                output.delete()
                throw PpkEngineException(
                    "PPK_OUTPUT_INVALID",
                    "RTKLIB 결과가 UTC LLH .pos 계약을 만족하지 않습니다: ${error.message ?: "형식 오류"}",
                )
            }
            // Q=1의 개수가 0이어도 엔진 실패로 바꾸지 않는다. 평가기가 '정답 부족'으로 판정한다.
            }
        } finally {
            cancellationRegistration.close()
            nativeToken.close()
        }
    }

    fun validateObservation(file: File): NativeRinexObservationSummary {
        requireReadableInput(file)
        val values = synchronized(processLock) {
            Demo5NativeBridge.observationSummary(file.canonicalPath)
        } ?: throw PpkEngineException(
            "RINEX_OBSERVATION_INVALID",
            "RTKLIB이 관측 RINEX의 epoch·위성·의사거리·반송파 위상을 읽지 못했습니다.",
        )
        if (values.size != 4) {
            throw PpkEngineException("RINEX_OBSERVATION_INVALID", "RTKLIB 관측 요약 형식이 잘못되었습니다.")
        }
        return NativeRinexObservationSummary(
            epochCount = values[0],
            satelliteCount = values[1],
            pseudorangeCount = values[2],
            carrierPhaseCount = values[3],
        )
    }

    private fun requireReadableInput(file: File) {
        if (!file.isFile || !file.canRead() || file.length() <= 0L) {
            throw PpkEngineException("PPK_INPUT_INVALID", "읽을 수 있는 비어 있지 않은 RINEX 파일이 필요합니다.")
        }
    }

    private fun eventOutputFor(output: File): File {
        val path = output.path
        val extension = path.lastIndexOf('.')
        val prefix = if (extension > 0) path.substring(0, extension) else path
        return File("${prefix}_events.pos")
    }
}
