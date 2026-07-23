plugins { `java-library` }

dependencies {
    api(project(":runtime-core"))
    implementation(project(":platform-velocity"))
    testImplementation(project(":runtime-javet"))
}
