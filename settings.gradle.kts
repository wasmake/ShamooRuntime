pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/") {
            name = "PaperMC"
        }
    }
}

rootProject.name = "ShamooRuntime"

include(
    "runtime-core",
    "runtime-javet",
    "runtime-protocol",
    "runtime-codegen-support",
    "platform-paper",
    "platform-velocity",
    "bootstrap-paper",
    "bootstrap-velocity",
    "integration-paper",
    "integration-velocity",
)
