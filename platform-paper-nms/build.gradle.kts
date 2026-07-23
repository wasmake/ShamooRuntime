plugins {
    `java-library`
    alias(libs.plugins.paperweight.userdev)
}

dependencies {
    paperweight.paperDevBundle("1.21.8-R0.1-20250906.215025-55")
    api(project(":platform-paper"))
    compileOnly(libs.paper.api)
    testImplementation(libs.netty.transport)
}

tasks.assemble {
    dependsOn(tasks.reobfJar)
}

val codegen = project(":runtime-codegen-support")
val packetRegistrations = layout.buildDirectory.file("codegen/packet-registrations.tsv")
val mappedServer = layout.projectDirectory.file(".gradle/caches/paperweight/taskCache/mappedServerJar.jar")
val minecraftLibraries = fileTree(gradle.gradleUserHomeDir.resolve("caches/paperweight-userdev/v2/work")) {
    include("**/minecraftLibraries/**/*.jar")
}

val extractPacketRegistrations = tasks.register<JavaExec>("extractPacketRegistrations") {
    group = "code generation"
    description = "Read exact packet IDs from the mapped 1.21.8 protocol tables in an isolated JVM"
    dependsOn(tasks.compileJava)
    classpath = files(sourceSets.main.get().output.classesDirs, configurations.compileClasspath,
        configurations.runtimeClasspath,
        mappedServer, minecraftLibraries)
    mainClass.set("dev.shamoo.runtime.platform.paper.nms.codegen.PacketRegistrationExtractor")
    args(packetRegistrations.get().asFile.absolutePath)
    outputs.file(packetRegistrations)
}
codegen.tasks.matching { it.name == "generatePaperweightNmsModels" }.configureEach {
    mustRunAfter(extractPacketRegistrations)
}

val generatePaperNmsModels = tasks.register("generatePaperNmsModels") {
    group = "code generation"
    description = "Generate exact Paper NMS and packet models from paperweight's mapped server"
    dependsOn(tasks.named("paperweightUserdevSetup"), extractPacketRegistrations,
        codegen.tasks.named("generatePaperweightNmsModels"))
}

tasks.processResources {
    dependsOn(generatePaperNmsModels)
    from(layout.projectDirectory.dir("generated")) {
        into("dev/shamoo/runtime/generated")
    }
}
