plugins { alias(libs.plugins.kotlin.jvm) }

kotlin { jvmToolchain(17) }

val regressionCheck by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Run deterministic pipeline regression checks without models or Android."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.captionglass.engine.PipelineCheckKt")
    dependsOn(tasks.testClasses)
}

tasks.check { dependsOn(regressionCheck) }
