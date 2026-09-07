package kr.co.hanium.dreamup.walksafe.inference

import android.util.Log
import kr.co.hanium.dreamup.walksafe.BuildConfig

/** Debug-only model metadata. Never pass exception messages, images, or account data here. */
internal object DetectorLoadDiagnostics {
    fun record(event: String, details: String = "", error: Throwable? = null) {
        if (!BuildConfig.DEBUG) return
        // Diagnostics must not change loading behavior, including in local JVM environments.
        runCatching {
            val failure = error?.let {
                " error_class=${it.javaClass.simpleName}" +
                    " cause_class=${it.cause?.javaClass?.simpleName ?: "none"}"
            }.orEmpty()
            Log.i("WalkSafeDetectorLoad", "event=$event $details$failure".trimEnd())
        }
    }
}
