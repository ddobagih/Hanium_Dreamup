package kr.co.hanium.dreamup.walksafe.positioneval.core

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.net.ssl.HttpsURLConnection
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class NgiiException(val reasonCode: String, message: String) : Exception(message)

data class NgiiDirectoryFile(val name: String, val byteCount: Long)

data class NgiiRemoteFile(
    val name: String,
    val url: String,
    val archive: Boolean,
)

data class DownloadedBaseData(
    val station: BaseStation,
    val portalHours: List<String>,
    val downloads: List<StrictHttpsTransport.Download>,
    val rinexEntries: List<ExtractedEntry>,
)

class NgiiStationCatalogCache(
    private val file: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxAgeMs: Long = 30L * 24L * 60L * 60L * 1000L,
) {
    companion object {
        private const val SCHEMA = "walksafe.ngii_station_catalog.v1"
        private const val MAX_FUTURE_SKEW_MS = 5L * 60L * 1000L
    }

    init {
        require(maxAgeMs > 0L)
    }

    fun load(): List<BaseStation>? = runCatching {
        require(file.isFile && file.length() in 1..StrictHttpsTransport.JSON_LIMIT_BYTES)
        val root = JSONObject(file.readText(Charsets.UTF_8))
        val keys = mutableSetOf<String>()
        val keyIterator = root.keys()
        while (keyIterator.hasNext()) keys += keyIterator.next()
        require(keys == setOf("schema_version", "saved_at_utc_ms", "catalog_json", "catalog_sha256"))
        require(root.getString("schema_version") == SCHEMA)
        val savedAt = root.getLong("saved_at_utc_ms")
        val age = clock() - savedAt
        require(age in -MAX_FUTURE_SKEW_MS..maxAgeMs)
        val catalogJson = root.getString("catalog_json")
        require(catalogJson.toByteArray(Charsets.UTF_8).size <= StrictHttpsTransport.JSON_LIMIT_BYTES)
        val expectedHash = root.getString("catalog_sha256")
        val actualHash = sha256(catalogJson.toByteArray(Charsets.UTF_8))
        require(
            MessageDigest.isEqual(
                expectedHash.toByteArray(Charsets.US_ASCII),
                actualHash.toByteArray(Charsets.US_ASCII),
            ),
        )
        NgiiJson.parseStationsStrict(catalogJson)
    }.getOrNull()

    fun store(catalogJson: String): Boolean = runCatching {
        NgiiJson.parseStationsStrict(catalogJson)
        val catalogBytes = catalogJson.toByteArray(Charsets.UTF_8)
        require(catalogBytes.size <= StrictHttpsTransport.JSON_LIMIT_BYTES)
        val envelope = JSONObject()
            .put("schema_version", SCHEMA)
            .put("saved_at_utc_ms", clock())
            .put("catalog_json", catalogJson)
            .put("catalog_sha256", sha256(catalogBytes))
            .toString()
            .toByteArray(Charsets.UTF_8)
        val parent = file.parentFile ?: throw IllegalArgumentException("기준국 캐시 상위 폴더가 없습니다.")
        if (!parent.exists() && !parent.mkdirs()) throw IllegalArgumentException("기준국 캐시 폴더를 만들 수 없습니다.")
        val part = File(parent, "${file.name}.part")
        part.delete()
        try {
            FileOutputStream(part).use { output ->
                output.write(envelope)
                output.flush()
                output.fd.sync()
            }
            Files.move(
                part.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            part.delete()
        }
        true
    }.getOrDefault(false)

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}

object NgiiJson {
    fun parseStations(json: String): List<BaseStation> {
        val root = JSONObject(json)
        val array = root.optJSONArray("totalCtrlpnt_list") ?: root.getJSONArray("resultList")
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                if (item.optString("ctrlpnt_se") != "ngii") continue
                val code = item.optString("rinex_nm", item.optString("ctrlpnt_eng_nm")).trim().uppercase()
                if (!code.matches(Regex("[A-Z0-9]{4}"))) continue
                val point = runCatching {
                    GeoPoint(parseDms(item.getString("lat")), parseDms(item.getString("lon")))
                }.getOrNull() ?: continue
                add(
                    BaseStation(
                        code,
                        item.optString("ctrlpnt_nm", code),
                        point,
                        item.optString("oper_sttus") in setOf("O", "1"),
                    ),
                )
            }
        }
    }

    fun parseStationsStrict(json: String): List<BaseStation> {
        val root = JSONObject(json)
        val array = root.optJSONArray("totalCtrlpnt_list") ?: root.getJSONArray("resultList")
        var ngiiRecordCount = 0
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            if (item.optString("ctrlpnt_se") == "ngii") ngiiRecordCount++
        }
        val stations = parseStations(json)
        require(stations.isNotEmpty() && stations.size == ngiiRecordCount) { "NGII 기준국 목록 스키마가 올바르지 않습니다." }
        require(stations.map(BaseStation::code).distinct().size == stations.size) { "NGII 기준국 코드가 중복되었습니다." }
        require(stations.all { it.location.latitudeDeg in 30.0..40.0 && it.location.longitudeDeg in 120.0..135.0 }) {
            "NGII 기준국 좌표가 대한민국 범위를 벗어납니다."
        }
        return stations
    }

    fun parseHourlyHealth(json: String): Map<String, Boolean> {
        val root = JSONObject(json)
        val array = root.optJSONArray("ctrlpntSttus") ?: return emptyMap()
        return buildMap {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                put(item.optString("ctrlpnt_eng_nm").uppercase(), item.optString("rinex_hourly_sttus_at") == "Y")
            }
        }
    }

    fun parseCoverage(json: String): List<RinexHour> {
        val array = JSONObject(json).getJSONArray("resultList")
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                if (item.optString("rinex_time_se") != "Hourly") continue
                val hour = item.optString("rinex_date_hour")
                if (!hour.matches(Regex("\\d{10}"))) continue
                add(RinexHour(hour, item.optInt("data_count", -1), item.optInt("data_count_all", -1)))
            }
        }
    }

    fun parseDirectoryFiles(json: String): List<NgiiDirectoryFile> {
        val array = JSONObject(json).getJSONArray("fileList")
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val name = item.optString("name").trim()
                if (!name.matches(Regex("[A-Za-z0-9._-]{1,120}"))) continue
                val size = item.optLong("size", -1L)
                if (size == 0L || size > StrictHttpsTransport.DOWNLOAD_LIMIT_BYTES) continue
                add(NgiiDirectoryFile(name, size))
            }
        }
    }

    fun parseDms(raw: String): Double {
        val parts = raw.trim().split('-')
        require(parts.size == 3)
        val degrees = parts[0].trim().toDouble()
        val minutes = parts[1].trim().toDouble()
        val seconds = parts[2].trim().toDouble()
        require(minutes in 0.0..<60.0 && seconds in 0.0..<60.0)
        val sign = if (degrees < 0) -1 else 1
        return degrees + sign * minutes / 60.0 + sign * seconds / 3600.0
    }
}

