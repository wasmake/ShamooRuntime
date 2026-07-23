pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
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
    "platform-paper-nms",
    "platform-velocity",
    "bootstrap-paper",
    "bootstrap-velocity",
    "integration-paper",
    "integration-velocity",
)
