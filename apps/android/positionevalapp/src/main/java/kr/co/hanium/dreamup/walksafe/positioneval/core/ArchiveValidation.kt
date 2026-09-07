package kr.co.hanium.dreamup.walksafe.positioneval.core

import org.apache.commons.compress.compressors.z.ZCompressorInputStream
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class ExtractedEntry(val originalName: String, val file: File, val byteCount: Long, val sha256: String)
data class EcefPoint(val xM: Double, val yM: Double, val zM: Double)
data class RinexHeader(
    val version: Double,
    val type: Char,
    val firstObservationUtcMs: Long?,
    val lastObservationUtcMs: Long? = null,
    val markerName: String? = null,
    val approximatePosition: EcefPoint? = null,
    val maxObservationGapMs: Long? = null,
)

object SafeRinexFileExtractor {
    const val MAX_OUTPUT_BYTES = 256L * 1024L * 1024L
    private const val MAX_COMPRESSION_RATIO = 200L
    private const val RATIO_SLACK_BYTES = 1024L * 1024L
    private const val Z_MEMORY_LIMIT_KIB = 64 * 1024

    fun extract(source: File, sourceName: String, stagingDirectory: File, outputIndex: Int = 0): ExtractedEntry {
        require(source.isFile && source.length() > 0L) { "빈 RINEX 원본 파일입니다." }
        if (!stagingDirectory.exists() && !stagingDirectory.mkdirs()) {
            throw IllegalArgumentException("RINEX 압축 해제 폴더를 만들 수 없습니다.")
        }
        require(stagingDirectory.isDirectory) { "RINEX 압축 해제 경로가 폴더가 아닙니다." }
        val format = detectFormat(source)
        val lowerName = sourceName.lowercase()
        when {
            lowerName.endsWith(".gz") && format != CompressionFormat.GZIP ->
                throw IllegalArgumentException(".gz 파일 서명이 올바르지 않습니다.")
            lowerName.endsWith(".z") && format != CompressionFormat.UNIX_Z ->
                throw IllegalArgumentException(".Z 파일 서명이 올바르지 않습니다.")
            !lowerName.endsWith(".gz") && !lowerName.endsWith(".z") && format != CompressionFormat.PLAIN ->
                throw IllegalArgumentException("파일 이름과 압축 형식이 일치하지 않습니다.")
        }

        val output = File(stagingDirectory, "rinex-$outputIndex.rnx")
        val part = File(stagingDirectory, "${output.name}.part")
        require(!output.exists() && !part.exists()) { "새 RINEX 출력 경로가 필요합니다." }
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        val ratioLimit = max(
            RATIO_SLACK_BYTES,
            if (source.length() > (MAX_OUTPUT_BYTES - RATIO_SLACK_BYTES) / MAX_COMPRESSION_RATIO) {
                MAX_OUTPUT_BYTES
            } else {
                source.length() * MAX_COMPRESSION_RATIO + RATIO_SLACK_BYTES
            },
        )
        val limit = min(MAX_OUTPUT_BYTES, ratioLimit)
        try {
            openDecoded(source, format).use { input ->
                FileOutputStream(part).use { target ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        count += read
                        if (count > limit) throw IllegalArgumentException("RINEX 압축 해제 크기 또는 압축률이 제한을 넘습니다.")
                        digest.update(buffer, 0, read)
                        target.write(buffer, 0, read)
                    }
                    target.flush()
                    target.fd.sync()
                }
            }
            if (count == 0L) throw IllegalArgumentException("압축 해제된 RINEX 파일이 비어 있습니다.")
            if (!part.renameTo(output)) throw IllegalArgumentException("압축 해제 파일을 확정하지 못했습니다.")
            val logicalName = when (format) {
                CompressionFormat.GZIP -> sourceName.dropLast(3)
                CompressionFormat.UNIX_Z -> sourceName.dropLast(2)
                CompressionFormat.PLAIN -> sourceName
            }
            return ExtractedEntry(logicalName, output, count, digest.digest().toHexValue())
        } catch (error: Throwable) {
            part.delete()
            output.delete()
            throw error
        }
    }

    private fun openDecoded(source: File, format: CompressionFormat): InputStream {
        val raw = BufferedInputStream(FileInputStream(source))
        return try {
            when (format) {
                CompressionFormat.GZIP -> GZIPInputStream(raw)
                CompressionFormat.UNIX_Z -> ZCompressorInputStream(raw, Z_MEMORY_LIMIT_KIB)
                CompressionFormat.PLAIN -> raw
            }
        } catch (error: Throwable) {
            raw.close()
            throw error
        }
    }

    private fun detectFormat(source: File): CompressionFormat {
        val signature = ByteArray(4)
        val count = FileInputStream(source).use { it.read(signature) }
        if (count >= 2 && signature[0] == 0x1f.toByte() && signature[1] == 0x8b.toByte()) return CompressionFormat.GZIP
        if (count >= 2 && signature[0] == 0x1f.toByte() && signature[1] == 0x9d.toByte()) return CompressionFormat.UNIX_Z
        if (count >= 2 && signature[0] == 'P'.code.toByte() && signature[1] == 'K'.code.toByte()) {
            throw IllegalArgumentException("ZIP은 파일 단위 압축 해제기로 처리할 수 없습니다.")
        }
        return CompressionFormat.PLAIN
    }

    private enum class CompressionFormat { PLAIN, GZIP, UNIX_Z }
}

