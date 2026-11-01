package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.depth.FrozenImageToTextureCoordinateMapper
import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.content.Intent
import android.graphics.Color
import android.graphics.RectF
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import kr.co.hanium.dreamup.walksafe.depth.ArCoreFrameProvider
import kr.co.hanium.dreamup.walksafe.depth.DepthFrameSnapshot
import kr.co.hanium.dreamup.walksafe.depth.FullDepthTiming
import kr.co.hanium.dreamup.walksafe.depth.ImageSize
import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import kr.co.hanium.dreamup.walksafe.depth.MessagePolicy
import kr.co.hanium.dreamup.walksafe.depth.MessagePolicyConfig
import kr.co.hanium.dreamup.walksafe.depth.MessageRateLimitConfig
import kr.co.hanium.dreamup.walksafe.depth.ObjectDepthRuntimePipeline
import kr.co.hanium.dreamup.walksafe.depth.ObjectTracker
import kr.co.hanium.dreamup.walksafe.depth.Point2
import kr.co.hanium.dreamup.walksafe.inference.AdaptiveInferencePacingPolicy
import kr.co.hanium.dreamup.walksafe.inference.AndroidRuntimeLoadObservationSource
import kr.co.hanium.dreamup.walksafe.inference.RuntimeCandidate
import kr.co.hanium.dreamup.walksafe.inference.composite.AndroidJointRuntimeChoiceStore
import kr.co.hanium.dreamup.walksafe.inference.composite.AndroidJointRuntimeLoader
import kr.co.hanium.dreamup.walksafe.inference.composite.ValidatedJointRuntimeChoice
import kr.co.hanium.dreamup.walksafe.inference.composite.TransferredJointRuntimeAttachment
import kr.co.hanium.dreamup.walksafe.inference.composite.JointPairTransferWitness
import kr.co.hanium.dreamup.walksafe.inference.composite.JointRuntimeAttachmentReservation
import kr.co.hanium.dreamup.walksafe.device.AndroidDeviceCheckAuthority
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionProcessCoordinator
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.*
import kr.co.hanium.dreamup.walksafe.depth.TrackedObjectDepth
import kr.co.hanium.dreamup.walksafe.inference.AndroidDetectorLoadTiming
import kr.co.hanium.dreamup.walksafe.inference.AndroidDetectorLoadPhase
import kr.co.hanium.dreamup.walksafe.depth.UnknownObjectFeedbackPolicy
import kr.co.hanium.dreamup.walksafe.feedback.AndroidFeedbackActuator
import kr.co.hanium.dreamup.walksafe.feedback.NavigationSpeechDispatchResult
import kr.co.hanium.dreamup.walksafe.navigation.FrozenImageToDepthTransform
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import kr.co.hanium.dreamup.walksafe.inference.TfliteAndroidFrameDetector
import kr.co.hanium.dreamup.walksafe.inference.TwoModelRuntimeConfig
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamRuntimeService
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamRuntimeOptions
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamFrameToken
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamResult
import kr.co.hanium.dreamup.walksafe.depth.unknown.FrozenUnknownDepthCapture
import kr.co.hanium.dreamup.walksafe.depth.unknown.UnknownObjectDepthPipeline
import java.util.Locale
import kr.co.hanium.dreamup.walksafe.depth.DepthPredictionPresentation
import java.util.concurrent.Executors
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.lang.ref.WeakReference
import java.util.UUID
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.RemoteVisionClient
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionCohortLease
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionOwnerRetirement
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionRetirementSnapshot
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionRuntimeContext
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionRuntimeFailures
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** A failed close operation cannot replace the model's independent physical release receipt. */
internal fun closeCameraPrimaryRuntime(
    close: () -> Unit,
    actualReleased: () -> CompletionStage<Unit>,
    onSemanticFailure: (Throwable) -> Unit = {},
): CompletableFuture<Unit> {
    try { close() } catch (error: Throwable) { runCatching { onSemanticFailure(error) } }
    val released = CompletableFuture<Unit>()
    try {
        actualReleased().whenComplete { _, error ->
            if (error == null) released.complete(Unit) else released.completeExceptionally(error)
        }
    } catch (error: Throwable) { released.completeExceptionally(error) }
    return released
}

/** The queue barrier covers Images/invoke/output consumers before collecting detached models. */
internal fun drainCameraPrimaryForRetirement(
    enqueue: (() -> Unit) -> Unit,
    closeCurrent: () -> CompletionStage<Unit>,
    retiredReleases: () -> List<CompletionStage<*>>,
): CompletableFuture<Unit> {
    val drained = CompletableFuture<Unit>()
    try { enqueue drain@{
        val releases = try { (listOf(closeCurrent()) + retiredReleases()).distinct() }
        catch (error: Throwable) { drained.completeExceptionally(error); return@drain }
        try {
            CompletableFuture.allOf(*releases.map { it.toCompletableFuture() }.toTypedArray())
                .whenComplete { _, error ->
                    if (error == null) drained.complete(Unit) else drained.completeExceptionally(error)
                }
        } catch (error: Throwable) { drained.completeExceptionally(error) }
    } } catch (error: Throwable) { drained.completeExceptionally(error) }
    return drained
}

/** Serializes a detached model's retirement with primary work on the same camera worker. */
internal fun enqueueCameraPrimaryRetirement(
    enqueue: (() -> Unit) -> Unit,
    physicalReleases: MutableCollection<CompletableFuture<Unit>>,
    retire: () -> CompletionStage<Unit>,
    onUnprovenDrain: (Throwable) -> Unit,
): CompletableFuture<Unit> {
    val released = CompletableFuture<Unit>()
    try { enqueue {
        // Only the worker may register an accepted cleanup. Otherwise a preparation already
        // running on this lane could wait for this task queued behind that same preparation.
        physicalReleases.add(released)
        try {
            retire().whenComplete { _, error ->
                if (error == null) released.complete(Unit) else released.completeExceptionally(error)
            }
        } catch (error: Throwable) {
            released.completeExceptionally(error)
            onUnprovenDrain(error)
        }
    } } catch (error: Throwable) {
        released.completeExceptionally(error)
        // Rejection has no queued dependency: retain an already-terminal unproven drain.
        physicalReleases.add(released)
        onUnprovenDrain(error)
    }
    return released
}

/** Runtime ownership is separate from camera viewport/geometry revisions. */
internal class CameraPrimaryRuntimeAuthority(private val lock: Any) {
    @Volatile private var revision = 0L

    fun beginPreparation(): Long = synchronized(lock) { ++revision }
    fun isCurrent(request: Long): Boolean = revision == request

    fun invalidateIf(isOwned: () -> Boolean, detach: () -> Unit): Boolean = synchronized(lock) {
        if (!isOwned()) false else { revision++; detach(); true }
    }

    fun publish(request: Long, lifecycleCurrent: () -> Boolean, adopt: () -> Unit): Boolean = synchronized(lock) {
        if (!isCurrent(request) || !lifecycleCurrent()) false else { adopt(); true }
    }

    fun clearFailed(request: Long, clear: () -> Unit): Boolean = synchronized(lock) {
        if (!isCurrent(request)) false else { clear(); true }
    }
}

/** Camera owns one participant; joining again would make its own successful cleanup wait forever. */
internal fun retireCameraRuntime(
    ownerId: String,
    participant: CameraRuntimeHandoff.Claim?,
    joinRetirement: () -> CameraRuntimeHandoff.Claim,
    primaryDrained: CompletionStage<Unit>,
    cameraReleased: List<CompletionStage<*>>,
    auxiliaryAfterPrimaryDrain: () -> List<CompletionStage<*>>,
    nativeAfterConsumers: () -> VisionRetirementSnapshot?,
    requestExit: (VisionCohortLease) -> Unit,
    onProblem: (Throwable) -> Unit = {},
): CameraRuntimeHandoff.Claim {
    val claim = participant ?: joinRetirement()
    VisionOwnerRetirement.begin(ownerId, claim, primaryDrained,
        { cameraReleased + auxiliaryAfterPrimaryDrain() }, nativeAfterConsumers, requestExit, onProblem)
    return claim
}

/** A primary reload may resume only after the preceding auxiliary native owner actually closed. */
internal fun deferCameraRuntimeUntilAuxiliaryReleased(
    service: FastSamRuntimeService<*>?,
    dispatch: (() -> Unit) -> Unit,
    isCurrent: () -> Boolean,
    onReleased: () -> Unit,
    onReleaseFailure: () -> Unit,
): Boolean {
    val previous = service ?: return false
    val state = previous.stats()
    if (!state.stopping || state.nativeReleaseConfirmed) return false
    previous.releasedFuture().whenComplete { _, error -> dispatch {
        if (isCurrent()) {
            if (error == null) onReleased() else onReleaseFailure()
        }
    } }
    return true
}

