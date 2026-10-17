plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sait.koserai"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sait.koserai"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    signingConfigs {
        // Test anahtarı: her derleme aynı imzayı kullansın diye (güncelleme kurulabilsin).
        // Play Store için ayrı, gizli bir yükleme anahtarı kullanılacak (release).
        getByName("debug") {
            storeFile = file("../keystore/test.jks")
            storePassword = "koser-test"
            keyAlias = "koser-test"
            keyPassword = "koser-test"
        }
        create("release") {
            val ks = System.getenv("KOSER_KEYSTORE")
            if (ks != null && file(ks).exists()) {
                storeFile = file(ks)
                storePassword = System.getenv("KOSER_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KOSER_KEY_ALIAS")
                keyPassword = System.getenv("KOSER_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val ks = System.getenv("KOSER_KEYSTORE")
            signingConfig = if (ks != null && file(ks).exists()) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
        }
    }

    flavorDimensions += "dist"
    productFlavors {
        create("full") { dimension = "dist" }
        create("lite") { dimension = "dist"; versionNameSuffix = "-lite" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { buildConfig = true }
    lint { checkReleaseBuilds = false; abortOnError = false }
    packaging { jniLibs { useLegacyPackaging = false } }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // Çevrimdışı "Hey Koser" uyandırma kelimesi algılama
    implementation("com.alphacephei:vosk-android:0.3.47")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
}
