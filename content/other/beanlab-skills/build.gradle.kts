plugins {
    id("base-conventions")
}

dependencies {
    implementation(projects.api.pluginCommons)
    implementation(projects.api.gameProcess)
    implementation(projects.api.scriptAdvanced)
}