open class StrictHttpsTransport(
    private val allowedHost: String = "geodesy.ngii.go.kr",
    private val connectionFactory: (URL) -> HttpsURLConnection = { url ->
        url.openConnection() as HttpsURLConnection
    },
) {
    companion object {
        const val JSON_LIMIT_BYTES = 4L * 1024L * 1024L
        const val DOWNLOAD_LIMIT_BYTES = 128L * 1024L * 1024L
        private const val MAX_REDIRECTS = 3
    }

    data class Download(val file: File, val sha256: String, val byteCount: Long, val sourceUrl: String)

    open fun getText(url: String): String {
        val bytes = execute(url, null, JSON_LIMIT_BYTES, null, null)
        return bytes.first.toString(Charsets.UTF_8)
    }

    open fun getText(url: String, cancellation: AnalysisCancellation): String {
        val bytes = execute(url, null, JSON_LIMIT_BYTES, null, cancellation)
        return bytes.first.toString(Charsets.UTF_8)
    }

    open fun download(url: String, fileKey: CharArray, destination: File): Download {
        destination.parentFile?.mkdirs()
        val part = File(destination.parentFile, "${destination.name}.part")
        part.delete()
        return try {
            val result = execute(url, fileKey, DOWNLOAD_LIMIT_BYTES, part, null)
            if (!part.renameTo(destination)) throw NgiiException("PRIVATE_COMMIT_FAILED", "다운로드 파일을 확정하지 못했습니다.")
            Download(destination, result.second, result.third, result.fourth)
        } catch (error: Throwable) {
            part.delete()
            throw error
        }
    }

    open fun download(
        url: String,
        fileKey: CharArray,
        destination: File,
        cancellation: AnalysisCancellation,
    ): Download {
        destination.parentFile?.mkdirs()
        val part = File(destination.parentFile, "${destination.name}.part")
        part.delete()
        return try {
            val result = execute(url, fileKey, DOWNLOAD_LIMIT_BYTES, part, cancellation)
            if (!part.renameTo(destination)) throw NgiiException("PRIVATE_COMMIT_FAILED", "다운로드 파일을 확정하지 못했습니다.")
            Download(destination, result.second, result.third, result.fourth)
        } catch (error: Throwable) {
            part.delete()
            throw error
        }
    }

    private data class Response(val first: ByteArray, val second: String, val third: Long, val fourth: String)

    private fun execute(
        initialUrl: String,
        credential: CharArray?,
        limit: Long,
        sink: File?,
        cancellation: AnalysisCancellation?,
    ): Response {
        cancellation?.throwIfCancelled()
        var current = validateUri(URI(initialUrl), credential != null)
        val visited = mutableSetOf<String>()
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            if (!visited.add(current.toASCIIString())) throw NgiiException("HTTPS_REDIRECT_LOOP", "리다이렉트가 반복됩니다.")
            val connection = connectionFactory(current.toURL())
            val cancellationRegistration = cancellation?.register(connection::disconnect)
            try {
                cancellation?.throwIfCancelled()
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15_000
                connection.readTimeout = 60_000
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept-Encoding", "identity")
                if (credential != null) connection.setRequestProperty("NGII-File-Key", String(credential))
                val status = connection.responseCode
                cancellation?.throwIfCancelled()
                if (status in setOf(301, 302, 303, 307, 308)) {
                    if (redirectCount >= MAX_REDIRECTS) throw NgiiException("HTTPS_REDIRECT_LIMIT", "리다이렉트가 너무 많습니다.")
                    val location = connection.getHeaderField("Location")
                        ?: throw NgiiException("HTTPS_REDIRECT_INVALID", "리다이렉트 주소가 없습니다.")
                    current = validateUri(current.resolve(location), credential != null)
                    return@repeat
                }
                if (status == 401 || status == 403) throw NgiiException("NGII_KEY_REJECTED", "다운로드 키를 확인해 주세요.")
                if (status == 404) throw NgiiException("BASE_DATA_UNAVAILABLE", "해당 시간 기준국 자료가 없습니다.")
                if (status !in 200..299) throw NgiiException("NGII_HTTP_ERROR", "국토지리정보원 응답 코드 $status")
                val declared = connection.contentLengthLong
                if (declared > limit) throw NgiiException("DOWNLOAD_TOO_LARGE", "응답이 허용 크기를 넘습니다.")
                if (credential != null && declared == 0L) throw NgiiException("NGII_EMPTY_RESPONSE", "빈 파일입니다. 다운로드 키를 확인해 주세요.")
                val digest = MessageDigest.getInstance("SHA-256")
                var count = 0L
                val memory = if (sink == null) java.io.ByteArrayOutputStream() else null
                val output = if (sink != null) FileOutputStream(sink) else memory!!
                connection.inputStream.use { input ->
                    output.use { target ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            cancellation?.throwIfCancelled()
                            val read = input.read(buffer)
                            if (read < 0) break
                            count += read
                            if (count > limit) throw NgiiException("DOWNLOAD_TOO_LARGE", "실제 응답이 허용 크기를 넘습니다.")
                            digest.update(buffer, 0, read)
                            target.write(buffer, 0, read)
                        }
                        target.flush()
                        if (target is FileOutputStream) target.fd.sync()
                    }
                }
                if (count == 0L) throw NgiiException("NGII_EMPTY_RESPONSE", "국토지리정보원 응답이 비어 있습니다.")
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                cancellation?.throwIfCancelled()
                return Response(memory?.toByteArray() ?: byteArrayOf(), hash, count, current.toASCIIString())
            } catch (error: Throwable) {
                cancellation?.throwIfCancelled()
                throw error
            } finally {
                cancellationRegistration?.close()
                connection.disconnect()
            }
        }
        throw NgiiException("HTTPS_REDIRECT_LIMIT", "리다이렉트가 너무 많습니다.")
    }

    internal fun validateUri(uri: URI, carriesCredential: Boolean): URI {
        if (uri.scheme != "https" || !uri.host.equals(allowedHost, ignoreCase = true) || uri.port !in setOf(-1, 443)) {
            throw NgiiException("HTTPS_ORIGIN_REJECTED", "허용되지 않은 다운로드 주소입니다.")
        }
        if (uri.userInfo != null || uri.fragment != null) throw NgiiException("HTTPS_URL_REJECTED", "안전하지 않은 URL입니다.")
        if (carriesCredential && (uri.rawQuery != null || !uri.path.startsWith("/file/ngii/"))) {
            throw NgiiException("CREDENTIAL_SCOPE_REJECTED", "다운로드 키를 허용되지 않은 경로로 보낼 수 없습니다.")
        }
        return uri
    }
}

