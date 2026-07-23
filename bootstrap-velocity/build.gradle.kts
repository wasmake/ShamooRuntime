plugins { java }

dependencies {
    implementation(project(":runtime-javet"))
    implementation(project(":platform-velocity"))
    compileOnly(libs.velocity.api)
    compileOnly("com.google.inject:guice:7.0.0")
    annotationProcessor(libs.velocity.api)
}

tasks.jar {
    archiveBaseName.set("shamoo-runtime-velocity")
    dependsOn(configurations.runtimeClasspath)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}

tasks.processResources {
    dependsOn(":runtime-codegen-support:generateVelocityApi")
    from(project(":runtime-codegen-support").layout.projectDirectory.dir("generated/velocity")) {
        into("dev/shamoo/runtime/generated/velocity")
    }
}
