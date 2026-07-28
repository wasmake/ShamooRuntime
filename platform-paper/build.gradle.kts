plugins { `java-library` }

dependencies {
    api(project(":runtime-core"))
    compileOnlyApi(libs.paper.api)
    compileOnlyApi(libs.netty.transport)
    testImplementation(libs.netty.transport)
    testImplementation(libs.mockito.core)
    testCompileOnly(libs.paper.api)
    testRuntimeOnly(libs.paper.api)
}
