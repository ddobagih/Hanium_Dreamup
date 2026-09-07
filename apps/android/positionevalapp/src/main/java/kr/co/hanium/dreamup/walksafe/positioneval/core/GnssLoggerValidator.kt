package kr.co.hanium.dreamup.walksafe.positioneval.core

import java.io.File

data class GnssLoggerSummary(val rawMeasurementCount: Int, val hasFormatHeader: Boolean)

object GnssLoggerValidator {
    fun validate(file: File): GnssLoggerSummary {
        var rawCount = 0
        var header = false
        file.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { line ->
                if (line.startsWith("# Version:") || line.startsWith("# Raw,")) header = true
                if (line.startsWith("Raw,")) rawCount++
            }
        }
        if (!header || rawCount < 4) {
            throw IllegalArgumentException("GnssLogger TXT에서 충분한 Raw 위성 측정값을 찾지 못했습니다.")
        }
        return GnssLoggerSummary(rawCount, header)
    }
}
