plugins { `java-library` }

dependencies {
    api(project(":runtime-core"))
    compileOnly(libs.velocity.api)
    testImplementation(libs.velocity.api)
}
