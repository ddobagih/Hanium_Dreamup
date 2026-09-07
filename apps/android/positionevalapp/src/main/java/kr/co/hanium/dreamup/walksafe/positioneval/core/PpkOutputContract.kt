package kr.co.hanium.dreamup.walksafe.positioneval.core

import java.io.File

internal data class PpkOutputSummary(val dataLineCount: Int, val fixedLineCount: Int)

internal object PpkOutputContract {
    const val MAX_OUTPUT_BYTES = 64L * 1024L * 1024L
    const val MAX_LINE_CHARS = 4_096
    const val MAX_DATA_LINES = 500_000

    fun validate(
        file: File,
        maxOutputBytes: Long = MAX_OUTPUT_BYTES,
        maxLineChars: Int = MAX_LINE_CHARS,
        maxDataLines: Int = MAX_DATA_LINES,
    ): PpkOutputSummary {
        require(file.isFile && file.length() in 1..maxOutputBytes) {
            "RTKLIB 결과 크기가 허용 범위를 벗어났습니다."
        }
        var utcLlhHeader = false
        var dataLines = 0
        var fixedLines = 0
        val line = StringBuilder()
        val chunk = CharArray(8_192)

        file.bufferedReader(Charsets.US_ASCII).use { reader ->
            while (true) {
                val count = reader.read(chunk)
                if (count < 0) break
                for (index in 0 until count) {
                    val character = chunk[index]
                    if (character == '\n') {
                        val result = inspectLine(line.toString(), utcLlhHeader)
                        utcLlhHeader = utcLlhHeader || result.utcLlhHeader
                        if (result.data) {
                            dataLines++
                            check(dataLines <= maxDataLines) { "RTKLIB 결과 epoch 수가 허용 범위를 벗어났습니다." }
                            if (result.fixed) fixedLines++
                        }
                        line.setLength(0)
                    } else {
                        check(line.length < maxLineChars) { "RTKLIB 결과 한 줄이 너무 깁니다." }
                        line.append(character)
                    }
                }
            }
            if (line.isNotEmpty()) {
                val result = inspectLine(line.toString(), utcLlhHeader)
                utcLlhHeader = utcLlhHeader || result.utcLlhHeader
                if (result.data) {
                    dataLines++
                    check(dataLines <= maxDataLines) { "RTKLIB 결과 epoch 수가 허용 범위를 벗어났습니다." }
                    if (result.fixed) fixedLines++
                }
            }
        }
        check(file.length() in 1..maxOutputBytes) { "RTKLIB 결과 크기가 허용 범위를 벗어났습니다." }
        check(utcLlhHeader) { "RTKLIB 결과가 UTC LLH 헤더를 포함하지 않습니다." }
        check(dataLines > 0) { "RTKLIB 결과에 위치 epoch가 없습니다." }
        return PpkOutputSummary(dataLines, fixedLines)
    }

    private fun inspectLine(value: String, headerAlreadyFound: Boolean): LineResult {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return LineResult()
        if (trimmed.startsWith('%')) {
            return LineResult(
                utcLlhHeader = !headerAlreadyFound &&
                    trimmed.contains("UTC") &&
                    trimmed.contains("latitude(deg)") &&
                    trimmed.contains("longitude(deg)") &&
                    trimmed.contains("height(m)"),
            )
        }
        val fields = trimmed.split(Regex("\\s+"))
        check(fields.size >= 6) { "RTKLIB 위치 epoch 형식이 잘못되었습니다." }
        check(fields[0].matches(Regex("\\d{4}/\\d{2}/\\d{2}"))) { "RTKLIB epoch 날짜가 잘못되었습니다." }
        check(fields[1].matches(Regex("\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?"))) { "RTKLIB epoch 시각이 잘못되었습니다." }
        val latitude = fields[2].toDouble()
        val longitude = fields[3].toDouble()
        val height = fields[4].toDouble()
        val quality = fields[5].toInt()
        check(latitude.isFinite() && latitude in -90.0..90.0) { "RTKLIB 위도가 잘못되었습니다." }
        check(longitude.isFinite() && longitude in -180.0..180.0) { "RTKLIB 경도가 잘못되었습니다." }
        check(height.isFinite()) { "RTKLIB 높이가 잘못되었습니다." }
        check(quality in 1..7) { "RTKLIB 해 품질 값이 잘못되었습니다." }
        return LineResult(data = true, fixed = quality == 1)
    }

    private data class LineResult(
        val utcLlhHeader: Boolean = false,
        val data: Boolean = false,
        val fixed: Boolean = false,
    )
}
