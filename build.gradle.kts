plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// Developer-only download. The APK has no network permission and imports data files via SAF.
tasks.register("downloadModels") {
    group = "setup"
    description = "Download and SHA-256 verify the pinned M1 language pack into ignored artifacts/models."
    doLast {
        val manifest = groovy.json.JsonSlurper().parse(file("models/zh-en.json")) as Map<*, *>
        val entries = (manifest["models"] as List<*>).flatMap { (it as Map<*, *>)["files"] as List<*> }
        val destination = file("artifacts/models").also { it.mkdirs() }
        entries.forEach { value ->
            val entry = value as Map<*, *>
            val name = (entry["path"] as String).substringAfterLast('/')
            val target = destination.resolve(name)
            fun valid(candidate: java.io.File): Boolean {
                if (!candidate.isFile || candidate.length() != (entry["sizeBytes"] as Number).toLong()) return false
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                candidate.inputStream().buffered().use { input ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
                }
                return digest.digest().joinToString("") { "%02x".format(it) } == entry["sha256"]
            }
            if (!valid(target)) {
                val partial = destination.resolve("$name.part")
                check(ProcessBuilder("curl", "--fail", "--location", "--retry", "3", entry["url"] as String,
                    "--output", partial.path).inheritIO().start().waitFor() == 0) { "Download failed: $name" }
                check(valid(partial)) { "Model verification failed: $name" }
                java.nio.file.Files.move(partial.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            }
            logger.lifecycle("Verified $name")
        }
    }
}
