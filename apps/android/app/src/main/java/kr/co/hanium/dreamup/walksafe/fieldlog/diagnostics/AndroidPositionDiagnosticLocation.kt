package kr.co.hanium.dreamup.walksafe.fieldlog.diagnostics

import android.location.Location

/** Construction does not query Location; getters preserve original short-circuit read order. */
internal class AndroidPositionDiagnosticLocation(private val source: Location) : PositionDiagnosticLocation {
    override val elapsedRealtimeNanos get() = source.elapsedRealtimeNanos
    override val time get() = source.time
    override val accuracy get() = source.accuracy
    override val speed get() = source.speed
    override val bearing get() = source.bearing
    override fun hasAccuracy() = source.hasAccuracy()
    override fun hasSpeed() = source.hasSpeed()
    override fun hasBearing() = source.hasBearing()
}
