plugins { java }

dependencies {
    implementation(project(":runtime-javet"))
    implementation(project(":platform-paper"))
    compileOnly(libs.paper.api)
}

tasks.jar {
    archiveBaseName.set("shamoo-runtime-paper")
}
