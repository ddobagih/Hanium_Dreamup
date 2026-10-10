import com.android.build.api.artifact.SingleArtifact
import groovy.json.JsonSlurper
import java.net.URI
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Comparator

plugins {
    id("com.android.application")
}

dependencyLocking {
    lockMode.set(LockMode.STRICT)
    lockAllConfigurations()
}

val sourceCommitPattern = Regex("^[0-9a-fA-F]{40}$")
val configuredSourceCommit = providers.gradleProperty("WALKSAFE_SOURCE_COMMIT")
    .orElse(providers.environmentVariable("WALKSAFE_SOURCE_COMMIT"))
    .orNull
    ?.trim()
val verifiedSourceCommit = configuredSourceCommit?.takeIf(sourceCommitPattern::matches)?.lowercase()
fun normalizedHttpsOriginOrNull(raw: String?): String? {
    val value = raw?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null
    val uri = runCatching { URI(value) }.getOrNull() ?: return null
    if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) return null
    if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
    if (uri.rawPath?.takeIf { it.isNotEmpty() } !in setOf(null, "/")) return null
    val port = if (uri.port == -1) "" else ":${uri.port}"
    return "https://${uri.host.lowercase()}$port"
}

val configuredGatewayOrigin = providers.gradleProperty("WALKSAFE_GATEWAY_ORIGIN")
    .orElse(providers.environmentVariable("WALKSAFE_GATEWAY_ORIGIN"))
    .orNull
    ?.trim()
val verifiedReleaseGatewayOrigin = normalizedHttpsOriginOrNull(configuredGatewayOrigin)
val developmentReportQueue = providers.gradleProperty("developmentReportQueue").orNull == "true"
val developmentQuickStart = providers.gradleProperty("developmentQuickStart").orNull == "true"
fun buildConfigString(value: String): String = "\"" + value
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "\\n")
    .replace("\r", "\\r") + "\""
val debugGatewayOrigin = if (developmentQuickStart) {
    "http://127.0.0.1:8081"
} else verifiedReleaseGatewayOrigin ?: "http://127.0.0.1:8081"
fun configuredReportQueueValue(name: String): String? = providers.gradleProperty(name)
    .orElse(providers.environmentVariable(name))
    .orNull
    ?.trim()
    ?.takeIf { it.isNotEmpty() }

fun normalizedDebugOriginOrNull(raw: String?): String? {
    normalizedHttpsOriginOrNull(raw)?.let { return it }
    val value = raw?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null
    val uri = runCatching { URI(value) }.getOrNull() ?: return null
    if (!uri.scheme.equals("http", ignoreCase = true)) return null
    if (uri.host.isNullOrBlank() || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) {
        return null
    }
    if (uri.rawPath?.takeIf { it.isNotEmpty() } !in setOf(null, "/")) return null
    if (!uri.host.equals("127.0.0.1") && !uri.host.equals("localhost", ignoreCase = true)) {
        return null
    }
    val port = if (uri.port == -1) "" else ":${uri.port}"
    return "http://${uri.host.lowercase()}$port"
}

val configuredReportQueueTestOrigin =
    configuredReportQueueValue("WALKSAFE_REPORT_QUEUE_TEST_ORIGIN")
        ?: "http://127.0.0.1:8081"
val verifiedReportQueueTestOrigin =
    normalizedDebugOriginOrNull(configuredReportQueueTestOrigin)

val reportQueueEnabled = when (
    val configured = configuredReportQueueValue("WALKSAFE_REPORT_QUEUE_ENABLED")?.lowercase()
) {
    null, "false" -> false
    "true" -> true
    else -> error("WALKSAFE_REPORT_QUEUE_ENABLED must be true or false")
}
fun configuredPositiveInt(name: String): Int? = configuredReportQueueValue(name)
    ?.toIntOrNull()
    ?.takeIf { it > 0 }
fun configuredPositiveLong(name: String): Long? = configuredReportQueueValue(name)
    ?.toLongOrNull()
    ?.takeIf { it > 0L }

val reportQueueMaxEntries = configuredPositiveInt("WALKSAFE_REPORT_QUEUE_MAX_ENTRIES")
val reportQueueMaxPayloadBytes =
    configuredPositiveInt("WALKSAFE_REPORT_QUEUE_MAX_PAYLOAD_BYTES")
val reportQueueMaxStoredEntryBytes =
    configuredPositiveLong("WALKSAFE_REPORT_QUEUE_MAX_STORED_ENTRY_BYTES")
