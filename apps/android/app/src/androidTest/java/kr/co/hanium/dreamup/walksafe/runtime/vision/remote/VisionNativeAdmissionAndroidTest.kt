package kr.co.hanium.dreamup.walksafe.runtime.vision.remote

import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kr.co.hanium.dreamup.walksafe.BuildConfig
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.inference.TwoModelRuntimeConfig
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Explicit memory-I/O diagnostic injection. Binder ordering is real; GPU/disk durability is not claimed. */
@RunWith(AndroidJUnit4::class)
class VisionNativeAdmissionAndroidTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var client: RemoteVisionClient
    private lateinit var authority: AndroidDeviceCheckAuthority
    private lateinit var owner: DeviceCheckOwnerRef
    private val persistence = ControlledPersistence()
    private var lease: VisionCohortLease? = null
    private val gates = mutableListOf<CountDownLatch>()
    private class ControlledPersistence : DeviceCheckAuthorityPersistence {
        @Volatile var text: String? = null
        @Volatile var gate: ((String) -> Unit)? = null
        override fun read() = text
        override fun publish(canonicalState: String): DeviceCheckPublication {
            gate?.invoke(canonicalState); text = canonicalState
            return DeviceCheckPublication.COMMITTED
        }
    }
    @Before fun setup() {
        assumeTrue(BuildConfig.DEBUG && Build.VERSION.SDK_INT >= 31)
        assumeTrue(InstrumentationRegistry.getArguments().getString("visionRemoteFaultTests") == "true")
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish") || Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"))
        val core = DeviceCheckAuthorityCore(persistence, AndroidVisionProcessObserver(app), SystemClock::elapsedRealtime,
            { Runtime.getRuntime().availableProcessors() }, true)
        authority = AndroidDeviceCheckAuthority(core, diagnostic = true)
        val binding = DeviceCheckAuthorityBinding("1".repeat(64), "2".repeat(64), "3".repeat(64), "4".repeat(64))
        val started = SystemClock.elapsedRealtime()
        val attempt = (authority.beginAttempt(binding, UUID.randomUUID().toString(), started, started + 60_000)
            .toCompletableFuture().get(5, TimeUnit.SECONDS) as DeviceCheckAuthorityOutcome.Committed<DeviceCheckAttemptRef>).value
        owner = requireNotNull(authority.registerOwner("admission-test", attempt, 1))
        client = RemoteVisionClient.forMainProcess(app)
        lease = client.acquireForDiagnostics(owner, deadline(), authority)
        client.injectDebugFault(requireNotNull(lease), "FAKE_ENGINE", deadline())
    }
    @After fun cleanup() {
        gates.forEach(CountDownLatch::countDown); persistence.gate = null
        lease?.let { client.requestExit(it, "admission_test_cleanup")
            client.actualReleasedFuture(it.cohortId).toCompletableFuture().get(10, TimeUnit.SECONDS)
            client.whenAvailable(it.cohortId).toCompletableFuture().get(10, TimeUnit.SECONDS) }
    }
    private fun deadline(ms: Long = 5_000) = SystemClock.elapsedRealtime() + ms
    private fun spec(): VisionModelSpec {
        val model = requireNotNull(TwoModelRuntimeConfig.load(app).unifiedWalksafe)
        return VisionModelSpec(VisionRole.PRIMARY, TwoModelRuntimeConfig.UNIFIED_MODEL_KEY, model.asset,
            requireNotNull(model.artifactSha256), VisionRuntimeCodec.encodeOptions(VisionRuntimeOptions("cpu", 1)),
            intArrayOf(1, model.inputSize, model.inputSize, 3), arrayOf(model.outputTensorShape()))
    }
    private fun blockPublication(opened: Boolean): Pair<CountDownLatch, CountDownLatch> {
        val entered = CountDownLatch(1); val resume = CountDownLatch(1).also(gates::add)
        persistence.gate = { text ->
            val cohorts = JSONObject(text).getJSONArray("cohorts")
            if (cohorts.length() > 0) {
                val cohort = cohorts.getJSONObject(cohorts.length() - 1)
                val loads = cohort.getJSONArray("loads")
                if (cohort.isNull("release") && loads.length() > 0 && !loads.getJSONObject(0).isNull("modelId") == opened) {
                    entered.countDown(); check(resume.await(10, TimeUnit.SECONDS))
                }
            }
        }
        return entered to resume
    }
    private fun beginOpen(): CompletableFuture<VisionModelHandle> = CompletableFuture<VisionModelHandle>().also { future ->
        Thread { try { future.complete(client.open(requireNotNull(lease), spec(), deadline(8_000))) }
            catch (error: Throwable) { future.completeExceptionally(error) } }.start()
    }
    @Test fun loadIntentAcknowledgementMustPrecedeAnyNativeOpenDispatch() {
        val (entered, resume) = blockPublication(opened = false)
        val result = beginOpen()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            client.injectDebugFault(requireNotNull(lease), "ASSERT_NO_MODELS", deadline())
            assertFalse(result.isDone)
        } finally { resume.countDown() }
        assertTrue(result.get(5, TimeUnit.SECONDS).modelInstanceId.isNotEmpty())
    }
    @Test fun cancellationDuringIntentWriteRevokesTheLatePermitWithoutWaitingForIo() {
        val (entered, resume) = blockPublication(opened = false)
        val result = beginOpen()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val current = requireNotNull(lease)
            client.requestExit(current, "cancel_before_open_permit")
            assertEquals(VisionReleaseProof.NO_NATIVE_START,
                client.actualReleasedFuture(current.cohortId).toCompletableFuture().get(1, TimeUnit.SECONDS).releaseProof)
            assertFalse("Physical release cannot bypass the blocked journal",
                client.whenAvailable(current.cohortId).toCompletableFuture().isDone)
            assertThrows(java.util.concurrent.ExecutionException::class.java) { result.get(1, TimeUnit.SECONDS) }
        } finally { resume.countDown() }
        assertFalse(authority.cachedSnapshot(DeviceCheckAuthorityBinding("1".repeat(64), "2".repeat(64), "3".repeat(64), "4".repeat(64)))
            is DeviceCheckAuthorityRead.Ready)
    }
    @Test fun openedModelAcknowledgementMustPrecedeHandlePublication() {
        val (entered, resume) = blockPublication(opened = true)
        val result = beginOpen()
        try { assertTrue(entered.await(5, TimeUnit.SECONDS)); assertFalse(result.isDone) }
        finally { resume.countDown() }
        val handle = result.get(5, TimeUnit.SECONDS)
        val current = requireNotNull(lease)
        assertNotNull(authority.modelRef(current.cohortId, current.ownerEpoch, handle.modelInstanceId))
        client.validateAttach(current, handle)
    }
}
