@file:Suppress("UnstableApiUsage")

import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.library)
}

// Inline gomobile build & verify tasks from gomobile.gradle.kts to avoid lint crash
// on applied Kotlin DSL scripts (lint internal bug: findFirCompiledSymbol on uncompiled declaration)
import java.io.File

tasks.register("buildOlcrtcAar") {
    group = "olcrtc"
    description = "Build olcrtc.aar from olcrtc Go source using gomobile"
    doLast {
        val olcrtcRepo = project.findProperty("olcrtc.repo.path")?.toString()
            ?: System.getenv("OLCRTC_REPO")
        if (olcrtcRepo == null) {
            logger.warn("OLCRTC_REPO not set. Skipping AAR build.")
            return@doLast
        }
        val repoDir = File(olcrtcRepo)
        if (!repoDir.exists()) {
            logger.warn("olcrtc repo not found at $olcrtcRepo")
            return@doLast
        }
        val gomobileOnPath = findOnPath("gomobile")
        val gomobileInGoAndroid = File(System.getenv("GOANDROID_HOME") ?: "/usr/local/go/bin", "gomobile").exists()
        if (!gomobileOnPath && !gomobileInGoAndroid) {
            logger.warn("gomobile not found in PATH, GOANDROID_HOME, or /usr/local/go/bin")
            return@doLast
        }
        val outputAar = File(project.projectDir, "src/main/libs/olcrtc.aar").absolutePath
        val pb = ProcessBuilder(
            "gomobile", "bind", "-target=android", "-androidapi", "21",
            "-ldflags", "-s -w -checklinkname=0",
            "-o", outputAar, "./mobile"
        )
        pb.directory(repoDir)
        pb.inheritIO()
        val process = pb.start()
        val exitCode = process.waitFor()
        if (exitCode != 0) throw GradleException("gomobile bind failed with exit code $exitCode")
        logger.lifecycle("olcrtc.aar built: $outputAar")
    }
}

tasks.register("verifyOlcrtcBinaries") {
    group = "olcrtc"
    description = "Verify SHA256 checksums of committed binary blobs"
    doLast {
        val expected = mapOf(
            "../jniLibs/arm64-v8a/libgojni.so" to "561ad9beef951ebeff3373c35c9b5cfaeda8c5ec6f9f354030b1ab1931fe22d9",
            "../jniLibs/arm64-v8a/libhev-socks5-tunnel.so" to "c2b14023abe53863a04a82cf836d147ff8eeaf2563ca507a025d3f3e1a991772",
            "../jniLibs/x86_64/libgojni.so" to "561ad9beef951ebeff3373c35c9b5cfaeda8c5ec6f9f354030b1ab1931fe22d9",
            "../jniLibs/x86_64/libhev-socks5-tunnel.so" to "ea11700dc262b0a81e45f874cb7a2416d41d33bb5fb49c8d636208261d1867a0",
            "../libs/olcrtc-classes.jar" to "77c5ecf2f1532eb2a52f733bd1d47beb830f596317dde8b6e3f0eefb98a8a23f"
        )
        var allMatch = true
        val baseDir = project.projectDir
        expected.forEach { (relativePath, expectedSha) ->
            val file = File(baseDir, relativePath)
            if (!file.exists()) {
                logger.warn("MISSING: $relativePath")
                allMatch = false; return@forEach
            }
            val actual = file.sha256()
            if (actual == expectedSha) {
                logger.lifecycle("OK: $relativePath")
            } else {
                logger.warn("CHECKSUM MISMATCH: $relativePath")
                logger.warn("  expected: $expectedSha")
                logger.warn("  actual:   $actual")
                allMatch = false
            }
        }
        if (allMatch) {
            logger.lifecycle("All binary checksums match.")
        } else {
            throw GradleException("Binary checksum verification FAILED. Update THIRD_PARTY_NOTICES.md or rebuild.")
        }
    }
}

fun findOnPath(name: String): Boolean {
    val pathEnv = System.getenv("PATH") ?: return false
    return pathEnv.split(File.pathSeparator).any { dir -> File(dir, name).exists() }
}

fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { stream ->
        val buffer = ByteArray(8192)
        var read: Int
        while (stream.read(buffer).also { read = it } != -1) {
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
}

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
            jniLibs.dirs("src/main/jniLibs")
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
