plugins {
    base
    alias(libs.plugins.spotbugs) apply false
}

group = "dev.shamoo.runtime"
version = providers.gradleProperty("projectVersion").get()
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
