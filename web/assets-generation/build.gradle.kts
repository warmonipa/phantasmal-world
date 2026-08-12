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

tasks.register<JavaExec>("generateSideStoryNpcGolden") {
    description = "Regenerate the checked-in V4 Side Story scripted-NPC golden data"

    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("world.phantasmal.web.assetsGeneration.GenerateSideStoryNpcGoldenKt")

    // Write to a reviewable temporary file first; the checked-in golden is a test oracle.
    // Usage: ./gradlew :web:assets-generation:generateSideStoryNpcGolden --args=/tmp/golden.tsv
}

val sideStoryNpcGoldenFile =
    file("src/test/resources/golden/v4-side-story-script-npcs.tsv")

val verifySideStoryNpcGolden by tasks.registering(JavaExec::class) {
    description = "Verify V4 Side Story scripted-NPC analysis against checked-in golden data"

    val generatedFile = layout.buildDirectory.file(
        "generatedSideStoryNpcGolden/v4-side-story-script-npcs.tsv"
    )
    inputs.file(sideStoryNpcGoldenFile)
    inputs.files(fileTree("src/main/resources/ephinea/ship-config/quest") {
        include("episode_*/story/side_story/*.qst")
    })
    outputs.file(generatedFile)

    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("world.phantasmal.web.assetsGeneration.GenerateSideStoryNpcGoldenKt")
    args = listOf(generatedFile.get().asFile.absolutePath)

    doLast {
        check(generatedFile.get().asFile.readText() == sideStoryNpcGoldenFile.readText()) {
            "V4 Side Story scripted-NPC analysis changed. Review the corpus diff, then run " +
                ":web:assets-generation:generateSideStoryNpcGolden with an explicit output path."
        }
    }
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
    dependsOn(verifySideStoryNpcGolden)
}

tasks.register<JavaExec>("prepareSinowVariants") {
    description = "Prepare original Red textures and the retracted Blue bind pose"

    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("world.phantasmal.web.assetsGeneration.PrepareSinowVariantsKt")

    // Usage: ./gradlew :web:assets-generation:prepareSinowVariants --args="<npcs-assets-dir>"
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
