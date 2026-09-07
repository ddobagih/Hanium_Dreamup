package kr.co.hanium.dreamup.walksafe.positioneval.core

internal object Demo5NativeBridge {
    private val loadResult: Result<String> by lazy {
        runCatching {
            System.loadLibrary("walksafe_ppk")
            nativeVersion()
        }
    }

    val versionOrNull: String?
        get() = loadResult.getOrNull()

    val loadFailureOrNull: Throwable?
        get() = loadResult.exceptionOrNull()

    fun createRunToken(generation: Long): Demo5NativeRunToken {
        check(loadResult.isSuccess) { "RTKLIB JNI를 불러오지 못했습니다." }
        require(generation > 0L)
        val handle = nativeCreateRunToken(generation)
        check(handle != 0L) { "RTKLIB 취소 토큰을 만들지 못했습니다." }
        return Demo5NativeRunToken(handle, generation)
    }

    fun run(
        inputPaths: Array<String>,
        baseObservationCount: Int,
        outputPath: String,
        token: Demo5NativeRunToken,
    ): Int {
        check(loadResult.isSuccess) { "RTKLIB JNI를 불러오지 못했습니다." }
        return token.withHandle { handle, generation ->
            nativeRun(inputPaths, baseObservationCount, outputPath, handle, generation)
        }
    }

    fun observationSummary(inputPath: String): IntArray? {
        check(loadResult.isSuccess) { "RTKLIB JNI를 불러오지 못했습니다." }
        return nativeObservationSummary(inputPath)
    }

    private external fun nativeVersion(): String
    private external fun nativeCreateRunToken(generation: Long): Long
    private external fun nativeCancelRunToken(handle: Long, generation: Long): Boolean
    private external fun nativeDestroyRunToken(handle: Long, generation: Long): Boolean
    private external fun nativeObservationSummary(inputPath: String): IntArray?
    private external fun nativeRun(
        inputPaths: Array<String>,
        baseObservationCount: Int,
        outputPath: String,
        tokenHandle: Long,
        generation: Long,
    ): Int

    fun cancelRunToken(handle: Long, generation: Long): Boolean =
        nativeCancelRunToken(handle, generation)

    fun destroyRunToken(handle: Long, generation: Long): Boolean =
        nativeDestroyRunToken(handle, generation)
}

internal class Demo5NativeRunToken(
    private var handle: Long,
    val generation: Long,
) : AutoCloseable {
    private val guard = Any()

    fun cancel() = synchronized(guard) {
        if (handle != 0L) Demo5NativeBridge.cancelRunToken(handle, generation)
    }

    fun <T> withHandle(block: (Long, Long) -> T): T {
        val current = synchronized(guard) {
            check(handle != 0L) { "RTKLIB 취소 토큰이 닫혔습니다." }
            handle
        }
        return block(current, generation)
    }

    override fun close() = synchronized(guard) {
        if (handle != 0L) {
            check(Demo5NativeBridge.destroyRunToken(handle, generation)) {
                "실행 중인 RTKLIB 취소 토큰을 폐기할 수 없습니다."
            }
            handle = 0L
        }
    }
}