class NgiiPortalClient(
    private val transport: StrictHttpsTransport = StrictHttpsTransport(),
    private val stationCatalogCache: NgiiStationCatalogCache? = null,
    private val cancellation: AnalysisCancellation? = null,
) {
    private val root = "https://geodesy.ngii.go.kr"

    companion object {
        private const val MAX_SESSION_DOWNLOAD_BYTES = 512L * 1024L * 1024L
        private const val MAX_SESSION_RINEX_BYTES = 1024L * 1024L * 1024L
        private const val MAX_BASELINE_DISTANCE_M = 30_000.0
        private const val MAX_STATION_HEADER_DISTANCE_M = 1_000.0
    }

    private fun getText(url: String): String = cancellation?.let { transport.getText(url, it) }
        ?: transport.getText(url)

    private fun download(url: String, fileKey: CharArray, destination: File): StrictHttpsTransport.Download =
        cancellation?.let { transport.download(url, fileKey, destination, it) }
            ?: transport.download(url, fileKey, destination)

    fun stationCatalog(): List<BaseStation> {
        val live = try {
            val json = getText("$root/backend/portal/openapi/totalCtrlpnt/selectTotalCtrlpntList?mngr_id=MC06")
            val stations = NgiiJson.parseStationsStrict(json)
            stationCatalogCache?.store(json)
            stations
        } catch (cancelled: AnalysisCancelledException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
        if (live != null) return live
        return stationCatalogCache?.load()
            ?: throw NgiiException("STATION_CATALOG_UNAVAILABLE", "검증 가능한 최신 기준국 목록이나 유효한 캐시가 없습니다.")
    }

    fun hourlyHealth(): Map<String, Boolean> = NgiiJson.parseHourlyHealth(
        getText("$root/backend/portal/openapi/main/m001/list"),
    )

    fun coverage(stationCode: String, startUtcMs: Long, endUtcMs: Long): List<RinexHour> {
        val zone = ZoneOffset.UTC
        val start = Instant.ofEpochMilli(startUtcMs).atZone(zone).withMinute(0).withSecond(0).withNano(0)
        val end = Instant.ofEpochMilli(endUtcMs).atZone(zone).withMinute(0).withSecond(0).withNano(0)
        val params = linkedMapOf(
            "start" to start.toOffsetDateTime().toString(),
            "end" to end.toOffsetDateTime().toString(),
            "ctrlpnt_se" to "ngii",
            "ctrlpnt_eng_nm" to stationCode,
            "rinex_time_se" to "Hourly",
        ).entries.joinToString("&") { (name, value) ->
            "$name=${URLEncoder.encode(value, StandardCharsets.UTF_8.name())}"
        }
        return NgiiJson.parseCoverage(
            getText("$root/backend/portal/openapi/totalRinex/selectTotalRinex/calendar?$params"),
        )
    }

    fun nearestCompleteStation(trace: TraceSession): Pair<BaseStation, List<RinexHour>> {
        val ranked = rankedStations(trace)
        val selected = ranked.firstOrNull()
            ?: throw NgiiException("BASE_DATA_UNAVAILABLE", "30km 안에서 사용할 기준국 후보를 찾지 못했습니다.")
        return selected.station to selected.coverage
    }

    fun directoryFiles(portalHour: String): List<NgiiDirectoryFile> = NgiiJson.parseDirectoryFiles(
        getText(RinexDownloadPlanner.directoryApiUrl(portalHour)),
    )

    fun downloadNearestCompleteBase(
        trace: TraceSession,
        fileKey: CharArray,
        workRoot: File,
    ): DownloadedBaseData {
        require(!workRoot.exists()) { "새 기준국 작업 폴더가 필요합니다." }
        val requiredHours = RinexDownloadPlanner.requiredPortalHours(trace.startUtcEpochMs, trace.endUtcEpochMs)
        val candidates = rankedStations(trace)
        if (!workRoot.mkdirs()) throw NgiiException("PRIVATE_COMMIT_FAILED", "기준국 작업 폴더를 만들 수 없습니다.")
        val directoryListings = mutableMapOf<String, List<NgiiDirectoryFile>>()
        var lastDataFailure: NgiiException? = null
        for ((candidateIndex, candidate) in candidates.withIndex()) {
            val station = candidate.station
            val stationRoot = File(workRoot, "station-$candidateIndex")
            try {
                val downloads = mutableListOf<StrictHttpsTransport.Download>()
                val entries = mutableListOf<ExtractedEntry>()
                requiredHours.forEachIndexed { hourIndex, hour ->
                    val hourRoot = File(stationRoot, "hour-$hourIndex")
                    val remoteFiles = plannedRemoteFiles(station.code, hour, directoryListings)
                    val hourEntries = downloadAndExtractHour(remoteFiles, fileKey, hourRoot, downloads)
                    entries += validateHour(hourEntries, station, hour)
                    if (entries.sumOf(ExtractedEntry::byteCount) > MAX_SESSION_RINEX_BYTES) {
                        throw NgiiException("DOWNLOAD_TOO_LARGE", "세션 기준국 RINEX 전체 크기가 제한을 넘습니다.")
                    }
                }
                return DownloadedBaseData(station, requiredHours, downloads, entries)
            } catch (error: NgiiException) {
                stationRoot.deleteRecursively()
                if (error.reasonCode !in setOf("BASE_DATA_UNAVAILABLE", "BASE_DATA_INVALID")) throw error
                lastDataFailure = error
            } catch (error: IllegalArgumentException) {
                stationRoot.deleteRecursively()
                lastDataFailure = NgiiException("BASE_DATA_INVALID", error.message ?: "기준국 자료 형식이 잘못되었습니다.")
            }
        }
        workRoot.deleteRecursively()
        throw NgiiException(
            "BASE_DATA_UNAVAILABLE",
            lastDataFailure?.message ?: "가까운 정상 기준국에 필요한 모든 시간 자료가 없습니다.",
        )
    }

    private fun plannedRemoteFiles(
        stationCode: String,
        portalHour: String,
        directoryListings: MutableMap<String, List<NgiiDirectoryFile>>,
    ): List<NgiiRemoteFile> {
        val listing = directoryListings.getOrPut(portalHour) {
            try {
                directoryFiles(portalHour)
            } catch (cancelled: AnalysisCancelledException) {
                throw cancelled
            } catch (_: Throwable) {
                emptyList()
            }
        }
        return RinexDownloadPlanner.remoteFiles(stationCode, portalHour, listing)
    }

    private fun downloadAndExtractHour(
        remoteFiles: List<NgiiRemoteFile>,
        fileKey: CharArray,
        hourRoot: File,
        downloads: MutableList<StrictHttpsTransport.Download>,
    ): List<ExtractedEntry> {
        if (!hourRoot.mkdirs()) throw NgiiException("PRIVATE_COMMIT_FAILED", "시간별 기준국 폴더를 만들 수 없습니다.")
        return try {
            buildList {
                remoteFiles.forEachIndexed { index, remote ->
                    val payload = File(hourRoot, "download-$index.bin")
                    val downloaded = download(remote.url, fileKey, payload)
                    downloads += downloaded
                    if (downloads.sumOf(StrictHttpsTransport.Download::byteCount) > MAX_SESSION_DOWNLOAD_BYTES) {
                        throw NgiiException("DOWNLOAD_TOO_LARGE", "세션 기준국 다운로드 전체 크기가 제한을 넘습니다.")
                    }
                    val materialized = if (remote.archive) {
                        SafeZipExtractor.extract(payload, File(hourRoot, "archive-$index"))
                    } else {
                        listOf(SafeRinexFileExtractor.extract(payload, remote.name, File(hourRoot, "direct"), index))
                    }
                    if (!payload.delete()) throw NgiiException("PRIVATE_CLEANUP_FAILED", "압축 원본 임시 파일을 지우지 못했습니다.")
                    addAll(materialized)
                }
            }
        } catch (cancelled: AnalysisCancelledException) {
            throw cancelled
        } catch (error: NgiiException) {
            throw error
        } catch (error: Exception) {
            throw NgiiException("BASE_DATA_INVALID", error.message ?: "기준국 압축 파일을 처리하지 못했습니다.")
        }
    }

    private fun validateHour(
        entries: List<ExtractedEntry>,
        station: BaseStation,
        portalHour: String,
    ): List<ExtractedEntry> {
        val start = RinexDownloadPlanner.portalHourStartUtcMs(portalHour)
        val end = start + 3_600_000L - 1L
        val validated = entries.mapNotNull { entry ->
            val expectedType = RinexDownloadPlanner.rinexTypeForFile(station.code, portalHour, entry.originalName)
            if (expectedType == null) {
                entry.file.delete()
                return@mapNotNull null
            }
            val header = try {
                RinexHeaderValidator.validate(entry.file, start, end)
            } catch (error: IllegalArgumentException) {
                throw NgiiException("BASE_DATA_INVALID", error.message ?: "RINEX 형식이 잘못되었습니다.")
            }
            if (header.type != expectedType) {
                throw NgiiException("BASE_DATA_INVALID", "RINEX 파일명과 헤더 자료 종류가 일치하지 않습니다.")
            }
            if (header.type == 'O') validateStationBinding(header, station)
            entry to header
        }
        val margin = 5L * 60L * 1000L
        val completeObservation = validated.any { (_, header) ->
            header.type == 'O' &&
                header.firstObservationUtcMs != null && header.firstObservationUtcMs <= start + margin &&
                header.lastObservationUtcMs != null && header.lastObservationUtcMs >= end - margin
        }
        val hasNavigation = validated.any { (_, header) -> header.type in setOf('N', 'G', 'H', 'L', 'J', 'C', 'I', 'S') }
        if (!completeObservation || !hasNavigation) {
            throw NgiiException("BASE_DATA_INVALID", "$portalHour 기준국 관측·항법 RINEX를 모두 확인하지 못했습니다.")
        }
        return validated.map { it.first }
    }

    private fun validateStationBinding(header: RinexHeader, station: BaseStation) {
        header.markerName?.let { marker ->
            val normalized = marker.uppercase().filter(Char::isLetterOrDigit)
            if (normalized.length >= 4 && station.code !in normalized) {
                throw NgiiException("BASE_DATA_INVALID", "RINEX MARKER NAME이 선택한 기준국과 일치하지 않습니다.")
            }
        }
        val approximate = header.approximatePosition ?: return
        val headerLocation = ecefToGeoPoint(approximate)
            ?: throw NgiiException("BASE_DATA_INVALID", "RINEX 기준국 좌표가 유효하지 않습니다.")
        if (distanceMeters(headerLocation, station.location) > MAX_STATION_HEADER_DISTANCE_M) {
            throw NgiiException("BASE_DATA_INVALID", "RINEX 기준국 좌표가 선택한 기준국과 일치하지 않습니다.")
        }
    }

    private data class RankedStation(
        val station: BaseStation,
        val distanceM: Double,
        val currentPreferred: Boolean,
        val calendarComplete: Boolean,
        val coverage: List<RinexHour>,
    )

    private fun rankedStations(trace: TraceSession): List<RankedStation> {
        val requiredHours = RinexDownloadPlanner.requiredPortalHours(trace.startUtcEpochMs, trace.endUtcEpochMs).toSet()
        val health = try {
            hourlyHealth()
        } catch (cancelled: AnalysisCancelledException) {
            throw cancelled
        } catch (_: Throwable) {
            emptyMap()
        }
        val withinBaseline = stationCatalog().map { station ->
            val withHealth = station.copy(hourlyHealthy = health[station.code] == true)
            withHealth to distanceMeters(trace.center, withHealth.location)
        }.filter { (_, distance) -> distance <= MAX_BASELINE_DISTANCE_M }
        if (withinBaseline.isEmpty()) {
            throw NgiiException("BASE_STATION_TOO_FAR", "테스트 경로 30km 안에 검증 가능한 기준국이 없습니다.")
        }
        return withinBaseline.map { (station, distance) ->
            val stationCoverage = try {
                coverage(station.code, trace.startUtcEpochMs, trace.endUtcEpochMs)
            } catch (cancelled: AnalysisCancelledException) {
                throw cancelled
            } catch (_: Throwable) {
                emptyList()
            }
            val completeHours = stationCoverage.filter(RinexHour::complete).map(RinexHour::portalHour).toSet()
            RankedStation(
                station = station,
                distanceM = distance,
                currentPreferred = station.operational && station.hourlyHealthy,
                calendarComplete = completeHours.containsAll(requiredHours),
                coverage = stationCoverage,
            )
        }.sortedWith(
            compareByDescending<RankedStation>(RankedStation::calendarComplete)
                .thenByDescending(RankedStation::currentPreferred)
                .thenBy(RankedStation::distanceM),
        )
    }

    private fun ecefToGeoPoint(point: EcefPoint): GeoPoint? = runCatching {
        val semiMajorM = 6_378_137.0
        val eccentricitySquared = 6.69437999014e-3
        val longitude = atan2(point.yM, point.xM)
        val horizontal = sqrt(point.xM * point.xM + point.yM * point.yM)
        require(horizontal > 1.0)
        var latitude = atan2(point.zM, horizontal * (1.0 - eccentricitySquared))
        repeat(8) {
            val sinLatitude = sin(latitude)
            val primeVertical = semiMajorM / sqrt(1.0 - eccentricitySquared * sinLatitude * sinLatitude)
            latitude = atan2(point.zM + eccentricitySquared * primeVertical * sinLatitude, horizontal)
        }
        GeoPoint(Math.toDegrees(latitude), Math.toDegrees(longitude))
    }.getOrNull()

    private fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = Math.toRadians(a.latitudeDeg)
        val lat2 = Math.toRadians(b.latitudeDeg)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.longitudeDeg - a.longitudeDeg)
        val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        return 2 * 6_371_008.8 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
}

