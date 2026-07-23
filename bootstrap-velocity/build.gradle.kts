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
}
