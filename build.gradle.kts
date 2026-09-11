plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// Developer convenience: download the same pinned files used by the in-app model manager.
tasks.register("downloadModels") {
    group = "setup"
    description = "Download pinned models into artifacts/models/<id>; optionally select -Pmodel=<id>."
    doLast {
        val manifest = groovy.json.JsonSlurper().parse(file("models/catalog.json")) as Map<*, *>
        val requested = providers.gradleProperty("model").orNull
        val models = (manifest["models"] as List<*>).map { it as Map<*, *> }
            .filter { if (requested == null) it["id"] in listOf("x-asr-zh-en-480ms", "hy-mt2-1.8b-q4-k-m") else it["id"] == requested }
        check(models.isNotEmpty()) { "Unknown model: $requested" }
        models.forEach { model ->
            check(model["installable"] == true) { "Candidate model is not installable" }
            val destination = file("artifacts/models/${model["id"]}").also { it.mkdirs() }
            (model["files"] as List<*>).forEach { value ->
                val entry = value as Map<*, *>
                val name = entry["name"] as? String ?: (entry["path"] as String).substringAfterLast('/')
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
}
