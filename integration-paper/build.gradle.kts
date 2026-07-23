plugins { `java-library` }

dependencies {
    api(project(":runtime-core"))
    implementation(project(":platform-paper"))
    testImplementation(project(":runtime-javet"))
}

val paperServer = layout.buildDirectory.file("runtime-server/paper-1.21.8-55.jar")
val paperWork = layout.buildDirectory.dir("runtime-server/work")
val paperBootstrap = project(":bootstrap-paper").tasks.named("reobfJar").map { it.outputs.files.singleFile }

val downloadPaperServer = tasks.register("downloadPaperServer") {
    group = "integration testing"
    description = "Download and cache pinned Paper 1.21.8 build 55"
    notCompatibleWithConfigurationCache("Ant download keeps the immutable server outside normal dependencies")
    outputs.file(paperServer)
    doLast {
        ant.invokeMethod("get", mapOf(
            "src" to "https://fill-data.papermc.io/v1/objects/8d2d772fbf77210ede1ea670c262aa7c8f8ef7f31d8e86e14a37c20b4d36fd20/paper-1.21.8-55.jar",
            "dest" to paperServer.get().asFile,
            "verbose" to true,
            "usetimestamp" to true,
        ))
    }
}

tasks.register<JavaExec>("paperProcessIntegration") {
    group = "integration testing"
    description = "Launch pinned Paper with the built bootstrap and run its readiness scenario"
    notCompatibleWithConfigurationCache("launches and controls an external server process")
    dependsOn(downloadPaperServer, tasks.classes, ":bootstrap-paper:reobfJar")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.shamoo.runtime.integration.paper.PaperServerHarness")
    doFirst {
        args(paperServer.get().asFile.absolutePath, paperBootstrap.get().absolutePath,
            paperWork.get().asFile.absolutePath)
    }
}
