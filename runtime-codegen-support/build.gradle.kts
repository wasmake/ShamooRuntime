plugins { `java-library` }

dependencies {
    api(project(":runtime-protocol"))
    implementation(libs.swc4j)
}
