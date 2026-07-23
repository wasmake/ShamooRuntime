plugins { `java-library` }

dependencies {
    api(project(":runtime-core"))
    implementation(project(":platform-paper"))
    testImplementation(project(":runtime-javet"))
}
