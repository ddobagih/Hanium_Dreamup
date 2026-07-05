plugins {
    id("com.android.application")
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
    }

    buildTypes {
        release {
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
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("org.tensorflow:tensorflow-lite:2.17.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
