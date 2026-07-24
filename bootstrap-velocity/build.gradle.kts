plugins { java }

dependencies {
    implementation(project(":runtime-javet"))
    implementation(project(":platform-velocity"))
    compileOnly(libs.velocity.api)
    compileOnly("com.google.inject:guice:7.0.0")
    annotationProcessor(libs.velocity.api)
}

val generatedVersionDirectory = layout.buildDirectory.dir("generated/sources/runtimeVersion/java")
val runtimeBuildVersion = version.toString()
val generateRuntimeBuildVersion = tasks.register("generateRuntimeBuildVersion") {
    val output = generatedVersionDirectory.map {
        it.file("dev/shamoo/runtime/bootstrap/velocity/RuntimeBuildVersion.java")
    }
    outputs.file(output)
    inputs.property("runtimeBuildVersion", runtimeBuildVersion)
    doLast {
        val file = output.get().asFile
        file.parentFile.mkdirs()
        file.writeText("package dev.shamoo.runtime.bootstrap.velocity;\n"
            + "final class RuntimeBuildVersion { static final String VERSION = \"$runtimeBuildVersion\"; "
            + "private RuntimeBuildVersion() { } }\n")
    }
}
sourceSets.main { java.srcDir(generatedVersionDirectory) }
tasks.compileJava { dependsOn(generateRuntimeBuildVersion) }
tasks.named("sourcesJar") { dependsOn(generateRuntimeBuildVersion) }

tasks.jar {
    archiveBaseName.set("shamoo-runtime-velocity")
    dependsOn(configurations.runtimeClasspath)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}

tasks.processResources {
    dependsOn(":runtime-codegen-support:generateVelocityApi")
    from(project(":runtime-codegen-support").layout.projectDirectory.dir("generated/velocity")) {
        into("dev/shamoo/runtime/generated/velocity")
    }
}
