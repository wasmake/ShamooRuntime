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
    dependsOn(":runtime-codegen-support:generatePaperApi")
    from(project(":runtime-codegen-support").layout.projectDirectory.dir("generated/paper")) {
        into("dev/shamoo/runtime/generated/paper")
    }
}
