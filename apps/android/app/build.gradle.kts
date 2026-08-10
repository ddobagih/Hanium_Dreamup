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

tasks.configureEach {
    if (name == "preReleaseBuild") dependsOn(validateWalkSafeSourceCommit)
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

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.tensorflow:tensorflow-lite-metadata:0.2.0")

    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
