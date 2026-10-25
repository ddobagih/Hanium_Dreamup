package kr.co.hanium.dreamup.walksafe.feedback.delivery
import kr.co.hanium.dreamup.walksafe.depth.*
import kr.co.hanium.dreamup.walksafe.navigation.*

        internal fun deliveryFrame(outputs: List<TrackedObjectDepth>) = TactileSnapshotFrameResult(
            null, emptyList(), false,
            TactileRouteGuidanceResult(outputs, null, TactileRouteDecision(LocalRouteMode.TMAP, null, "ready-test", null)),
        )

        internal fun deliveryRiskOutput(qualitativeMode: Boolean = false): TrackedObjectDepth {
            val box = RectNorm(.1f, .3f, .2f, .4f)
            val distance = 1f.takeUnless { qualitativeMode }
            return TrackedObjectDepth(
                frameId = 51_000_000_000L, timestampMs = 51_000L, trackId = "current-risk", className = "person",
                detectionConfidence = .95f, bboxNorm = box, polygonNorm = emptyList(), maskAreaNorm = box.area,
                centerNorm = box.center, bottomContactNorm = null,
                source = if (qualitativeMode) DepthSource.POLYGON_TREND_PSEUDO_DEPTH else DepthSource.ARCORE_RAW_DEPTH,
                zDistanceM = distance, rayDistanceM = distance, groundDistanceM = distance, riskDistanceM = distance,
                validSampleCount = if (qualitativeMode) 0 else 80, validSampleRatio = if (qualitativeMode) 0f else .9f,
                depthMedianM = distance, depthP20M = distance, depthIqrM = .05f.takeUnless { qualitativeMode },
                trend = if (qualitativeMode) Trend.APPROACHING else Trend.STABLE,
                approachScore = 0f, approachSpeedMps = null, timeToCollisionMs = null,
                confidence = DepthConfidenceBreakdown(if (qualitativeMode) .25f else .95f, 1f, 1f, 1f, 1f, 1f, 1f, 1f),
                userFacing = UserFacingDepth(null, MessageLevel.CAUTION, "앞에 장애물이 있습니다. 주의하세요."),
                trackAgeFrames = 20, trackStableMs = 1_900L,
                depthObservedAtMs = 51_000L.takeUnless { qualitativeMode },
                sourceValidUntilElapsedRealtimeMs = 1_700L,
                sourceObservedAtElapsedRealtimeMs = 1_000L,
                sourceMaxAgeMs = DepthAgeBudget.RISK_GUIDANCE_MAX_AGE_MS,
            )
        }
