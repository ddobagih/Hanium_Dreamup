plugins {
    id("com.android.application")
}

dependencyLocking {
    lockMode.set(LockMode.STRICT)
    lockAllConfigurations()
}

android {
    namespace = "kr.co.hanium.dreamup.walksafe.positioneval"
    compileSdk = 36

    defaultConfig {
        applicationId = "kr.co.hanium.dreamup.walksafe.positioneval"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")

    implementation("org.apache.commons:commons-compress:1.27.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
