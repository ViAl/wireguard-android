/**
 * Gradle script to build olcrtc.aar from the OlcRTC Go source using gomobile.
 *
 * Usage:
 *   ./gradlew :olcrtc:buildOlcrtcAar -Polcrtc.repo.path=/path/to/olcrtc
 *
 * Environment:
 *   OLCRTC_REPO — path to the OlcRTC Go repo (fallback for -Polcrtc.repo.path)
 *   GOANDROID_HOME — path to gomobile (e.g. $GOPATH/bin)
 */

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

        // Check gomobile availability via PATH or GOANDROID_HOME
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
        if (exitCode != 0) {
            throw GradleException("gomobile bind failed with exit code $exitCode")
        }
        logger.lifecycle("olcrtc.aar built: $outputAar")
    }
}

tasks.register("verifyOlcrtcBinaries") {
    group = "olcrtc"
    description = "Verify SHA256 checksums of committed binary blobs"
    doLast {
        val expected = mapOf(
            "src/main/jniLibs/arm64-v8a/libgojni.so" to "561ad9beef951ebeff3373c35c9b5cfaeda8c5ec6f9f354030b1ab1931fe22d9",
            "src/main/jniLibs/arm64-v8a/libhev-socks5-tunnel.so" to "c2b14023abe53863a04a82cf836d147ff8eeaf2563ca507a025d3f3e1a991772",
            "src/main/jniLibs/x86_64/libgojni.so" to "561ad9beef951ebeff3373c35c9b5cfaeda8c5ec6f9f354030b1ab1931fe22d9",
            "src/main/jniLibs/x86_64/libhev-socks5-tunnel.so" to "ea11700dc262b0a81e45f874cb7a2416d41d33bb5fb49c8d636208261d1867a0",
            "src/main/libs/olcrtc-classes.jar" to "77c5ecf2f1532eb2a52f733bd1d47beb830f596317dde8b6e3f0eefb98a8a23f"
        )

        var allMatch = true
        val baseDir = project.projectDir

        expected.forEach { (relativePath, expectedSha) ->
            val file = File(baseDir, relativePath)
            if (!file.exists()) {
                logger.warn("MISSING: $relativePath")
                allMatch = false
                return@forEach
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
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    inputStream().use { stream ->
        val buffer = ByteArray(8192)
        var read: Int
        while (stream.read(buffer).also { read = it } != -1) {
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
