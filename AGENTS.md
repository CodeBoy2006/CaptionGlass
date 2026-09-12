# CaptionGlass development

- Read `README.md` and `docs/architecture.md` before changing session behavior. Use real pinned inference: ASR stays on CPU, Hy-MT2 uses Vulkan GPU; never use synthetic text or offline throughput as evidence of live speech quality.
- Keep `engine` pure Kotlin/JVM. `app` owns Android lifecycle and UI; `native` owns integrated inference libraries. Prefer platform APIs and these three modules over extra frameworks.
- One capture owner, one ASR stream, one MT worker. Confine mutable engine state to its session owner. Every async result must match session, segment, and revision.
- Confirmed segments need a translated or explicit untranslated outcome. Consume queue overflow, expiry, and stop results. Reading overflow and inference overflow are separate events.
- Use monotonic session time. Never reuse MediaProjection consent, implicitly switch to cloud, save raw audio by default, or claim silence proves DRM.
- Pin model revisions, matched files, hashes and native runtimes before enabling a language pack. Candidate metadata is not a validated installable pack.
- Keep comments in English and product copy clear. Do not expose model/backend tuning in the normal user flow.
- Run `./gradlew :engine:check :app:assembleDebug :app:lintDebug` before committing code. Add focused executable checks to `engine/src/test/kotlin/com/captionglass/engine/PipelineCheck.kt` for nontrivial engine changes.
- Use JDK 17 and the checked-in Gradle wrapper. Android SDK location, caches, signing keys, model files, recordings and APKs stay out of Git.
- Make focused conventional commits. Keep the root `statusquo.md` local-only and append a task entry after substantive changes. Do not push unless authorized.
