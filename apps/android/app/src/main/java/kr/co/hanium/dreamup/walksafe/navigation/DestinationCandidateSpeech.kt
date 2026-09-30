package kr.co.hanium.dreamup.walksafe.navigation

import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The origin one candidate list was measured from (S06-31): the point and the accuracy of the fix
 * that centered the search. A search fix may be up to [DestinationSearchLocationPolicy.MAX_ACCURACY_M]
 * uncertain, so the spoken distance and direction are never more precise than this origin.
 * [observedAtElapsedRealtimeMs] is when that fix was measured (elapsed realtime); null when unknown.
 */
data class DestinationSearchOrigin(
    val point: RoutePoint,
    val accuracyM: Float?,
    val observedAtElapsedRealtimeMs: Long? = null,
)

/** S06-31: after this long since the origin fix, the list says its distances may have changed. */
internal const val DESTINATION_ORIGIN_STALE_AFTER_MS = 120_000L

/**
 * S06-31: said once with a re-heard page, the confirmation and the selection when the list was
 * measured more than [DESTINATION_ORIGIN_STALE_AFTER_MS] ago. Distances, numbers and order stay
 * as searched (S06-32); nothing is re-measured or re-sorted. Null without a known fix time.
 */
fun destinationOriginAgeNoticeKo(origin: DestinationSearchOrigin?, nowElapsedRealtimeMs: Long?): String? {
    val observedAt = origin?.observedAtElapsedRealtimeMs ?: return null
    val now = nowElapsedRealtimeMs ?: return null
    if (observedAt < 0L || now < observedAt) return null
    return if (now - observedAt > DESTINATION_ORIGIN_STALE_AFTER_MS) {
        "검색한 지 시간이 지나 지금 거리는 달라졌을 수 있습니다."
    } else {
        null
    }
}

/**
 * voice-F6 (S06-31; 06.txt:237 ㊲ shorter prompts): the age notice of a candidate list is said once per
 * searched list - one origin shared by its pages, repeats and previous pages - not with every page.
 * A new search has a new origin and may say it again.
 */
internal class DestinationOriginAgeNoticeOnce {
    private var spokenFor: DestinationSearchOrigin? = null

    fun noticeOrNull(origin: DestinationSearchOrigin?, nowElapsedRealtimeMs: Long): String? {
        if (origin == null || origin === spokenFor) return null
        return destinationOriginAgeNoticeKo(origin, nowElapsedRealtimeMs)
    }

    /** A queued or interrupted page has not said the notice; commit only after TTS completion. */
    fun markDelivered(origin: DestinationSearchOrigin?) {
        if (origin != null) spokenFor = origin
    }
}

/** Above this origin accuracy distances are read in 100 m steps and the page says they are rough. */
internal const val DESTINATION_ORIGIN_COARSE_ACCURACY_M = 50f

/** A direction is only said when the candidate is clearly outside the origin's uncertainty. */
private const val DESTINATION_DIRECTION_MIN_SEPARATION_M = 30.0

/**
 * FP-022-DETAIL-01 '대략적 거리': 10 m steps under 100 m, 50 m steps under 1 km, 0.1 km above.
 * A coarse origin (> 50 m) widens every step under 1 km to 100 m (S06-31). Unknown stays unknown.
 */
fun approximateDestinationDistanceKo(distanceM: Int?, originAccuracyM: Float? = null): String {
    if (distanceM == null || distanceM < 0) return "거리 미상"
    val coarse = originAccuracyM != null && originAccuracyM.isFinite() &&
        originAccuracyM > DESTINATION_ORIGIN_COARSE_ACCURACY_M
    val step = when {
        distanceM >= 1_000 || coarse -> 100L
        distanceM < 100 -> 10L
        else -> 50L
    }
    if (distanceM < step) return "약 ${step}미터 이내"
    val rounded = ((distanceM.toLong() + step / 2) / step) * step
    if (rounded < 1_000L) return "약 ${rounded}미터"
    val tenths = (rounded + 50L) / 100L
    val kilometers = if (tenths % 10L == 0L) "${tenths / 10L}" else "${tenths / 10L}.${tenths % 10L}"
    return "약 ${kilometers}킬로미터"
}

/**
 * S06-30: a candidate's rough direction and distance with the kind of distance, so it is never taken
 * for a walking distance: "북쪽으로 직선 약 300미터". A provider radius is measured from the search
 * origin but is not guaranteed to be the geometric straight line (backend schemas.py
 * distance_source), so its own kind is named. Without a known kind the existing wording stays.
 */
fun destinationDirectionDistanceKo(
    direction: String?,
    distanceM: Int?,
    source: DestinationDistanceSource?,
    originAccuracyM: Float? = null,
): String {
    val approximate = approximateDestinationDistanceKo(distanceM, originAccuracyM)
    if (distanceM == null || distanceM < 0) return direction?.let { "$it $approximate" } ?: approximate
    return when (source) {
        DestinationDistanceSource.STRAIGHT_LINE ->
            direction?.let { "${it}으로 직선 $approximate" } ?: "직선으로 $approximate"
        DestinationDistanceSource.TMAP_RADIUS ->
            "검색 위치 기준 " + (direction?.let { "${it} 방향 반경 $approximate" } ?: "반경 $approximate") +
                ", 도보 거리는 아닙니다"
        null -> direction?.let { "$it $approximate" } ?: approximate
    }
}

