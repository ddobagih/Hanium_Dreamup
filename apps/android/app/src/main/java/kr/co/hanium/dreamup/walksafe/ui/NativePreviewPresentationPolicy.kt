package kr.co.hanium.dreamup.walksafe.ui

import kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingStage
import kr.co.hanium.dreamup.walksafe.device.PostLoginDeviceCheckState
import kr.co.hanium.dreamup.walksafe.session.AccountDeletionPhase
import kr.co.hanium.dreamup.walksafe.ui.workflow.NativeWorkflowController.NativeUiPage

/** Pure display decisions, separated from the ordered, potentially state-changing queries below. */
internal object NativePreviewPresentationPolicy {
    data class Input(
        val homeAvailable: Boolean,
        val homeVisible: Boolean,
        val settingsVisible: Boolean,
        val forceSettings: Boolean,
        val stage: FirstRunOnboardingStage,
        val accountPanelVisible: Boolean,
        val training: Boolean,
        val deviceState: PostLoginDeviceCheckState,
        val page: NativeUiPage,
        val pendingPage: NativeUiPage?,
        val runtimeActive: Boolean = false,
        val devicePassesFeatureGate: Boolean = true,
        val prewalkPending: Boolean,
        val developmentBypass: Boolean,
        val cameraEnabled: Boolean = false,
        val voiceAvailable: Boolean = false,
        val destinationAvailable: Boolean = false,
        val deletionPhase: AccountDeletionPhase = AccountDeletionPhase.IDLE,
    )

    fun plan(input: Input) = Plan(input)

    /** Immutable-fact entry point, also useful when a caller already owns a coherent snapshot. */
    fun project(input: Input): NativeScreenPresenter.PreviewFacts =
        NativePreviewPresentationReader.read(input, object : NativePreviewPresentationReader.Queries {
            override fun runtimeActive() = input.runtimeActive
            override fun devicePassesFeatureGate() = input.devicePassesFeatureGate
            override fun cameraEnabled() = input.cameraEnabled
            override fun featureAvailable(page: NativeUiPage) = when (page) {
                NativeUiPage.VOICE_COMMAND -> input.voiceAvailable
                else -> input.destinationAvailable
            }
            override fun deletionPhase() = input.deletionPhase
        })

    class Plan internal constructor(private val input: Input) {
        val accountVisible = input.accountPanelVisible && !input.homeAvailable && !input.forceSettings
        val purpose = input.stage == FirstRunOnboardingStage.PURPOSE_AND_SAFETY && !accountVisible
        val guidanceVisible = input.homeAvailable && input.page == NativeUiPage.GUIDANCE &&
            !input.settingsVisible && !input.forceSettings
        val deviceRuntimeRequired = !guidanceVisible && !accountVisible && !purpose && !input.settingsVisible
        private val deviceStage = input.stage in setOf(
            FirstRunOnboardingStage.JIT_PERMISSION_OBSERVATION, FirstRunOnboardingStage.DEVICE_CHECK,
        )
        val deviceFeatureGateRequired = !deviceStage && (input.training || input.pendingPage != null ||
            input.homeVisible || input.page in setOf(NativeUiPage.DESTINATION_SEARCH, NativeUiPage.VOICE_COMMAND))
        val preparationRuntimeRequired = input.prewalkPending || input.pendingPage == NativeUiPage.DESTINATION_CONFIRM
        fun deviceScreen(runtimeInactive: Boolean, featureGatePassed: Boolean) =
            runtimeInactive && (deviceStage || deviceFeatureGateRequired && !featureGatePassed)
        fun physicalPreparation(preparing: Boolean, deviceScreen: Boolean) =
            !guidanceVisible && input.homeAvailable && preparing && !input.developmentBypass &&
                !input.settingsVisible && !accountVisible && !purpose && !deviceScreen
        fun limitedHomeRuntimeRequired(deviceScreen: Boolean) = input.homeVisible && !deviceScreen

        fun finish(deviceScreen: Boolean, preparing: Boolean, mounting: Boolean, limitedHome: Boolean,
            deletionPhase: AccountDeletionPhase): NativeScreenPresenter.PreviewFacts =
            NativeScreenPresenter.PreviewFacts(
                homeAvailable = input.homeAvailable, homeVisible = input.homeVisible,
                settingsVisible = input.settingsVisible, forceSettings = input.forceSettings,
                accountVisible = accountVisible, purpose = purpose, training = input.training,
                checking = input.deviceState in setOf(PostLoginDeviceCheckState.RUNNING,
                    PostLoginDeviceCheckState.REQUESTING_PERMISSIONS, PostLoginDeviceCheckState.SAVING),
                guidanceVisible = guidanceVisible, deviceScreen = deviceScreen,
                deviceDetails = deviceScreen && input.deviceState != PostLoginDeviceCheckState.NOT_RUN,
                preparing = preparing, showPhysicalPreparation = physicalPreparation(preparing, deviceScreen),
                showMountingPreparation = mounting, limitedHome = limitedHome, deletionPhase = deletionPhase,
            )
    }
}

/** Preserves short-circuit query order: runtimeActive may expire a gateway lease and its pending work. */
internal object NativePreviewPresentationReader {
    interface Queries {
        fun runtimeActive(): Boolean
        fun devicePassesFeatureGate(): Boolean
        fun cameraEnabled(): Boolean
        fun featureAvailable(page: NativeUiPage): Boolean
        fun deletionPhase(): AccountDeletionPhase
    }

    fun read(input: NativePreviewPresentationPolicy.Input, queries: Queries): NativeScreenPresenter.PreviewFacts {
        val plan = NativePreviewPresentationPolicy.plan(input)
        val deviceRuntimeInactive = plan.deviceRuntimeRequired && !queries.runtimeActive()
        val featureGatePassed = !deviceRuntimeInactive || !plan.deviceFeatureGateRequired ||
            queries.devicePassesFeatureGate()
        val deviceScreen = plan.deviceScreen(deviceRuntimeInactive, featureGatePassed)
        val preparing = plan.preparationRuntimeRequired && !queries.runtimeActive()
        val mounting = plan.physicalPreparation(preparing, deviceScreen) && queries.cameraEnabled()
        val limitedHome = plan.limitedHomeRuntimeRequired(deviceScreen) && queries.runtimeActive() &&
            (!queries.featureAvailable(NativeUiPage.VOICE_COMMAND) ||
                !queries.featureAvailable(NativeUiPage.DESTINATION_SEARCH))
        return plan.finish(deviceScreen, preparing, mounting, limitedHome, queries.deletionPhase())
    }
}
