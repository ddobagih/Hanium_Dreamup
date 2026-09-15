package kr.co.hanium.dreamup.walksafe.depth

/** Camera timestamps and elapsed time have different origins; transfer only the remaining duration. */
object DepthPredictionPresentation {
    fun validUntilMs(prediction: PredictedDepthEstimate, capturedAtElapsedMs: Long): Long? {
        if (capturedAtElapsedMs < 0L || prediction.horizonMs <= 0L ||
            prediction.predictionAgeMs !in 0L..prediction.horizonMs) return null
        val remaining = prediction.horizonMs - prediction.predictionAgeMs
        if (capturedAtElapsedMs > Long.MAX_VALUE - remaining) return null
        return capturedAtElapsedMs + remaining
    }

    fun isCurrent(prediction: PredictedDepthEstimate, capturedAtElapsedMs: Long, nowElapsedMs: Long): Boolean =
        nowElapsedMs >= capturedAtElapsedMs &&
            validUntilMs(prediction, capturedAtElapsedMs)?.let { nowElapsedMs <= it } == true
}
