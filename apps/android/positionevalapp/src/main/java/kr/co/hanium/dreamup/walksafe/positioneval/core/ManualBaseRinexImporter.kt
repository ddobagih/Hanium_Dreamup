package kr.co.hanium.dreamup.walksafe.positioneval.core

import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale
import kr.co.hanium.dreamup.walksafe.positioneval.BaseObservationSetPolicy
import kotlin.math.atan2
import kotlin.math.sin
import kotlin.math.sqrt

/** Offline validation of user-selected NGII Hourly RINEX2 files; no key or catalogue is required. */
object ManualBaseRinexImporter {
    const val MAX_INPUT_FILES = 32
    const val MAX_INPUT_BYTES = 256L * 1024L * 1024L
    const val MAX_EXPANDED_BYTES = 512L * 1024L * 1024L
    private const val MAX_NAV_AGE_MS = 4L * 60L * 60L * 1000L
    private const val MAX_STATION_DISTANCE_M = 30_000.0
    private val namePattern = Regex("^([A-Z0-9]{4})(\\d{3})([A-X])\\.(\\d{2})([ONGHLJCIS])$", RegexOption.IGNORE_CASE)

    fun prepare(
        artifacts: List<ImportedArtifact>, trace: TraceSession, workRoot: File,
        cancellation: AnalysisCancellation = AnalysisCancellation(),
    ): DownloadedBaseData {
        if (artifacts.size !in 1..MAX_INPUT_FILES || artifacts.any { it.byteCount !in 1..MAX_INPUT_BYTES } ||
            artifacts.sumOf { it.byteCount } > MAX_INPUT_BYTES
        ) invalid()
        require(!workRoot.exists()) { "새 수동 기준국 작업 폴더가 필요합니다." }
        val entries = mutableListOf<ExtractedEntry>()
        try {
            artifacts.forEachIndexed { index, artifact ->
                cancellation.throwIfCancelled()
                verifyArtifact(artifact, cancellation)
                val directory = File(workRoot, "input-$index")
                val remaining = MAX_EXPANDED_BYTES - entries.sumOf { it.byteCount }
                if (remaining <= 0L) invalid()
                val extracted = if (artifact.displayName.endsWith(".zip", ignoreCase = true)) {
                    SafeZipExtractor.extract(artifact.file, directory, cancellation, remaining)
                } else {
                    listOf(SafeRinexFileExtractor.extract(
                        artifact.file, artifact.displayName, directory, cancellation = cancellation,
                        maxOutputBytes = minOf(SafeRinexFileExtractor.MAX_OUTPUT_BYTES, remaining),
                    ))
                }
                entries += extracted
                if (entries.size > SafeZipExtractor.MAX_ENTRIES) invalid()
            }
            val named = entries.map { it to parseName(it.originalName) }
            if (named.map { it.second }.toSet().size != named.size) invalid()
            val navigationRanges = mutableListOf<LongRange>()
            val stationCodes = named.map { it.second.stationCode }.toSet()
            if (stationCodes.size != 1) invalid()
            val code = stationCodes.single()
            val headers = named.map { (entry, name) ->
                cancellation.throwIfCancelled()
                val header = RinexHeaderValidator.validate(entry.file, trace.startUtcEpochMs, trace.endUtcEpochMs, cancellation)
                if (header.version >= 3.0 || header.type != name.type) invalid()
                if (header.type == 'O') {
                    val marker = header.markerName?.uppercase(Locale.ROOT)?.filter(Char::isLetterOrDigit).orEmpty()
                    if (!marker.startsWith(code)) invalid()
                    val first = header.firstObservationUtcMs ?: invalid()
                    val last = header.lastObservationUtcMs ?: invalid()
                    if (first < name.startUtcMs - 300_000L || last > name.startUtcMs + 3_900_000L) invalid()
                } else {
                    val coverage = validateNavigationTime(entry.file, name, trace, cancellation)
                    if (header.type == 'N') navigationRanges += coverage
                }
                header
            }
            val observations = headers.filter { it.type == 'O' }
            if (observations.isEmpty() || headers.none { it.type == 'N' }) invalid()
            BaseObservationSetPolicy.validate(observations, trace.startUtcEpochMs, trace.endUtcEpochMs)
            var navigationThrough = trace.startUtcEpochMs
            for (range in navigationRanges.sortedBy { it.first }) {
                if (range.first > navigationThrough) invalid()
                navigationThrough = maxOf(navigationThrough, range.last)
            }
            if (navigationThrough < trace.endUtcEpochMs) invalid()
            val location = ecefLocation(observations.first().approximatePosition ?: invalid())
            if (PositionEvaluator.haversineMeters(location, trace.center) > MAX_STATION_DISTANCE_M) {
                throw NgiiException("BASE_STATION_TOO_FAR", "선택한 기준국이 테스트 경로에서 30km를 넘습니다.")
            }
            cancellation.throwIfCancelled()
            return DownloadedBaseData(
                BaseStation(code, "$code (RINEX 헤더 기준)", location, operational = false),
                RinexDownloadPlanner.requiredPortalHours(trace.startUtcEpochMs, trace.endUtcEpochMs),
                emptyList(), entries,
            )
        } catch (error: Throwable) {
            workRoot.deleteRecursively()
            if (error is AnalysisCancelledException || error is NgiiException) throw error
            throw NgiiException("BASE_DATA_INVALID", "선택한 기준국 파일의 형식·시간·압축 구성을 확인해 주세요.")
        }
    }

