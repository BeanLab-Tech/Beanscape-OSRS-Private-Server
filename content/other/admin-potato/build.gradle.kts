plugins {
    id("base-conventions")
}

dependencies {
    implementation(projects.api.invtx)
    implementation(projects.api.player)
    implementation(projects.api.pluginCommons)
    implementation(projects.api.script)
    implementation(projects.api.config)
    implementation(projects.api.type.typeSymbols)
    implementation(projects.api.utils.utilsFormat)
    implementation(projects.engine.game)
    implementation(projects.engine.plugin)
    // Rotten potato additions (fix compile errors):
    //  - objs.rotten_potato (obj type reference)
    implementation(projects.api.type.typeReferences)
    //  - ChatType + MiscOutput (player output)
    implementation(projects.api.playerOutput)
    //  - SafeServiceExit (utils.system)
    implementation(projects.api.utils.utilsSystem)
}
