plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
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
        versionName = propVersionName ?: (if (envRunNumber != null) {
            val major = 1 + envRunNumber / 1000
            val minor = (envRunNumber / 100) % 10
            val patch = envRunNumber % 100
            "$major.$minor.$patch"
        } else "1.0.0")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a", "x86_64"))
        }

        vectorDrawables {
            useSupportLibrary = true
        }

        buildConfigField("boolean", "IS_PRERELEASE", "false")
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
                storePassword = envStorePass
                keyAlias = envKeyAlias ?: "onyx-browser"
                keyPassword = envKeyPass ?: envStorePass
            } else if (defaultKeystore.exists()) {
                storeFile = defaultKeystore
                storePassword = envStorePass
                keyAlias = envKeyAlias ?: "onyx-browser"
                keyPassword = envKeyPass ?: envStorePass
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
            buildConfigField("boolean", "IS_PRERELEASE", "false")
        }
        create("prerelease") {
            initWith(getByName("release"))
            applicationIdSuffix = ".prerelease"
            signingConfig = signingConfigs.getByName("release")
            matchingFallbacks += listOf("release")
            buildConfigField("boolean", "IS_PRERELEASE", "true")
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

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":core:common"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.cardview)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.swiperefreshlayout)

    // Room Database
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Encrypted Database (SQLCipher + AndroidX Security)
    implementation(libs.sqlcipher)
    implementation(libs.androidx.sqlite.ktx)
    implementation(libs.androidx.security.crypto)

    // AndroidX WebKit & Media Playback
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.media)
    implementation(libs.androidx.biometric)

    // Preferences
    implementation(libs.androidx.preference.ktx)

    // Coroutines & Networking (Internal Multi-Threaded Downloader)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)

    // CameraX
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // ML Kit Barcode Scanning (for QR code scanner)
    implementation(libs.mlkit.barcode.scanning)

    // AndroidX WorkManager (Periodic Silent Background Filter Sync)
    implementation(libs.androidx.work.runtime.ktx)

    // Credential Manager & Passkeys (WebAuthn / FIDO2 / Google Password Manager)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)

    // Unit Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}


