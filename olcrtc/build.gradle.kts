@file:Suppress("UnstableApiUsage")

plugins {
    alias(libs.plugins.android.library)
}

// Apply gomobile build and verify tasks
apply(from = "gomobile.gradle.kts")

android {
    namespace = "com.wireguard.android.olcrtc"
    compileSdk = 35

    defaultConfig {
        minSdk = 23
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    buildTypes {
        release {
            consumerProguardFiles("consumer-rules.pro")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // Pre-built classes.jar from gomobile AAR (mobile.Mobile + proxy classes)
    implementation(files("src/main/libs/olcrtc-classes.jar"))

    // AndroidX Core (for NotificationCompat, etc.)
    implementation("androidx.core:core-ktx:1.13.1")

    // AndroidX Fragment (for Fragment, lifecycle, etc.)
    implementation("androidx.fragment:fragment-ktx:1.8.5")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("org.mockito.kotlin:mockito-kotlin:4.1.0")
}
