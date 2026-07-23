import java.security.MessageDigest
import java.util.HexFormat

plugins { `java-library` }

dependencies {
    api(project(":runtime-protocol"))
    implementation(libs.swc4j)
    implementation(libs.asm)
    implementation(libs.jackson.databind)
}

val paperApi = configurations.create("paperApi")
val velocityApi = configurations.create("velocityApi")
val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
val paperVersion = catalog.findVersion("paper").orElseThrow().requiredVersion
val velocityVersion = catalog.findVersion("velocity").orElseThrow().requiredVersion
paperApi.isTransitive = false
velocityApi.isTransitive = false

dependencies {
    paperApi(libs.paper.api)
    paperApi(libs.paper.adventure.api)
    velocityApi(libs.velocity.api)
    velocityApi(libs.velocity.adventure.api)
}

fun registerApiTask(name: String, platform: String, apiVersion: String, mapping: String, artifacts: Configuration) =
    tasks.register<JavaExec>(name) {
        val artifactPaths = artifacts.incoming.artifacts.resolvedArtifacts.map { resolved ->
            resolved.map { it.file.absolutePath }.sorted()
        }
        group = "code generation"
        description = "Generate deterministic $platform API descriptors and coverage"
        dependsOn(tasks.classes)
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass.set("dev.shamoo.runtime.codegen.ApiGeneratorCli")
        args(platform, apiVersion, mapping, layout.projectDirectory.dir("generated/$platform").asFile.absolutePath)
        argumentProviders.add(CommandLineArgumentProvider { artifactPaths.get() })
    }

val generatePaperApi = registerApiTask("generatePaperApi", "paper", paperVersion,
    "paper-api+adventure", paperApi)
val generateVelocityApi = registerApiTask("generateVelocityApi", "velocity", velocityVersion,
    "velocity-api+adventure", velocityApi)

val generatedSnapshot = layout.buildDirectory.dir("verification/platform-api-snapshot")
val snapshotPlatformApis = tasks.register<Sync>("snapshotPlatformApis") {
    from(layout.projectDirectory.dir("generated"))
    into(generatedSnapshot)
}
generatePaperApi.configure { mustRunAfter(snapshotPlatformApis) }
generateVelocityApi.configure { mustRunAfter(snapshotPlatformApis) }

tasks.register("generatePlatformApis") {
    group = "code generation"
    dependsOn(generatePaperApi, generateVelocityApi)
}

tasks.register("syncPlatformApis") {
    group = "code generation"
    description = "Synchronize checked-in descriptors with pinned platform artifacts"
    dependsOn("generatePlatformApis")
}

tasks.register("diffPlatformApis") {
    group = "verification"
    description = "Fail when generated descriptors differ from checked-in registries"
    dependsOn(snapshotPlatformApis, "generatePlatformApis")
    doLast {
        fun checksums(root: File): Map<String, String> {
            if (!root.isDirectory) return emptyMap()
            val digest = MessageDigest.getInstance("SHA-256")
            return root.walkTopDown().filter(File::isFile).associate { file ->
                file.relativeTo(root).invariantSeparatorsPath to
                    HexFormat.of().formatHex(digest.digest(file.readBytes()))
            }.toSortedMap()
        }
        val expected = checksums(generatedSnapshot.get().asFile)
        val actual = checksums(layout.projectDirectory.dir("generated").asFile)
        if (expected != actual) {
            val missing = expected.keys - actual.keys
            val stale = actual.keys - expected.keys
            val changed = expected.keys.intersect(actual.keys).filter { expected[it] != actual[it] }
            throw GradleException("generated platform API drift: missing=$missing stale=$stale changed=$changed")
        }
    }
}

tasks.register<JavaExec>("generatePaperNms") {
    group = "code generation"
    description = "Generate exact-version Paper NMS packet descriptors from -PnmsArtifact and -PnmsVersion"
    dependsOn(tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.shamoo.runtime.codegen.NmsGeneratorCli")
    val artifact = providers.gradleProperty("nmsArtifact")
    val nmsVersion = providers.gradleProperty("nmsVersion")
    val registrations = providers.gradleProperty("nmsRegistrations")
    onlyIf { artifact.isPresent && nmsVersion.isPresent && registrations.isPresent }
    doFirst {
        args(nmsVersion.get(), layout.projectDirectory.dir("generated/paper-nms").asFile.absolutePath,
            layout.projectDirectory.dir("generated/paper-packets").asFile.absolutePath,
            registrations.get(), artifact.get())
    }
    notCompatibleWithConfigurationCache("accepts an external exact-version Paper server artifact")
}

tasks.register<JavaExec>("generatePaperweightNmsModels") {
    group = "code generation"
    description = "Scan platform-paper-nms paperweight mapped output"
    dependsOn(tasks.classes)
    mustRunAfter(":platform-paper-nms:paperweightUserdevSetup")
    val mappedServer = project(":platform-paper-nms").layout.projectDirectory
        .file(".gradle/caches/paperweight/taskCache/mappedServerJar.jar")
    val packetRegistrations = project(":platform-paper-nms").layout.buildDirectory
        .file("codegen/packet-registrations.tsv")
    inputs.file(mappedServer)
    inputs.file(packetRegistrations)
    outputs.dir(project(":platform-paper-nms").layout.projectDirectory.dir("generated"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.shamoo.runtime.codegen.NmsGeneratorCli")
    args(
        "1.21.8+paper.55+mache.2",
        project(":platform-paper-nms").layout.projectDirectory
            .dir("generated/paper-nms").asFile.absolutePath,
        project(":platform-paper-nms").layout.projectDirectory
            .dir("generated/paper-packets").asFile.absolutePath,
        packetRegistrations.get().asFile.absolutePath,
        mappedServer.asFile.absolutePath,
    )
}