object RinexDownloadPlanner {
    private val portalZone = ZoneOffset.UTC
    private val keyFormatter = DateTimeFormatter.ofPattern("yyyyMMddHH")

    fun requiredPortalHours(startUtcMs: Long, endUtcMs: Long): List<String> {
        require(endUtcMs >= startUtcMs)
        var value = Instant.ofEpochMilli(startUtcMs).atZone(portalZone).withMinute(0).withSecond(0).withNano(0)
        val end = Instant.ofEpochMilli(endUtcMs).atZone(portalZone).withMinute(0).withSecond(0).withNano(0)
        return buildList {
            while (!value.isAfter(end)) {
                add(value.format(keyFormatter))
                value = value.plusHours(1)
                require(size <= 49) { "48시간을 넘는 trace는 한 번에 분석할 수 없습니다." }
            }
        }
    }

    fun archiveUrl(stationCode: String, portalHour: String): String {
        return downloadUrl(portalHour, archiveFileName(stationCode, portalHour))
    }

    fun directoryApiUrl(portalHour: String): String {
        return "https://geodesy.ngii.go.kr/backend/portal/openapi/dir?path=" +
            URLEncoder.encode(directoryPath(portalHour), StandardCharsets.UTF_8.name())
    }

    fun remoteFiles(
        stationCode: String,
        portalHour: String,
        directoryFiles: List<NgiiDirectoryFile>,
    ): List<NgiiRemoteFile> {
        val archiveName = archiveFileName(stationCode, portalHour)
        directoryFiles.firstOrNull { it.name == archiveName }?.let {
            return listOf(NgiiRemoteFile(it.name, downloadUrl(portalHour, it.name), archive = true))
        }
        val prefix = directFilePrefix(stationCode, portalHour)
        val pattern = Regex("^${Regex.escape(prefix)}([oOnNgGlLsShHjJcCiI])\\.(Z|gz)$")
        val selected = directoryFiles.mapNotNull { file ->
            val match = pattern.matchEntire(file.name) ?: return@mapNotNull null
            match.groupValues[1].uppercase() to file
        }.groupBy({ it.first }, { it.second }).mapValues { (_, candidates) ->
            candidates.sortedWith(compareBy<NgiiDirectoryFile>({ !it.name.endsWith(".gz") }, { it.name })).first()
        }
        val observation = selected["O"]
        val navigation = selected.filterKeys { it != "O" }.values.sortedBy(NgiiDirectoryFile::name)
        if (observation == null || navigation.isEmpty()) {
            return listOf(NgiiRemoteFile(archiveName, archiveUrl(stationCode, portalHour), archive = true))
        }
        return (listOf(observation) + navigation).map {
            NgiiRemoteFile(it.name, downloadUrl(portalHour, it.name), archive = false)
        }
    }

