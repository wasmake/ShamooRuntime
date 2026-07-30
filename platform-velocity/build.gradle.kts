plugins { `java-library` }

dependencies {
    api(project(":runtime-core"))
    compileOnlyApi(libs.velocity.api)
    testImplementation(libs.velocity.api)
}