/** Local debug camera and hazard feedback; no account, route, walk lease or report is opened. */
class CameraTestActivity : Activity(), GLSurfaceView.Renderer {
    private lateinit var preview: CameraPreviewLayout
    private lateinit var surface: GLSurfaceView
    private lateinit var boxes: DebugBboxOverlayView
    private lateinit var unknownBoxes: DebugBboxOverlayView
    private lateinit var unknownStatus: TextView
    private lateinit var feedbackStatus: TextView
    private val feedback = CameraTestFeedbackCoordinator()
    private val objectPresentation = CameraObjectPresentation()
    private var primaryOverlayCapture: CameraObjectPresentation.Capture? = null
    private var unknownOverlay: UnknownOverlay? = null
    private data class UnknownOverlay(
        val scene: CameraScenePresentation.UnknownSnapshot,
        val trackingScope: CameraUnknownMaterialPointScope,
        val rawCount: Int,
        val candidateCount: Int,
        val detail: String,
    ) {
        val capture get() = scene.capture
        val predictionDeadlinesMs get() = scene.predictionDeadlinesMs
    }
    private val feedbackHandler = Handler(Looper.getMainLooper())
    private val primaryUiPresentation = CameraUiPresentationCoordinator<List<Int>>(
        { callback, delayMs -> feedbackHandler.postDelayed(callback, delayMs) },
        { callback -> feedbackHandler.removeCallbacks(callback) },
    )
    private val unknownUiPresentation = CameraUiPresentationCoordinator<Pair<Map<Int, Int>, List<Int>>>(
        { callback, delayMs -> feedbackHandler.postDelayed(callback, delayMs) },
        { callback -> feedbackHandler.removeCallbacks(callback) },
    )
    private var feedbackActuator: AndroidFeedbackActuator? = null
    private var speechReadiness = "한국어 음성 준비 중"
    private var lastFeedbackEvent = ""
    private var lastFeedbackLog = ""
    private var lastFeedbackLogMs = 0L
    private var unknownFeedbackPolicy = UnknownObjectFeedbackPolicy()
    private val unknownRuntime = CameraUnknownRuntime<UnknownCapture>(
        create = ::createUnknownService,
        dispatch = { action -> runOnUiThread { action() } },
        changed = ::showUnknownRuntimeStatus,
        requiresTransferredPair = true,
    )
    private val auxiliaryStartup = CameraAuxiliaryStartupCoordinator()
    private var auxiliaryRetryRequested = false
    private var unknownPipeline = UnknownObjectDepthPipeline(SystemClock::elapsedRealtime)
    private var primaryDepth = PrimaryDepthState(0)
    private class PrimaryDepthState(private val epoch: Int) {
        private var pipelineGeneration = 0
        var geometryId: String? = null
        var pipeline = createPipeline()
        var sourceGate = CameraTestDepthSourceGate()
        fun forGeometry(id: String): ObjectDepthRuntimePipeline {
            if (geometryId != id) { reset(); geometryId = id }
            return pipeline
        }
        fun reset() {
            geometryId = null
            pipelineGeneration++
            pipeline = createPipeline()
            sourceGate = CameraTestDepthSourceGate()
        }
        private fun createPipeline() = ObjectDepthRuntimePipeline(
            tracker = ObjectTracker(trackIdPrefix = "camera-$epoch-primary-$pipelineGeneration-"),
            rawDepthMotionOnly = true,
            messagePolicy = MessagePolicy(config = MessagePolicyConfig(
                rateLimit = MessageRateLimitConfig(0L, 0L, 0L))),
        )
    }
    private data class UnknownCapture(val epoch: Int, val view: FloatArray,
        val depth: FrozenUnknownDepthCapture, val trackingScope: CameraUnknownMaterialPointScope,
        val imageQuarterTurns: Int, val trackingFlight: CameraUnknownMaterialPointFlight,
        val feedbackPolicy: UnknownObjectFeedbackPolicy,
        val runtimeChoice: ValidatedJointRuntimeChoice, val primaryModel: TfliteAndroidFrameDetector)
    private lateinit var status: TextView
    private val worker = Executors.newSingleThreadExecutor()
    private val cameraWorker = Executors.newSingleThreadExecutor { task -> Thread(task, "WalkSafeCameraSetup") }
    private val busy = AtomicBoolean(false)
    private val primaryInferencePacing = AdaptiveInferencePacingPolicy()
    private val auxiliaryPacing = CameraAuxiliaryPacing()
    @Volatile private var cameraFrameBudgetMs: Long? = null
    private val loadObservations by lazy {
        AndroidRuntimeLoadObservationSource(feedbackHandler, { cameraFrameBudgetMs }, {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.refreshRate.takeIf { it.isFinite() && it > 0f }
                ?.let { kotlin.math.ceil(1000.0 / it).toLong() }
        })
    }
    @Volatile private var preparedCameraGeneration = -1
    private val jointChoiceLock = Any()
    private val primaryRuntimeAuthority = CameraPrimaryRuntimeAuthority(jointChoiceLock)
    @Volatile private var jointChoice: ValidatedJointRuntimeChoice? = null
    @Volatile private var primaryCandidate: RuntimeCandidate? = null
    @Volatile private var auxiliaryCandidate: RuntimeCandidate? = null
    @Volatile private var primarySetting = "기기 점검 필요"
    @Volatile private var auxiliarySetting = "기기 점검 필요"
    @Volatile private var foreground = false
    @Volatile private var generation = 0
    @Volatile private var session: Session? = null
    private var cameraSessionEpoch: Long? = null
    @Volatile private var provider: ArCoreFrameProvider? = null
    @Volatile private var detector: TfliteAndroidFrameDetector? = null
    private val cameraRelease = CameraSessionRelease()
    private var runtimeHandoff: CameraRuntimeHandoff.Claim? = null
    private var handoffWaitSerial = 0L
    private var waitingHandoff: CompletableFuture<Unit>? = null
    private val cameraNativeReleases = ConcurrentHashMap<Long, CompletableFuture<Unit>>()
    private val retiredPrimaryReleases = ConcurrentLinkedQueue<CompletableFuture<Unit>>()
    private var entryTransfer: VisionPairConsumerTransferTicket? = null
    private var scopedVisionContext: VisionRuntimeContext? = null
    private val visionRuntimeOwnerId get() = scopedVisionContext?.let(VisionRuntimeContext::ownerId) ?: "unclaimed-camera"
    private val visionRuntimeContext get() = requireNotNull(scopedVisionContext) { "Camera pair ownership missing" }
    @Volatile private var cameraPair: AndroidJointRuntimeLoader.PreparedPair<UnknownCapture>? = null
    @Volatile private var cameraConsumer: VisionConsumerLease? = null
    private var cameraConsumerDrained = CompletableFuture<Unit>()
    private var cameraAttachmentReservation: JointRuntimeAttachmentReservation<UnknownCapture>? = null
    private val pairPreparationPending = AtomicBoolean()
    private var returningPair = false
    private var returnTransfer: VisionPairConsumerTransferTicket? = null
    private var returnPrepared = false
    private var returnHostDrained: CompletableFuture<Unit>? = null
    private val closingCamera get() = cameraRelease.blocksStart
    private var startingCamera = false
    private var installRequested = false
    private var background = CameraBackgroundRenderer()
    private var texture = 0
    private var width = 0
    private var height = 0
    private var cameraDisplayRotation: Int? = null // GL thread display geometry.
    private val previewRequests = CameraPreviewGeometry.Requests()
    @Volatile private var previewViewport: CameraPreviewGeometry.Viewport? = null
    private var lastFrame = 0L
    @Volatile private var lastNoticeMs = 0L
    private val firstInferenceLog = CameraFirstInferenceTimingLog() // Accessed only by worker.
    private val unknownGeometryGeneration = AtomicLong()
    private val unknownMaterialPoints = CameraUnknownMaterialPointOwner(enqueueOwner = { action ->
        worker.execute { action() }
    })

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ticketId = intent.getStringExtra(VisionPairTransfers.EXTRA_TICKET)
        entryTransfer = ticketId?.let { VisionPairTransfers.forApp(applicationContext).pending(it) }
        entryTransfer?.let { ticket ->
            if (GatewaySessionProcessCoordinator.snapshot().generation == ticket.targetOwner.sessionGeneration)
                scopedVisionContext = VisionRuntimeContext(applicationContext, ticket.targetOwner)
        }
        runtimeHandoff = CameraRuntimeHandoff.begin()
        surface = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setRenderer(this@CameraTestActivity)
        }
        boxes = DebugBboxOverlayView(this)
        unknownBoxes = DebugBboxOverlayView(this)
        unknownStatus = TextView(this).apply {
            text = "이름 없는 영역 · FastSAM 준비 중"
            textSize = 15f
            setTextColor(Color.YELLOW)
            setBackgroundColor(0xcc000000.toInt())
        }
        feedbackStatus = TextView(this).apply {
            text = "위험 조건 충족 시 음성·진동 · 안내 시작 불필요"
            textSize = 14f
            setTextColor(Color.WHITE)
            setBackgroundColor(0xcc000000.toInt())
        }
        status = TextView(this).apply {
            text = "테스트 카메라 · 모델 준비 중\n안내 시작 없이 실행합니다."
            textSize = 16f
            setTextColor(Color.WHITE)
            setBackgroundColor(0xcc000000.toInt())
            setPadding(18, 12, 18, 12)
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            addView(unknownStatus)
            addView(feedbackStatus)
            addView(Button(this@CameraTestActivity).apply {
                text = "카메라 다시 연결"
                setOnClickListener {
                    startCamera()
                    if (detector != null) {
                        auxiliaryRetryRequested = true
                        resumeUnknownAfterPrimaryLiveAttempt()
                    }
                }
            })
            addView(Button(this@CameraTestActivity).apply {
                text = "카메라 닫기"
                setOnClickListener { finish() }
            })
        }
        setContentView(FrameLayout(this).apply {
            preview = CameraPreviewLayout(this@CameraTestActivity).apply {
                onViewportChanged = { viewport ->
                    previewViewport = viewport
                    invalidateUnknownGeometry()
                }
                addView(surface, FrameLayout.LayoutParams(-1, -1))
                addView(boxes, FrameLayout.LayoutParams(-1, -1))
                addView(unknownBoxes, FrameLayout.LayoutParams(-1, -1))
            }
            addView(preview, FrameLayout.LayoutParams(-1, -1))
            addView(controls, FrameLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        })
    }

    /** Only the already-warmed pair may enter this screen; historical settings cannot OPEN models. */
    private fun prepareCameraDetector() {
        val prepared = cameraPair
        if (prepared != null) {
            if (!cameraPairCurrent()) {
                invalidateJointRuntime(prepared.choice, "camera_pair_ownership_lost")
                return
            }
            preparedCameraGeneration = generation
            return
        }
        val ticket = entryTransfer
        val context = scopedVisionContext
        if (ticket == null || context == null) {
            status.text = "검증한 인식 모델을 전달받지 못했습니다. 홈에서 다시 점검한 뒤 카메라를 열어 주세요."
            return
        }
        if (!foreground || isDestroyed || isFinishing || !pairPreparationPending.compareAndSet(false, true)) return
        val epoch = generation
        val auxiliaryEpoch = unknownRuntime.reserveTransferredEpoch()
        if (auxiliaryEpoch == null) { pairPreparationPending.set(false); return }
        val request = primaryRuntimeAuthority.beginPreparation()
        val reservation = JointRuntimeAttachmentReservation<UnknownCapture>()
        cameraAttachmentReservation = reservation
        val targetDrained = reservation.hostDrained
        cameraConsumerDrained = targetDrained
        val transfers = VisionPairTransfers.forApp(applicationContext)
        val authority = AndroidDeviceCheckAuthority.forApp(applicationContext)
        val client = RemoteVisionClient.forMainProcess(applicationContext)
        try { worker.execute {
            var attachment: TransferredJointRuntimeAttachment<UnknownCapture>? = null
            var adoption: VisionPairConsumerAdoption? = null
            var pair: AndroidJointRuntimeLoader.PreparedPair<UnknownCapture>? = null
            fun current() = foreground && !isDestroyed && !isFinishing && epoch == generation &&
                primaryRuntimeAuthority.isCurrent(request) && !reservation.stopping &&
                GatewaySessionProcessCoordinator.snapshot().generation == ticket.targetOwner.sessionGeneration &&
                authority.ownerRef(ticket.targetOwner.ownerId) === ticket.targetOwner
            val result = runCatching {
                check(current()) { "Camera transfer target retired" }
                check(transfers.take(ticket.id, ticket.targetOwner, ticket.authorityTag) === ticket)
                val config = TwoModelRuntimeConfig.load(context)
                val choice = requireNotNull(AndroidJointRuntimeChoiceStore(context).load(config))
                check(choice.measurementId == ticket.witness.measurementId && choice.candidate == ticket.witness.candidate)
                adoption = transfers.adopt(ticket, ticket.targetOwner, ticket.authorityTag, targetDrained,
                    object : VisionPairTransferTargetEffects {
                        override fun attachBoth(tokens: VisionPairDetachTokens, runtime: VisionTransferResult,
                            deadlineMs: Long): CompletionStage<Set<String>> {
                            attachment = TransferredJointRuntimeAttachment.attach(context, config, choice, authority,
                                tokens.primary, tokens.auxiliary, runtime, auxiliaryEpoch,
                                unknownListener(auxiliaryEpoch, choice), deadlineMs, ::current,
                                onPrimaryCreated = reservation::retainPrimary,
                                onAuxiliaryCreated = reservation::retainAuxiliary)
                            return CompletableFuture.completedFuture(requireNotNull(attachment).modelIds)
                        }
                        override fun activateAndReissue(witness: JointPairTransferWitness,
                            runtime: VisionTransferResult, deadlineMs: Long): kr.co.hanium.dreamup.walksafe.inference.composite.JointPairReadyProof {
                            pair = requireNotNull(attachment).activateAndProve(witness)
                            return requireNotNull(pair).proof
                        }
                    })
                // Register metadata before posting UI. Teardown's worker barrier sees an adopted
                // claim even when publication never ran on the screen thread.
                cameraConsumer = adoption?.consumer
                check(current()) { "Camera transfer publication retired" }
                requireNotNull(pair)
            }
            fun discard() {
                pair?.proof?.revoke()
                adoption?.consumer?.retire(targetDrained)
                transfers.cancel(ticket, "camera_target_retired")
                client.retirementSnapshot(ticket.targetOwner)?.let { client.requestExit(it.lease, "camera_target_retired") }
                reservation.closeHost()
            }
            if (result.isFailure) {
                discard()
                runOnUiThread {
                    pairPreparationPending.set(false)
                    unknownRuntime.cancelTransferredEpoch(auxiliaryEpoch)
                    if (foreground && generation == epoch && !isDestroyed && !isFinishing)
                        status.text = "인식 모델 인계를 확인하지 못했습니다. 홈에서 다시 점검해 주세요."
                }
            } else runOnUiThread {
                val ready = requireNotNull(result.getOrNull())
                val accepted = current() && unknownRuntime.adoptTransferred(auxiliaryEpoch, ready.auxiliary) &&
                    primaryRuntimeAuthority.publish(request, ::current) {
                        detector = ready.load.detector
                        jointChoice = ready.choice
                        cameraPair = ready
                        cameraConsumer = requireNotNull(adoption).consumer
                        primaryCandidate = ready.choice.candidate.primary
                        auxiliaryCandidate = ready.choice.candidate.auxiliary
                        primaryInferencePacing.configureMinimumInterval(ready.choice.candidate.primaryIntervalMs)
                        auxiliaryPacing.begin(auxiliaryEpoch, ready.choice.candidate.auxiliaryIntervalMs)
                        preparedCameraGeneration = epoch
                        primarySetting = "샘플 이미지 점검 설정 · ${ready.choice.candidate.primaryIntervalMs}ms 간격"
                        auxiliarySetting = "샘플 이미지 점검 설정 · ${ready.choice.candidate.auxiliaryIntervalMs}ms 간격"
                    }
                pairPreparationPending.set(false)
                if (accepted) {
                    status.text = "검증한 두 인식 모델 준비 완료 · $primarySetting"
                    unknownRuntime.resume()
                } else {
                    unknownRuntime.cancelTransferredEpoch(auxiliaryEpoch)
                    unknownRuntime.rollbackTransferred(auxiliaryEpoch, ready.auxiliary)
                    reservation.revokeAdmission()
                    try { worker.execute { discard() } } catch (_: RuntimeException) {
                        client.requestExit(requireNotNull(adoption).consumer.runtime.lease, "camera_publication_rejected")
                    }
                }
            }
        } } catch (error: RuntimeException) {
            pairPreparationPending.set(false)
            unknownRuntime.cancelTransferredEpoch(auxiliaryEpoch)
            reservation.closeHost() // No target wrapper work entered the rejected queue.
            transfers.cancel(ticket, "camera_target_queue_rejected")
            status.text = "인계 작업을 시작하지 못했습니다. 홈에서 다시 점검해 주세요."
        }
    }

    private fun cameraPairCurrent(): Boolean {
        val pair = cameraPair ?: return false
        val consumer = cameraConsumer ?: return false
        return pair.proof.isCurrent() && consumer.permits() && detector === pair.load.detector &&
            unknownRuntime.service === pair.auxiliary && pair.auxiliary.isReady &&
            GatewaySessionProcessCoordinator.snapshot().generation == consumer.owner.sessionGeneration
    }

    private fun awaitModelAvailability(receipt: CompletionStage<*>, epoch: Int, request: Long) {
        val activityRef = WeakReference(this)
        receipt.whenComplete { _, error ->
            activityRef.get()?.runOnUiThread {
                val activity = activityRef.get() ?: return@runOnUiThread
                if (!activity.foreground || activity.isDestroyed || activity.isFinishing ||
                    activity.generation != epoch || activity.detector != null ||
                    !activity.primaryRuntimeAuthority.isCurrent(request)) return@runOnUiThread
                if (error == null) activity.prepareCameraDetector()
                else activity.status.text = "이전 화면의 모델 해제가 확인되지 않았습니다. 앱을 완전히 종료한 뒤 다시 실행해 주세요."
            }
        }
    }

    private fun createUnknownService(epoch: Long): FastSamRuntimeService<UnknownCapture> =
        error("A camera auxiliary requires the verified transferred pair")

    private fun unknownListener(epoch: Long, choice: ValidatedJointRuntimeChoice): FastSamRuntimeService.Listener<UnknownCapture> {
        val selected = choice.candidate.auxiliary
        return object : FastSamRuntimeService.Listener<UnknownCapture> {
                override fun onResult(result: FastSamResult<UnknownCapture>) {
                    if (jointChoice !== choice) return
                    if (result.runtime.fallbackUsed || result.runtime.actualBackend.name != selected.backend.name ||
                        result.runtime.numThreads != selected.numThreads) {
                        invalidateJointRuntime(choice, "auxiliary_runtime_mismatch")
                        return
                    }
                    try { showUnknownResult(result) }
                    finally {
                        val token = result.token
                        val now = SystemClock.elapsedRealtime()
                        if (unknownTrackingLive(result.attachment.trackingScope)) {
                            auxiliaryPacing.complete(token.sessionEpoch, token.frameId, now,
                                result.inferenceMs.toLong(), thermalThrottled(), loadObservations.latestObservation(now))
                        } else auxiliaryPacing.cancel(token.sessionEpoch, token.frameId)
                    }
                }
                override fun onError(error: Throwable) {
                    auxiliaryPacing.cancelService(epoch)
                    Log.e("WalkSafeCameraTest", "FastSAM failed", error)
                    unknownMaterialPoints.invalidate(epoch)
                    // Contention before acquiring a lease is not evidence against the measured choice.
                    if (VisionRuntimeFailures.busy(error) != null) return
                    unknownRuntime.failed(epoch)
                    invalidateJointRuntime(choice, "auxiliary_execution_failed")
                }
                override fun onDiscard(token: FastSamFrameToken, reason: String) {
                    if (reason.startsWith("stale") && foreground && unknownRuntime.accepts(token.sessionEpoch)) {
                        val now = SystemClock.elapsedRealtime()
                        auxiliaryPacing.complete(token.sessionEpoch, token.frameId, now,
                            thermalThrottled = thermalThrottled(), load = loadObservations.latestObservation(now))
                    } else auxiliaryPacing.cancel(token.sessionEpoch, token.frameId)
                    if (foreground && !isDestroyed && !isFinishing)
                        unknownMaterialPoints.cancelCapture(token) { scope -> unknownTrackingLive(scope) }
                }
            }
    }

    private fun invalidateJointRuntime(expected: ValidatedJointRuntimeChoice, reason: String) {
        if (!primaryRuntimeAuthority.invalidateIf({ jointChoice === expected }) {
            unknownGeometryGeneration.incrementAndGet()
            jointChoice = null
            val retired = detector
            detector = null
            preparedCameraGeneration = -1
            // Reserve cleanup before a newer preparation can enter this same worker queue.
            enqueueCameraPrimaryRetirement(
                enqueue = { action -> worker.execute { action() } },
                physicalReleases = retiredPrimaryReleases,
                retire = {
                    try { AndroidJointRuntimeChoiceStore(applicationContext).invalidateIfMatches(expected) }
                    catch (error: Throwable) {
                        // Preference failure is not native release evidence. Always continue close.
                        Log.e("WalkSafeCameraTest", "Runtime invalidation failed", error)
                    }
                    closePrimaryNative(retired)
                },
                onUnprovenDrain = { error -> runtimeHandoff?.fail(error); Unit },
            )
        }) return
        runOnUiThread {
            if (isDestroyed || isFinishing) return@runOnUiThread
            unknownRuntime.pauseAndRelease()
            unknownMaterialPoints.invalidate()
            primaryOverlayCapture = null
            unknownOverlay = null
            objectPresentation.clear()
            primaryUiPresentation.clear()
            unknownUiPresentation.clear()
            boxes.clear()
            unknownBoxes.clear()
            feedback.clear(CameraTestFeedbackCoordinator.Source.PRIMARY)
            feedback.clear(CameraTestFeedbackCoordinator.Source.UNKNOWN)
            status.text = "기기 점검 필요 · 실행 설정이 달라졌습니다. 홈에서 다시 점검해 주세요."
            unknownStatus.text = "기기 점검 필요 · 추가 인식을 중지했습니다."
        }
        Log.w("WalkSafeCameraTest", "Joint runtime retired: $reason")
    }

    private fun showUnknownRuntimeStatus() {
        if (!foreground || isDestroyed || isFinishing) return
        val state = unknownRuntime.status
        val choice = jointChoice ?: run {
            unknownStatus.setTextIfChanged("기기 점검 필요 · 추가 인식을 중지했습니다.")
            return
        }
        if (state == CameraUnknownRuntime.Status.READY &&
            unknownRuntime.backendName != choice.candidate.auxiliary.backend.name) {
            invalidateJointRuntime(choice, "auxiliary_ready_runtime_mismatch")
            return
        }
        if (state != CameraUnknownRuntime.Status.READY) {
            unknownOverlay = null
            unknownUiPresentation.clear()
            unknownBoxes.clear()
            feedback.clear(CameraTestFeedbackCoordinator.Source.UNKNOWN)
        }
        unknownStatus.setTextIfChanged(when (state) {
            CameraUnknownRuntime.Status.IDLE, CameraUnknownRuntime.Status.LOADING -> "이름 없는 영역 · FastSAM 준비 중"
            CameraUnknownRuntime.Status.READY -> "이름 없는 영역 · ${unknownRuntime.backendName ?: "FastSAM"} · $auxiliarySetting"
            CameraUnknownRuntime.Status.FAILED -> "이름 없는 영역 실패 · 카메라 다시 연결을 눌러 재시도하세요"
            CameraUnknownRuntime.Status.WAITING_RELEASE -> "이름 없는 영역 · 이전 모델 종료를 기다리는 중"
            CameraUnknownRuntime.Status.WAITING_AVAILABILITY -> "이름 없는 영역 · 다른 화면의 모델 종료를 기다리는 중"
            CameraUnknownRuntime.Status.RELEASE_FAILED -> "이름 없는 영역 · 이전 모델 해제 미확인으로 재시작할 수 없습니다"
        })
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        auxiliaryStartup.begin(generation)
        prepareCameraDetector()
        startCamera()
        resumeUnknownAfterPrimaryLiveAttempt()
    }

    private fun resumeUnknownAfterPrimaryLiveAttempt() {
        if (jointChoice == null) return
        if (!foreground || isDestroyed || isFinishing || session == null || detector == null ||
            preparedCameraGeneration != generation ||
            !auxiliaryStartup.mayStart(generation, busy.get())
        ) return
        unknownRuntime.resume()
        if (auxiliaryRetryRequested) {
            auxiliaryRetryRequested = false
            unknownRuntime.retry()
        }
    }

    private fun startCamera() {
        if (!foreground || isDestroyed || isFinishing) return
        if (runtimeHandoff == null) {
            runtimeHandoff = CameraRuntimeHandoff.begin()
            if (runtimeHandoff == null) { awaitPreviousRuntimeHandoff(); return }
        }
        if (runtimeHandoff?.released?.isCompletedExceptionally == true) {
            status.text = "이전 카메라 실행의 해제가 확인되지 않아 시작할 수 없습니다. 화면을 닫아 주세요."
            return
        }
        if (cameraRelease.failure != null) {
            status.text = "이전 카메라 해제가 확인되지 않아 다시 연결할 수 없습니다. 화면을 종료해 주세요."
            return
        }
        if (!foreground || isDestroyed || isFinishing || startingCamera || closingCamera || session != null) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
            return
        }
        try {
            if (ArCoreApk.getInstance().requestInstall(this, !installRequested) ==
                ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                installRequested = true
                return
            }
            startingCamera = true
            val startGeneration = generation
            status.text = "카메라 연결 중 · 성능 재시험 없음"
            val sessionEpoch = nextCameraSessionEpoch.incrementAndGet()
            val cameraNativeRelease = CompletableFuture<Unit>()
            cameraNativeReleases[sessionEpoch] = cameraNativeRelease
            try { cameraWorker.execute {
                var owned: Session? = null
                var ownedSource: ArCoreFrameProvider? = null
                val prepared = runCatching {
                    val camera = measureCameraStep("session_create", sessionEpoch) { Session(applicationContext) }
                    owned = camera
                    val source = ArCoreFrameProvider(camera)
                    ownedSource = source
                    Triple(camera, source, source.configureDepthMode())
                }
                runOnUiThread {
                    startingCamera = false
                    val value = prepared.getOrNull()
                    val current = foreground && !isDestroyed && !isFinishing && generation == startGeneration
                    if (value == null || !current) {
                        releasePreparedCamera(owned, ownedSource, sessionEpoch, retry = value != null && !current)
                        if (value == null && current)
                            status.text = "카메라 시작 실패: ${prepared.exceptionOrNull()?.javaClass?.simpleName}"
                        return@runOnUiThread
                    }
                    try {
                        // Setup is off UI; resume/pause retain Android lifecycle ordering.
                        measureCameraStep("session_resume", sessionEpoch) { value.first.resume() }
                        generation++
                        primaryInferencePacing.reset()
                        auxiliaryPacing.reset()
                        auxiliaryStartup.begin(generation)
                        unknownMaterialPoints.invalidate()
                        unknownPipeline = UnknownObjectDepthPipeline(SystemClock::elapsedRealtime)
                        unknownFeedbackPolicy = UnknownObjectFeedbackPolicy()
                        primaryDepth = PrimaryDepthState(generation)
                        objectPresentation.clear()
                        primaryUiPresentation.clear()
                        unknownUiPresentation.clear()
                        primaryOverlayCapture = null
                        unknownOverlay = null
                        startFeedback(generation)
                        lastFrame = 0L
                        provider = value.second
                        cameraSessionEpoch = sessionEpoch
                        session = value.first
                        cameraFrameBudgetMs = value.first.cameraConfig.fpsRange.lower.takeIf { it > 0 }
                            ?.let { kotlin.math.ceil(1000.0 / it).toLong() }
                        loadObservations.start()
                        prepareCameraDetector()
                        surface.onResume()
                        status.text = if (value.third) "Depth 지원 · 실제 프레임을 기다리는 중"
                            else "Depth 미지원 · 카메라와 객체 인식만 표시합니다."
                    } catch (error: Exception) {
                        val published = session === value.first
                        if (published) {
                            // Revoke the published camera before release; drain any Images already queued.
                            session = null
                            provider = null
                            cameraSessionEpoch = null
                            runCatching { surface.onPause() }
                        }
                        runCatching { value.first.pause() }
                        queueCameraRelease(value.first, value.second, sessionEpoch,
                            if (published) worker else cameraWorker, retry = false)
                        status.text = "카메라 시작 실패: ${error.javaClass.simpleName}"
                    }
                }
            } } catch (error: RuntimeException) {
                // Rejection proves the setup task never acquired a camera.
                cameraNativeRelease.complete(Unit)
                throw error
            }
        } catch (error: Exception) {
            startingCamera = false
            status.text = "카메라 시작 실패: ${error.javaClass.simpleName}\n카메라를 사용하는 다른 앱과 AR 서비스 설치 상태를 확인하세요."
        }
    }

    private fun awaitPreviousRuntimeHandoff() {
        val pending = CameraRuntimeHandoff.pendingRelease() ?: run { startCamera(); return }
        if (waitingHandoff === pending) return
        waitingHandoff = pending
        val serial = ++handoffWaitSerial
        val epoch = generation
        status.text = "이전 카메라 실행의 종료를 기다리는 중"
        val owner = WeakReference(this)
        pending.whenComplete { _, error -> owner.get()?.let { activity ->
            activity.feedbackHandler.post {
                if (activity.waitingHandoff !== pending || activity.handoffWaitSerial != serial) return@post
                activity.waitingHandoff = null
                if (!activity.foreground || activity.generation != epoch || activity.isDestroyed || activity.isFinishing) return@post
                if (error != null) activity.status.text = "이전 카메라 실행의 해제가 확인되지 않아 시작할 수 없습니다. 화면을 닫아 주세요."
                else {
                    activity.runtimeHandoff = CameraRuntimeHandoff.begin()
                    activity.startCamera()
                }
            }
        } }
    }

    private fun releasePreparedCamera(camera: Session?, source: ArCoreFrameProvider?, epoch: Long, retry: Boolean) {
        queueCameraRelease(camera, source, epoch, cameraWorker, retry)
    }

    private fun queueCameraRelease(
        camera: Session?, source: ArCoreFrameProvider?, epoch: Long?,
        lane: java.util.concurrent.Executor, retry: Boolean,
    ) {
        val attempt = cameraRelease.begin(epoch,
            closeProvider = { source?.let { measureCameraStep("provider_close", epoch) { it.close() } } },
            closeSession = { camera?.let { measureCameraStep("session_close", epoch) { it.close() } } },
        )
        val closingEpoch = generation
        val closeQueuedNs = System.nanoTime()
        try {
            lane.execute {
                logCameraQueue("camera_close_queue", closingEpoch, closeQueuedNs, epoch)
                val nativeRelease = epoch?.let(cameraNativeReleases::get)
                val released = try {
                    check(cameraRelease.execute(attempt)) {
                        cameraRelease.failure?.toString() ?: "Camera native release unconfirmed"
                    }
                    nativeRelease?.complete(Unit)
                    true
                } catch (error: Throwable) {
                    nativeRelease?.completeExceptionally(error)
                    runtimeHandoff?.fail(error)
                    false
                }
                runOnUiThread {
                    if (isDestroyed || isFinishing) cameraWorker.shutdown()
                    else if (!released) status.text = "이전 카메라 해제가 확인되지 않아 다시 연결할 수 없습니다. 화면을 종료해 주세요."
                    else if (foreground && retry) startCamera()
                }
            }
        } catch (failure: RuntimeException) {
            cameraRelease.schedulingFailed(attempt, failure)
            epoch?.let(cameraNativeReleases::get)?.completeExceptionally(failure)
            runtimeHandoff?.fail(failure)
            if (!isDestroyed && !isFinishing) status.text = "카메라 종료 작업을 시작하지 못했습니다. 다시 연결할 수 없습니다."
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startCamera()
        else status.text = "실시간 영상을 보려면 카메라 권한이 필요합니다."
    }

    override fun finish() {
        if (returnPrepared) { super.finish(); return }
        if (returningPair) return
        val pair = cameraPair
        val consumer = cameraConsumer
        val previousMainOwner = entryTransfer?.source?.owner
        val authority = AndroidDeviceCheckAuthority.forApp(applicationContext)
        if (pair == null || consumer == null || previousMainOwner == null || !cameraPairCurrent() ||
            authority.ownerRef(previousMainOwner.ownerId) !== previousMainOwner || !foreground) {
            super.finish()
            return
        }
        // Keep the host ID for its return reservation, but revoke the old opaque allocation
        // scope. A producer delayed before the outbound handoff cannot reacquire the new epoch.
        authority.retireOwner(previousMainOwner)
        val returnOwner = authority.registerOwner(previousMainOwner.ownerId, previousMainOwner.source,
            previousMainOwner.sessionGeneration) ?: run { super.finish(); return }
        val witness = pair.proof.claimForTransfer() ?: run { super.finish(); return }
        val transfers = VisionPairTransfers.forApp(applicationContext)
        val drained = CompletableFuture<Unit>()
        val ticket = transfers.reserve(consumer, witness, returnOwner, consumer.claim.owner.authority,
            drained, SystemClock.elapsedRealtime() + 5_000L)
        if (ticket == null) { super.finish(); return }
        returningPair = true
        returnTransfer = ticket
        returnHostDrained = drained
        generation++
        unknownMaterialPoints.invalidate()
        jointChoice = null
        status.text = "검증한 인식 모델을 홈으로 전달하는 중"
        val primaryToken = CompletableFuture<kr.co.hanium.dreamup.walksafe.inference.PrimaryTransferredDetector>()
        val auxiliaryToken = try { unknownRuntime.detachForTransfer() }
        catch (error: Throwable) { CompletableFuture<kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamTransferredModel>().also {
            it.completeExceptionally(error)
        } }
        val model = detector
        detector = null
        try { worker.execute {
            try { primaryToken.complete(requireNotNull(model).detachForTransfer()) }
            catch (error: Throwable) { primaryToken.completeExceptionally(error) }
        } } catch (error: RuntimeException) { primaryToken.completeExceptionally(error) }
        val sourceDrained = primaryToken.thenCombine(auxiliaryToken) { primary, auxiliary ->
            VisionPairDetachTokens(primary, auxiliary)
        }
        val client = RemoteVisionClient.forMainProcess(applicationContext)
        try { cameraWorker.execute {
            val result = runCatching {
                transfers.prepare(ticket, object : VisionPairTransferSourceEffects {
                    override fun drainSource(deadlineMs: Long): CompletionStage<VisionPairDetachTokens> {
                        return sourceDrained.thenCompose { tokens ->
                            val host = cameraAttachmentReservation?.closeHost() ?: CompletableFuture.completedFuture(Unit)
                            host.thenApply { drained.complete(Unit); tokens }
                        }
                    }
                    override fun park(lease: VisionCohortLease, deadlineMs: Long) = client.park(lease, deadlineMs)
                    override fun transfer(lease: VisionCohortLease, targetOwner: kr.co.hanium.dreamup.walksafe.device.DeviceCheckOwnerRef,
                        deadlineMs: Long) = client.transfer(lease, targetOwner, deadlineMs)
                })
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (result.isSuccess && !isFinishing) {
                    returnPrepared = true
                    setResult(RESULT_OK, Intent().putExtra(VisionPairTransfers.EXTRA_TICKET, ticket.id))
                    super.finish()
                } else {
                    transfers.cancel(ticket, "camera_return_failed")
                    // Destruction follows the ordinary physical cleanup path on a failed return.
                    returningPair = false
                    returnPrepared = true
                    super.finish()
                }
            }
        } } catch (_: RuntimeException) {
            transfers.cancel(ticket, "camera_return_queue_rejected")
            returningPair = false
            returnPrepared = true
            super.finish()
        }
    }

    @Deprecated("Back remains an explicit bounded pair handoff")
    override fun onBackPressed() { finish() }

    override fun onPause() {
        foreground = false
        ++handoffWaitSerial
        waitingHandoff = null
        generation++
        primaryInferencePacing.reset()
        auxiliaryPacing.reset()
        loadObservations.pause()
        auxiliaryStartup.clear()
        auxiliaryRetryRequested = false
        unknownMaterialPoints.invalidate()
        unknownRuntime.pause()
        stopFeedback()
        surface.onPause() // Stop Session.update before pausing or closing the camera.
        val camera = session
        val source = provider
        source?.invalidateRecognitionCameraImageTimestamps()
        val closingSessionEpoch = cameraSessionEpoch
        session = null
        provider = null
        cameraSessionEpoch = null
        objectPresentation.clear()
        primaryUiPresentation.clear()
        unknownUiPresentation.clear()
        primaryOverlayCapture = null
        unknownOverlay = null
        boxes.clear()
        unknownBoxes.clear()
        if (camera != null) {
            runCatching { measureCameraStep("session_pause", closingSessionEpoch) { camera.pause() } }
            // This lane runs after queued inference has returned its Image.
            queueCameraRelease(camera, source, closingSessionEpoch, worker, retry = true)
        }
        super.onPause()
    }

    override fun onDestroy() {
        stopFeedback()
        foreground = false
        ++handoffWaitSerial
        waitingHandoff = null
        generation++
        primaryInferencePacing.reset()
        auxiliaryPacing.reset()
        loadObservations.close()
        unknownMaterialPoints.invalidate()
        entryTransfer?.let { VisionPairTransfers.forApp(applicationContext).cancel(it, "camera_destroyed") }
        if (returningPair && returnPrepared) {
            val participant = runtimeHandoff ?: CameraRuntimeHandoff.joinRetirement()
            val releases = cameraNativeReleases.values.toList() + listOfNotNull(returnHostDrained)
            participant.completeAfter(releases)
            runtimeHandoff = participant
            unknownRuntime.destroy() // The transferred source slot is already empty.
            worker.shutdown()
            if (!startingCamera && (!closingCamera || cameraRelease.failure != null)) cameraWorker.shutdown()
            super.onDestroy()
            return
        }
        returnTransfer?.let { VisionPairTransfers.forApp(applicationContext).cancel(it, "camera_return_destroyed") }
        cameraPair?.proof?.revoke()
        cameraAttachmentReservation?.revokeAdmission()
        val attachmentReservation = cameraAttachmentReservation
        // Stop auxiliary admission before the primary worker barrier returns its final Image/copy.
        val auxiliaryStop = CompletableFuture<Unit>()
        try { unknownRuntime.destroy(); auxiliaryStop.complete(Unit) }
        catch (error: Throwable) { auxiliaryStop.completeExceptionally(error) }
        val auxiliaryReleases = unknownRuntime.retainedReleaseReceipts().toList() + auxiliaryStop
        primaryRuntimeAuthority.invalidateIf({ true }) { jointChoice = null }
        val closingEpoch = generation
        val closeQueuedNs = System.nanoTime()
        val primaryRelease = drainCameraPrimaryForRetirement(
            enqueue = { action -> worker.execute { action() } },
            closeCurrent = {
                logCameraQueue("detector_close_queue", closingEpoch, closeQueuedNs)
                val model = detector
                detector = null
                try {
                    val pendingHost = attachmentReservation?.closeHost() ?: CompletableFuture.completedFuture(Unit)
                    cameraConsumer?.retire(pendingHost)
                    CompletableFuture.allOf(closePrimaryNative(model), pendingHost).thenApply { Unit }
                }
                finally { logDetectorTiming("detector_close", model?.lifecycleTiming()) }
            },
            retiredReleases = { retiredPrimaryReleases.toList() },
        )
        val hostDrained = cameraConsumerDrained
        val failedReturnDrain = returnHostDrained
        val allConsumers = CompletableFuture.allOf(primaryRelease,
            *auxiliaryReleases.map { it.toCompletableFuture() }.toTypedArray()).thenApply { Unit }
        cameraConsumer?.retire(allConsumers)
        allConsumers.whenComplete { _, error ->
            if (error == null) { hostDrained.complete(Unit); failedReturnDrain?.complete(Unit) }
            else { hostDrained.completeExceptionally(error); failedReturnDrain?.completeExceptionally(error) }
        }
        val ownerId = visionRuntimeOwnerId
        val owner = scopedVisionContext?.let(VisionRuntimeContext::ownerRef)
        val client = if (Build.VERSION.SDK_INT >= 31) RemoteVisionClient.forMainProcess(applicationContext) else null
        // No callback below retains this Activity, a listener, an Image or a model wrapper.
        runtimeHandoff = retireCameraRuntime(ownerId, runtimeHandoff, CameraRuntimeHandoff::joinRetirement,
            primaryRelease, cameraNativeReleases.values.toList(), { auxiliaryReleases },
            { owner?.let { client?.retirementSnapshot(it) } },
            { lease -> client?.requestExit(lease, "camera_destroyed"); Unit },
            { error -> Log.w("WalkSafeCameraRetirement", "Camera resource release unconfirmed", error); Unit })
        worker.shutdown()
        if (!startingCamera && (!closingCamera || cameraRelease.failure != null)) cameraWorker.shutdown()
        super.onDestroy()
    }

    private fun closePrimaryNative(model: TfliteAndroidFrameDetector?): CompletableFuture<Unit> {
        if (model == null) return CompletableFuture.completedFuture(Unit)
        return closeCameraPrimaryRuntime(model::close, model::actualReleasedFuture,
            { error -> Log.w("WalkSafeCameraTest", "Primary close failed; waiting for actual release", error); Unit })
            .also { retiredPrimaryReleases.add(it) }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        previewRequests.reset()
        background = CameraBackgroundRenderer().also { it.createOnGlThread() }
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        texture = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        if (this.width != width || this.height != height) {
            provider?.invalidateRecognitionCameraImageTimestamps()
            invalidateUnknownGeometry()
        }
        this.width = width
        this.height = height
        GLES20.glViewport(0, 0, width, height)
    }

    private fun thermalThrottled(): Boolean? = if (Build.VERSION.SDK_INT >= 29) runCatching {
        getSystemService(PowerManager::class.java)?.currentThermalStatus?.let { it >= PowerManager.THERMAL_STATUS_SEVERE }
    }.getOrNull() else null

    private fun recordCameraLoad(timestampNs: Long, depthMs: Long? = null) {
        loadObservations.recordFrame(timestampNs, SystemClock.elapsedRealtime(), depthMs, null, null,
            "$generation:${unknownGeometryGeneration.get()}:$primaryCandidate")
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val camera = session ?: return
        val source = provider ?: return
        if (!foreground || width <= 0 || height <= 0) return
        val epoch = generation
        try {
            camera.setCameraTextureName(texture)
            @Suppress("DEPRECATION")
            val displayRotation = windowManager.defaultDisplay.rotation
            if (cameraDisplayRotation != displayRotation) {
                source.invalidateRecognitionCameraImageTimestamps()
                invalidateUnknownGeometry()
                cameraDisplayRotation = displayRotation
            }
            camera.setDisplayGeometry(displayRotation, width, height)
            val frame = camera.update()
            updatePreviewGeometry(frame, camera, epoch, displayRotation)
            val previewRequest = previewRequests.current(epoch, displayRotation) ?: return
            if (!CameraPreviewGeometry.matches(previewViewport, width, height, previewRequest.revision)) return
            background.draw(frame, texture)
            if (frame.timestamp <= lastFrame) return
            lastFrame = frame.timestamp
            val model = detector?.takeIf { preparedCameraGeneration == epoch }
                ?: run { recordCameraLoad(frame.timestamp); return }
            val choice = jointChoice ?: return
            if (!cameraPairCurrent()) return
            if (!busy.compareAndSet(false, true)) { recordCameraLoad(frame.timestamp); return }
            val capturedMs = SystemClock.elapsedRealtime()
            val pacingTicket = primaryInferencePacing.tryStart(capturedMs) ?: run {
                recordCameraLoad(frame.timestamp)
                busy.set(false)
                return
            }
            val auxiliary = unknownRuntime.service
            var transferred = false
            var image: android.media.Image? = null
            try {
                val depthStartedMs = SystemClock.elapsedRealtime()
                val capture = captureCameraTestFrame(source, frame, profileFullDepth = true) ?: return
                recordCameraLoad(frame.timestamp, (SystemClock.elapsedRealtime() - depthStartedMs).coerceAtLeast(0L))
                image = capture.image
                val ownedImage = image
                val snapshot = capture.snapshot
                val capturedSnapshot = snapshot
                val basis = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f, 0.5f, 0.5f)
                val view = FloatArray(10)
                val depth = FloatArray(10)
                frame.transformCoordinates2d(Coordinates2d.IMAGE_NORMALIZED, basis, Coordinates2d.VIEW, view)
                frame.transformCoordinates2d(Coordinates2d.IMAGE_NORMALIZED, basis, Coordinates2d.TEXTURE_NORMALIZED, depth)
                val frameId = frame.timestamp
                val trackingGeometryGeneration = unknownGeometryGeneration.get()
                val tracking = frame.camera.trackingState.name
                val trackingFailure = frame.camera.trackingFailureReason.name
                val turns = UprightCameraImage.quarterTurns(view) ?: return
                val geometryId = "camera-test:$epoch:${ownedImage.width}x${ownedImage.height}:$turns:$trackingGeometryGeneration"
                val frozenTransform = FrozenImageToDepthTransform.create(frameId,
                    depth.toList().chunked(2).take(4).map { Point2(it[0], it[1]) }, Point2(depth[8], depth[9]))
                val grid = capturedSnapshot.rawDepth ?: capturedSnapshot.fullDepth
                // With no depth pixels, the current normalized transform still lets the tracked
                // object reach the nonmetric gap estimator. The 1x1 size is never sampled.
                val mapper = if (frozenTransform != null) FrozenImageToTextureCoordinateMapper(
                    frameId, frozenTransform, ImageSize(grid?.width ?: 1, grid?.height ?: 1)) else null
                val pipeline = unknownPipeline
                val auxiliaryFeedbackPolicy = unknownFeedbackPolicy
                val primary = primaryDepth
                worker.execute {
                    var detectorInvoked = false
                    var inferenceDurationMs: Long? = null
                    try {
                        if (!foreground || epoch != generation || model !== detector || jointChoice !== choice) return@execute
                        // Match the calibrated workload: primary first, then independently paced FastSAM.
                        val meanLuma = meanLuma(ownedImage)
                        val result = try {
                            detectorInvoked = true
                            model.detectOriented(ownedImage, frameId / 1_000_000L, turns)
                        }
                        finally {
                            if (firstInferenceLog.pending) {
                                logDetectorTiming("first_inference", firstInferenceLog.take(model.lifecycleTiming()))
                            }
                        }
                        inferenceDurationMs = result.timing.modelInferenceMs
                        if (jointChoice !== choice || detector !== model) return@execute
                        val runtime = result.timing.modelRuntime
                        if (runtime == null || runtime.fallbackUsed ||
                            runtime.activeDelegate != choice.candidate.primary.backend.delegate ||
                            runtime.numThreads != choice.candidate.primary.numThreads) {
                            invalidateJointRuntime(choice, "primary_runtime_mismatch")
                            return@execute
                        }
                        val qualifiedUnknownFrame = !primaryInferencePacing.snapshot().auxiliaryHoldActive &&
                            unknownMaterialPoints.qualifyFrame(capturedSnapshot.cameraImageTimestampNs) {
                            foreground && epoch == generation && trackingGeometryGeneration == unknownGeometryGeneration.get() &&
                                jointChoice === choice && detector === model
                        }
                        // RGB-only captures must not read an unqualified clock for FastSAM tokens.
                        if (qualifiedUnknownFrame && auxiliary != null && foreground && epoch == generation &&
                            unknownRuntime.accepts(auxiliary.sessionEpoch)) {
                            val token = FastSamFrameToken(FastSamFrameToken.Source.LIVE_CAMERA,
                                auxiliary.sessionEpoch, frameId, capturedSnapshot.frameTimestampNs, ownedImage.timestamp,
                                capturedMs * 1_000_000L, geometryId, ownedImage.width, ownedImage.height)
                            val trackingScope = unknownMaterialPoints.acquireScope(epoch, auxiliary.sessionEpoch,
                                trackingGeometryGeneration, pipeline, geometryId) {
                                foreground && epoch == generation && trackingGeometryGeneration == unknownGeometryGeneration.get() &&
                                    unknownRuntime.accepts(auxiliary.sessionEpoch) && jointChoice === choice && detector === model
                            }
                            if (trackingScope != null) runCatching {
                                unknownMaterialPoints.offerImage(trackingScope, token, turns, ownedImage,
                                    SystemClock.elapsedRealtime()) { unknownTrackingLive(trackingScope) }
                            }.onFailure { Log.w("WalkSafeCameraTest", "Unknown tracking frame admission failed", it) }
                            val state = auxiliary.stats()
                            if (trackingScope != null && unknownTrackingLive(trackingScope) &&
                                !state.inflight && !state.pending && !state.stopping) {
                                val h = frozenTransform?.imagePixelsToDepthUvMatrix(ownedImage.width, ownedImage.height)
                                val capture = FrozenUnknownDepthCapture.freeze(token,
                                    capturedSnapshot, h, frameId,
                                    imageQuarterTurns = turns)
                                if (capture != null) {
                                    val flight = unknownMaterialPoints.reserveCapture(trackingScope, token, turns,
                                        SystemClock.elapsedRealtime()) { unknownTrackingLive(trackingScope) }
                                    if (flight != null) runCatching {
                                        if (!unknownTrackingLive(trackingScope) || !flight.accepts ||
                                            jointChoice !== choice || detector !== model ||
                                            primaryInferencePacing.snapshot().auxiliaryHoldActive ||
                                            !auxiliaryPacing.tryStart(auxiliary.sessionEpoch, frameId, capturedMs)) {
                                            unknownMaterialPoints.cancelFlight(trackingScope, flight) { unknownTrackingLive(trackingScope) }
                                        } else {
                                            val admission = auxiliary.submit(ownedImage, token, UnknownCapture(epoch, view, capture,
                                                trackingScope, turns, flight, auxiliaryFeedbackPolicy, choice, model))
                                            if (admission != FastSamRuntimeService.Admission.ACCEPTED &&
                                                admission != FastSamRuntimeService.Admission.REPLACED_PENDING) {
                                                auxiliaryPacing.cancel(auxiliary.sessionEpoch, frameId)
                                                unknownMaterialPoints.cancelFlight(trackingScope, flight) { unknownTrackingLive(trackingScope) }
                                            }
                                        }
                                    }.onFailure {
                                        auxiliaryPacing.cancel(auxiliary.sessionEpoch, frameId)
                                        unknownMaterialPoints.cancelFlight(trackingScope, flight) { unknownTrackingLive(trackingScope) }
                                        Log.w("WalkSafeCameraTest", "FastSAM frame submission failed", it)
                                    }
                                }
                            }
                        }
                        val outputs = if (mapper != null && tracking == "TRACKING") {
                            val primaryPipeline = primary.forGeometry(geometryId)
                            primary.sourceGate.qualify(capturedSnapshot)?.let { qualified -> primaryPipeline.process(
                                snapshot = qualified, frameId = frameId, timestampMs = frameId / 1_000_000L,
                                detections = result.detections, detectionSequenceId = frameId,
                                detectionCompleted = !result.partial, mapper = mapper, mapperFrameId = mapper.frameId,
                                imageQuarterTurns = turns,
                            ) } ?: emptyList()
                        } else { primary.reset(); emptyList() }
                        val mapped = result.detections.map { item ->
                            val rect = item.bboxNorm
                            val corners = listOf(point(view, rect.x, rect.y),
                                point(view, rect.x + rect.width, rect.y),
                                point(view, rect.x, rect.y + rect.height),
                                point(view, rect.x + rect.width, rect.y + rect.height))
                            val output = outputs.firstOrNull { it.className == item.className && it.bboxNorm == rect }
                            val label = "분류 ${item.className} ${String.format(Locale.US, "%.0f%%", item.detectionConfidence * 100)} · " +
                                (output?.riskDistanceM?.let { String.format(Locale.US, "위험 거리 %.2fm", it) }
                                    ?: output?.prediction?.let { String.format(Locale.US, "예측 %.2fm ±%.2fm · 공백 %dms",
                                        it.distanceM, it.errorBoundM, it.predictionAgeMs) }
                                    ?: if (output?.depthAvailability == kr.co.hanium.dreamup.walksafe.depth.DepthAvailability.TEMPORARILY_UNAVAILABLE)
                                        "깊이 일시 공백" else "거리 미확인")
                            DebugBboxOverlayView.DebugOverlayBox(RectF(corners.minOf { it.first },
                                corners.minOf { it.second }, corners.maxOf { it.first }, corners.maxOf { it.second }),
                                label, false, item.className)
                        }
                        val presentationCapture = CameraObjectPresentation.Capture(epoch, frameId, capturedMs, geometryId)
                        val presentationRegions = result.detections.map { detection ->
                            val output = outputs.firstOrNull {
                                it.className == detection.className && it.bboxNorm == detection.bboxNorm
                            }
                            CameraObjectPresentation.Region(detection.bboxNorm, output?.trackId,
                                output.trustedPresentationDistanceM(), detection.detectionConfidence)
                        }
                        val predictionDeadlines = result.detections.map { item ->
                            outputs.firstOrNull { it.className == item.className && it.bboxNorm == item.bboxNorm }
                                ?.prediction?.let { DepthPredictionPresentation.validUntilMs(it, capturedMs) ?: capturedMs - 1L }
                        }
                        val message = "테스트 카메라 · ARCore $tracking\n" +
                            "$primarySetting · 실행 ${result.timing.modelRuntime?.activeDelegate}\n" +
                            "객체 ${result.detections.size}개 · 추론 ${result.timing.modelInferenceMs}ms\n" +
                            "센서 영상 → 화면 방향 보정 ${turns * 90}° (휴대폰을 돌리라는 뜻이 아닙니다)\n" +
                            "Raw Depth ${snapshot.hasMetricRawDepth} · Full Depth ${snapshot.hasFullDepth}\n" +
                            (capture.fullDepthTiming?.let { cameraFullDepthTimingDiagnostic(it) + "\n" } ?: "") +
                            (if (tracking != "TRACKING") "깊이 추적 대기: $trackingFailure\n" else "") +
                            (capture.depthError?.let { "깊이 오류: ${it.javaClass.simpleName} · 객체 추론은 계속합니다.\n" } ?: "") +
                            (if (meanLuma < 25) "입력 영상이 매우 어둡습니다. 렌즈 가림과 조명을 확인하세요.\n"
                                else if (result.detections.isEmpty()) "모델 실행 정상 · 학습한 21개 클래스 중 검출 없음\n" else "")
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastNoticeMs > 2000) {
                            lastNoticeMs = now
                            Log.i("WalkSafeCameraTest", "inferenceMs=${result.timing.modelInferenceMs} objects=${mapped.size} meanLuma=$meanLuma tracking=$tracking/$trackingFailure depthError=${capture.depthError?.javaClass?.simpleName} " +
                                "primaryIntervalMs=${primaryInferencePacing.snapshot().targetIntervalMs} auxiliaryIntervalMs=${auxiliaryPacing.snapshot().targetIntervalMs} thermal=${thermalThrottled()} " +
                                (capture.fullDepthTiming?.let { cameraFullDepthTimingDiagnostic(it) } ?: "Full 요청 없음"))
                        }
                        runOnUiThread {
                            if (foreground && epoch == generation && trackingGeometryGeneration == unknownGeometryGeneration.get() &&
                                jointChoice === choice && detector === model) {
                                if (!objectPresentation.recordPrimary(presentationCapture, presentationRegions)) return@runOnUiThread
                                if (!primaryUiPresentation.begin(presentationCapture)) return@runOnUiThread
                                primaryOverlayCapture = presentationCapture
                                fun renderPrimaryDepth() {
                                    if (!foreground || epoch != generation || trackingGeometryGeneration != unknownGeometryGeneration.get() ||
                                        jointChoice !== choice || detector !== model ||
                                        primaryOverlayCapture != presentationCapture) return
                                    val displayNow = SystemClock.elapsedRealtime()
                                    val expiredIndices = predictionDeadlines.indices.filter { index ->
                                        predictionDeadlines[index]?.let { displayNow > it } == true
                                    }
                                    if (primaryUiPresentation.shouldRender(presentationCapture, expiredIndices)) {
                                        val currentBoxes = mapped.mapIndexed { index, box ->
                                            if (index in expiredIndices)
                                                box.copy(label = "분류 ${result.detections[index].className} · 거리 미확인") else box
                                        }
                                        boxes.updateMapped(currentBoxes, frameId, capturedMs, sourceComplete = true, animate = false)
                                        status.setTextIfChanged(message + currentBoxes.take(4).joinToString("\n") { it.label })
                                    }
                                    primaryUiPresentation.scheduleExpiry(presentationCapture, predictionDeadlines, displayNow,
                                        { foreground && epoch == generation && trackingGeometryGeneration == unknownGeometryGeneration.get() &&
                                            jointChoice === choice && detector === model &&
                                            primaryOverlayCapture == presentationCapture },
                                        ::renderPrimaryDepth)
                                }
                                renderPrimaryDepth()
                                val displayNow = SystemClock.elapsedRealtime()
                                renderUnknownObjects(displayNow)
                                feedback.offer(CameraTestFeedbackCoordinator.Sample(
                                    CameraTestFeedbackCoordinator.Source.PRIMARY, epoch, frameId,
                                    capturedMs, now, geometryId, outputs))
                                updateFeedback()
                            }
                        }
                    } catch (error: Exception) {
                        Log.e("WalkSafeCameraTest", "Object inference failed", error)
                        if (jointChoice === choice && detector === model)
                            showError(epoch, "객체 인식 실패: ${error.javaClass.simpleName}")
                    } finally {
                        try {
                            ownedImage.close()
                        } finally {
                            try {
                                // Include capture, queueing, preprocessing and publication, not just native inference.
                                if (detectorInvoked && foreground && epoch == generation && model === detector && jointChoice === choice) {
                                    val completedAtMs = SystemClock.elapsedRealtime()
                                    primaryInferencePacing.complete(pacingTicket, completedAtMs, inferenceDurationMs,
                                        thermalThrottled(), loadObservations.latestObservation(completedAtMs))
                                } else primaryInferencePacing.cancel(pacingTicket)
                            } finally {
                                busy.set(false)
                                if (foreground && epoch == generation) runOnUiThread {
                                    if (foreground && epoch == generation && !isDestroyed && !isFinishing &&
                                        jointChoice === choice && detector === model) {
                                        if (detectorInvoked) auxiliaryStartup.complete(epoch)
                                        // A newer primary flight may have started before this UI callback.
                                        // Its completion gets another chance to start the auxiliary model.
                                        resumeUnknownAfterPrimaryLiveAttempt()
                                    }
                                }
                            }
                        }
                    }
                }
                transferred = true
            } finally {
                try {
                    releaseUntransferredCameraTestFrame(source, image, busy, transferred)
                } finally {
                    if (!transferred) {
                        try { primaryInferencePacing.cancel(pacingTicket) }
                        finally { busy.set(false) }
                    }
                }
            }
        } catch (error: Exception) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastNoticeMs > 1000) {
                lastNoticeMs = now
                Log.w("WalkSafeCameraTest", "Camera frame failed", error)
                showError(epoch, "카메라 프레임 대기: ${error.javaClass.simpleName}")
            }
        }
    }

    private fun showError(epoch: Int, message: String) = runOnUiThread {
        if (foreground && epoch == generation && jointChoice != null && detector != null) {
            status.text = message
            objectPresentation.clear()
            primaryUiPresentation.clear()
            primaryOverlayCapture = null
            boxes.clear()
            renderUnknownObjects(SystemClock.elapsedRealtime())
        }
    }

    private fun logDetectorTiming(event: String, timing: AndroidDetectorLoadTiming?) {
        if (timing == null) return
        Log.i("WalkSafeCameraTest", "$event loadGeneration=${timing.loadGeneration} loadMs=${timing.loadElapsedMs} " +
            "available=${timing.detectorAvailable} requestThread=${timing.requestThreadId}/${timing.requestThreadName}")
        timing.phases.forEach { step ->
            Log.i("WalkSafeCameraTest", "$event loadGeneration=${timing.loadGeneration} phase=${step.phase} " +
                "ms=${step.elapsedMs} outcome=${step.outcome} model=${step.modelKey} delegate=${step.delegate} " +
                "thread=${step.threadId}/${step.threadName} failure=${step.failureType}")
        }
    }

    private fun logCameraQueue(phase: String, epoch: Int, queuedNs: Long, sessionEpoch: Long? = null) {
        Log.i("WalkSafeCameraTest", "cameraGeneration=$epoch cameraSessionEpoch=$sessionEpoch phase=$phase ms=${(System.nanoTime() - queuedNs) / 1_000_000.0} " +
            "outcome=STARTED thread=${Thread.currentThread().id}/${Thread.currentThread().name}")
    }

    private fun <T> measureCameraStep(phase: String, sessionEpoch: Long?, block: () -> T): T {
        val startedNs = System.nanoTime()
        var failure: Throwable? = null
        try { return block() }
        catch (error: Throwable) { failure = error; throw error }
        finally {
            Log.i("WalkSafeCameraTest", "cameraSessionEpoch=$sessionEpoch phase=$phase ms=${(System.nanoTime() - startedNs) / 1_000_000.0} " +
                "outcome=${if (failure == null) "SUCCEEDED" else "FAILED"} failure=${failure?.javaClass?.simpleName} " +
                "thread=${Thread.currentThread().id}/${Thread.currentThread().name}")
        }
    }

    private fun feedbackLive(epoch: Int): Boolean =
        foreground && epoch == generation && !isDestroyed && !isFinishing

    private val feedbackTick = object : Runnable {
        override fun run() {
            if (!feedbackLive(generation) || feedbackActuator == null) return
            renderUnknownObjects(SystemClock.elapsedRealtime())
            updateFeedback()
            feedbackHandler.postDelayed(this, 200L)
        }
    }

    private fun startFeedback(epoch: Int) {
        stopFeedback()
        feedback.start(epoch)
        speechReadiness = "한국어 음성 준비 중"
        lastFeedbackEvent = ""
        feedbackActuator = AndroidFeedbackActuator(
            context = applicationContext,
            speechAllowed = { feedbackLive(epoch) },
            hapticAllowed = { feedbackLive(epoch) },
            onOfflineKoreanSpeechReady = {
                runOnUiThread { if (feedbackLive(epoch)) speechReadiness = "한국어 음성 준비됨" }
            },
            onOfflineKoreanSpeechUnavailable = {
                runOnUiThread { if (feedbackLive(epoch)) speechReadiness = "한국어 TTS 사용 불가 · 진동 경고 유지" }
            },
        )
        feedbackHandler.post(feedbackTick)
    }

    private fun stopFeedback() {
        feedbackHandler.removeCallbacksAndMessages(null)
        feedback.stop()
        feedbackActuator?.close() // Stops pending/on-going TTS and vibration on pause/close.
        feedbackActuator = null
    }

    private fun updateFeedback() {
        val epoch = generation
        if (!feedbackLive(epoch)) return
        val actuator = feedbackActuator ?: return
        val now = SystemClock.elapsedRealtime()
        discardRetiredUnknownOverlay()
        val delivery = feedback.next(now)
        if (delivery != null) {
            if (!feedback.claim(delivery, now)) feedback.reject(delivery)
            else {
                var vibrationAccepted = false
                val dispatch = actuator.emit(
                    action = delivery.action,
                    isStillValidAtStart = {
                        discardRetiredUnknownOverlay()
                        feedbackLive(epoch) && feedback.isDeliverable(delivery, SystemClock.elapsedRealtime())
                    },
                    onSpeechCompleted = {
                        feedbackHandler.post {
                            if (feedbackLive(epoch)) {
                                if (feedback.complete(delivery, SystemClock.elapsedRealtime())) {
                                    lastFeedbackEvent = "음성 완료: ${delivery.action.message}"
                                }
                            }
                        }
                    },
                    onSpeechFailed = {
                        feedbackHandler.post {
                            if (feedbackLive(epoch)) resolveFailedSpeech(delivery, vibrationAccepted, now)
                        }
                    },
                )
                vibrationAccepted = dispatch.vibrationAccepted
                lastFeedbackEvent = if (dispatch.speech == NavigationSpeechDispatchResult.ACCEPTED)
                    "음성 요청: ${delivery.action.message}"
                else "음성 ${dispatch.speech} · 진동 ${if (vibrationAccepted) "요청 수락" else "사용 불가"}"
                if (dispatch.speech != NavigationSpeechDispatchResult.ACCEPTED) {
                    resolveFailedSpeech(delivery, vibrationAccepted, now)
                }
            }
        }
        val diagnostic = "$speechReadiness\n${feedback.diagnostic(now)}"
        feedbackStatus.setTextIfChanged(diagnostic + if (lastFeedbackEvent.isBlank()) "" else "\n$lastFeedbackEvent")
        if (diagnostic != lastFeedbackLog && now - lastFeedbackLogMs >= 2_000L) {
            lastFeedbackLog = diagnostic
            lastFeedbackLogMs = now
            Log.i("WalkSafeCameraTest", "feedback=${diagnostic.replace('\n', ' ')}")
        }
    }

    private fun resolveFailedSpeech(delivery: CameraTestFeedbackCoordinator.Delivery,
                                    vibrationAccepted: Boolean, startedAtMs: Long) {
        if (!vibrationAccepted) { feedback.reject(delivery); return }
        // A vibration request is not physical completion. Hold the shared queue for its duration
        // before consuming cooldown, mirroring the production haptic fallback.
        val duration = delivery.action.vibrationPatternMs?.sum() ?: 0L
        feedbackHandler.postDelayed({
            if (feedbackLive(delivery.epoch)) {
                if (feedback.complete(delivery, SystemClock.elapsedRealtime())) {
                    lastFeedbackEvent = "음성 미완료 · 진동 요청 수락"
                }
            }
        }, (startedAtMs + duration - SystemClock.elapsedRealtime()).coerceAtLeast(1L))
    }

    private fun updatePreviewGeometry(frame: com.google.ar.core.Frame, camera: Session, epoch: Int, rotation: Int) {
        if (frame.timestamp <= 0L || !previewRequests.shouldRequest(epoch, rotation)) return
        // GPU dimensions are unrotated. The live IMAGE -> VIEW axis supplies rotation parity;
        // CPU image dimensions are deliberately not used to size the displayed texture.
        val dimensions = frame.camera.textureIntrinsics.imageDimensions
        if (dimensions.size < 2 || dimensions[0] <= 0 || dimensions[1] <= 0) return
        val axis = FloatArray(4)
        frame.transformCoordinates2d(Coordinates2d.IMAGE_NORMALIZED, floatArrayOf(0f, 0f, 1f, 0f), Coordinates2d.VIEW, axis)
        val turns = UprightCameraImage.quarterTurns(axis) ?: return
        val request = previewRequests.request(epoch, rotation) ?: return
        invalidateUnknownGeometry()
        runOnUiThread {
            @Suppress("DEPRECATION")
            val currentRotation = windowManager.defaultDisplay.rotation
            if (foreground && epoch == generation && session === camera && currentRotation == rotation &&
                previewRequests.isCurrent(request)
            ) preview.setImageSize(dimensions[0], dimensions[1], turns, request.revision)
            else previewRequests.reject(request)
        }
    }

    private fun invalidateUnknownGeometry() {
        val geometry = unknownGeometryGeneration.incrementAndGet()
        unknownMaterialPoints.invalidate()
        runOnUiThread {
            if (geometry != unknownGeometryGeneration.get()) return@runOnUiThread
            primaryOverlayCapture = null
            unknownOverlay = null
            primaryUiPresentation.clear()
            unknownUiPresentation.clear()
            objectPresentation.clear()
            boxes.clear()
            unknownBoxes.clear()
            feedback.clear(CameraTestFeedbackCoordinator.Source.PRIMARY)
            feedback.clear(CameraTestFeedbackCoordinator.Source.UNKNOWN)
        }
    }

    private fun unknownTrackingLive(scope: CameraUnknownMaterialPointScope): Boolean =
        scope.accepts && foreground && scope.cameraEpoch == generation &&
            scope.geometryGeneration == unknownGeometryGeneration.get() && unknownRuntime.accepts(scope.serviceEpoch) &&
            jointChoice != null && detector != null

    private fun unknownCaptureLive(capture: UnknownCapture): Boolean =
        unknownTrackingLive(capture.trackingScope) && jointChoice === capture.runtimeChoice && detector === capture.primaryModel

    private fun showUnknownResult(result: FastSamResult<UnknownCapture>) {
        val callbackStartedNs = System.nanoTime()
        val capture = result.attachment
        if (!unknownCaptureLive(capture) || !capture.trackingFlight.accepts) {
            unknownMaterialPoints.cancelFlight(capture.trackingScope, capture.trackingFlight) { unknownTrackingLive(capture.trackingScope) }
            return
        }
        val consumed = unknownMaterialPoints.process(capture.trackingScope, capture.depth, result.token,
            capture.imageQuarterTurns, result.masks, SystemClock.elapsedRealtime(), callbackStartedNs,
            capture.trackingFlight) {
            unknownCaptureLive(capture)
        } ?: return
        val processed = consumed.depth.forDisplayAt(SystemClock.elapsedRealtime())
        val completedMs = SystemClock.elapsedRealtime()
        val cameraEpoch = WalkRuntimeEpoch("local-camera-test", capture.epoch.toLong())
        val allOutputs = processed.objects + processed.proximityObjects
        val admitted = capture.feedbackPolicy.admit(
            outputs = allOutputs,
            sourceFrameId = result.token.frameId,
            sourceTimestampMs = result.token.cameraTimestampNs / 1_000_000L,
            sourceCapturedAtElapsedRealtimeNs = result.token.capturedElapsedNs,
            completedAtElapsedRealtimeMs = completedMs,
            sourceEpoch = cameraEpoch,
            currentEpoch = cameraEpoch.takeIf { unknownCaptureLive(capture) && capture.trackingFlight.accepts },
            nowElapsedRealtimeMs = completedMs,
        )
        val candidates = processed.observations.filter { it.walkingSelection?.show == true }
        // Rank display patches and separate measured warnings together, then apply the display cap.
        val presentationCapture = CameraObjectPresentation.Capture(capture.epoch, result.token.frameId,
            result.token.capturedElapsedNs / 1_000_000L, result.token.geometryId)
        val scene = CameraScenePresentation.prepareUnknown(presentationCapture, candidates, allOutputs,
            admitted?.outputs ?: emptyList(), capture.view)
        runOnUiThread {
            if (unknownCaptureLive(capture) && capture.trackingFlight.accepts) {
                if (primaryOverlayCapture?.let {
                        it.geometryId != presentationCapture.geometryId && it.frameId >= presentationCapture.frameId
                    } == true || unknownOverlay?.capture?.frameId?.let { it >= result.token.frameId } == true
                ) return@runOnUiThread
                val measured = processed.observations.count { CameraUnknownObservation.distanceM(it) != null }
                val waiting = processed.observations.firstOrNull { CameraUnknownObservation.distanceM(it) == null }?.depth?.reason
                val publishedOverlay = UnknownOverlay(scene, capture.trackingScope, result.masks.size, candidates.size,
                    "거리 확인 ${measured}개 · 추론 ${result.inferenceMs.toLong()}ms\n" +
                    "3m 이내 보행 위험 조건 충족 시 음성·진동\n" +
                    (processed.rejectionReason?.let { "깊이 대기: $it" }
                        ?: if (measured == 0) "거리 대기: ${waiting ?: "인식 영역 없음"}" else ""))
                if (!unknownUiPresentation.begin(presentationCapture)) return@runOnUiThread
                unknownOverlay = publishedOverlay
                val displayNow = SystemClock.elapsedRealtime()
                renderUnknownObjects(displayNow)
                if (!offerCameraUnknownFeedbackAfterRender(capture.trackingScope, capture.trackingFlight,
                        { unknownOverlay === publishedOverlay }, { unknownCaptureLive(capture) }) {
                    feedback.offer(CameraTestFeedbackCoordinator.Sample(
                        CameraTestFeedbackCoordinator.Source.UNKNOWN, capture.epoch, result.token.frameId,
                        result.token.capturedElapsedNs / 1_000_000L, completedMs, result.token.geometryId,
                        admitted?.outputs ?: emptyList()),
                        retainedUnknownTrackIds = processed.retainedProximityRegionIds,
                        nowMs = SystemClock.elapsedRealtime())
                }) return@runOnUiThread
                updateFeedback()
            }
        }
    }

    /** Recompute presentation when either model finishes; the risk queue retains both sources. */
    private fun renderUnknownObjects(nowMs: Long) {
        discardRetiredUnknownOverlay()
        val observation = unknownOverlay ?: return
        if (observation.capture.epoch != generation ||
            nowMs - observation.capture.capturedAtMs !in 0L..UnknownObjectFeedbackPolicy.MAX_SOURCE_AGE_MS ||
            primaryOverlayCapture?.let { it.geometryId != observation.capture.geometryId } == true
        ) {
            unknownOverlay = null
            unknownUiPresentation.clear()
            unknownBoxes.clear()
            return
        }
        val rendered = CameraScenePresentation.renderUnknown(objectPresentation, observation.scene, nowMs, unknownBoxes) {
                merged, visible -> unknownUiPresentation.shouldRender(observation.capture, merged to visible)
        }
        if (rendered.updated) {
            unknownStatus.setTextIfChanged("FastSAM ${observation.rawCount}개 → 3m 내 후보 ${observation.candidateCount}개 · " +
                "학습 객체와 통합 ${rendered.merged.size}개 · 추가 표시 ${rendered.visibleIndices.size}개\n" + observation.detail)
        }
        unknownUiPresentation.scheduleExpiry(observation.capture,
            observation.predictionDeadlinesMs + (observation.capture.capturedAtMs + UnknownObjectFeedbackPolicy.MAX_SOURCE_AGE_MS),
            nowMs, { unknownOverlay === observation && unknownTrackingLive(observation.trackingScope) }) {
            renderUnknownObjects(SystemClock.elapsedRealtime())
        }
    }

    private fun discardRetiredUnknownOverlay() {
        discardRetiredCameraUnknownOverlay(unknownOverlay?.trackingScope, ::unknownTrackingLive) {
            unknownOverlay = null
            unknownUiPresentation.clear()
            unknownBoxes.clear()
            feedback.clear(CameraTestFeedbackCoordinator.Source.UNKNOWN)
        }
    }

    private fun TextView.setTextIfChanged(value: String) {
        if (!value.contentEquals(text)) text = value
    }

    private fun TrackedObjectDepth?.trustedPresentationDistanceM(): Double? = this?.takeIf {
        it.source.metric && it.confidence.hardGate > 0f && it.confidence.freshnessQuality > 0f
    }?.riskDistanceM?.takeIf { it.isFinite() && it > 0f }?.toDouble()

    private fun point(basis: FloatArray, x: Float, y: Float): Pair<Float, Float> =
        Pair(basis[0] + x * (basis[2] - basis[0]) + y * (basis[4] - basis[0]),
            basis[1] + x * (basis[3] - basis[1]) + y * (basis[5] - basis[1]))

    /** Sparse read of the actual model input image; does not retain or upload camera pixels. */
    private fun meanLuma(image: android.media.Image): Int {
        val plane = image.planes[0]
        val buffer = plane.buffer.duplicate()
        val base = buffer.position()
        var sum = 0L
        var count = 0
        for (y in 0 until image.height step maxOf(1, image.height / 16)) {
            for (x in 0 until image.width step maxOf(1, image.width / 16)) {
                val index = base + y * plane.rowStride + x * plane.pixelStride
                if (index < buffer.limit()) {
                    sum += buffer.get(index).toInt() and 0xff
                    count++
                }
            }
        }
        return if (count > 0) (sum / count).toInt() else 0
    }

    private companion object { val nextCameraSessionEpoch = AtomicLong() }
}