val reportQueueMaxTotalBytes =
    configuredPositiveLong("WALKSAFE_REPORT_QUEUE_MAX_TOTAL_BYTES")
val reportQueueAutomaticMaxEntries =
    configuredPositiveInt("WALKSAFE_REPORT_QUEUE_AUTOMATIC_MAX_ENTRIES")
val reportQueueAutomaticMaxTotalBytes =
    configuredPositiveLong("WALKSAFE_REPORT_QUEUE_AUTOMATIC_MAX_TOTAL_BYTES")

if (reportQueueEnabled) {
    check(verifiedReportQueueTestOrigin != null) {
        "Enabled report queue requires WALKSAFE_REPORT_QUEUE_TEST_ORIGIN as HTTPS or loopback HTTP"
    }
    check(reportQueueMaxEntries != null && reportQueueMaxEntries >= 2) {
        "Enabled report queue requires WALKSAFE_REPORT_QUEUE_MAX_ENTRIES >= 2"
    }
    check(reportQueueMaxPayloadBytes != null && reportQueueMaxPayloadBytes <= 16 * 1_024 * 1_024) {
        "Enabled report queue requires WALKSAFE_REPORT_QUEUE_MAX_PAYLOAD_BYTES in 1..16777216"
    }
    check(
        reportQueueMaxStoredEntryBytes != null &&
            reportQueueMaxStoredEntryBytes >= reportQueueMaxPayloadBytes.toLong() &&
            reportQueueMaxStoredEntryBytes <= 44L * 1_024L * 1_024L
    ) {
        "Enabled report queue requires max stored entry bytes from max payload to 46137344"
    }
    check(
        reportQueueMaxTotalBytes != null &&
            reportQueueMaxTotalBytes >= reportQueueMaxStoredEntryBytes
    ) {
        "Enabled report queue requires total bytes >= max stored entry bytes"
    }
    check(
        reportQueueAutomaticMaxEntries != null &&
            reportQueueAutomaticMaxEntries < reportQueueMaxEntries
    ) {
        "Enabled report queue requires an automatic entry limit below the total entry limit"
    }
    check(
        reportQueueAutomaticMaxTotalBytes != null &&
            reportQueueAutomaticMaxTotalBytes >= reportQueueMaxStoredEntryBytes &&
            reportQueueAutomaticMaxTotalBytes < reportQueueMaxTotalBytes &&
            reportQueueMaxTotalBytes - reportQueueAutomaticMaxTotalBytes >=
            reportQueueMaxStoredEntryBytes
    ) {
        "Enabled report queue requires automatic capacity plus one max stored explicit entry reserve"
    }
}
val reportQueueBuildMaxEntries = if (reportQueueEnabled) reportQueueMaxEntries!! else 0
val reportQueueBuildMaxPayloadBytes = if (reportQueueEnabled) reportQueueMaxPayloadBytes!! else 0
val reportQueueBuildMaxStoredEntryBytes =
    if (reportQueueEnabled) reportQueueMaxStoredEntryBytes!! else 0L
val reportQueueBuildMaxTotalBytes = if (reportQueueEnabled) reportQueueMaxTotalBytes!! else 0L
val reportQueueBuildAutomaticMaxEntries =
    if (reportQueueEnabled) reportQueueAutomaticMaxEntries!! else 0
val reportQueueBuildAutomaticMaxTotalBytes =
    if (reportQueueEnabled) reportQueueAutomaticMaxTotalBytes!! else 0L
val reportQueueBuildTestOrigin =
    if (reportQueueEnabled) verifiedReportQueueTestOrigin!! else "disabled"
// S1617-18: RAW phone-queue limits are a measured device value (GATE-PHONE-QUEUE-BYTE-LIMIT).
// No default is written here; without both values the app keeps at most one retained segment.
val rawQueueBuildMaxSegments = configuredPositiveInt("WALKSAFE_RAW_QUEUE_MAX_SEGMENTS") ?: 0
val rawQueueBuildMaxTotalBytes = configuredPositiveLong("WALKSAFE_RAW_QUEUE_MAX_TOTAL_BYTES") ?: 0L
val debugLongLivedLoginEnabled =
    providers.gradleProperty("walksafe.longLivedLoginEnabled").orNull == "true"
val debugPostLoginDeviceCheckRequired =
    providers.gradleProperty("walksafe.postLoginDeviceCheckRequired").orNull != "false"
val debugGuidanceStartBypass =
    providers.gradleProperty("walksafe.guidanceStartBypass").orNull == "true"
