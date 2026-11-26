plugins {
    id("base-conventions")
}

dependencies {
    implementation(libs.jackson.databind)
    implementation(projects.api.cheat)
    implementation(projects.api.config)
    implementation(projects.api.db)
    implementation(projects.api.dbGateway)
    implementation(projects.api.invtx)
    implementation(projects.api.player)
    implementation(projects.api.playerOutput)
    implementation(projects.api.pluginCommons)
    implementation(projects.api.pwHash)
    implementation(projects.api.realm)
    implementation(projects.api.script)
    implementation(projects.api.type.typeReferences)
    implementation(projects.api.utils.utilsSystem)
    implementation(projects.content.other.adminPotato)
    implementation(projects.content.other.beanlabSoulwars)
    implementation(projects.engine.game)
    implementation(projects.engine.plugin)
    testImplementation(kotlin("test"))
}