    fun portalHourStartUtcMs(portalHour: String): Long {
        require(portalHour.matches(Regex("\\d{10}")))
        return java.time.LocalDateTime.parse(portalHour, keyFormatter).toInstant(ZoneOffset.UTC).toEpochMilli()
    }

    fun rinexTypeForFile(stationCode: String, portalHour: String, originalName: String): Char? {
        val baseName = originalName.replace('\\', '/').substringAfterLast('/')
        val match = Regex("^${Regex.escape(directFilePrefix(stationCode, portalHour))}([oOnNgGlLsShHjJcCiI])$")
            .matchEntire(baseName)
            ?: return null
        return match.groupValues[1].uppercase().single()
    }

    private fun archiveFileName(stationCode: String, portalHour: String): String =
        directFilePrefix(stationCode, portalHour) + "z.zip"

    private fun directFilePrefix(stationCode: String, portalHour: String): String {
        require(stationCode.matches(Regex("[A-Z0-9]{4}")))
        require(portalHour.matches(Regex("\\d{10}")))
        val year = portalHour.substring(0, 4).toInt()
        val month = portalHour.substring(4, 6).toInt()
        val day = portalHour.substring(6, 8).toInt()
        val hour = portalHour.substring(8, 10).toInt()
        val date = java.time.LocalDate.of(year, month, day)
        val dayOfYear = "%03d".format(date.dayOfYear)
        val hourLetter = ('a'.code + hour).toChar()
        val shortYear = "%02d".format(year % 100)
        return "$stationCode$dayOfYear$hourLetter.$shortYear"
    }

    private fun directoryPath(portalHour: String): String {
        require(portalHour.matches(Regex("\\d{10}")))
        val year = portalHour.substring(0, 4).toInt()
        val date = java.time.LocalDate.of(
            year,
            portalHour.substring(4, 6).toInt(),
            portalHour.substring(6, 8).toInt(),
        )
        return "/ngii/RINEX/Hourly/$year/${"%03d".format(date.dayOfYear)}"
    }

    private fun downloadUrl(portalHour: String, fileName: String): String {
        require(fileName.matches(Regex("[A-Za-z0-9._-]{1,120}")))
        return "https://geodesy.ngii.go.kr/file${directoryPath(portalHour)}/$fileName"
    }
}
