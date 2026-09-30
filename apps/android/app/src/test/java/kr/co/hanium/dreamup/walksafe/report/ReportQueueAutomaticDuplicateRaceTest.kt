package kr.co.hanium.dreamup.walksafe.report

import java.io.File
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kr.co.hanium.dreamup.walksafe.security.AeadLimits
import kr.co.hanium.dreamup.walksafe.security.AeadOpenResult
import kr.co.hanium.dreamup.walksafe.security.AeadSealResult
import kr.co.hanium.dreamup.walksafe.security.LocalAead
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * S15-5: the same-place pending automatic check runs inside the final OS file lock, so separate
 * store instances and separate processes cannot both publish a report for one place.
 */
class ReportQueueAutomaticDuplicateRaceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun concurrentStoreInstancesPublishOneAutomaticReportPerPlace() {
        repeat(ROUNDS) { round ->
            val root = temporary.newFolder()
            val barrier = CyclicBarrier(2)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val results = (1L..2L).map { worker ->
                    executor.submit<Boolean> {
                        val store = raceStore(root, UUID(round.toLong(), worker)) { barrier.await(5, TimeUnit.SECONDS) }
                        store.enqueueAutomaticAt(37.0, 127.0) != null
                    }
                }.map { it.get(20, TimeUnit.SECONDS) }
                assertEquals("round $round", 1, results.count { it })
                assertEquals("round $round", 1, reportFiles(root).size)
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun separateProcessesPublishOneAutomaticReportPerPlace() {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val classpath = System.getProperty("java.class.path")
        repeat(PROCESS_ROUNDS) { round ->
            val root = temporary.newFolder()
            val go = File(root, "go")
            val processes = (1..2).map { worker ->
                ProcessBuilder(
                    java, "-cp", classpath, ReportQueueRaceChild::class.java.name,
                    root.path, go.path, "$round", "$worker",
                ).redirectErrorStream(true).start()
            }
            Thread.sleep(300)
            go.writeText("go")
            val outputs = processes.map { process ->
                check(process.waitFor(60, TimeUnit.SECONDS)) { "child timed out" }
                process.inputStream.bufferedReader().readText().trim()
            }
            assertEquals("round $round: $outputs", 1, outputs.count { it.endsWith("stored") })
            assertEquals("round $round: $outputs", 1, outputs.count { it.endsWith("rejected") })
            assertEquals(1, reportFiles(root).size)
        }
    }

    private fun reportFiles(root: File): List<File> =
        File(root, "report_queue_v1/reports").listFiles()?.filter { it.name.endsWith(".aead") }.orEmpty()

    private companion object {
        const val ROUNDS = 25
        const val PROCESS_ROUNDS = 3
    }
}

internal fun raceStore(root: File, id: UUID, beforeClock: () -> Unit = {}): AndroidReportQueueStore =
    AndroidReportQueueStore(
        rootDirectory = root,
        capacityProfile = ApprovedReportQueueCapacityProfile(8, 1_024, 4_096L, 32_768L, 6, 24_576L),
        aead = RaceAead(),
        idFactory = { id },
        nowMillis = {
            beforeClock()
            1_000L
        },
    )

internal fun AndroidReportQueueStore.enqueueAutomaticAt(latitude: Double, longitude: Double): QueuedReport? =
    enqueue(
        expectedReporterActorId = RACE_ACTOR,
        metadataUtf8 = JSONObject()
            .put("schema_version", "detect.v2")
            .put("trigger", AndroidReportCandidatePolicy.TRIGGER_AUTO)
            .put("auto_reported", true)
            .put("reporter_user_id", RACE_ACTOR)
            .put("class_name", AndroidReportCandidatePolicy.DAMAGED_TACTILE_BLOCK)
            .put("gps", JSONObject().put("latitude", latitude).put("longitude", longitude))
            .toString()
            .toByteArray(),
        imageJpeg = reportTestJpeg(),
        priority = ReportQueuePriority.AUTOMATIC,
        walkSessionId = "123e4567-e89b-42d3-a456-426614174001",
        consentReceiptSha256 = "c".repeat(64),
    )

/** Child process for the cross-process race: waits for the shared start file, then enqueues. */
object ReportQueueRaceChild {
    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args[0])
        val go = File(args[1])
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (!go.exists()) {
            check(System.nanoTime() < deadline) { "start signal missing" }
            Thread.onSpinWait()
        }
        val queued = raceStore(root, UUID(args[2].toLong(), args[3].toLong() + 100L))
            .enqueueAutomaticAt(37.0, 127.0)
        println(if (queued != null) "stored" else "rejected")
    }
}

private const val RACE_ACTOR = "walker-1"

private class RaceAead : LocalAead {
    override fun seal(plaintext: ByteArray, domainAad: ByteArray, limits: AeadLimits) =
        AeadSealResult.Sealed(Base64.getEncoder().encodeToString(plaintext))
    override fun open(envelope: String, domainAad: ByteArray, limits: AeadLimits) =
        AeadOpenResult.Opened(Base64.getDecoder().decode(envelope), 1, false)
    override fun destroyVersion(version: Int) = true
    override fun destroyKnownVersions() = true
    override fun createFreshAfterVerifiedPurge() = true
}
