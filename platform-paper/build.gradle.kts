plugins { `java-library` }

dependencies {
    api(project(":runtime-core"))
    compileOnly(libs.paper.api)
    compileOnly(libs.netty.transport)
    testImplementation(libs.netty.transport)
    testImplementation(libs.mockito.core)
    testCompileOnly(libs.paper.api)
    testRuntimeOnly(libs.paper.api)
}