    private data class RinexName(val stationCode: String, val startUtcMs: Long, val type: Char)

    private fun parseName(originalName: String): RinexName {
        val name = originalName.replace('\\', '/').substringAfterLast('/')
        val match = namePattern.matchEntire(name) ?: invalid()
        val yearPart = match.groupValues[4].toInt()
        val year = if (yearPart >= 80) 1900 + yearPart else 2000 + yearPart
        val date = LocalDate.ofYearDay(year, match.groupValues[2].toInt())
        val hour = match.groupValues[3].uppercase(Locale.ROOT).single() - 'A'
        return RinexName(
            match.groupValues[1].uppercase(Locale.ROOT),
            date.atTime(hour, 0).toInstant(ZoneOffset.UTC).toEpochMilli(),
            match.groupValues[5].uppercase(Locale.ROOT).single(),
        )
    }

    private fun validateNavigationTime(
        file: File, name: RinexName, trace: TraceSession, cancellation: AnalysisCancellation,
    ): LongRange {
        var headerEnded = false
        var firstEpoch: Long? = null
        var lastEpoch: Long? = null
        var remainingLines = 0
        val epochPattern = Regex("^\\s*\\d{1,3}\\s+(\\d{2})\\s+(\\d{1,2})\\s+(\\d{1,2})\\s+(\\d{1,2})\\s+(\\d{1,2})\\s+(\\d{1,2}(?:\\.\\d+)?)")
        file.bufferedReader(Charsets.US_ASCII).useLines { lines ->
            lines.forEach { line ->
                cancellation.throwIfCancelled()
                if (!headerEnded) {
                    headerEnded = line.contains("END OF HEADER")
                } else {
                    val match = epochPattern.find(line)
                    if (match != null) {
                        if (remainingLines != 0 || !validNavigationNumbers(line.substring(match.range.last + 1), 3..3)) invalid()
                        remainingLines = if (name.type in setOf('G', 'H')) 3 else 7
                        val rawYear = match.groupValues[1].toInt()
                        val second = match.groupValues[6].toDouble()
                        if (second !in 0.0..<60.0) invalid()
                        val local = LocalDateTime.of(
                            if (rawYear >= 80) 1900 + rawYear else 2000 + rawYear,
                            match.groupValues[2].toInt(), match.groupValues[3].toInt(),
                            match.groupValues[4].toInt(), match.groupValues[5].toInt(), second.toInt(),
                        )
                        val utc = PpkPosParser.toUtc(local, if (name.type == 'G') "UTC" else "GPST").toEpochMilli()
                        if (utc < name.startUtcMs - MAX_NAV_AGE_MS || utc > name.startUtcMs + 3_600_000L + MAX_NAV_AGE_MS) invalid()
                        firstEpoch = minOf(firstEpoch ?: utc, utc)
                        lastEpoch = maxOf(lastEpoch ?: utc, utc)
                    } else if (line.isNotBlank()) {
                        if (remainingLines <= 0 || !validNavigationNumbers(line, 1..4)) invalid()
                        remainingLines -= 1
                    }
                }
            }
        }
        val first = firstEpoch ?: invalid()
        val last = lastEpoch ?: invalid()
        if (remainingLines != 0) invalid()
        if (first > trace.endUtcEpochMs + MAX_NAV_AGE_MS || last < trace.startUtcEpochMs - MAX_NAV_AGE_MS) invalid()
        return (first - MAX_NAV_AGE_MS)..(last + MAX_NAV_AGE_MS)
    }

    private fun validNavigationNumbers(line: String, expectedCount: IntRange): Boolean {
        val number = Regex("[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[DdEe][+-]?\\d+)?")
        val matches = number.findAll(line).toList()
        return matches.size in expectedCount && number.replace(line, "").isBlank() && matches.all {
            it.value.replace('D', 'E').replace('d', 'e').toDoubleOrNull()?.isFinite() == true
        }
    }

    private fun verifyArtifact(artifact: ImportedArtifact, cancellation: AnalysisCancellation) {
        if (!artifact.file.isFile || artifact.byteCount !in 1..MAX_INPUT_BYTES || artifact.file.length() != artifact.byteCount) invalid()
        val digest = MessageDigest.getInstance("SHA-256")
        artifact.file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                cancellation.throwIfCancelled()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        if (digest.digest().joinToString("") { "%02x".format(it) } != artifact.sha256) invalid()
    }

    private fun ecefLocation(point: EcefPoint): GeoPoint {
        val semiMajorM = 6_378_137.0
        val eccentricitySquared = 6.69437999014e-3
        val horizontal = sqrt(point.xM * point.xM + point.yM * point.yM)
        if (horizontal <= 1.0) invalid()
        var latitude = atan2(point.zM, horizontal * (1.0 - eccentricitySquared))
        repeat(8) {
            val primeVertical = semiMajorM / sqrt(1.0 - eccentricitySquared * sin(latitude) * sin(latitude))
            latitude = atan2(point.zM + eccentricitySquared * primeVertical * sin(latitude), horizontal)
        }
        return GeoPoint(Math.toDegrees(latitude), Math.toDegrees(atan2(point.yM, point.xM)))
    }

    private fun invalid(): Nothing = throw NgiiException("BASE_DATA_INVALID", "기준국 관측·항법 파일의 구성을 확인해 주세요.")
}
