package kr.co.hanium.dreamup.walksafe.navigation.positioning

/** Android's reported horizontal radius at 68% confidence; not an axis standard deviation. */
data class ReportedAccuracyRadius68M(val meters: Double)

/**
 * Nominal per-axis GNSS noise used by this filter, before innovation gating.
 * The existing reported-radius proxy, floor/default and signal weight do not establish a
 * physical error distribution or a calibrated conversion from the Android radius.
 */
data class MeasurementAxisStdDevM(val meters: Double)

/** sqrt(largest eigenvalue) of the filter's horizontal ENU covariance, not a confidence radius. */
data class PositionMaxAxisSigmaM(val meters: Double)

/** Meaning of a legacy scalar at a route boundary. Unspecified values are never inferred. */
enum class HorizontalUncertaintyKind {
    LEGACY_UNSPECIFIED,
    ANDROID_RADIUS_68,
    FILTER_MAX_AXIS_SIGMA,
}
