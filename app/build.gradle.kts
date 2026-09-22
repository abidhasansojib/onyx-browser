plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.onyx.browser"
    compileSdk = 35

    val propVersionCode = project.findProperty("versionCode")?.toString()?.toIntOrNull()
    val propVersionName = project.findProperty("versionName")?.toString()
    val envRunNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()

    defaultConfig {
        applicationId = "com.onyx.browser"
        minSdk = 26
        targetSdk = 35
        versionCode = propVersionCode ?: (if (envRunNumber != null) 1000 + envRunNumber else 1)
        versionName = propVersionName ?: (if (envRunNumber != null) "1.0.$envRunNumber" else "1.0.0")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a", "x86_64"))
        }

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    val enableSplits = project.findProperty("enableAbiSplits")?.toString()?.toBoolean() ?: false

    splits {
        abi {
            isEnable = enableSplits
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("KEYSTORE_PATH")
            val defaultKeystore = rootProject.file("keystore/release.keystore")

            val envStorePass = (System.getenv("KEYSTORE_PASSWORD") ?: System.getenv("STORE_PASSWORD"))?.takeUnless { it.isBlank() }
            val envKeyAlias = System.getenv("KEY_ALIAS")?.takeUnless { it.isBlank() }
            val envKeyPass = System.getenv("KEY_PASSWORD")?.takeUnless { it.isBlank() }

            if (!keystorePath.isNullOrBlank() && file(keystorePath).exists()) {
                storeFile = file(keystorePath)
                storePassword = envStorePass ?: "onyxrelease123"
                keyAlias = envKeyAlias ?: "onyx-browser"
                keyPassword = envKeyPass ?: "onyxrelease123"
            } else if (defaultKeystore.exists()) {
                storeFile = defaultKeystore
                storePassword = envStorePass ?: "onyxrelease123"
                keyAlias = envKeyAlias ?: "onyx-browser"
                keyPassword = envKeyPass ?: "onyxrelease123"
            } else {
                val debugConfig = signingConfigs.getByName("debug")
                storeFile = debugConfig.storeFile
                storePassword = debugConfig.storePassword
                keyAlias = debugConfig.keyAlias
                keyPassword = debugConfig.keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    // Room Database
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // Encrypted Database (SQLCipher + AndroidX Security)
    implementation("net.zetetic:android-database-sqlcipher:4.5.4")
    implementation("androidx.sqlite:sqlite-ktx:2.4.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // AndroidX WebKit & Media Playback
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.media:media:1.7.0")

    // Preferences
    implementation("androidx.preference:preference-ktx:1.2.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // CameraX
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    // ML Kit Barcode Scanning (for QR code scanner)
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // Credential Manager & Passkeys (WebAuthn / FIDO2 / Google Password Manager)
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
}