val debugGuidanceBuildMarker =
    if (debugGuidanceStartBypass) "walksafe-debug-v1-bypass-on" else "walksafe-debug-v1"
val debugGuidanceArtifactLabel = if (debugGuidanceStartBypass) "on" else "off"
val validateWalkSafeSourceCommit by tasks.registering {
    doLast {
        check(verifiedSourceCommit != null) {
            "Release builds require WALKSAFE_SOURCE_COMMIT as a 40-character hexadecimal commit id"
        }
        check(verifiedReleaseGatewayOrigin != null) {
            "Release builds require WALKSAFE_GATEWAY_ORIGIN as one exact root HTTPS origin"
        }
    }
}

val bundledVoskModelAssetDirectory =
    file("src/main/assets/voice-models/vosk-model-small-ko-0.22")
private fun verifyBundledVoskModelDirectory(bundledVoskModelAssetDirectory: File) {
    val manifest = bundledVoskModelAssetDirectory.resolve("MODEL_FILES.sha256")
    check(manifest.isFile) {
        "Bundled Korean Vosk model is missing. Run " +
            "python3 scripts/prepare_vosk_ko_small_model.py from the repository root."
    }
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
    check(!Files.isSymbolicLink(manifest.toPath())) {
        "Bundled Korean Vosk manifest contains a symbolic link."
    }
    val manifestLines = manifest.readLines().filter(String::isNotBlank)
    check(manifestLines.isNotEmpty()) { "Bundled Korean Vosk manifest is empty." }
    // Same pinned manifest as prepare_vosk_ko_small_model.py; no asset contract change.
    check(sha256(manifest) == "8396458f1990e174b88c531f7db14587a4b52273443a1b39dfd6b75552e135c3") {
        "Bundled Korean Vosk manifest SHA-256 mismatch. Re-run the model preparation script."
    }
    val expectedFiles = mutableSetOf("MODEL_FILES.sha256")
    manifestLines.forEach { line ->
            val separator = line.indexOf("  ")
            check(
                separator == 64 &&
                    line.take(64).matches(Regex("[0-9a-f]{64}")),
            ) { "Bundled Korean Vosk manifest is malformed." }
            val relative = line.substring(separator + 2).also { relative ->
                check(
                    relative.isNotBlank() &&
                        !relative.startsWith('/') &&
                        '\\' !in relative &&
                        relative.split('/').none { it.isBlank() || it == "." || it == ".." },
                ) { "Bundled Korean Vosk manifest contains an unsafe path." }
            }
            check(expectedFiles.add(relative)) { "Bundled Korean Vosk manifest repeats a path." }
            val payload = bundledVoskModelAssetDirectory.resolve(relative)
            check(payload.isFile && !Files.isSymbolicLink(payload.toPath()) &&
                payload.canonicalFile.toPath().startsWith(bundledVoskModelAssetDirectory.canonicalFile.toPath())) {
                "Bundled Korean Vosk model is incomplete or contains an unsafe file: $relative"
            }
            check(sha256(payload) == line.take(64)) {
                "Bundled Korean Vosk model SHA-256 mismatch: $relative"
            }
        }
    val actualFiles = mutableSetOf<String>()
    Files.walk(bundledVoskModelAssetDirectory.toPath()).use { paths ->
        paths.forEach { path ->
            check(!Files.isSymbolicLink(path)) { "Bundled Korean Vosk model contains a symbolic link." }
            if (Files.isRegularFile(path)) {
                actualFiles.add(bundledVoskModelAssetDirectory.toPath().relativize(path).toString().replace('\\', '/'))
            }
        }
    }
    check(actualFiles == expectedFiles) {
        "Bundled Korean Vosk file set differs from its pinned manifest."
    }
}

val verifyBundledVoskModelAssets by tasks.registering {
    doLast {
        verifyBundledVoskModelDirectory(bundledVoskModelAssetDirectory)
    }
}

