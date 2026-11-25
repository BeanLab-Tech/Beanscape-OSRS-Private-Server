plugins {
    id("base-conventions")
}

dependencies {
    implementation(projects.api.pluginCommons)
    implementation(projects.api.gameProcess)
    implementation(projects.api.cache)
    implementation(libs.fastutil)
    testImplementation(libs.netty.buffer)
    testImplementation(kotlin("test"))
    implementation(projects.api.type.typeBuilders)
}
