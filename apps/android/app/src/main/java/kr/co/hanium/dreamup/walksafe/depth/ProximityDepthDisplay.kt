package kr.co.hanium.dreamup.walksafe.depth

import java.util.Locale

/** Presentation of the already selected distance; it does not recalculate range or renew a source. */
internal object ProximityDepthDisplay {
    /** A rendered number always gets a recheck, even if UI work exhausted its last millisecond. */
    fun expiryDelayMs(displayedNumericDepth: Boolean, remainingMsAtScheduling: Long?): Long? =
        if (!displayedNumericDepth) null else (remainingMsAtScheduling?.plus(1L) ?: 0L)

    /** Live status only; the original diagnostic formatter and its captured output stay intact. */
    fun summaryFor(
        output: TrackedObjectDepth,
        capturedAtElapsedRealtimeMs: Long?,
        displayAtElapsedRealtimeMs: Long,
    ): String {
        if (distanceFor(output, capturedAtElapsedRealtimeMs, displayAtElapsedRealtimeMs) != null) {
            return output.debugSummaryText()
        }
        val hadNumericGuidance = output.riskDistanceM != null || output.userFacing.stepsAhead != null
        return output.copy(
            riskDistanceM = null,
            userFacing = output.userFacing.copy(
                stepsAhead = null,
                message = if (hadNumericGuidance) null else output.userFacing.message,
            ),
        ).debugSummaryText()
    }

    fun geometryFor(
        output: TrackedObjectDepth,
        capturedAtElapsedRealtimeMs: Long?,
        displayAtElapsedRealtimeMs: Long,
    ): String {
        val median = output.depthMedianM?.takeIf {
            it.isFinite() && it > 0f && distanceFor(output, capturedAtElapsedRealtimeMs, displayAtElapsedRealtimeMs) != null
        }
        return "bbox: c=(${String.format(Locale.US, "%.2f", output.centerNorm.x)},${String.format(Locale.US, "%.2f", output.centerNorm.y)}) " +
            "wh=(${String.format(Locale.US, "%.2f", output.bboxNorm.width)},${String.format(Locale.US, "%.2f", output.bboxNorm.height)}) · " +
            "samples=${output.validSampleCount} ratio=${String.format(Locale.US, "%.0f%%", output.validSampleRatio * 100f)} " +
            "median=${median?.let { String.format(Locale.US, "%.2fm", it) } ?: "-"}"
    }

    fun distanceFor(
        output: TrackedObjectDepth,
        capturedAtElapsedRealtimeMs: Long?,
        displayAtElapsedRealtimeMs: Long,
    ): Float? = output.riskDistanceM.takeIf {
        remainingMs(output, capturedAtElapsedRealtimeMs, displayAtElapsedRealtimeMs) != null
    }

    /** Inclusive display boundary; the UI removes the distance after this duration plus one ms. */
    fun remainingMs(
        output: TrackedObjectDepth,
        capturedAtElapsedRealtimeMs: Long?,
        displayAtElapsedRealtimeMs: Long,
    ): Long? {
        val distance = output.riskDistanceM ?: return null
        if (!output.source.metric || !distance.isFinite() || distance <= 0f) return null
        val capturedAtMs = capturedAtElapsedRealtimeMs ?: return null
        val depthObservedAtMs = output.depthObservedAtMs ?: return null
        if (capturedAtMs < 0L || displayAtElapsedRealtimeMs < capturedAtMs ||
            depthObservedAtMs < 0L || output.timestampMs < depthObservedAtMs) return null

        // Only a same-frame time difference crosses clocks. Camera and elapsed timestamps never
        // compare directly, and a later frame cannot issue a new timestamp for independent depth.
        val depthAgeAtCaptureMs = output.timestampMs - depthObservedAtMs
        if (depthAgeAtCaptureMs > capturedAtMs) return null
        val normalizedDepthAtMs = capturedAtMs - depthAgeAtCaptureMs
        val suppliedObservedAtMs = output.sourceObservedAtElapsedRealtimeMs
        if (suppliedObservedAtMs != null && suppliedObservedAtMs !in 0L..capturedAtMs) return null
        val originalObservedAtMs = minOf(normalizedDepthAtMs, suppliedObservedAtMs ?: normalizedDepthAtMs)
        val ageAtDisplayMs = displayAtElapsedRealtimeMs - originalObservedAtMs
        // Preserve any earlier producer provenance, independently of its 800 ms risk lease.
        // The display budget remains 1200 ms; sourceMaxAge/sourceValidUntil are not display caps.
        val maxAgeMs = requireNotNull(DepthAgeBudget.maxAgeMs(DepthUse.PROXIMITY_DISPLAY))
        if (ageAtDisplayMs > maxAgeMs || depthObservedAtMs > Long.MAX_VALUE - ageAtDisplayMs) return null
        val depthUseTimeMs = depthObservedAtMs + ageAtDisplayMs
        if (!DepthAgeBudget.admits(DepthUse.PROXIMITY_DISPLAY, output, depthUseTimeMs)) return null
        return maxAgeMs - ageAtDisplayMs
    }

    /** Pure label rendering, also used to reevaluate already mapped boxes on the UI thread. */
    fun labelFor(
        output: TrackedObjectDepth,
        capturedAtElapsedRealtimeMs: Long?,
        displayAtElapsedRealtimeMs: Long,
    ): String = buildString {
        append(output.className)
        append(" ")
        append(String.format(Locale.US, "%.0f%%", output.detectionConfidence * 100f))
        append(" · ")
        append(output.source.name)
        distanceFor(output, capturedAtElapsedRealtimeMs, displayAtElapsedRealtimeMs)?.let {
            append(String.format(Locale.US, " · %.2fm", it))
        }
        output.prediction?.takeIf {
            capturedAtElapsedRealtimeMs != null &&
                DepthPredictionPresentation.isCurrent(it, capturedAtElapsedRealtimeMs, displayAtElapsedRealtimeMs)
        }?.let {
            append(String.format(Locale.US, " · 예측 %.2fm ±%.2fm · 공백 %dms",
                it.distanceM, it.errorBoundM, it.predictionAgeMs))
        }
        append(" · samples ")
        append(output.validSampleCount)
        append(" · motion=${output.motionEstimate.direction}")
        output.motionEstimate.objectSpeedMps?.let {
            append(String.format(Locale.US, " · 물체 %.1fm/s", it))
        }
        output.motionEstimate.relativeClosingSpeedMps?.let {
            val direction = if (it >= 0f) "상대 접근" else "멀어짐"
            append(String.format(Locale.US, " · %s %.1fm/s", direction, kotlin.math.abs(it)))
        }
    }
}