val verifyBundledVoskModelAssetsRegression by tasks.registering {
    dependsOn(verifyBundledVoskModelAssets)
    doLast {
        val sourceRoot = bundledVoskModelAssetDirectory.canonicalFile.toPath()
        val fixtureParent = File(System.getProperty("java.io.tmpdir")).canonicalFile.toPath()
        check(!fixtureParent.startsWith(sourceRoot)) {
            "S0237 temporary fixture parent must be outside the bundled model."
        }
        val fixtureRoot = Files.createTempDirectory(fixtureParent, "walksafe-s0237-vosk-")
        logger.lifecycle("S0237_FIXTURE_ROOT ${fixtureRoot.toAbsolutePath()}")

        fun copyModelFixture(directory: File) {
            val destination = directory.canonicalFile.toPath()
            check(destination.startsWith(fixtureRoot) && destination != fixtureRoot &&
                !destination.startsWith(sourceRoot) && !sourceRoot.startsWith(destination)) {
                "S0237 fixture destination must be isolated from the bundled model."
            }
            Files.createDirectory(destination)
            Files.walk(sourceRoot).use { paths ->
                paths.forEach { source ->
                    check(!Files.isSymbolicLink(source)) {
                        "S0237 fixture copy refuses symbolic links."
                    }
                    val target = destination.resolve(sourceRoot.relativize(source))
                    if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
                        if (source != sourceRoot) Files.createDirectory(target)
                    } else {
                        check(Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                            "S0237 fixture copy requires regular files."
                        }
                        Files.newInputStream(
                            source,
                            StandardOpenOption.READ,
                            LinkOption.NOFOLLOW_LINKS,
                        ).use { input ->
                            Files.newOutputStream(
                                target,
                                StandardOpenOption.CREATE_NEW,
                                StandardOpenOption.WRITE,
                                LinkOption.NOFOLLOW_LINKS,
                            ).use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                }
            }
        }

        fun expectValidationFailure(directory: File, expectedMessage: String) {
            val failure = try {
                verifyBundledVoskModelDirectory(directory)
                null
            } catch (failure: Exception) {
                failure
            }
            check(failure != null &&
                failure.javaClass == IllegalStateException::class.java &&
                failure.message == expectedMessage) {
                "S0237 expected IllegalStateException: $expectedMessage; actual " +
                    "${failure?.javaClass?.name}: ${failure?.message}"
            }
        }

        try {
            val firstPayloadRelative = bundledVoskModelAssetDirectory
                .resolve("MODEL_FILES.sha256")
                .readLines()
                .first(String::isNotBlank)
                .substring(66)

            val emptyManifestFixture = fixtureRoot.resolve("empty-manifest").toFile()
            copyModelFixture(emptyManifestFixture)
            Files.write(
                emptyManifestFixture.resolve("MODEL_FILES.sha256").toPath(),
                ByteArray(0),
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
                LinkOption.NOFOLLOW_LINKS,
            )
            expectValidationFailure(
                emptyManifestFixture,
                "Bundled Korean Vosk manifest is empty.",
            )
            logger.lifecycle("S0237_CASE_PASS empty_manifest")

            val mutatedPayloadFixture = fixtureRoot.resolve("mutated-payload").toFile()
            copyModelFixture(mutatedPayloadFixture)
            val payload = mutatedPayloadFixture.resolve(firstPayloadRelative)
            val payloadPath = payload.toPath()
            check(payload.canonicalFile.toPath()
                .startsWith(mutatedPayloadFixture.canonicalFile.toPath()) &&
                Files.isRegularFile(payloadPath, LinkOption.NOFOLLOW_LINKS)) {
                "S0237 mutation target must be one regular fixture payload."
            }
            val originalFirstByte = Files.newInputStream(
                payloadPath,
                StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS,
            ).use { it.read() }
            val wrongByte = if (originalFirstByte < 0) 1 else originalFirstByte xor 1
            Files.newOutputStream(
                payloadPath,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            ).use { it.write(wrongByte) }
            expectValidationFailure(
                mutatedPayloadFixture,
                "Bundled Korean Vosk model SHA-256 mismatch: $firstPayloadRelative",
            )
            logger.lifecycle("S0237_CASE_PASS mutated_payload")

            val missingPayloadFixture = fixtureRoot.resolve("missing-payload").toFile()
            copyModelFixture(missingPayloadFixture)
            val missingPayload = missingPayloadFixture.resolve(firstPayloadRelative)
            val missingPayloadPath = missingPayload.toPath()
            check(missingPayload.canonicalFile.toPath()
                .startsWith(missingPayloadFixture.canonicalFile.toPath()) &&
                Files.isRegularFile(missingPayloadPath, LinkOption.NOFOLLOW_LINKS)) {
                "S0237 deletion target must be one regular fixture payload."
            }
            Files.delete(missingPayloadPath)
            expectValidationFailure(
                missingPayloadFixture,
                "Bundled Korean Vosk model is incomplete or contains an unsafe file: $firstPayloadRelative",
            )
            logger.lifecycle("S0237_CASE_PASS missing_payload")
        } catch (failure: Exception) {
            logger.error("S0237_FIXTURE_PRESERVED ${fixtureRoot.toAbsolutePath()}")
            throw failure
        }

        try {
            Files.walk(fixtureRoot).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { path ->
                    Files.delete(path)
                }
            }
        } catch (failure: Exception) {
            logger.error("S0237_FIXTURE_CLEANUP_FAILED ${fixtureRoot.toAbsolutePath()}")
            throw failure
        }
        logger.lifecycle("S0237_NEGATIVE_CASES_PASS 3")
    }
}

