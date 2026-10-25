package kr.co.hanium.dreamup.walksafe.positioneval

import android.net.Uri
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.createTempDirectory
import kr.co.hanium.dreamup.walksafe.positioneval.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AnalysisPipelineTest {
    @Test
    fun converterReceivesRunCancellationAndPipelineStopsBeforeBaseDownload() = fixture { f ->
        val token = AnalysisCancellation()
        var baseCalls = 0
        val converter = object : GnssLoggerRinexConverter {
            override val status = PinnedAndroidRinexConverter().status
            override fun convert(rawGnssLogger: File, outputDirectory: File, cancellation: AnalysisCancellation): ConversionOutput {
                assertSame(token, cancellation)
                val output = File(outputDirectory, "rover.obs")
                GnssLoggerRinex3Converter.convert(
                    rawGnssLogger, output, java.time.LocalDateTime.of(2026, 10, 10, 0, 0), cancellation,
                ) { source ->
                    object : java.io.BufferedReader(source.reader()) {
                        override fun readLine(): String? = super.readLine().also {
                            if (it?.startsWith("Raw,") == true) cancellation.cancel()
                        }
                    }
                }
                return ConversionOutput(output, null, "fixture", "fixture")
            }
        }
        val before = f.inputs.gnss.file.readBytes()
        val result = f.pipeline(converter = converter, base = AnalysisBaseProvider { inputs, directory, cancellation ->
            baseCalls++
            f.prepareBase(inputs, directory, cancellation)
        }).run(f.inputs, PpkRunContext(cancellation = token)) {}
        assertNull(result.completion)
        assertFalse(result.cleanupFailed)
        assertEquals(0, baseCalls)
        assertTrue(f.workspaces.none(File::exists))
        assertArrayEquals(before, f.inputs.gnss.file.readBytes())
    }

    @Test
    fun traceReplacementAfterPartialBaseDeletionRetainsOnlyExistingCopiesAndDisablesManualAnalysis() = fixture { f ->
        traceReplacementFailure(f, failBaseIndex = 1, failTraceDeletion = false)
    }

    @Test
    fun traceDeletionFailureAfterBaseCleanupClearsManualReadinessAndSummary() = fixture { f ->
        traceReplacementFailure(f, failBaseIndex = null, failTraceDeletion = true)
    }

    @Test
    fun failureBeforeAnyBaseDeletionKeepsValidatedOldInputsReady() = fixture { f ->
        traceReplacementFailure(f, failBaseIndex = 0, failTraceDeletion = false)
    }

    @Test
    fun manualBaseReselectionFailureKeepsLeftoversOwnedUntilSuccessfulRetry() = fixture { f ->
        var engineRuns = 0
        val pipeline = f.pipeline(engine = f.engine { request, _ -> engineRuns++; f.writeSolution(request) })
        withCoordinator(f, pipeline) { coordinator, worker, state ->
            val originals = f.baseFiles(File(f.root, "original-base"))
            val hashes = originals.map { f.artifact(it).sha256 }
            val first = originals[0].copyTo(File(f.root, "owned-observation"))
            val remaining = ControlledDeleteFile(originals[1].copyTo(File(f.root, "owned-navigation")))
            val copies = listOf(first, remaining).mapIndexed { index, file ->
                f.artifact(file).copy(displayName = originals[index].name)
            }
            coordinator.importBaseDocuments(copies.size, copies.map { artifact -> { _: Long, _: AnalysisCancellation -> artifact } })
            worker.submit {}.get(3, TimeUnit.SECONDS)
            assertTrue(state.get().canAnalyzeManual)
            var sourceReads = 0
            val sources = originals.map { original ->
                { _: Long, _: AnalysisCancellation ->
                    sourceReads++
                    val copy = original.copyTo(File(f.root, "replacement-$sourceReads-${original.name}"))
                    f.artifact(copy).copy(displayName = original.name)
                }
            }

            coordinator.importBaseDocuments(sources.size, sources)
            worker.submit {}.get(3, TimeUnit.SECONDS)
            assertFalse(first.exists())
            assertTrue(remaining.isFile)
            assertFalse(state.get().manualBaseReady)
            assertFalse(state.get().busy)
            assertEquals(0, sourceReads)
            val attemptsBeforeRetry = remaining.deleteAttempts

            coordinator.importBaseDocuments(sources.size, sources)
            worker.submit {}.get(3, TimeUnit.SECONDS)
            assertTrue("The next selection must retry deleting the retained copy", remaining.deleteAttempts > attemptsBeforeRetry)
            assertEquals(0, sourceReads)
            assertTrue(remaining.isFile)
            assertFalse(state.get().manualBaseReady)
            assertTrue(requireNotNull(state.get().manualBaseSummary).contains("다시 선택"))
            coordinator.startAnalysis(useManualBase = true)
            worker.submit {}.get(3, TimeUnit.SECONDS)
            assertEquals(0, engineRuns)

            remaining.allowDelete = true
            coordinator.importBaseDocuments(sources.size, sources)
            worker.submit {}.get(3, TimeUnit.SECONDS)
            assertFalse(remaining.exists())
            assertEquals(2, sourceReads)
            assertTrue(state.get().canAnalyzeManual)
            coordinator.startAnalysis(useManualBase = true)
            worker.submit {}.get(3, TimeUnit.SECONDS)
            assertEquals(1, engineRuns)
            assertTrue(state.get().hasResult)
            assertEquals(hashes, originals.map { f.artifact(it).sha256 })
        }
    }

    @Test
    fun queuedBaseReselectionCancellationRetainsCleanupFailuresForTheNextSelection() = fixture { f ->
        withCoordinator(f, f.pipeline()) { coordinator, worker, state ->
            val originals = f.baseFiles(File(f.root, "original-base"))
            val first = originals[0].copyTo(File(f.root, "owned-observation"))
            val remaining = ControlledDeleteFile(originals[1].copyTo(File(f.root, "owned-navigation")))
            val copies = listOf(first, remaining).mapIndexed { index, file ->
                f.artifact(file).copy(displayName = originals[index].name)
            }
            coordinator.importBaseDocuments(copies.size, copies.map { artifact -> { _: Long, _: AnalysisCancellation -> artifact } })
            worker.submit {}.get(3, TimeUnit.SECONDS)
            assertTrue(state.get().canAnalyzeManual)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            try {
                worker.execute { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                coordinator.importBaseDocuments(1, listOf({ _, _ -> error("Cancelled queued import must not run") }))
                coordinator.cancelAnalysis()
            } finally { release.countDown() }
            worker.submit {}.get(3, TimeUnit.SECONDS)
            assertFalse(first.exists())
            assertTrue(remaining.isFile)
            assertFalse(state.get().manualBaseReady)
            assertFalse(state.get().cancelling)
            assertTrue(requireNotNull(state.get().manualBaseSummary).contains("다시 선택"))

            remaining.allowDelete = true
            val sources = originals.map { original ->
                { _: Long, _: AnalysisCancellation ->
                    f.artifact(original.copyTo(File(f.root, "replacement-${original.name}"))).copy(displayName = original.name)
                }
            }
            coordinator.importBaseDocuments(sources.size, sources)
            worker.submit {}.get(3, TimeUnit.SECONDS)
            assertFalse(remaining.exists())
            assertTrue(state.get().canAnalyzeManual)
        }
    }

    @Test
    fun invalidNewBaseCopyWhoseCleanupFailsRemainsOwnedAndBlocksReplacementUntilDeleted() = fixture { f ->
        withCoordinator(f, f.pipeline()) { coordinator, worker, state ->
            val originals = f.baseFiles(File(f.root, "original-base"))
            val originalHashes = originals.map { f.artifact(it).sha256 }
            val incomplete = ControlledDeleteFile(originals[0].copyTo(File(f.root, "incomplete-observation")))
            val artifact = f.artifact(incomplete).copy(displayName = originals[0].name)
            coordinator.importBaseDocuments(1, listOf({ _, _ -> artifact }))
            worker.submit {}.get(3, TimeUnit.SECONDS)
            assertTrue(incomplete.isFile)
            assertFalse(state.get().manualBaseReady)
            assertTrue(requireNotNull(state.get().manualBaseSummary).contains("다시 선택"))
            val attemptsBeforeRetry = incomplete.deleteAttempts
            var sourceReads = 0
            val sources = originals.map { original ->
                { _: Long, _: AnalysisCancellation ->
                    sourceReads++
                    f.artifact(original.copyTo(File(f.root, "replacement-${original.name}"))).copy(displayName = original.name)
                }
            }
            coordinator.importBaseDocuments(sources.size, sources)
            worker.submit {}.get(3, TimeUnit.SECONDS)
            assertTrue(incomplete.deleteAttempts > attemptsBeforeRetry)
            assertEquals(0, sourceReads)
            assertFalse(state.get().manualBaseReady)
            incomplete.allowDelete = true
            coordinator.importBaseDocuments(sources.size, sources)
            worker.submit {}.get(3, TimeUnit.SECONDS)
            assertFalse(incomplete.exists())
            assertEquals(2, sourceReads)
            assertTrue(state.get().canAnalyzeManual)
            assertEquals(originalHashes, originals.map { f.artifact(it).sha256 })
        }
    }

    @Test
    fun injectedPipelinePreservesQ1MetricsRedactionAndInputCopies() = fixture { f ->
        val cancellation = AnalysisCancellation()
        val context = PpkRunContext(cancellation = cancellation)
        var received: PpkRunContext? = null
        val inputBytes = f.inputs.trace.file.readBytes()
        val result = f.pipeline(engine = f.engine { request, run ->
            received = run
            f.writeSolution(request)
        }).run(f.inputs, context) {}

        assertSame(context, received)
        val completion = requireNotNull(result.completion)
        val json = JSONObject(completion.json)
        assertEquals("evaluated", json.getString("status"))
        assertEquals("2026-09-09T00:00:00Z", json.getString("generated_at_utc"))
        assertEquals(31, json.getJSONObject("truth").getInt("fixed_epoch_count_q1"))
        val channels = json.getJSONArray("channels")
        assertEquals(3, channels.length())
        repeat(channels.length()) {
            val channel = channels.getJSONObject(it)
            assertEquals(0.0, channel.getDouble("p95_error_m"), 0.0)
            assertEquals(0.0, channel.getDouble("rmse_error_m"), 0.0)
            assertEquals(1.0, channel.getDouble("two_meter_coverage"), 0.0)
        }
        assertEquals("omitted", json.getJSONObject("inputs").getJSONObject("walksafe_trace").getString("display_name"))
        assertFalse(completion.json.contains(f.root.absolutePath))
        assertFalse(completion.json.contains("private-trace"))
        assertArrayEquals(inputBytes, f.inputs.trace.file.readBytes())
        assertFalse(f.workspaces.single().exists())
        assertFalse(result.cleanupFailed)
    }

    @Test
    fun conversionFailureAndUnavailableEngineRetainPublicCodes() = fixture { f ->
        val converter = object : GnssLoggerRinexConverter {
            override val status = ComponentStatus(true, "AVAILABLE", "")
            override fun convert(rawGnssLogger: File, outputDirectory: File, cancellation: AnalysisCancellation): ConversionOutput =
                throw GnssLoggerConversionException("INSUFFICIENT_VALID_MEASUREMENTS", "private detail")
        }
        val failure = f.pipeline(converter = converter).run(f.inputs, PpkRunContext()) {}
        assertFailure(failure, "truth_insufficient", "INSUFFICIENT_VALID_MEASUREMENTS")
        assertFalse(requireNotNull(failure.completion).json.contains("private detail"))
        val unavailable = f.engine(status = ComponentStatus(false, "ENGINE_NOT_AVAILABLE", "private native path")) {
            _, _ -> fail("Unavailable JNI must not run")
        }
        assertFailure(f.pipeline(engine = unavailable).run(f.inputs, PpkRunContext()) {}, "failed", "ENGINE_NOT_AVAILABLE")
        assertTrue(f.workspaces.none(File::exists))
    }

    @Test
    fun baseAbsenceAndMalformedOutputRemainDistinctFailures() = fixture { f ->
        val noBase = AnalysisBaseProvider { _, _, _ -> throw NgiiException("BASE_DATA_UNAVAILABLE", "private URL") }
        assertFailure(f.pipeline(base = noBase).run(f.inputs, PpkRunContext()) {}, "truth_insufficient", "BASE_DATA_UNAVAILABLE")
        val malformed = f.engine { request, _ ->
            requireNotNull(request.outputPosition.parentFile).mkdirs()
            // Native output validation accepts the shape; the evaluator still rejects invalid satellite counts.
            request.outputPosition.writeText(
                "% UTC latitude(deg) longitude(deg) height(m) Q ns\n" +
                    "2026/09/08 09:00:00.000 0 0 0 1 999\n",
            )
        }
        assertFailure(f.pipeline(engine = malformed).run(f.inputs, PpkRunContext()) {}, "failed", "PPK_OUTPUT_INVALID")
    }

    @Test
    fun realManualProviderUsesValidatedFilesWithoutDownloadAndPreservesOriginals() = fixture { f ->
        val manual = f.baseFiles(File(f.root, "manual"))
        val sources = manual.map(f::artifact)
        val inputs = f.inputs.copy(manualBase = sources)
        val neverDownload = AnalysisBaseProvider { _, _, _ -> error("Manual analysis must stay offline") }
        val result = f.pipeline(base = neverDownload).run(inputs, PpkRunContext()) {}
        val json = JSONObject(requireNotNull(result.completion).json)
        assertEquals("evaluated", json.getString("status"))
        assertEquals("USER_SELECTED_RINEX_HEADER_VALIDATED", json.getJSONObject("reference_provenance").getString("base_input_mode"))
        sources.forEach { assertEquals(it.sha256, f.artifact(it.file).sha256) }
    }

    @Test
    fun cancelledProviderAndLateEngineOutputNeverProduceCompletion() = fixture { f ->
        val downloadToken = AnalysisCancellation()
        val cancelledDownload = AnalysisBaseProvider { inputs, directory, token ->
            assertSame(downloadToken, token)
            token.cancel()
            f.prepareBase(inputs, directory, token)
        }
        val download = f.pipeline(base = cancelledDownload).run(f.inputs, PpkRunContext(cancellation = downloadToken)) {}
        assertNull(download.completion)
        val nativeToken = AnalysisCancellation()
        val lateEngine = f.engine { request, context ->
            assertSame(nativeToken, context.cancellation)
            context.cancellation.cancel()
            f.writeSolution(request)
        }
        val native = f.pipeline(engine = lateEngine).run(f.inputs, PpkRunContext(cancellation = nativeToken)) {}
        assertNull(native.completion)
        assertTrue(f.workspaces.none(File::exists))
    }

    @Test
    fun cancellationDuringEvaluationOrCleanupDiscardsFinalJson() = fixture { f ->
        val token = AnalysisCancellation()
        val pipeline = f.pipeline(clock = { token.cancel(); Instant.parse("2026-09-09T00:00:00Z") })
        assertNull(pipeline.run(f.inputs, PpkRunContext(cancellation = token)) {}.completion)
        val cleanupToken = AnalysisCancellation()
        val cleanup = f.pipeline(onCleanup = { cleanupToken.cancel() })
        assertNull(cleanup.run(f.inputs, PpkRunContext(cancellation = cleanupToken)) {}.completion)
    }

    @Test
    fun cleanupFailureIsReportedSeparatelyFromEvaluationResult() = fixture { f ->
        val result = f.pipeline(onCleanup = { error("synthetic cleanup failure") }).run(f.inputs, PpkRunContext()) {}
        assertEquals("evaluated", JSONObject(requireNotNull(result.completion).json).getString("status"))
        assertTrue(result.cleanupFailed)
    }

    @Test
    fun realCoordinatorSerializesCancelledDownloadBeforeImmediateReplacement() = replacementRace(inDownload = true)

    @Test
    fun realCoordinatorSerializesLateEngineResultBeforeImmediateReplacement() = replacementRace(inDownload = false)

    @Test
    fun realCoordinatorPublishesCleanupWarningAndKeyFailureState() = fixture { f ->
        withCoordinator(f, f.pipeline(onCleanup = { error("synthetic cleanup failure") })) { coordinator, worker, state ->
            coordinator.startAnalysis()
            worker.submit {}.get(3, TimeUnit.SECONDS)
            assertTrue(state.get().hasResult)
            assertFalse(state.get().busy)
            assertTrue(state.get().status.contains("임시 작업 파일을 완전히 삭제하지 못했습니다"))
        }
        val missingKey = AnalysisBaseProvider { _, _, _ -> throw PpkEngineException("KEY_NOT_AVAILABLE", "private key failure") }
        withCoordinator(f, f.pipeline(base = missingKey)) { coordinator, worker, state ->
            coordinator.startAnalysis()
            worker.submit {}.get(3, TimeUnit.SECONDS)
            assertFalse(state.get().keyStored)
            assertTrue(state.get().hasResult)
            assertTrue(state.get().status.contains("KEY_NOT_AVAILABLE"))
        }
    }

    @Test
    fun observerReattachmentKeepsRunningJobAndCompletion() = fixture { f ->
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val engine = f.engine { request, _ ->
            started.countDown()
            assertTrue(release.await(3, TimeUnit.SECONDS))
            f.writeSolution(request)
        }
        try {
            withCoordinator(f, f.pipeline(engine = engine)) { coordinator, worker, _ ->
                val first: (AnalysisSnapshot) -> Unit = {}
                coordinator.attach(first)
                coordinator.startAnalysis()
                assertTrue(started.await(3, TimeUnit.SECONDS))
                coordinator.detach(first)
                val next = AtomicReference<AnalysisSnapshot>()
                val listener: (AnalysisSnapshot) -> Unit = { next.set(it) }
                coordinator.attach(listener)
                assertTrue(next.get().analysisRunning)
                release.countDown()
                worker.submit {}.get(3, TimeUnit.SECONDS)
                assertTrue(next.get().hasResult)
                coordinator.detach(listener)
            }
        } finally { release.countDown() }
    }

    private fun replacementRace(inDownload: Boolean) = fixture { f ->
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<String>())
        var calls = 0
        fun blockFirst() {
            calls++
            if (calls == 1) {
                started.countDown()
                var waiting = true
                while (waiting) {
                    try { check(release.await(3, TimeUnit.SECONDS)); waiting = false }
                    catch (_: InterruptedException) { /* Model a provider/JNI result arriving after cancellation. */ }
                }
                events += "old-return"
            } else events += "new-run"
        }
        val base = AnalysisBaseProvider { inputs, directory, cancellation ->
            if (inDownload) blockFirst()
            f.prepareBase(inputs, directory, cancellation)
        }
        val engine = f.engine { request, _ ->
            if (!inDownload) blockFirst()
            f.writeSolution(request)
        }
        try {
            withCoordinator(f, f.pipeline(base = base, engine = engine, onCleanup = { events += "cleanup" })) { coordinator, worker, state ->
                coordinator.startAnalysis()
                assertTrue(started.await(3, TimeUnit.SECONDS))
                coordinator.cancelAnalysis()
                assertFalse(state.get().hasResult)
                coordinator.startAnalysis()
                assertTrue(state.get().busy)
                release.countDown()
                worker.submit {}.get(3, TimeUnit.SECONDS)
                assertTrue(state.get().hasResult)
                assertFalse(state.get().cancelling)
                assertTrue(state.get().status.startsWith("6/6 분석 완료"))
                assertEquals(listOf("old-return", "cleanup", "new-run", "cleanup"), events.toList())
            }
        } finally { release.countDown() }
    }

    private fun withCoordinator(
        f: PipelineFixture,
        pipeline: AnalysisPipeline,
        trace: ImportedArtifact = f.inputs.trace,
        block: (AnalysisCoordinator, java.util.concurrent.ExecutorService, AtomicReference<AnalysisSnapshot>) -> Unit,
    ) {
        val worker = Executors.newSingleThreadExecutor()
        val storage = object : AnalysisStorage {
            override val root = File(f.root, "store")
            override fun isKeyStored() = true
            override fun saveKey(value: CharArray) { value.fill('\u0000') }
            override fun clearKey() = Unit
            override fun import(uri: Uri, maxBytes: Long, cancellation: AnalysisCancellation?): ImportedArtifact = error("No SAF in JVM fixture")
            override fun export(uri: Uri, json: String) = error("No SAF in JVM fixture")
        }
        val coordinator = AnalysisCoordinator(storage, pipeline, worker)
        val state = AtomicReference<AnalysisSnapshot>()
        val listener: (AnalysisSnapshot) -> Unit = { state.set(it) }
        try {
            coordinator.initialize()
            worker.submit {}.get(3, TimeUnit.SECONDS)
            coordinator.attach(listener)
            coordinator.importDocument(ArtifactKind.TRACE) { trace }
            worker.submit {}.get(3, TimeUnit.SECONDS)
            coordinator.importDocument(ArtifactKind.GNSS) { f.inputs.gnss }
            worker.submit {}.get(3, TimeUnit.SECONDS)
            assertTrue(state.get().canAnalyze)
            block(coordinator, worker, state)
        } finally {
            coordinator.detach(listener)
            coordinator.cancelAnalysis()
            worker.shutdownNow()
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private fun traceReplacementFailure(f: PipelineFixture, failBaseIndex: Int?, failTraceDeletion: Boolean) {
        val oldTrace = if (failTraceDeletion) {
            f.inputs.trace.copy(file = RefusingDeleteFile(f.inputs.trace.file))
        } else f.inputs.trace
        var engineRuns = 0
        val pipeline = f.pipeline(engine = f.engine { request, _ -> engineRuns++; f.writeSolution(request) })
        withCoordinator(f, pipeline, trace = oldTrace) { coordinator, worker, state ->
            val originals = f.baseFiles(File(f.root, "original-base"))
            val hashes = originals.map { f.artifact(it).sha256 }
            val copies = originals.mapIndexed { index, original ->
                val copy = original.copyTo(File(f.root, "owned-${original.name}"))
                f.artifact(if (index == failBaseIndex) RefusingDeleteFile(copy) else copy).copy(displayName = original.name)
            }
            coordinator.importBaseDocuments(copies.size, copies.map { artifact -> { _: Long, _: AnalysisCancellation -> artifact } })
            worker.submit {}.get(3, TimeUnit.SECONDS)
            assertTrue(state.get().canAnalyzeManual)
            val oldSummary = state.get().traceSummary
            val newTrace = f.inputs.trace.file.copyTo(File(f.root, "replacement-trace.jsonl"))
            coordinator.importDocument(ArtifactKind.TRACE) { f.artifact(newTrace) }
            worker.submit {}.get(3, TimeUnit.SECONDS)

            assertFalse(state.get().busy)
            assertTrue(state.get().inputsReady)
            assertEquals(oldSummary, state.get().traceSummary)
            assertTrue(oldTrace.file.isFile)
            assertFalse(newTrace.exists())
            assertTrue(state.get().status.startsWith("입력 확인 필요"))
            val untouched = failBaseIndex == 0
            assertEquals(untouched, state.get().manualBaseReady)
            assertEquals(untouched, state.get().canAnalyzeManual)
            if (failTraceDeletion) {
                assertTrue(copies.none { it.file.exists() })
                assertNull(state.get().manualBaseSummary)
            } else if (!untouched) {
                assertFalse(copies[0].file.exists())
                assertTrue(copies[1].file.isFile)
                assertTrue(requireNotNull(state.get().manualBaseSummary).contains("다시 선택"))
                val retry = f.inputs.trace.file.copyTo(File(f.root, "replacement-retry.jsonl"))
                coordinator.importDocument(ArtifactKind.TRACE) { f.artifact(retry) }
                worker.submit {}.get(3, TimeUnit.SECONDS)
                assertEquals(oldSummary, state.get().traceSummary)
                assertFalse(retry.exists())
                assertTrue(copies[1].file.isFile)
                assertFalse(state.get().manualBaseReady)
            }
            coordinator.startAnalysis(useManualBase = true)
            worker.submit {}.get(3, TimeUnit.SECONDS)
            assertEquals(if (untouched) 1 else 0, engineRuns)
            assertEquals(untouched, state.get().hasResult)
            assertEquals(hashes, originals.map { f.artifact(it).sha256 })
        }
    }

    private class RefusingDeleteFile(file: File) : File(file.path) {
        override fun delete(): Boolean = false
    }

    private class ControlledDeleteFile(file: File) : File(file.path) {
        var allowDelete = false
        var deleteAttempts = 0
            private set
        override fun delete(): Boolean {
            deleteAttempts++
            return allowDelete && super.delete()
        }
    }

    private fun assertFailure(result: AnalysisPipelineResult, status: String, reason: String) {
        val json = JSONObject(requireNotNull(result.completion).json)
        assertEquals(status, json.getString("status"))
        assertEquals(reason, json.getJSONArray("reason_codes").getString(0))
    }

    private fun fixture(block: (PipelineFixture) -> Unit) {
        val f = PipelineFixture()
        try { block(f) } finally { f.root.deleteRecursively() }
    }
}

private class PipelineFixture {
    val root = createTempDirectory("analysis-pipeline-test-").toFile()
    val workspaces = mutableListOf<File>()
    private val start = Instant.parse("2026-09-08T09:00:00Z").toEpochMilli()
    private val point = GeoPoint(0.0, 0.0)
    val inputs: AnalysisInputs

    init {
        val lines = File("../../../tests/fixtures/positioning_trace_contract_v2.jsonl").readLines()
        val records = (0..30).map { second ->
            JSONObject(lines[1]).apply {
                put("seq", second + 1)
                put("elapsed_realtime_ns", (second + 1) * 1_000_000_000L)
                put("measurement_elapsed_realtime_ns", (second + 1) * 1_000_000_000L)
                put("measurement_utc_epoch_ms", start + second * 1_000L)
                listOf("raw_position", "filtered_position", "matched_position").forEach {
                    put(it, JSONObject().put("latitude_deg", 0.0).put("longitude_deg", 0.0))
                }
            }.toString()
        }
        val content = (listOf(lines[0]) + records).joinToString("\n", postfix = "\n")
        val footer = JSONObject().put("record_type", "footer").put("schema_version", TraceV2Parser.SCHEMA)
            .put("record_count", records.size).put("content_sha256", sha(content.toByteArray()))
        val trace = File(root, "private-trace.jsonl").apply { writeText(content + footer + "\n") }
        val gnss = File(root, "private-gnss.txt").apply {
            writeText(requireNotNull(PipelineFixture::class.java.getResource("/fixtures/gnsslogger_minimal.txt")).readText())
        }
        inputs = AnalysisInputs(artifact(trace), TraceV2Parser.parse(trace), artifact(gnss))
    }

    fun pipeline(
        converter: GnssLoggerRinexConverter = object : GnssLoggerRinexConverter {
            override val status = ComponentStatus(true, "AVAILABLE", "fixture")
            override fun convert(rawGnssLogger: File, outputDirectory: File, cancellation: AnalysisCancellation): ConversionOutput {
                outputDirectory.mkdirs()
                val observation = File(outputDirectory, "rover.obs").apply { writeText("fake converter output") }
                return ConversionOutput(observation, null, "fixture", "fixture")
            }
        },
        engine: PpkEngine = engine { request, _ -> writeSolution(request) },
        base: AnalysisBaseProvider = AnalysisBaseProvider(::prepareBase),
        onCleanup: () -> Unit = {},
        clock: () -> Instant = { Instant.parse("2026-09-09T00:00:00Z") },
    ) = AnalysisPipeline(converter, engine, base, workspaceFactory = {
        val workDirectory = File(root, "work-${workspaces.size}")
        workspaces += workDirectory
        object : AnalysisWorkspace {
            override val directory = workDirectory
            override fun cleanup() {
                DirectoryAnalysisWorkspace(directory).cleanup()
                onCleanup()
            }
        }
    }, clock = clock)

    fun engine(
        status: ComponentStatus = ComponentStatus(true, "AVAILABLE", "fixture"),
        action: (PpkRequest, PpkRunContext) -> Unit,
    ): PpkEngine = object : PpkEngine {
        override val status = status
        override val name = "fixture"
        override val sourceCommit = "fixture"
        override fun run(request: PpkRequest, context: PpkRunContext) = action(request, context)
    }

    fun prepareBase(inputs: AnalysisInputs, directory: File, cancellation: AnalysisCancellation): DownloadedBaseData {
        cancellation.throwIfCancelled()
        val entries = baseFiles(directory).map { file ->
            val artifact = artifact(file)
            ExtractedEntry(file.name, file, artifact.byteCount, artifact.sha256)
        }
        return DownloadedBaseData(BaseStation("KUNW", "fixture", inputs.traceSession.center, true), emptyList(), emptyList(), entries)
    }

    fun baseFiles(directory: File): List<File> {
        directory.mkdirs()
        val observation = buildString {
            append(header("     2.11           OBSERVATION DATA    G", "RINEX VERSION / TYPE"))
            append(header("KUNW", "MARKER NAME"))
            append(header("  6378137.0 0.0 0.0", "APPROX POSITION XYZ"))
            append(header("     1    C1", "# / TYPES OF OBSERV"))
            append(header("  2026     9     8     9     0   18.0000000     GPS", "TIME OF FIRST OBS"))
            append(header("", "END OF HEADER"))
            for (second in listOf(18, 48, 78)) {
                append(String.format(Locale.ROOT, " 26  9  8  9 %2d %10.7f  0  1G01\n", second / 60, (second % 60).toDouble()))
                append("  12345678.000\n")
            }
        }
        val navigation = header("     2.11           NAVIGATION DATA     G", "RINEX VERSION / TYPE") +
            header("", "END OF HEADER") + " 1 26  9 8  9  0 18.0 0.0D+00 0.0D+00 0.0D+00\n" +
            "    0.0D+00 0.0D+00 0.0D+00 0.0D+00\n".repeat(7)
        return listOf(
            File(directory, "KUNW251j.26o").apply { writeText(observation) },
            File(directory, "KUNW251j.26n").apply { writeText(navigation) },
        )
    }

    fun writeSolution(request: PpkRequest) {
        requireNotNull(request.outputPosition.parentFile).mkdirs()
        val formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss.SSS", Locale.ROOT).withZone(ZoneOffset.UTC)
        request.outputPosition.writeText("% UTC latitude(deg) longitude(deg) height(m) Q ns sdn(m) sde(m)\n" +
            (0..30).joinToString("\n", postfix = "\n") {
                "${formatter.format(Instant.ofEpochMilli(start + it * 1_000L))} ${point.latitudeDeg} ${point.longitudeDeg} 0 1 15 0.1 0.1"
            })
    }

    fun artifact(file: File) = ImportedArtifact(file.name, file, file.length(), sha(file.readBytes()))
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun header(content: String, label: String) = content.padEnd(60) + label + "\n"
}
