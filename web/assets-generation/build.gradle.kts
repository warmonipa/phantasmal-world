plugins {
    id("world.phantasmal.jvm")
}

kotlin {
    sourceSets.configureEach {
        languageSettings.optIn("kotlinx.serialization.ExperimentalSerializationApi")
    }
}

dependencies {
    implementation(project(":psolib"))
    implementation(project(":web:shared"))
    implementation("org.jsoup:jsoup:1.13.1")
}

tasks.register<JavaExec>("generateAssets") {
    val outputFile = layout.buildDirectory.get().asFile.resolve("generatedAssets")
    outputs.dir(outputFile)

    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("world.phantasmal.web.assetsGeneration.MainKt")
    args = listOf(outputFile.absolutePath)
}

val viewerWeaponCatalogFile =
    rootProject.file(
        "web/src/jsMain/kotlin/world/phantasmal/web/viewer/models/ViewerWeaponCatalog.kt"
    )

tasks.register<JavaExec>("generateViewerWeaponCatalog") {
    description = "Regenerate the checked-in Viewer weapon catalog from ItemPMT and Unitxt"

    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("world.phantasmal.web.assetsGeneration.GenerateViewerWeaponCatalogKt")
    args = listOf(viewerWeaponCatalogFile.absolutePath)
}

val verifyViewerWeaponCatalog by tasks.registering(JavaExec::class) {
    description = "Verify the checked-in Viewer weapon catalog matches ItemPMT and Unitxt"

    val generatedFile =
        layout.buildDirectory.file("generatedViewerWeaponCatalog/ViewerWeaponCatalog.kt")
    inputs.file(viewerWeaponCatalogFile)
    outputs.file(generatedFile)

    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("world.phantasmal.web.assetsGeneration.GenerateViewerWeaponCatalogKt")
    args = listOf(generatedFile.get().asFile.absolutePath)

    doLast {
        check(generatedFile.get().asFile.readText() == viewerWeaponCatalogFile.readText()) {
            "ViewerWeaponCatalog.kt is stale. Run " +
                    "./gradlew :web:assets-generation:generateViewerWeaponCatalog."
        }
    }
}

tasks.named("check") {
    dependsOn(verifyViewerWeaponCatalog)
}

tasks.register<JavaExec>("synthesizeSinowRed") {
    description = "Synthesize Sinow Red (Ultimate rare Sinow Gold) by recolor transfer"

    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("world.phantasmal.web.assetsGeneration.SynthesizeSinowRedKt")

    // Usage: ./gradlew :web:assets-generation:synthesizeSinowRed --args="<npcs-assets-dir>"
}

tasks.register<JavaExec>("extractNpcModels") {
    description = "Extract NPC models from PSO BB game data (data.gsl)"

    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("world.phantasmal.web.assetsGeneration.ExtractNpcModelsKt")

    // Usage: ./gradlew :web:assets-generation:extractNpcModels --args="<pso-data-dir> <output-dir>"
    // Example: --args="D:/PSO/EphineaPSO2/data web/src/jsMain/resources/assets/npcs"
}

tasks.register<JavaExec>("extractUltimateAreas") {
    description = "Copy Ultimate-difficulty area assets (Episode I) from PSO BB game data"

    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("world.phantasmal.web.assetsGeneration.ExtractUltimateAreasKt")

    // Usage: ./gradlew :web:assets-generation:extractUltimateAreas --args="<pso-data-dir> <areas-dir>"
    // Example: --args="D:/PSO/EphineaPSO2/data web/src/jsMain/resources/assets/areas"
}
