import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.deanwaylabs.quietos"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.deanwaylabs.quietos"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-alpha01"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // Two flavors: motorola gets the full build with the LiteRT AI model;
    // phoneB is the relay phone — no AI natives, installs on 32-bit devices.
    flavorDimensions += "device"
    productFlavors {
        create("motorola") {
            dimension = "device"
            ndk { abiFilters += listOf("arm64-v8a") }
        }
        create("phoneB") {
            dimension = "device"
            applicationIdSuffix = ".phoneb"
            versionNameSuffix = "-phoneB"
            // No ndk abiFilters and no LiteRT: pure Kotlin/Java.
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.documentfile:documentfile:1.1.0")
    // LiteRT is motorola-only: Phone B doesn't run the assistant brain,
    // and the native library is 64-bit-only (breaks 32-bit installs).
    "motorolaImplementation"("com.google.ai.edge.litertlm:litertlm-android:0.17.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // Firebase: cloud relay transport (Realtime Database) + push wake-up (FCM).
    // The relay envelopes are AES-256-GCM sealed before they touch Firebase —
    // Google only ever sees ciphertext.
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-database-ktx")
    implementation("com.google.firebase:firebase-messaging-ktx")
    implementation("com.google.firebase:firebase-auth-ktx")
    testImplementation("junit:junit:4.13.2")
}
