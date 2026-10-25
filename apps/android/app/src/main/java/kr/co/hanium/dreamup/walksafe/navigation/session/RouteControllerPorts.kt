package kr.co.hanium.dreamup.walksafe.navigation.session

/** Effects deliberately retain the original Android queue and callback identity. */
internal interface RouteCallbackScheduler {
    fun post(task: Runnable)
    fun postDelayed(task: Runnable, delayMs: Long)
    fun remove(task: Runnable)
}

internal interface RoutePresentation {
    fun status(title: String, detail: String)
    fun navigationStatus(text: String)
    fun speak(message: String)
    fun speakStatus(message: String)
    fun updateRouteButton()
    fun safetyStop(reason: String)
}
