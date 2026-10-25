package kr.co.hanium.dreamup.walksafe.startup.interaction

import java.util.Locale
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.device.postlogin.PostLoginLocationFixTerminalFailure
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.voice.*
import kr.co.hanium.dreamup.walksafe.ui.DeviceCheckPresenter

/** Owns Korean speech probe recovery and settings return while retaining live measured attempts. */
internal class VoiceDataSettingsRecoveryController(
    private val probe: VoiceDataProbePort,
    private val facts: VoiceSettingsFacts,
    private val effects: VoiceSettingsEffects,
    private val settings: StartupSettingsNavigation<*>,
) {
    private var recheckPending = false
    private var recheckInFlight = false

    /** A restored device check can leave a closed/unstarted probe PENDING. Start the actual
     * synthesis check for this Home request; actuator readiness never supplies its result.
     * Keep a running attempt and an already measured terminal result intact.
     */
    fun ensurePendingKoreanSpeechProbeStarted(): Boolean {
        if (!probe.initialized || probe.started ||
            probe.snapshot().offlineKoreanTextToSpeechAvailable != null) return false
        return runCatching { startReplacedProbe(); true }.getOrElse {
            probe.started = false
            probe.close()
            effects.status("한국어 음성 준비 확인 실패", "음성 점검을 시작하지 못했습니다. 안내 준비를 다시 시도해 주세요.")
            false
        }
    }

    private fun startReplacedProbe() {
        probe.close()
        probe.replace {
            completeVoiceDataInstallRecheckIfPossible()
            effects.refresh()
        }
        probe.started = true
        probe.start()
    }

    fun openPostLoginDeviceCheckSettings() {
        val failure = facts.snapshot.failure
        if (failure == PostLoginDeviceCheckFailure.KOREAN_TTS_UNAVAILABLE) {
            // Same cause-specific screen and return recheck as the voice-data button.
            openVoiceDataInstallSettings()
            return
        }
        val target = when (failure) {
            PostLoginDeviceCheckFailure.LOCATION_SERVICE_DISABLED ->
                StartupSettingsTarget.LOCATION
            PostLoginDeviceCheckFailure.LOCATION_FIX_UNAVAILABLE -> when {
                facts.locationTerminalFailure ==
                    PostLoginLocationFixTerminalFailure.REQUEST_FAILED ->
                    StartupSettingsTarget.APPLICATION
                facts.locationReason ==
                    DeviceCheckLocationFixReason.MOCK_LOCATION ->
                    StartupSettingsTarget.DEVELOPMENT
                else -> StartupSettingsTarget.LOCATION
            }
            else -> if (
                facts.snapshot.passesFeatureGate &&
                facts.requiredPermissions().isEmpty() &&
                !facts.locationServiceEnabled()
            ) {
                StartupSettingsTarget.LOCATION
            } else {
                StartupSettingsTarget.APPLICATION
            }
        }
        settings.openWithApplicationFallback(target)
    }

    /** S13-15: install voice data vs choose another preferred engine; never a network voice or auto switch. */
    fun offlineKoreanSpeechGuidance(): OfflineKoreanSpeechGuidance =
        OfflineKoreanSpeechGuidancePolicy.guidance(
            if (probe.initialized) {
                probe.unavailableCause()
            } else {
                null
            },
        )

    fun openVoiceDataInstallSettings() {
        val guidance = offlineKoreanSpeechGuidance()
        val opened = settings.openVoiceCandidates(
            guidance.settingsAction == OfflineKoreanSpeechSettingsAction.CHOOSE_PREFERRED_ENGINE,
        )
        if (opened) {
            recheckPending = true
            recheckInFlight = false
            effects.status(guidance.actionLabel, guidance.openedDetail)
            return
        }
        val detail = "휴대폰 설정에서 한국어 음성 데이터 설치 화면을 열 수 없습니다."
        effects.status("음성 설정을 열 수 없음", detail)
        effects.speak(detail)
    }

    fun recheckVoiceDataAfterSettingsIfNeeded() {
        if (
            !recheckPending ||
            recheckInFlight ||
            !probe.initialized
        ) return
        recheckInFlight = true
        effects.offlineTtsOverride = null
        val started = runCatching {
            if (probe.started) {
                probe.recheck()
            } else {
                startReplacedProbe()
            }
        }.isSuccess
        if (!started) {
            probe.started = false
            probe.close()
            recheckPending = false
            recheckInFlight = false
            effects.status(
                "한국어 음성 데이터 확인 실패",
                "음성 데이터 상태를 다시 확인하지 못했습니다. 설치 버튼을 눌러 다시 시도하세요.",
            )
            return
        }
        if (recheckPending && recheckInFlight) {
            effects.status(
                "한국어 음성 데이터 확인 중",
                "설치된 한국어 음성으로 실제 안내 파일을 만들 수 있는지 확인하고 있습니다.",
            )
        }
    }

    fun completeVoiceDataInstallRecheckIfPossible() {
        if (!recheckPending || !recheckInFlight) return
        val available = probe.snapshot()
            .offlineKoreanTextToSpeechAvailable ?: return
        recheckPending = false
        recheckInFlight = false
        if (!available) {
            effects.status(
                "한국어 음성 데이터 확인 필요",
                "한국어 오프라인 음성 안내를 아직 사용할 수 없습니다. ${offlineKoreanSpeechGuidance().detail}",
            )
            return
        }

        if (!effects.recoverPassedGate()) {
            effects.status("음성 기능 복구 저장 실패", "한국어 음성 데이터는 확인했지만 점검 결과를 저장하지 못했습니다. 다시 시도하세요.")
            return
        }
        effects.offlineTtsOverride = true
        effects.status(
            "한국어 음성 재점검 완료",
            "설치된 한국어 음성으로 실제 안내 생성을 다시 확인했습니다. 기기 점검 결과를 확인한 뒤 보행을 시작하거나 재개하세요.",
        )
    }
}
