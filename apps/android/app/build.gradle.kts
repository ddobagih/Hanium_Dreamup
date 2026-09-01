import java.net.URI

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
val debugGatewayOrigin = verifiedReleaseGatewayOrigin ?: "http://127.0.0.1:8081"
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
val debugLongLivedLoginEnabled =
    providers.gradleProperty("walksafe.longLivedLoginEnabled").orNull == "true"
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
val verifyBundledVoskModelAssets by tasks.registering {
    doLast {
        val manifest = bundledVoskModelAssetDirectory.resolve("MODEL_FILES.sha256")
        check(manifest.isFile) {
            "Bundled Korean Vosk model is missing. Run " +
                "python3 scripts/prepare_vosk_ko_small_model.py from the repository root."
        }
        val missing = manifest.readLines()
            .filter(String::isNotBlank)
            .map { line ->
                val separator = line.indexOf("  ")
                check(
                    separator == 64 &&
                        line.take(64).matches(Regex("[0-9a-f]{64}")),
                ) { "Bundled Korean Vosk manifest is malformed." }
                line.substring(separator + 2).also { relative ->
                    check(
                        relative.isNotBlank() &&
                            !relative.startsWith('/') &&
                            '\\' !in relative &&
                            relative.split('/').none { it.isBlank() || it == "." || it == ".." },
                    ) { "Bundled Korean Vosk manifest contains an unsafe path." }
                }
            }
            .filterNot { relative -> bundledVoskModelAssetDirectory.resolve(relative).isFile }
        check(missing.isEmpty()) {
            "Bundled Korean Vosk model is incomplete. Re-run the model preparation script."
        }
    }
}

tasks.configureEach {
    if (name == "preReleaseBuild") dependsOn(validateWalkSafeSourceCommit)
    if (name == "mergeDebugAssets" || name == "mergeReleaseAssets") {
        dependsOn(verifyBundledVoskModelAssets)
    }
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
    }

    buildTypes {
        debug {
            buildConfigField("String", "WALKSAFE_SOURCE_COMMIT", "\"${verifiedSourceCommit ?: "unverified"}\"")
            buildConfigField("String", "WALKSAFE_GATEWAY_ORIGIN", "\"$debugGatewayOrigin\"")
            buildConfigField("String", "WALKSAFE_BUILD_MARKER", "\"walksafe-debug-v1\"")
            buildConfigField("boolean", "WALKSAFE_LONG_LIVED_LOGIN_ENABLED", "$debugLongLivedLoginEnabled")
        }
        release {
            buildConfigField("String", "WALKSAFE_SOURCE_COMMIT", "\"${verifiedSourceCommit ?: "unverified"}\"")
            buildConfigField("String", "WALKSAFE_GATEWAY_ORIGIN", "\"${verifiedReleaseGatewayOrigin ?: "invalid-release-origin"}\"")
            buildConfigField("String", "WALKSAFE_BUILD_MARKER", "\"walksafe-release-v1\"")
            buildConfigField("boolean", "WALKSAFE_LONG_LIVED_LOGIN_ENABLED", "false")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    androidResources {
        noCompress += "tflite"
    }

    buildFeatures {
        buildConfig = true
    }

    kotlin {
        jvmToolchain(21)
    }
}

dependencies {
    implementation("com.google.ar:core:1.54.0")
    implementation("androidx.activity:activity:1.7.0")
    implementation("androidx.camera:camera-camera2:1.6.1")
    implementation("androidx.camera:camera-lifecycle:1.6.1")
    implementation("androidx.camera:camera-view:1.6.1")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("com.google.ai.edge.litert:litert:1.4.0")
    implementation("com.alphacephei:vosk-android:0.3.75")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.tensorflow:tensorflow-lite-metadata:0.2.0")

    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
