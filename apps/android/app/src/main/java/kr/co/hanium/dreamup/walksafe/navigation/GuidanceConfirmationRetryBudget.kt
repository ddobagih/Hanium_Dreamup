package kr.co.hanium.dreamup.walksafe.navigation

/** One automatic retry per explicit attempt; silence must never create an endless TTS loop. */
internal class GuidanceConfirmationRetryBudget {
    private var automaticRetries = 0

    fun beginExplicitAttempt() { automaticRetries = 0 }

    fun allowAutomaticRetry(): Boolean {
        if (automaticRetries >= 1) return false
        automaticRetries++
        return true
    }
}
