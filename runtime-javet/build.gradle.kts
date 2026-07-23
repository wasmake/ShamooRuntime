plugins { `java-library` }

dependencies {
    implementation(project(":runtime-core"))
    implementation(libs.javet)
    runtimeOnly(libs.javet.v8.linux)
}