object SafeZipExtractor {
    const val MAX_ENTRIES = 64
    const val MAX_ENTRY_BYTES = 256L * 1024L * 1024L
    const val MAX_TOTAL_BYTES = 512L * 1024L * 1024L
    private const val MAX_COMPRESSION_RATIO = 200L
    private const val RATIO_SLACK_BYTES = 1024L * 1024L

    fun extract(archive: File, stagingDirectory: File): List<ExtractedEntry> {
        require(archive.isFile && archive.length() > 0L) { "빈 ZIP 파일입니다." }
        if (stagingDirectory.exists() || !stagingDirectory.mkdirs()) {
            throw IllegalArgumentException("새 압축 해제 폴더가 필요합니다.")
        }
        val seen = mutableSetOf<String>()
        val results = mutableListOf<ExtractedEntry>()
        var packedTotal = 0L
        var finalTotal = 0L
        var entryCount = 0
        try {
            ZipInputStream(FileInputStream(archive)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entryCount++
                    if (entryCount > MAX_ENTRIES) throw IllegalArgumentException("ZIP 항목이 너무 많습니다.")
                    val normalized = validateEntryName(entry.name, entry.isDirectory)
                    if (!seen.add(normalized)) throw IllegalArgumentException("ZIP에 중복 경로가 있습니다.")
                    if (entry.isDirectory) continue
                    if (normalized.endsWith(".zip", true)) throw IllegalArgumentException("중첩 ZIP은 지원하지 않습니다.")
                    val packedFile = File(stagingDirectory, "entry-${results.size}.packed")
                    var entryBytes = 0L
                    FileOutputStream(packedFile).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            entryBytes += read
                            packedTotal += read
                            if (entryBytes > MAX_ENTRY_BYTES || packedTotal > MAX_TOTAL_BYTES) {
                                throw IllegalArgumentException("ZIP 해제 크기가 제한을 넘습니다.")
                            }
                            output.write(buffer, 0, read)
                        }
                        output.flush()
                        output.fd.sync()
                    }
                    if (entryBytes == 0L) throw IllegalArgumentException("ZIP에 빈 파일이 있습니다.")
                    val materialized = SafeRinexFileExtractor.extract(packedFile, entry.name, stagingDirectory, results.size)
                    if (!packedFile.delete()) throw IllegalArgumentException("ZIP 임시 파일을 지우지 못했습니다.")
                    finalTotal += materialized.byteCount
                    val ratioLimit = max(
                        RATIO_SLACK_BYTES,
                        if (archive.length() > (MAX_TOTAL_BYTES - RATIO_SLACK_BYTES) / MAX_COMPRESSION_RATIO) {
                            MAX_TOTAL_BYTES
                        } else {
                            archive.length() * MAX_COMPRESSION_RATIO + RATIO_SLACK_BYTES
                        },
                    )
                    if (finalTotal > MAX_TOTAL_BYTES || finalTotal > ratioLimit) {
                        throw IllegalArgumentException("ZIP 최종 해제 크기 또는 압축률이 제한을 넘습니다.")
                    }
                    results += materialized
                }
            }
            if (results.isEmpty()) throw IllegalArgumentException("ZIP에 파일이 없습니다.")
            return results
        } catch (error: Throwable) {
            stagingDirectory.deleteRecursively()
            throw error
        }
    }

    private fun validateEntryName(raw: String, directory: Boolean): String {
        if (raw.isBlank() || '\u0000' in raw || raw.length > 240) throw IllegalArgumentException("안전하지 않은 ZIP 경로입니다.")
        val value = raw.replace('\\', '/').let { if (directory) it.trimEnd('/') else it }
        if (value.startsWith('/') || Regex("^[A-Za-z]:").containsMatchIn(value)) {
            throw IllegalArgumentException("절대 ZIP 경로는 허용하지 않습니다.")
        }
        val segments = value.split('/')
        if (segments.any { it.isBlank() || it == "." || it == ".." }) {
            throw IllegalArgumentException("상위 폴더를 가리키는 ZIP 경로는 허용하지 않습니다.")
        }
        return segments.joinToString("/")
    }
}

