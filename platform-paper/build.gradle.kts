plugins { `java-library` }

dependencies {
    api(project(":runtime-core"))
    compileOnly(libs.paper.api)
}
