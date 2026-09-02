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

tasks.configureEach {
    if (name == "preReleaseBuild") dependsOn(validateWalkSafeAdminReleaseOrigin)
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

        buildConfigField("String", "WALKSAFE_PRODUCT_ROLE", "\"ADMIN\"")
        buildConfigField("String", "ADMIN_AUTHENTICATION_MODE", "\"PASSWORD_TOTP\"")
        buildConfigField("boolean", "ADMIN_SECURITY_WORKFLOWS_ENABLED", "true")
        buildConfigField("boolean", "ADMIN_OPERATIONAL_WORKFLOWS_ENABLED", "false")
        buildConfigField("String", "ADMIN_WORKFLOW_STATE", "\"SECURITY_CONTROL_ENABLED_OPERATIONS_LOCKED\"")
    }

    buildTypes {
        debug {
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
}
