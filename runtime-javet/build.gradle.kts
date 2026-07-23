plugins { `java-library` }

dependencies {
    implementation(project(":runtime-core"))
    implementation(libs.javet)
    runtimeOnly(libs.javet.node.linux)
}
