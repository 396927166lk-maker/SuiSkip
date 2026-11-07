plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sui.skip"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sui.skip"
        minSdk = 26
        targetSdk = 35
        versionCode = 200
        versionName = "2.0.0"
    }

    signingConfigs {
        create("fixedRelease") {
            storeFile = file("../keystore/suiskip_fixed.jks")
            storePassword = "SuiSkip@2026Fixed"
            keyAlias = "suiskip_key"
            keyPassword = "SuiSkip@2026Fixed"
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("fixedRelease")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("fixedRelease")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Shizuku API
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // Libsu (Root API)
    implementation("com.github.topjohnwu.libsu:core:6.0.0")

    // Unit testing
    testImplementation("junit:junit:4.13.2")
}