object RinexHeaderValidator {
    private const val MAX_HEADER_LINES = 10_000

    fun validate(file: File, sessionStartUtcMs: Long, sessionEndUtcMs: Long): RinexHeader {
        require(sessionEndUtcMs >= sessionStartUtcMs) { "테스트 시간 범위가 잘못되었습니다." }
        BufferedReader(InputStreamReader(FileInputStream(file), Charsets.US_ASCII)).use { reader ->
            val first = reader.readLine() ?: throw IllegalArgumentException("빈 RINEX 파일입니다.")
            if (first.length > 4096 || !first.contains("RINEX VERSION / TYPE")) {
                throw IllegalArgumentException("RINEX VERSION / TYPE 헤더가 없습니다.")
            }
            val version = first.take(20).trim().split(Regex("\\s+")).firstOrNull()?.toDoubleOrNull()
                ?: throw IllegalArgumentException("RINEX 버전이 잘못되었습니다.")
            if (!version.isFinite() || version !in 2.0..<4.0) throw IllegalArgumentException("지원하지 않는 RINEX 버전입니다.")
            val type = first.getOrNull(20)?.uppercaseChar() ?: throw IllegalArgumentException("RINEX 자료 종류가 없습니다.")
            if (type !in setOf('O', 'N', 'G', 'H', 'L', 'J', 'C', 'I', 'S', 'M')) {
                throw IllegalArgumentException("지원하지 않는 RINEX 자료 종류입니다.")
            }
            var hasObservationTypes = false
            var approximatePosition: EcefPoint? = null
            var markerName: String? = null
            var firstObservationLine: String? = null
            var lastObservationLine: String? = null
            var ended = false
            var headerLines = 0
            while (headerLines < MAX_HEADER_LINES) {
                val line = reader.readLine() ?: break
                headerLines++
                if (line.length > 4096) throw IllegalArgumentException("RINEX 헤더 줄이 너무 깁니다.")
                if (line.contains("# / TYPES OF OBSERV") || line.contains("SYS / # / OBS TYPES")) hasObservationTypes = true
                if (line.contains("APPROX POSITION XYZ")) approximatePosition = parseApproximatePosition(line)
                if (line.contains("MARKER NAME")) markerName = line.take(60).trim().ifBlank { null }
                if (line.contains("TIME OF FIRST OBS")) firstObservationLine = line
                if (line.contains("TIME OF LAST OBS")) lastObservationLine = line
                if (line.contains("END OF HEADER")) {
                    ended = true
                    break
                }
            }
            if (!ended) throw IllegalArgumentException("RINEX END OF HEADER가 없습니다.")
            val timeSystem = if (type == 'O') {
                val system = firstObservationLine?.let(::headerTimeSystem) ?: when (first.getOrNull(40)) {
                    'R' -> "GLO"
                    'G', ' ', null -> "GPS"
                    else -> throw IllegalArgumentException("RINEX 관측 시간계를 명시해야 합니다.")
                }
                require(system in setOf("GPS", "GLO", "UTC")) { "지원하지 않는 RINEX 관측 시간계입니다." }
                val lastSystem = lastObservationLine?.let(::headerTimeSystem)
                require(lastSystem == null || lastSystem == system) { "RINEX 첫 관측과 마지막 관측 시간계가 다릅니다." }
                system
            } else {
                "UTC"
            }
            var firstObservation = firstObservationLine?.let { parseHeaderObservation(it, timeSystem) }
            var lastObservation = lastObservationLine?.let { parseHeaderObservation(it, timeSystem) }
            require(lastObservationLine == null || lastObservation != null) { "RINEX 마지막 관측 시각이 잘못되었습니다." }
            var hasData = false
            var firstDataObservation: Long? = null
            var lastDataObservation: Long? = null
            var maxObservationGapMs: Long? = null
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                hasData = true
                if (type == 'O') {
                    val epoch = parseObservationEpoch(line, version, timeSystem) ?: continue
                    if (lastDataObservation != null && epoch <= lastDataObservation!!) {
                        throw IllegalArgumentException("RINEX 관측 epoch가 중복되거나 역순입니다.")
                    }
                    if (lastDataObservation != null) {
                        maxObservationGapMs = max(maxObservationGapMs ?: 0L, epoch - lastDataObservation!!)
                    }
                    if (firstDataObservation == null) firstDataObservation = epoch
                    lastDataObservation = epoch
                }
            }
            if (!hasData) throw IllegalArgumentException("RINEX 관측 데이터가 없습니다.")
            if (type == 'O') {
                if (!hasObservationTypes || approximatePosition == null || firstObservation == null) {
                    throw IllegalArgumentException("RINEX 관측 헤더 필수값이 없습니다.")
                }
                if (firstDataObservation == null || lastDataObservation == null) {
                    throw IllegalArgumentException("RINEX에 유효한 관측 epoch가 없습니다.")
                }
                val margin = 5L * 60L * 1000L
                if (abs(firstDataObservation!! - firstObservation!!) > margin) {
                    throw IllegalArgumentException("RINEX 헤더와 첫 관측 epoch가 일치하지 않습니다.")
                }
                if (lastObservation != null && abs(lastObservation!! - lastDataObservation!!) > margin) {
                    throw IllegalArgumentException("RINEX 헤더와 마지막 관측 epoch가 일치하지 않습니다.")
                }
                firstObservation = firstDataObservation
                lastObservation = lastDataObservation
                if (firstObservation!! > sessionEndUtcMs + margin || lastObservation!! < sessionStartUtcMs - margin) {
                    throw IllegalArgumentException("RINEX 관측 시각이 테스트 시간과 겹치지 않습니다.")
                }
            }
            return RinexHeader(
                version,
                type,
                firstObservation,
                lastObservation,
                markerName,
                approximatePosition,
                maxObservationGapMs,
            )
        }
    }

    private fun headerTimeSystem(line: String): String? =
        line.take(60).trim().split(Regex("\\s+")).getOrNull(6)

    private fun parseHeaderObservation(line: String, timeSystem: String): Long? {
        val values = line.take(48).trim().split(Regex("\\s+"))
        if (values.size < 6) return null
        return runCatching {
            toEpochMillis(
                values[0].toInt(), values[1].toInt(), values[2].toInt(),
                values[3].toInt(), values[4].toInt(), values[5].toDouble(),
                timeSystem,
            )
        }.getOrNull()
    }

    private fun parseApproximatePosition(line: String): EcefPoint? {
        val values = line.take(60).trim().split(Regex("\\s+"))
        if (values.size < 3) return null
        return runCatching {
            val point = EcefPoint(values[0].toDouble(), values[1].toDouble(), values[2].toDouble())
            val radius = kotlin.math.sqrt(point.xM * point.xM + point.yM * point.yM + point.zM * point.zM)
            require(point.xM.isFinite() && point.yM.isFinite() && point.zM.isFinite() && radius in 5_000_000.0..7_000_000.0)
            point
        }.getOrNull()
    }

    private fun parseObservationEpoch(line: String, version: Double, timeSystem: String): Long? {
        val match = if (version >= 3.0) {
            Regex("^>\\s*(\\d{4})\\s+(\\d{1,2})\\s+(\\d{1,2})\\s+(\\d{1,2})\\s+(\\d{1,2})\\s+(\\d{1,2}(?:\\.\\d+)?)\\s+([0-6])\\s+(\\d+)").find(line)
        } else {
            Regex("^\\s*(\\d{2})\\s+(\\d{1,2})\\s+(\\d{1,2})\\s+(\\d{1,2})\\s+(\\d{1,2})\\s+(\\d{1,2}(?:\\.\\d+)?)\\s+([0-6])\\s+(\\d+)").find(line)
        } ?: return null
        if (match.groupValues[7] !in setOf("0", "1") || match.groupValues[8].toIntOrNull()?.let { it > 0 } != true) return null
        return runCatching {
            val rawYear = match.groupValues[1].toInt()
            val year = if (version >= 3.0) rawYear else if (rawYear >= 80) 1900 + rawYear else 2000 + rawYear
            toEpochMillis(
                year,
                match.groupValues[2].toInt(),
                match.groupValues[3].toInt(),
                match.groupValues[4].toInt(),
                match.groupValues[5].toInt(),
                match.groupValues[6].toDouble(),
                timeSystem,
            )
        }.getOrNull()
    }

    private fun toEpochMillis(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Double, timeSystem: String): Long {
        require(second.isFinite() && second >= 0.0 && second < 60.0)
        val wholeSecond = second.toInt()
        val nanos = ((second - wholeSecond) * 1_000_000_000.0).toInt()
        val local = LocalDateTime.of(year, month, day, hour, minute, wholeSecond, nanos)
        return PpkPosParser.toUtc(local, if (timeSystem == "GPS") "GPST" else "UTC").toEpochMilli()
    }
}

private fun ByteArray.toHexValue(): String = joinToString("") { "%02x".format(it) }