val verifyMobileSamAssets by tasks.registering {
    doLast {
        listOf("mobile_sam_encoder.onnx", "mobile_sam_decoder.onnx", "mobile_sam_manifest.json").forEach {
            check(file("src/mobileSam/assets/models/mobilesam/$it").isFile) {
                "MobileSAM asset $it is missing. See src/mobileSam/README.md for the export command."
            }
        }
    }
}

tasks.configureEach {
    if (name == "preReleaseBuild") dependsOn(validateWalkSafeSourceCommit)
    if (name == "mergeDebugAssets" || name == "mergeReleaseAssets" || name == "mergeMobileSamAssets") {
        dependsOn(verifyBundledVoskModelAssets)
    }
    if (name == "mergeMobileSamAssets") dependsOn(verifyMobileSamAssets)
}

android {
    namespace = "kr.co.hanium.dreamup.walksafe"
    compileSdk = 36

    defaultConfig {
        applicationId = "kr.co.hanium.dreamup.walksafe"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "WALKSAFE_DEBUG_GATEWAY_ORIGIN_PINNED", "false")
        buildConfigField("boolean", "WALKSAFE_SERVER_STT_ENABLED",
            "${providers.gradleProperty("walksafe.serverSttEnabled").orNull == "true"}")
        buildConfigField("String", "WALKSAFE_PRODUCT_ROLE", "\"USER\"")
        buildConfigField("boolean", "WALKSAFE_REPORT_QUEUE_ENABLED", "$reportQueueEnabled")
        buildConfigField(
            "String",
            "WALKSAFE_REPORT_QUEUE_TEST_ORIGIN",
            "\"$reportQueueBuildTestOrigin\"",
        )
        buildConfigField("int", "WALKSAFE_REPORT_QUEUE_MAX_ENTRIES", "$reportQueueBuildMaxEntries")
        buildConfigField(
            "int",
            "WALKSAFE_REPORT_QUEUE_MAX_PAYLOAD_BYTES",
            "$reportQueueBuildMaxPayloadBytes",
        )
        buildConfigField(
            "long",
            "WALKSAFE_REPORT_QUEUE_MAX_STORED_ENTRY_BYTES",
            "${reportQueueBuildMaxStoredEntryBytes}L",
        )
        buildConfigField(
            "long",
            "WALKSAFE_REPORT_QUEUE_MAX_TOTAL_BYTES",
            "${reportQueueBuildMaxTotalBytes}L",
        )
        buildConfigField(
            "int",
            "WALKSAFE_REPORT_QUEUE_AUTOMATIC_MAX_ENTRIES",
            "$reportQueueBuildAutomaticMaxEntries",
        )
        buildConfigField(
            "long",
            "WALKSAFE_REPORT_QUEUE_AUTOMATIC_MAX_TOTAL_BYTES",
            "${reportQueueBuildAutomaticMaxTotalBytes}L",
        )
        buildConfigField("int", "WALKSAFE_RAW_QUEUE_MAX_SEGMENTS", "$rawQueueBuildMaxSegments")
        buildConfigField("long", "WALKSAFE_RAW_QUEUE_MAX_TOTAL_BYTES", "${rawQueueBuildMaxTotalBytes}L")
    }

    buildTypes {
        debug {
            if (developmentReportQueue && !reportQueueEnabled) {
                // Local development only; reserve 16 MiB beyond automatic storage for explicit reports.
                buildConfigField("boolean", "WALKSAFE_REPORT_QUEUE_ENABLED", "true")
                buildConfigField(
                    "String",
                    "WALKSAFE_REPORT_QUEUE_TEST_ORIGIN",
                    "\"http://127.0.0.1:8081\"",
                )
                buildConfigField("int", "WALKSAFE_REPORT_QUEUE_MAX_ENTRIES", "8")
                buildConfigField("int", "WALKSAFE_REPORT_QUEUE_MAX_PAYLOAD_BYTES", "4194304")
                buildConfigField(
                    "long",
                    "WALKSAFE_REPORT_QUEUE_MAX_STORED_ENTRY_BYTES",
                    "12582912L",
                )
                buildConfigField("long", "WALKSAFE_REPORT_QUEUE_MAX_TOTAL_BYTES", "67108864L")
                buildConfigField("int", "WALKSAFE_REPORT_QUEUE_AUTOMATIC_MAX_ENTRIES", "6")
                buildConfigField(
                    "long",
                    "WALKSAFE_REPORT_QUEUE_AUTOMATIC_MAX_TOTAL_BYTES",
                    "50331648L",
                )
            }
            buildConfigField("String", "WALKSAFE_SOURCE_COMMIT", "\"${verifiedSourceCommit ?: "unverified"}\"")
            buildConfigField("String", "WALKSAFE_GATEWAY_ORIGIN", "\"$debugGatewayOrigin\"")
            // Explicit HTTPS test builds must not silently reuse a saved USB origin.
            buildConfigField("boolean", "WALKSAFE_DEBUG_GATEWAY_ORIGIN_PINNED",
                "${!developmentQuickStart && verifiedReleaseGatewayOrigin != null}")
            buildConfigField(
                "String",
                "WALKSAFE_BUILD_MARKER",
                buildConfigString(debugGuidanceBuildMarker),
            )
            buildConfigField("boolean", "WALKSAFE_LONG_LIVED_LOGIN_ENABLED", "$debugLongLivedLoginEnabled")
            buildConfigField(
                "boolean",
                "WALKSAFE_POST_LOGIN_DEVICE_CHECK_REQUIRED",
                "$debugPostLoginDeviceCheckRequired",
            )
            buildConfigField("boolean", "WALKSAFE_DEBUG_GUIDANCE_START_BYPASS", "$debugGuidanceStartBypass")
            buildConfigField("boolean", "DEVELOPMENT_QUICK_START", "$developmentQuickStart")
        }
        release {
            // The development report queue is DEBUG-gated at runtime; keep its build-time
            // enablement, loopback test origin and limits out of release BuildConfig too.
            buildConfigField("boolean", "WALKSAFE_REPORT_QUEUE_ENABLED", "false")
            buildConfigField("String", "WALKSAFE_REPORT_QUEUE_TEST_ORIGIN", "\"disabled\"")
            buildConfigField("int", "WALKSAFE_REPORT_QUEUE_MAX_ENTRIES", "0")
            buildConfigField("int", "WALKSAFE_REPORT_QUEUE_MAX_PAYLOAD_BYTES", "0")
            buildConfigField("long", "WALKSAFE_REPORT_QUEUE_MAX_STORED_ENTRY_BYTES", "0L")
            buildConfigField("long", "WALKSAFE_REPORT_QUEUE_MAX_TOTAL_BYTES", "0L")
            buildConfigField("int", "WALKSAFE_REPORT_QUEUE_AUTOMATIC_MAX_ENTRIES", "0")
            buildConfigField("long", "WALKSAFE_REPORT_QUEUE_AUTOMATIC_MAX_TOTAL_BYTES", "0L")
            // Release has no injected RAW queue profile: single retained segment only.
            buildConfigField("int", "WALKSAFE_RAW_QUEUE_MAX_SEGMENTS", "0")
            buildConfigField("long", "WALKSAFE_RAW_QUEUE_MAX_TOTAL_BYTES", "0L")
            buildConfigField("String", "WALKSAFE_SOURCE_COMMIT", "\"${verifiedSourceCommit ?: "unverified"}\"")
            buildConfigField("String", "WALKSAFE_GATEWAY_ORIGIN", "\"${verifiedReleaseGatewayOrigin ?: "invalid-release-origin"}\"")
            buildConfigField("String", "WALKSAFE_BUILD_MARKER", "\"walksafe-release-v1\"")
            // Owner decision 2 (2026-09-29): release keeps the login until an explicit end.
            buildConfigField("boolean", "WALKSAFE_LONG_LIVED_LOGIN_ENABLED", "true")
            buildConfigField("boolean", "WALKSAFE_POST_LOGIN_DEVICE_CHECK_REQUIRED", "true")
            buildConfigField("boolean", "WALKSAFE_DEBUG_GUIDANCE_START_BYPASS", "false")
            buildConfigField("boolean", "DEVELOPMENT_QUICK_START", "false")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        create("mobileSam") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".mobilesam"
            versionNameSuffix = "-mobilesam-v1.1"
            matchingFallbacks += "debug"
            buildConfigField("String", "WALKSAFE_BUILD_MARKER", "\"walkmate-mobilesam-v1.1\"")
        }
    }

    if (providers.gradleProperty("mobileSamDeviceTests").orNull == "true") {
        testBuildType = "mobileSam"
    }
    sourceSets.getByName("mobileSam").kotlin.directories.add("src/debug/java")
    sourceSets.getByName("androidTest").assets.srcDir("../../../contracts/test-vectors")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    androidResources {
        noCompress += "tflite"
        noCompress += "onnx"
    }

    buildFeatures {
        buildConfig = true
    }

    kotlin {
        jvmToolchain(21)
    }
}

androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        val apkDirectory = variant.artifacts.get(SingleArtifact.APK)
        val apkLoader = variant.artifacts.getBuiltArtifactsLoader()
        val outputDirectory = layout.buildDirectory.dir("outputs/walksafe-guidance-start/debug")
        val apkFileName = "walksafe-debug-guidance-start-bypass-$debugGuidanceArtifactLabel.apk"
        val receiptFileName = "walksafe-debug-guidance-start-bypass-$debugGuidanceArtifactLabel.json"
        val assembleDebugTasks = tasks.matching { it.name == "assembleDebug" }
        val exportTask = tasks.register("exportGuidanceStartDebugApk") {
            group = "build"
            description = "Export the labeled debug APK and its guidance bypass build receipt."
            onlyIf("assembleDebug completed successfully") {
                assembleDebugTasks.singleOrNull()?.state?.let { state ->
                    state.executed && state.failure == null &&
                        (!state.skipped || state.upToDate)
                } == true
            }
            inputs.dir(apkDirectory)
            inputs.property("guidanceStartBypass", debugGuidanceStartBypass)
            inputs.property("buildMarker", debugGuidanceBuildMarker)
            outputs.file(outputDirectory.map { it.file(apkFileName) })
            outputs.file(outputDirectory.map { it.file(receiptFileName) })
            doLast {
                val builtApks = apkLoader.load(apkDirectory.get())
                    ?: error("Debug APK metadata is missing")
                check(builtApks.elements.size == 1) {
                    "Labeled debug export requires exactly one APK; found ${builtApks.elements.size}"
                }
                val sourceApk = File(builtApks.elements.single().outputFile)
                check(sourceApk.isFile) { "Debug APK is missing: $sourceApk" }
                val directory = outputDirectory.get().asFile
                check(directory.isDirectory || directory.mkdirs()) {
                    "Cannot create labeled debug output directory: $directory"
                }
                val exportedApk = sourceApk.copyTo(File(directory, apkFileName), overwrite = true)
                val digest = MessageDigest.getInstance("SHA-256")
                exportedApk.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                val sha256 = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                val receipt = File(directory, receiptFileName)
                receipt.writeText(
                    "{\"schema_version\":1,\"variant\":\"debug\"," +
                        "\"guidance_start_bypass_enabled\":$debugGuidanceStartBypass," +
                        "\"build_marker\":\"$debugGuidanceBuildMarker\"," +
                        "\"apk_file\":\"$apkFileName\",\"apk_sha256\":\"$sha256\"}\n",
                )
                logger.lifecycle("Labeled debug APK: ${exportedApk.absolutePath}")
                logger.lifecycle("Labeled debug APK receipt: ${receipt.absolutePath}")
            }
        }
        assembleDebugTasks.configureEach {
            finalizedBy(exportTask)
        }
    }
}

