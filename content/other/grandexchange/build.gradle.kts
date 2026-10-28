plugins {
    id("base-conventions")
}

dependencies {
    implementation(libs.fastutil)
    implementation(libs.simmetrics.core)
    implementation(projects.api.db)
    implementation(projects.api.dbGateway)
    implementation(projects.api.pluginCommons)
    implementation(projects.api.script)
    implementation(projects.api.cheat)
    implementation(projects.api.realm)
    implementation(projects.api.realmConfig)
    implementation(projects.api.type.typeSymbols)
    implementation(projects.api.utils.utilsSystem)
    implementation(projects.engine.utilsBits)
    testImplementation(kotlin("test"))
}
