plugins {
    java
    alias(libs.plugins.paperweight.userdev)
}

dependencies {
    implementation(project(":runtime-javet"))
    implementation(project(":platform-paper"))
    implementation(project(":platform-paper-nms"))
    compileOnly(libs.paper.api)
    paperweight.paperDevBundle("1.21.8-R0.1-20250906.215025-55")
}

val generatedVersionDirectory = layout.buildDirectory.dir("generated/sources/runtimeVersion/java")
val runtimeBuildVersion = version.toString()
val generateRuntimeBuildVersion = tasks.register("generateRuntimeBuildVersion") {
    val output = generatedVersionDirectory.map {
        it.file("dev/shamoo/runtime/bootstrap/paper/RuntimeBuildVersion.java")
    }
    outputs.file(output)
    inputs.property("runtimeBuildVersion", runtimeBuildVersion)
    doLast {
        val file = output.get().asFile
        file.parentFile.mkdirs()
        file.writeText("package dev.shamoo.runtime.bootstrap.paper;\n"
            + "final class RuntimeBuildVersion { static final String VERSION = \"$runtimeBuildVersion\"; "
            + "private RuntimeBuildVersion() { } }\n")
    }
}
sourceSets.main { java.srcDir(generatedVersionDirectory) }
tasks.compileJava { dependsOn(generateRuntimeBuildVersion) }
tasks.named("sourcesJar") { dependsOn(generateRuntimeBuildVersion) }

tasks.jar {
    dependsOn(configurations.runtimeClasspath)
    archiveBaseName.set("shamoo-runtime-paper")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}

tasks.assemble {
    dependsOn(tasks.reobfJar)
}

tasks.processResources {
    inputs.property("runtimeBuildVersion", runtimeBuildVersion)
    dependsOn(":runtime-codegen-support:generatePaperApi")
    from(project(":runtime-codegen-support").layout.projectDirectory.dir("generated/paper")) {
        into("dev/shamoo/runtime/generated/paper")
    }
    filesMatching("plugin.yml") { expand("version" to runtimeBuildVersion) }
}
