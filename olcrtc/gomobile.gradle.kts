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
        val repoDir = file(olcrtcRepo)
        if (!repoDir.exists()) {
            logger.warn("olcrtc repo not found at $olcrtcRepo")
            return@doLast
        }
        try { "gomobile version".execute() } catch (e: Exception) {
            logger.warn("gomobile not available: ${e.message}")
            return@doLast
        }
        val outputAar = file("src/main/libs/olcrtc.aar")
        exec {
            workingDir = repoDir
            commandLine("gomobile", "bind", "-target=android", "-androidapi", "21",
                "-ldflags", "-s -w -checklinkname=0",
                "-o", outputAar.absolutePath, "./mobile")
        }
        logger.lifecycle("olcrtc.aar built: ${outputAar.absolutePath}")
    }
}
