import groovy.util.Node
import groovy.util.NodeList
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.authentication.http.BasicAuthentication

fun Node.child(name: String): Node? =
    (get(name) as NodeList).filterIsInstance<Node>().firstOrNull()

plugins {
    base
    alias(libs.plugins.spotbugs) apply false
}

group = "com.shamoof"
version = providers.gradleProperty("projectVersion").get()
val publishedLibraries = setOf(
    "runtime-protocol",
    "runtime-core",
    "runtime-javet",
    "runtime-codegen-support",
    "platform-paper",
    "platform-velocity",
)
val publicationTaskName = "publishMavenJavaPublicationToShamooRepository"
val validatePublication = tasks.register("validatePublication") {
    group = "publishing"
    description = "Validates the immutable version and credentials used for Maven publication"
    doLast {
        val releaseVersion = project.version.toString()
        require(releaseVersion.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?"))) {
            "Publication requires a semantic projectVersion, received '$releaseVersion'"
        }
        require(!releaseVersion.endsWith("-SNAPSHOT", ignoreCase = true)) {
            "Publication requires an immutable non-SNAPSHOT projectVersion"
        }
        require(!providers.environmentVariable("SHAMOO_MAVEN_TOKEN").orNull.isNullOrBlank()) {
            "Publication requires SHAMOO_MAVEN_TOKEN"
        }
    }
}
val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
val javaVersion = catalog.findVersion("java").orElseThrow().requiredVersion.toInt()
val checkstyleVersion = catalog.findVersion("checkstyle").orElseThrow().requiredVersion
val pmdVersion = catalog.findVersion("pmd").orElseThrow().requiredVersion
val spotbugsToolVersion = catalog.findVersion("spotbugs").orElseThrow().requiredVersion
val junitBom = catalog.findLibrary("junit-bom").orElseThrow()
val junitJupiter = catalog.findLibrary("junit-jupiter").orElseThrow()
val junitLauncher = catalog.findLibrary("junit-launcher").orElseThrow()

allprojects {
    group = rootProject.group
    version = rootProject.version
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "jacoco")
    apply(plugin = "checkstyle")
    apply(plugin = "pmd")
    apply(plugin = "com.github.spotbugs")

    if (name in publishedLibraries) {
        apply(plugin = "maven-publish")
        extensions.configure<PublishingExtension> {
            publications {
                create<MavenPublication>("mavenJava") {
                    from(components["java"])
                    pom {
                        name.set("ShamooRuntime ${project.name}")
                        description.set("ShamooRuntime Java 21 ${project.name} module")
                        url.set("https://github.com/wasmake/ShamooRuntime")
                        licenses {
                            license {
                                name.set("Apache License, Version 2.0")
                                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                                distribution.set("repo")
                            }
                        }
                        scm {
                            connection.set("scm:git:https://github.com/wasmake/ShamooRuntime.git")
                            developerConnection.set("scm:git:ssh://git@github.com/wasmake/ShamooRuntime.git")
                            url.set("https://github.com/wasmake/ShamooRuntime")
                        }
                        withXml {
                            val providedCoordinates = setOf(
                                "com.velocitypowered:velocity-api",
                                "io.netty:netty-transport",
                                "io.papermc.paper:paper-api",
                            )
                            asNode().child("dependencies")
                                ?.let { it.get("dependency") as NodeList }
                                ?.filterIsInstance<Node>()
                                ?.forEach { dependency ->
                                    val coordinate = listOf("groupId", "artifactId")
                                        .joinToString(":") { dependency.child(it)?.text().orEmpty() }
                                    if (coordinate in providedCoordinates) {
                                        val scope = dependency.child("scope")
                                        if (scope === null) dependency.appendNode("scope", "provided")
                                        else scope.setValue("provided")
                                    }
                                }
                        }
                    }
                }
            }
            repositories {
                maven {
                    name = "Shamoo"
                    url = uri("https://shamoof.com/maven")
                    credentials {
                        username = providers.environmentVariable("SHAMOO_MAVEN_USERNAME")
                            .getOrElse("github-actions")
                        password = providers.environmentVariable("SHAMOO_MAVEN_TOKEN").orNull
                    }
                    authentication {
                        create<BasicAuthentication>("basic")
                    }
                }
            }
        }
        tasks.withType<PublishToMavenRepository>().configureEach {
            dependsOn(validatePublication)
        }
    }

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(javaVersion))
        withSourcesJar()
        withJavadocJar()
    }

    configurations.configureEach {
        resolutionStrategy {
            if (project.name.startsWith("runtime-") && name !in setOf("checkstyle", "pmd", "spotbugs")) {
                failOnVersionConflict()
            }
            cacheChangingModulesFor(0, "seconds")
        }
    }

    dependencies {
        "testImplementation"(platform(junitBom))
        "testImplementation"(junitJupiter)
        "testRuntimeOnly"(junitLauncher)
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release.set(javaVersion)
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:all,-processing", "-Werror", "-parameters"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        failFast = false
        reports.html.required.set(true)
        reports.junitXml.required.set(true)
        jvmArgs("-ea")
    }

    extensions.configure<CheckstyleExtension> {
        toolVersion = checkstyleVersion
        configFile = rootProject.file("config/checkstyle/checkstyle.xml")
        isIgnoreFailures = false
        maxWarnings = 0
    }

    extensions.configure<PmdExtension> {
        toolVersion = pmdVersion
        ruleSetFiles = files(rootProject.file("config/pmd/ruleset.xml"))
        ruleSets = emptyList()
        isConsoleOutput = true
        isIgnoreFailures = false
    }

    extensions.configure<com.github.spotbugs.snom.SpotBugsExtension> {
        toolVersion.set(spotbugsToolVersion)
        ignoreFailures.set(false)
        excludeFilter.set(rootProject.file("config/spotbugs/exclude.xml"))
    }

    tasks.withType<Jar>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
        manifest.attributes(
            "Implementation-Title" to project.name,
            "Implementation-Version" to project.version,
            "Automatic-Module-Name" to "dev.shamoo.${project.name.replace('-', '.')}",
        )
    }

    tasks.withType<Javadoc>().configureEach {
        (options as StandardJavadocDocletOptions).apply {
            encoding = "UTF-8"
            addStringOption("Xdoclint:all,-missing", "-quiet")
        }
    }
}

tasks.register("publishLibraries") {
    group = "publishing"
    description = "Publishes reusable ShamooRuntime libraries to the Shamoo Maven repository"
    dependsOn(publishedLibraries.map { ":$it:$publicationTaskName" })
}

gradle.projectsEvaluated {
    publishedLibraries.zipWithNext().forEach { (previous, next) ->
        project(":$next").tasks.named(publicationTaskName).configure {
            mustRunAfter(project(":$previous").tasks.named(publicationTaskName))
        }
    }
}
