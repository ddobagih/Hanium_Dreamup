package kr.co.hanium.dreamup.walksafe.voice.input

import java.io.File
import java.util.UUID
import java.util.concurrent.RejectedExecutionException
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.navigation.RouteNavigatorDecisionToken
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import kr.co.hanium.dreamup.walksafe.session.WalkSessionState

/** Owns one gateway recording/transcription/playback interaction and its temporary resources. */
internal class GatewayVoiceCaptureController(
    private val environment: VoiceInputEnvironment,
    private val gatewayEnvironment: GatewayVoiceEnvironment,
    private val inputSession: GatewayVoiceInputSession,
    private val output: VoiceInputOutput,
    private val presentation: VoiceInputPresentation,
    private val platform: GatewayVoicePlatform,
    private val scheduler: VoiceInputScheduler,
) {
    private val gatewaySpeechNetworkGate = GatewaySpeechNetworkGate(gatewayEnvironment::networkState)
    private var gatewayVoiceRecorder: GatewayVoiceRecorder? = null
    private var gatewaySpeechPlayer: GatewayVoicePlayer? = null
    private var gatewaySpeechInteractionGeneration = 0L
    @Volatile private var activeGatewaySpeechInteraction: ActiveGatewaySpeechInteraction? = null
    internal data class ActiveGatewaySpeechInteraction(
        val fence: GatewaySpeechCallbackFence,
        val session: GatewayFieldSession,
        val networkTicket: GatewaySpeechNetworkGate.Ticket,
        val expectedWalkEpoch: WalkRuntimeEpoch,
        val expectedNavigationDecisionToken: RouteNavigatorDecisionToken?,
    ) {
        // A retired process callback may only cancel this interaction's worker resources.
        @Volatile var uploadFile: File? = null
        @Volatile var speechCall: CancellableNetworkCall<*>? = null
    }
    fun isRecording(): Boolean = gatewayVoiceRecorder?.isRecording == true
    fun hasInteraction(): Boolean = activeGatewaySpeechInteraction != null
    fun hasPlayer(): Boolean = gatewaySpeechPlayer != null
    fun shutdownWorker() = platform.shutdownWorker()
    fun markNetworkPolicyChanged() = gatewaySpeechNetworkGate.onNetworkPolicyChanged()
    fun cancelForNetworkPolicyIfNeeded() {
        scheduler.runOnMain {
            val active = activeGatewaySpeechInteraction
            if (active != null && !gatewaySpeechNetworkGate.isCurrent(active.networkTicket)) {
                cancelGatewaySpeechInteraction("network_policy_changed")
            }
        }
    }
    fun onProcessSessionChanged(snapshot: GatewaySessionProcessSnapshot) {
        activeGatewaySpeechInteraction?.let { active ->
            // A delayed process event predating this interaction cannot revoke its newer session.
            if (snapshot.generation < active.fence.sessionGeneration) return
            if (active.fence.sessionGeneration != snapshot.generation ||
                active.fence.actorId != snapshot.session?.actorId ||
                active.fence.sessionInstanceId != snapshot.session?.lease?.instanceId) {
                if (scheduler.isMainThread()) {
                    if (activeGatewaySpeechInteraction === active) {
                        cancelGatewaySpeechInteraction("gateway_session_changed")
                    }
                } else {
                    active.speechCall?.cancel()
                    active.uploadFile?.delete()
                    scheduler.runOnMain {
                        if (activeGatewaySpeechInteraction === active) {
                            cancelGatewaySpeechInteraction("gateway_session_changed")
                        }
                    }
                }
            }
        }
    }

    /** Explicit foreground button capture only; this is not a 길라잡이 wake-word listener. */
    fun toggleGatewayVoiceCapture() {
        if (gatewayVoiceRecorder?.isRecording == true) {
            stopGatewayVoiceCapture()
        } else {
            startGatewayVoiceCapture()
        }
    }

    fun startGatewayVoiceCapture(): Boolean {
        if (activeGatewaySpeechInteraction != null || inputSession.recognitionActive()) return false
        if (!gatewayEnvironment.networkAllowed("server_speech")) return false
        val (session, sessionGeneration) = currentGatewaySpeechSessionOrNull() ?: return false
        val networkTicket = gatewaySpeechNetworkGate.capture() ?: return false
        val walk = requireNotNull(environment.walkOrNull())
        if (
            !walk.isForeground ||
            walk.state !in setOf(WalkSessionState.ACTIVE, WalkSessionState.PAUSED)
        ) return false
        if (!output.canSpeakNavigation(scheduler.nowMs())) return false
        if (inputSession.recognitionBlocked() ||
            output.prepareForRecognition() == false
        ) {
            presentation.voiceStatus("음성 안내가 끝난 뒤 녹음 버튼을 다시 눌러 주세요.")
            return false
        }
        inputSession.stopHandsFree()
        val interactionGeneration = ++gatewaySpeechInteractionGeneration
        val active = ActiveGatewaySpeechInteraction(
            fence = GatewaySpeechCallbackFence(
                actorId = session.actorId,
                sessionGeneration = sessionGeneration,
                sessionInstanceId = session.lease.instanceId,
                interactionGeneration = interactionGeneration,
            ),
            session = session,
            networkTicket = networkTicket,
            expectedWalkEpoch = walk.epoch,
            expectedNavigationDecisionToken = environment.pendingNavigationDecisionToken(),
        )
        val recorder = platform.createRecorder(
            onLimitReached = { scheduler.runOnMain { stopGatewayVoiceCapture(interactionGeneration) } },
            onInputFailure = { failure -> scheduler.runOnMain {
                if (activeGatewaySpeechInteraction !== active) return@runOnMain
                inputSession.inputFailed(failure)
                cancelGatewaySpeechInteraction("microphone_${failure.name.lowercase()}")
                presentation.voiceStatus(failure.guidance)
                output.speakInteraction(failure.guidance)
            } },
        )
        activeGatewaySpeechInteraction = active
        gatewayVoiceRecorder = recorder
        val started = recorder.start()
        if (activeGatewaySpeechInteraction !== active) return false
        if (!started) {
            activeGatewaySpeechInteraction = null
            gatewayVoiceRecorder = null
            gatewaySpeechInteractionGeneration += 1L
            presentation.voiceStatus("녹음을 시작하지 못했습니다.")
            output.speakInteraction("음성 녹음을 시작하지 못했습니다.")
            presentation.refreshControls(active = false)
            inputSession.scheduleRestart()
            return false
        }
        if (activeGatewaySpeechInteraction !== active || !recorder.isRecording) return false
        runCatching { output.playListeningStarted() }
        inputSession.invalidateHomeWakeInput()
        inputSession.setRecordingInputActive(true)
        presentation.voiceStatus("녹음 중입니다. 버튼을 다시 눌러 전송하세요.")
        presentation.refreshControls(active = true)
        presentation.navigationStatus("voice=gateway_recording")
        return true
    }

    fun stopGatewayVoiceCapture(expectedInteractionGeneration: Long? = null) {
        val active = activeGatewaySpeechInteraction ?: return
        if (
            expectedInteractionGeneration != null &&
            active.fence.interactionGeneration != expectedInteractionGeneration
        ) return
        val recorder = gatewayVoiceRecorder ?: return
        gatewayVoiceRecorder = null
        inputSession.setRecordingInputActive(false)
        val audioFile = recorder.stop()
        if (activeGatewaySpeechInteraction !== active) { audioFile?.delete(); return }
        runCatching { output.playListeningEnded() }
        if (audioFile == null) {
            finishGatewaySpeechInteraction(active)
            presentation.voiceStatus("녹음이 너무 짧거나 올바르지 않습니다.")
            output.speakInteraction("음성을 녹음하지 못했습니다. 버튼을 눌러 다시 말씀해 주세요.")
            return
        }
        presentation.voiceStatus("음성을 처리하고 있습니다.")
        presentation.refreshControls(active = false)
        submitGatewayVoiceRecording(active, audioFile)
    }

    fun submitGatewayVoiceRecording(
        active: ActiveGatewaySpeechInteraction,
        audioFile: File,
    ) {
        if (!isGatewaySpeechInteractionCurrent(active)) {
            audioFile.delete()
            finishGatewaySpeechInteraction(active)
            return
        }
        val requestId = UUID.randomUUID().toString()
        val call = runCatching {
            gatewaySpeechNetworkGate.bind(
                active.networkTicket,
                platform.transcribe(active.session, audioFile, requestId),
            )
        }.getOrElse {
            audioFile.delete()
            finishGatewaySpeechInteraction(active)
            output.speakInteraction("서버 음성 인식을 시작하지 못했습니다. 잠시 뒤 다시 시도해 주세요.")
            return
        }
        active.uploadFile = audioFile
        active.speechCall = call
        try {
            platform.execute {
                val result = runCatching { call.execute() }
                audioFile.delete()
                scheduler.runOnMain {
                    if (active.uploadFile === audioFile) active.uploadFile = null
                    if (active.speechCall === call) active.speechCall = null
                    if (!isGatewaySpeechInteractionCurrent(active)) return@runOnMain
                    result.onSuccess { transcript ->
                        handleGatewaySpeechTranscript(active, transcript)
                    }.onFailure {
                        if (call.isCancelled()) return@onFailure
                        finishGatewaySpeechInteraction(active)
                        presentation.voiceStatus("서버 음성 인식을 사용할 수 없습니다.")
                        output.speakInteraction("서버 음성 인식을 사용할 수 없습니다. 잠시 뒤 다시 시도해 주세요.")
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            call.cancel()
            audioFile.delete()
            if (active.uploadFile === audioFile) active.uploadFile = null
            finishGatewaySpeechInteraction(active)
        }
    }

    fun handleGatewaySpeechTranscript(
        active: ActiveGatewaySpeechInteraction,
        speechTranscript: GatewaySpeechTranscript,
    ) {
        if (!speechTranscript.acoustic.executionAllowed) {
            presentation.voiceStatus("음성 품질이 낮아 명령을 실행하지 않았습니다.")
            speakGatewayInteractionOrLocalFallback(
                "음성을 확실히 확인하지 못했습니다. 버튼을 눌러 다시 말씀해 주세요.",
                active,
            )
            return
        }
        finishGatewaySpeechInteraction(active)
        presentation.voiceStatus("음성 명령을 로컬 안전 규칙으로 확인했습니다.")
        inputSession.recognized(RecognizedVoiceInput(listOf(speechTranscript.transcript),
            listOf(speechTranscript.acoustic.confidence.toFloat()), active.expectedNavigationDecisionToken,
            false, "gateway_stt"))
    }

    fun speakGatewayInteractionOrLocalFallback(
        message: String,
        active: ActiveGatewaySpeechInteraction,
    ) {
        if (!isGatewaySpeechInteractionCurrent(active) || output.screenReaderActive()) {
            finishGatewaySpeechInteraction(active)
            output.speakInteraction(message)
            return
        }
        val requestId = UUID.randomUUID().toString()
        val call = runCatching {
            gatewaySpeechNetworkGate.bind(
                active.networkTicket,
                platform.synthesize(active.session, message, requestId),
            )
        }.getOrElse {
            finishGatewaySpeechInteraction(active)
            output.speakInteraction(message)
            return
        }
        active.speechCall = call
        presentation.voiceStatus("음성 안내를 준비하고 있습니다.")
        try {
            platform.execute {
                val result = runCatching { call.execute() }
                scheduler.runOnMain {
                    if (active.speechCall === call) active.speechCall = null
                    val audio = result.getOrNull()
                    if (!isGatewaySpeechInteractionCurrent(active)) {
                        audio?.temporaryFile?.delete()
                        return@runOnMain
                    }
                    if (audio == null) {
                        if (call.isCancelled()) return@runOnMain
                        finishGatewaySpeechInteraction(active)
                        output.speakInteraction(message)
                        return@runOnMain
                    }
                    presentation.voiceStatus("서버 음성 안내를 재생하고 있습니다.")
                    val player = platform.createPlayer()
                    inputSession.invalidateHomeWakeInput()
                    gatewaySpeechPlayer = player
                    val started = player.play(
                        temporaryFile = audio.temporaryFile,
                        onCompleted = {
                            if (releaseGatewaySpeechPlayerIfOwned(player)) {
                                finishGatewaySpeechInteraction(active)
                            }
                        },
                        onFailed = {
                            if (releaseGatewaySpeechPlayerIfOwned(player)) {
                                val wasCurrent = isGatewaySpeechInteractionCurrent(active)
                                finishGatewaySpeechInteraction(active)
                                if (wasCurrent) output.speakInteraction(message)
                            }
                        },
                    )
                    if (!started && releaseGatewaySpeechPlayerIfOwned(player)) {
                        val wasCurrent = isGatewaySpeechInteractionCurrent(active)
                        finishGatewaySpeechInteraction(active)
                        if (wasCurrent) output.speakInteraction(message)
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            call.cancel()
            finishGatewaySpeechInteraction(active)
            output.speakInteraction(message)
        }
    }

    /** Terminal events release only their own player, including an obsolete interaction. */
    fun releaseGatewaySpeechPlayerIfOwned(player: GatewayVoicePlayer): Boolean {
        if (gatewaySpeechPlayer !== player) return false
        gatewaySpeechPlayer = null
        return true
    }

    fun currentGatewaySpeechSessionOrNull(): Pair<GatewayFieldSession, Long>? {
        if (!gatewayEnvironment.sessionPrerequisitesAllowed()) return null
        val process = gatewayEnvironment.processSnapshot()
        val session = process.session?.takeUnless { process.deletionRecoveryOnly } ?: return null
        if (
            process.storageBlocked ||
            environment.identity().actorId != session.actorId ||
            !session.isUsableFor(environment.identity().actorId)
        ) return null
        return session to process.generation
    }

    fun isGatewaySpeechInteractionCurrent(
        active: ActiveGatewaySpeechInteraction,
    ): Boolean {
        if (activeGatewaySpeechInteraction !== active) return false
        if (!gatewaySpeechNetworkGate.isCurrent(active.networkTicket)) return false
        val process = gatewayEnvironment.processSnapshot()
        return requireNotNull(environment.walkOrNull()).let { walk ->
            walk.epoch == active.expectedWalkEpoch &&
                walk.state in setOf(WalkSessionState.ACTIVE, WalkSessionState.PAUSED) &&
                isGatewaySpeechCallbackCurrent(
                    expected = active.fence,
                    currentActorId = process.session?.actorId,
                    currentSessionGeneration = process.generation,
                    currentSessionInstanceId = process.session?.lease?.instanceId,
                    currentInteractionGeneration = gatewaySpeechInteractionGeneration,
                    foreground = environment.foreground() && walk.isForeground,
                    featureGatePassed = currentGatewaySpeechSessionOrNull() != null,
                )
        }
    }

    fun finishGatewaySpeechInteraction(active: ActiveGatewaySpeechInteraction) {
        if (activeGatewaySpeechInteraction !== active) return
        activeGatewaySpeechInteraction = null
        gatewayVoiceRecorder = null
        active.uploadFile?.delete()
        active.uploadFile = null
        active.speechCall = null
        gatewaySpeechPlayer = null
        gatewaySpeechInteractionGeneration += 1L
        inputSession.setRecordingInputActive(false)
        presentation.refreshControls(active = false)
        inputSession.scheduleRestart()
    }

    fun cancelGatewaySpeechInteraction(reason: String) {
        val active = activeGatewaySpeechInteraction
        if (
            active == null &&
            gatewayVoiceRecorder == null &&
            gatewaySpeechPlayer == null
        ) return
        gatewaySpeechInteractionGeneration += 1L
        val recorder = gatewayVoiceRecorder
        val wasRecording = recorder?.isRecording == true
        gatewayVoiceRecorder = null
        recorder?.cancel()
        if (wasRecording) {
            runCatching { output.playListeningEnded() }
        }
        active?.uploadFile?.delete()
        active?.uploadFile = null
        active?.speechCall?.cancel()
        active?.speechCall = null
        gatewaySpeechPlayer?.cancel()
        gatewaySpeechPlayer = null
        activeGatewaySpeechInteraction = null
        inputSession.setRecordingInputActive(false)
        presentation.voiceStatus("서버 음성 작업이 취소되었습니다.")
        presentation.navigationStatus("voice=gateway_cancelled reason=$reason")
        presentation.refreshControls(active = false)
        if (!reason.startsWith("microphone_")) inputSession.scheduleRestart()
    }
}
