package kr.co.hanium.dreamup.walksafe.startup.interaction

/** Retains the original eager candidate construction and ordered launch attempts. */
internal class StartupSettingsNavigation<I>(private val native: StartupSettingsNative<I>) {
    fun openWithApplicationFallback(target: StartupSettingsTarget) {
        val intent = when (target) {
            StartupSettingsTarget.APPLICATION -> native.applicationSettings()
            StartupSettingsTarget.LOCATION -> native.locationSettings()
            StartupSettingsTarget.DEVELOPMENT -> native.developmentSettings()
        }
        runCatching { native.start(intent) }.onFailure {
            runCatching { native.start(native.applicationSettings()) }
        }
    }

    fun openVoiceCandidates(preferEngine: Boolean): Boolean {
        val installVoiceData = native.installVoiceData()
        val chooseEngine = native.chooseVoiceEngine()
        val candidates = listOf(
            if (preferEngine) chooseEngine else installVoiceData,
            if (preferEngine) installVoiceData else chooseEngine,
            native.applicationSettings(),
        )
        return candidates.any { intent ->
            runCatching {
                native.start(intent)
                true
            }.getOrDefault(false)
        }
    }
}
