@file:Suppress("UnstableApiUsage")

val pkg: String = providers.gradleProperty("wireguardPackageName").get()

plugins {
    alias(libs.plugins.android.library)
}

android {
    compileSdk = 36
    namespace = "${pkg}.rtc"

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    lint {
        disable += "LongLogTag"
    }
}

dependencies {
    implementation(libs.androidx.annotation)
    implementation(libs.kotlinx.coroutines.android)

    compileOnly(fileTree("src/main/libs") { include("*.jar") })

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