/**
 * S06-7: the distance said when a candidate is chosen by voice (HOME and walking alike), on the basis
 * the confirmation screen shows: at search time, from the search origin and never a walking distance
 * (S06-30), no finer than the origin (S06-31). Null when the candidate has no distance.
 */
fun selectedDestinationDistanceSentenceKo(
    result: DestinationSearchResult,
    origin: DestinationSearchOrigin?,
    nowElapsedRealtimeMs: Long? = null,
): String? {
    val distanceM = result.distanceM?.takeIf { it >= 0 } ?: return null
    val distance = destinationDirectionDistanceKo(null, distanceM, result.distanceSource, origin?.accuracyM)
    val distanceSentence = if (result.distanceSource == DestinationDistanceSource.TMAP_RADIUS) {
        "검색 시점의 검색 위치 기준 반경 ${approximateDestinationDistanceKo(distanceM, origin?.accuracyM)}입니다. 도보 거리가 아닙니다."
    } else {
        "검색 시점 기준 $distance 거리입니다."
    }
    return listOfNotNull(
        distanceSentence,
        destinationOriginUncertaintyNoticeKo(origin),
        destinationOriginAgeNoticeKo(origin, nowElapsedRealtimeMs),
    ).joinToString(" ")
}

/** S06-30: the kind of a shown distance for the screen list and the confirmation; null when unknown. */
fun destinationDistanceKindKo(source: DestinationDistanceSource?): String? = when (source) {
    DestinationDistanceSource.STRAIGHT_LINE -> "직선 거리"
    DestinationDistanceSource.TMAP_RADIUS -> "검색 위치 기준 반경, 도보 거리 아님"
    null -> null
}

/**
 * FP-022-DETAIL-01 '대략적 방향': one of eight compass directions from the search origin to the
 * candidate. Null (not spoken) without an origin, without a known accuracy, or when the candidate is
 * so close that the origin's own uncertainty could flip the direction.
 */
fun approximateDestinationDirectionKo(origin: DestinationSearchOrigin?, target: RoutePoint): String? {
    if (origin == null) return null
    val accuracy = origin.accuracyM?.takeIf { it.isFinite() && it >= 0f } ?: return null
    val from = origin.point
    if (!from.isFiniteCoordinate() || !target.isFiniteCoordinate()) return null
    val separation = greatCircleDistanceM(from, target)
    if (!separation.isFinite() || separation < max(DESTINATION_DIRECTION_MIN_SEPARATION_M, 2.0 * accuracy)) {
        return null
    }
    val startLat = Math.toRadians(from.latitude)
    val endLat = Math.toRadians(target.latitude)
    val deltaLng = Math.toRadians(target.longitude - from.longitude)
    val y = sin(deltaLng) * cos(endLat)
    val x = cos(startLat) * sin(endLat) - sin(startLat) * cos(endLat) * cos(deltaLng)
    val bearing = (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    if (!bearing.isFinite()) return null
    return COMPASS_DIRECTIONS_KO[((bearing + 22.5) / 45.0).toInt() % COMPASS_DIRECTIONS_KO.size]
}

/** The provider kind ("카페", "음식점>한식" → "한식"); omitted rather than cut when it is not short. */
fun destinationCategoryLabelKo(category: String?): String? {
    val last = category?.replace(WHITESPACE_RUN, " ")
        ?.split('>', '/', ';')
        ?.map(String::trim)
        ?.lastOrNull(String::isNotEmpty)
        ?: return null
    return last.takeIf { it.codePointCount(0, it.length) <= MAX_CATEGORY_LABEL_CODE_POINTS }
}

/** Said once per page when the search origin is coarse (S06-31). */
fun destinationOriginUncertaintyNoticeKo(origin: DestinationSearchOrigin?): String? {
    val accuracy = origin?.accuracyM ?: return null
    return if (accuracy.isFinite() && accuracy > DESTINATION_ORIGIN_COARSE_ACCURACY_M) {
        "현재 위치 오차가 커서 거리는 대략입니다."
    } else {
        null
    }
}

/** FP-022-DETAIL-01: an address is read only to tell same-name places in one list apart. */
fun DestinationSearchResult.sharesNameWith(results: List<DestinationSearchResult>): Boolean {
    val key = name.destinationNameKey()
    return results.any { other -> other.id != id && other.name.destinationNameKey() == key }
}

private fun String.destinationNameKey(): String = replace(WHITESPACE_RUN, "").lowercase(Locale.KOREAN)

private fun RoutePoint.isFiniteCoordinate(): Boolean =
    latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0

private fun greatCircleDistanceM(start: RoutePoint, end: RoutePoint): Double {
    val startLat = Math.toRadians(start.latitude)
    val endLat = Math.toRadians(end.latitude)
    val deltaLat = endLat - startLat
    val deltaLng = Math.toRadians(end.longitude - start.longitude)
    val value = sin(deltaLat / 2.0) * sin(deltaLat / 2.0) +
        cos(startLat) * cos(endLat) * sin(deltaLng / 2.0) * sin(deltaLng / 2.0)
    val bounded = min(1.0, max(0.0, value))
    return 6_371_000.0 * 2.0 * atan2(sqrt(bounded), sqrt(1.0 - bounded))
}

private val COMPASS_DIRECTIONS_KO = listOf("북쪽", "북동쪽", "동쪽", "남동쪽", "남쪽", "남서쪽", "서쪽", "북서쪽")
private val WHITESPACE_RUN = Regex("\\s+")
private const val MAX_CATEGORY_LABEL_CODE_POINTS = 20