/** Shared with direct tests of the render-clear-feedback-offer ordering in the UI callback. */
internal fun offerCameraUnknownFeedbackAfterRender(
    scope: CameraUnknownMaterialPointScope,
    flight: CameraUnknownMaterialPointFlight,
    hasOriginalOverlay: () -> Boolean,
    isCurrent: (CameraUnknownMaterialPointScope) -> Boolean,
    offer: () -> Unit,
): Boolean {
    if (!scope.accepts || !flight.accepts || !isCurrent(scope) || !hasOriginalOverlay()) return false
    offer()
    return true
}

/** A preprocessing failure must not consume the one log for the first actual native attempt. */
internal class CameraFirstInferenceTimingLog {
    private var emitted = false
    val pending: Boolean get() = !emitted

    fun take(timing: AndroidDetectorLoadTiming): AndroidDetectorLoadTiming? {
        if (emitted || timing.phases.none { it.phase == AndroidDetectorLoadPhase.FIRST_INFERENCE }) return null
        emitted = true
        return timing
    }
}

/** Owns the image until return; the GL caller then owns transfer or close in its existing finally. */
internal data class CameraTestFrameCapture(
    val image: android.media.Image,
    val snapshot: DepthFrameSnapshot,
    val depthError: Throwable?,
    val fullDepthTiming: FullDepthTiming? = null,
)

