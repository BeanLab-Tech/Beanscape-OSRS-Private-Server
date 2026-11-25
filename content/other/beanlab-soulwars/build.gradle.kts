plugins {
    id("base-conventions")
}

dependencies {
    implementation(projects.api.pluginCommons)
    implementation(projects.api.gameProcess)
    implementation(projects.api.cache)
    implementation(projects.api.type.typeBuilders)
    implementation(libs.fastutil)
    implementation(libs.jackson.databind)
    testImplementation(libs.netty.buffer)
    testImplementation(kotlin("test"))
}

val layoutResources = "src/main/resources/org/rsmod/content/other/beanlab/soulwars"

// Rewrites the hub's m files from a layout file, then run `packCache`:
//   ./gradlew :content:other:beanlab-soulwars:generateHubTerrain [-PhubLayout=path/to/layout.json]
tasks.register<JavaExec>("generateHubTerrain") {
    group = "beanlab"
    description = "Regenerates the Market Hub terrain files from hub-layout.json."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.rsmod.content.other.beanlab.soulwars.HubTerrainToolKt")
    workingDir = projectDir
    val layout = providers.gradleProperty("hubLayout").orElse("$layoutResources/hub-layout.json")
    args(layout.get(), "$layoutResources/map")
}