// S10-36 (FP-037, FP-039, FP-016-DETAIL-03): release packages only the models of the approved
// runtime bundle. Fail-closed allowlist: every other file under models/ is dropped from the
// release assets, and a missing allowlisted model fails the build. Debug and androidTest keep
// every asset (the androidTest suites use the candidate and legacy models).
// S10-36-F1: the filter also derives the models the packaged configs load (primary and non-null
// fallback of two_model_runtime.json, the FastSAM contract asset) and fails the release build
// when this list drifts from them, instead of silently shipping without a configured model.
val releaseModelAssetAllowlist = setOf(
    "models/walkmate_yolo11n_21cls_768_float32.tflite",
    "models/fastsam_s_768_float32.tflite",
)

abstract class FilterReleaseModelAssetsTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val mergedAssets: DirectoryProperty

    @get:Input
    abstract val allowedModelAssets: SetProperty<String>

    @get:OutputDirectory
    abstract val filteredAssets: DirectoryProperty

    @TaskAction
    fun filter() {
        val source = mergedAssets.get().asFile
        val target = filteredAssets.get().asFile
        val allowed = allowedModelAssets.get()
        val configured = configuredReleaseModelAssets(source)
        check(configured == allowed.toSortedSet()) {
            "Release model allowlist ${allowed.toSortedSet()} does not match the packaged model configs $configured"
        }
        check(!target.exists() || target.deleteRecursively()) { "Cannot reset $target" }
        check(target.mkdirs()) { "Cannot create $target" }
        val kept = sortedSetOf<String>()
        source.walkTopDown().filter { it.isFile }.forEach { file ->
            val relative = file.relativeTo(source).invariantSeparatorsPath
            if (relative.startsWith("models/") && relative !in allowed) return@forEach
            if (relative in allowed) kept += relative
            file.copyTo(target.resolve(relative), overwrite = false)
        }
        check(kept == allowed.toSortedSet()) { "Release model assets missing: ${allowed - kept}" }
        logger.lifecycle("WALKSAFE_RELEASE_MODEL_ASSETS ${kept.joinToString(",")}")
    }

    /**
     * Model assets the packaged configs load: `primary_model` and a non-null `fallback_model` of
     * model-config/two_model_runtime.json, and the FastSAM contract `asset`. A missing or
     * malformed entry fails the build.
     */
    private fun configuredReleaseModelAssets(assets: File): java.util.SortedSet<String> {
        fun jsonObject(path: String): Map<*, *> =
            JsonSlurper().parse(File(assets, path)) as? Map<*, *> ?: error("$path is not a JSON object")
        val runtime = jsonObject("model-config/two_model_runtime.json")
        val models = runtime["models"] as? Map<*, *> ?: error("two_model_runtime.json has no models")
        fun assetOf(name: Any): String =
            ((models[name] as? Map<*, *>)?.get("asset") as? String)?.takeIf { it.startsWith("models/") }
                ?: error("two_model_runtime.json model $name has no models/ asset")
        val loaded = listOfNotNull(
            runtime["primary_model"] ?: error("two_model_runtime.json has no primary_model"),
            runtime["fallback_model"],
        ).map(::assetOf)
        val fastSam = (jsonObject("model-config/fastsam_s_768_contract.json")["asset"] as? String)
            ?.takeIf { it.startsWith("models/") }
            ?: error("fastsam_s_768_contract.json has no models/ asset")
        return (loaded + fastSam).toSortedSet()
    }
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        val filter = tasks.register<FilterReleaseModelAssetsTask>(
            "filter${variant.name.replaceFirstChar(Char::uppercaseChar)}ModelAssets",
        ) {
            allowedModelAssets.set(releaseModelAssetAllowlist)
        }
        variant.artifacts.use(filter)
            .wiredWithDirectories(
                FilterReleaseModelAssetsTask::mergedAssets,
                FilterReleaseModelAssetsTask::filteredAssets,
            )
            .toTransform(SingleArtifact.ASSETS)
    }
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    systemProperty("walksafe.test.expectedQueue", developmentReportQueue || reportQueueEnabled)
    systemProperty("walksafe.test.expectedQuickStart", developmentQuickStart)
    systemProperty("walksafe.test.expectedBypass", debugGuidanceStartBypass)
    // macOS exposes /var through a symlink. Test fixtures need a real path so the production
    // storage symlink rejection is tested, rather than rejecting every TemporaryFolder at /var.
    systemProperty("java.io.tmpdir", File(System.getProperty("java.io.tmpdir")).canonicalPath)
    systemProperty(
        "walksafe.guidanceStartBypass",
        providers.gradleProperty("walksafe.guidanceStartBypass").orNull.orEmpty(),
    )
}

dependencies {
    "mobileSamImplementation"("com.microsoft.onnxruntime:onnxruntime-android:1.30.0")
    implementation("com.google.ar:core:1.54.0")
    implementation("androidx.activity:activity:1.7.0")
    implementation("androidx.camera:camera-camera2:1.6.1")
    implementation("androidx.camera:camera-lifecycle:1.6.1")
    implementation("androidx.camera:camera-view:1.6.1")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("com.google.ai.edge.litert:litert:1.4.0")
    implementation("com.google.ai.edge.litert:litert-gpu:1.4.0")
    implementation("com.google.ai.edge.litert:litert-gpu-api:1.4.0")
    implementation("com.alphacephei:vosk-android:0.3.75")
    implementation("org.opencv:opencv:4.13.0")

    testImplementation("junit:junit:4.13.2")
    // Host tests bridge Android ICU IDNA to the real ICU4J implementation; not bundled in the APK.
    testImplementation("com.ibm.icu:icu4j:76.1")
    testImplementation("org.json:json:20240303")
    testImplementation("org.tensorflow:tensorflow-lite-metadata:0.2.0")

    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
