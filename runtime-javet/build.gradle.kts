plugins { `java-library` }

dependencies {
    implementation(project(":runtime-core"))
    implementation(libs.javet)
    implementation(libs.jackson.databind)
    runtimeOnly(libs.javet.node.linux)
}
