import java.net.URI

plugins {
    id("com.android.application")
}

dependencyLocking {
    lockMode.set(LockMode.STRICT)
    lockAllConfigurations()
}

fun normalizedAdminApiOriginOrNull(raw: String?, allowDebugLoopbackHttp: Boolean): String? {
    val value = raw?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null
    val uri = runCatching { URI(value) }.getOrNull() ?: return null
    if (uri.host.isNullOrBlank() || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
    if (uri.rawPath?.takeIf { it.isNotEmpty() } !in setOf(null, "/")) return null
    val port = if (uri.port == -1) "" else ":${uri.port}"
    if (uri.scheme.equals("https", ignoreCase = true)) return "https://${uri.host.lowercase()}$port"
    val loopback = uri.host.equals("127.0.0.1") || uri.host.equals("localhost", ignoreCase = true)
    return "http://${uri.host.lowercase()}$port"
        .takeIf { allowDebugLoopbackHttp && uri.scheme.equals("http", ignoreCase = true) && loopback }
}

// Same source-commit provenance contract as the user app (S21-24, FP-051 signed release bundle).
val sourceCommitPattern = Regex("^[0-9a-fA-F]{40}$")
val configuredSourceCommit = providers.gradleProperty("WALKSAFE_SOURCE_COMMIT")
    .orElse(providers.environmentVariable("WALKSAFE_SOURCE_COMMIT"))
    .orNull
    ?.trim()
val verifiedSourceCommit = configuredSourceCommit?.takeIf(sourceCommitPattern::matches)?.lowercase()

val configuredAdminApiOrigin = providers.gradleProperty("WALKSAFE_ADMIN_API_ORIGIN")
    .orElse(providers.environmentVariable("WALKSAFE_ADMIN_API_ORIGIN"))
    .orNull
val verifiedReleaseAdminApiOrigin = normalizedAdminApiOriginOrNull(configuredAdminApiOrigin, false)
val debugAdminApiOrigin = normalizedAdminApiOriginOrNull(configuredAdminApiOrigin, true) ?: "http://127.0.0.1:8000"
val requestedOperationalDebug = providers.gradleProperty("WALKSAFE_ADMIN_OPERATIONAL_DEBUG")
    .orElse(providers.environmentVariable("WALKSAFE_ADMIN_OPERATIONAL_DEBUG"))
    .orNull
val operationalDebugEnabled = when (requestedOperationalDebug?.trim()?.lowercase()) {
    null, "", "false" -> false
    "true" -> true
    else -> error("WALKSAFE_ADMIN_OPERATIONAL_DEBUG must be exactly true or false")
}
val validateWalkSafeAdminReleaseOrigin by tasks.registering {
    doLast {
        check(verifiedReleaseAdminApiOrigin != null) {
            "Release builds require WALKSAFE_ADMIN_API_ORIGIN as one exact root HTTPS origin"
        }
    }
}
val validateWalkSafeAdminSourceCommit by tasks.registering {
    doLast {
        check(verifiedSourceCommit != null) {
            "Release builds require WALKSAFE_SOURCE_COMMIT as a 40-character hexadecimal commit id"
        }
    }
}

tasks.configureEach {
    if (name == "preReleaseBuild") {
        dependsOn(validateWalkSafeAdminReleaseOrigin)
        dependsOn(validateWalkSafeAdminSourceCommit)
    }
}

android {
    namespace = "kr.co.hanium.dreamup.walksafe.admin"
    compileSdk = 36

    defaultConfig {
        applicationId = "kr.co.hanium.dreamup.walksafe.admin"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.2.0-security"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "WALKSAFE_PRODUCT_ROLE", "\"ADMIN\"")
        buildConfigField("String", "ADMIN_AUTHENTICATION_MODE", "\"PASSWORD_TOTP\"")
        buildConfigField("boolean", "ADMIN_SECURITY_WORKFLOWS_ENABLED", "true")
        buildConfigField("boolean", "ADMIN_OPERATIONAL_WORKFLOWS_ENABLED", "false")
        buildConfigField("String", "ADMIN_WORKFLOW_STATE", "\"SECURITY_CONTROL_ENABLED_OPERATIONS_LOCKED\"")
    }

    buildTypes {
        debug {
            buildConfigField("String", "WALKSAFE_SOURCE_COMMIT", "\"${verifiedSourceCommit ?: "unverified"}\"")
            buildConfigField("String", "WALKSAFE_BUILD_MARKER", "\"walksafe-admin-debug-v1\"")
            buildConfigField("String", "WALKSAFE_ADMIN_API_ORIGIN", "\"$debugAdminApiOrigin\"")
            buildConfigField(
                "boolean",
                "ADMIN_OPERATIONAL_WORKFLOWS_ENABLED",
                operationalDebugEnabled.toString(),
            )
            buildConfigField(
                "String",
                "ADMIN_WORKFLOW_STATE",
                if (operationalDebugEnabled) {
                    "\"INTERNAL_DEBUG_REVIEW_DELIVERY_ENABLED\""
                } else {
                    "\"SECURITY_CONTROL_ENABLED_OPERATIONS_LOCKED\""
                },
            )
        }
        release {
            buildConfigField("String", "WALKSAFE_SOURCE_COMMIT", "\"${verifiedSourceCommit ?: "unverified"}\"")
            buildConfigField("String", "WALKSAFE_BUILD_MARKER", "\"walksafe-admin-release-v1\"")
            buildConfigField("boolean", "ADMIN_OPERATIONAL_WORKFLOWS_ENABLED", "false")
            buildConfigField("String", "ADMIN_WORKFLOW_STATE", "\"SECURITY_CONTROL_ENABLED_OPERATIONS_LOCKED\"")
            buildConfigField(
                "String",
                "WALKSAFE_ADMIN_API_ORIGIN",
                "\"${verifiedReleaseAdminApiOrigin ?: "invalid-release-origin"}\"",
            )
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

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver3:5.4.0")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver3:5.4.0")
}

// Check the generated debug constants against the requested matrix configuration.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    systemProperty("walksafe.test.expectedAdminOperations", operationalDebugEnabled)
}
