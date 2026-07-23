plugins { `java-library` }

dependencies {
    api(project(":runtime-core"))
    implementation(project(":platform-velocity"))
    testImplementation(project(":runtime-javet"))
}

val velocityServer = layout.buildDirectory.file("runtime-server/velocity-3.4.0-566.jar")
val velocityWork = layout.buildDirectory.dir("runtime-server/work")
val velocityBootstrap = project(":bootstrap-velocity").tasks.named<Jar>("jar").flatMap { it.archiveFile }

val downloadVelocityServer = tasks.register("downloadVelocityServer") {
    group = "integration testing"
    description = "Download and cache pinned Velocity 3.4.0 build 566"
    notCompatibleWithConfigurationCache("Ant download keeps the immutable server outside normal dependencies")
    outputs.file(velocityServer)
    doLast {
        ant.invokeMethod("get", mapOf(
            "src" to "https://fill-data.papermc.io/v1/objects/fb599cbda6a6d01decce5e281f71f51cae7cacffcfafca32a09601f407b0583e/velocity-3.4.0-566.jar",
            "dest" to velocityServer.get().asFile,
            "verbose" to true,
            "usetimestamp" to true,
        ))
    }
}

tasks.register<JavaExec>("velocityProcessIntegration") {
    group = "integration testing"
    description = "Launch pinned Velocity with the built bootstrap and run its readiness scenario"
    notCompatibleWithConfigurationCache("launches and controls an external server process")
    dependsOn(downloadVelocityServer, tasks.classes, ":bootstrap-velocity:jar")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.shamoo.runtime.integration.velocity.VelocityServerHarness")
    doFirst {
        args(velocityServer.get().asFile.absolutePath, velocityBootstrap.get().asFile.absolutePath,
            velocityWork.get().asFile.absolutePath)
    }
}