internal fun captureCameraTestFrame(
    source: ArCoreFrameProvider,
    frame: Frame,
    profileFullDepth: Boolean = false,
): CameraTestFrameCapture? {
    // Select once before acquisition: an unsuccessful TRACKING packet never retries as RGB-only.
    val untracked = frame.camera.trackingState != TrackingState.TRACKING
    val recognition = if (untracked) null else {
        source.acquireRecognitionCameraImageOrNull(frame) ?: return null
    }
    val image = if (untracked) {
        source.acquireUntrackedRecognitionCameraImageOrNull(frame) ?: return null
    } else requireNotNull(recognition).image
    return try {
        if (recognition == null) CameraTestFrameCapture(image, DepthFrameSnapshot(frame.timestamp, null, null, null), null)
        else {
            // Depth is optional evidence; its failure must not discard a usable RGB frame.
            // Synchronous local collection belongs only to this capture/receipt, never a latest frame.
            var fullTiming: FullDepthTiming? = null
            val timingObserver: ((FullDepthTiming) -> Unit)? = if (profileFullDepth) {
                { timing -> fullTiming = timing }
            } else null
            val depthResult = runCatching {
                source.acquireDepthSnapshot(frame, cameraImageTimestampEvidence = recognition.timestampEvidence,
                    includeFullDepthWhenRawAvailable = true, fullDepthTimingObserver = timingObserver)
            }
            val snapshot = depthResult.getOrElse { DepthFrameSnapshot(frame.timestamp, null, null, null) }
            CameraTestFrameCapture(image, snapshot, depthResult.exceptionOrNull(), fullTiming)
        }
    } catch (error: Throwable) {
        source.invalidateRecognitionCameraImageTimestamps()
        image.close()
        throw error
    }
}

/** Debug request costs only; null phases must not masquerade as measured zero milliseconds. */
internal fun cameraFullDepthTimingDiagnostic(timing: FullDepthTiming): String {
    fun phase(durationNs: Long?, succeeded: Boolean?): String {
        if (succeeded == null) return "미관측"
        val duration = durationNs?.let { "${it / 1_000_000.0}ms" } ?: "시간 미관측"
        return "$duration ${if (succeeded) "성공" else "실패"}"
    }
    return "Full 요청 ${timing.purpose} · 획득 ${phase(timing.acquisitionDurationNs, timing.acquisitionSucceeded)} · " +
        "변환 ${phase(timing.conversionDurationNs, timing.conversionSucceeded)} · " +
        "종료 ${phase(timing.closeDurationNs, timing.closeSucceeded)}"
}

/** Accepted work keeps its image; only a capture that failed to transfer is released by GL. */
internal fun releaseUntransferredCameraTestFrame(
    source: ArCoreFrameProvider,
    image: android.media.Image?,
    busy: AtomicBoolean,
    transferred: Boolean,
) {
    if (!transferred) {
        if (image != null) source.invalidateRecognitionCameraImageTimestamps()
        image?.close()
        busy.set(false)
    }
}
