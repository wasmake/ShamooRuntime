plugins { `java-library` }

dependencies {
    api(project(":runtime-core"))
    api(libs.javet)
    implementation(libs.jackson.databind)
    runtimeOnly(libs.javet.node.linux)
}
