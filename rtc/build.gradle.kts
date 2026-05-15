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

    lint {
        disable += "LongLogTag"
    }
}

dependencies {
    implementation(libs.androidx.annotation)
    implementation(libs.kotlinx.coroutines.android)

    implementation(fileTree("src/main/libs") { include("*.jar") })

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
