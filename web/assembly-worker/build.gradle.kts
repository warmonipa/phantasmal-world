plugins {
    id("world.phantasmal.js")
}

kotlin {
    js {
        binaries.executable()
    }

    sourceSets {
        getByName("jsMain") {
            dependencies {
                api(project(":web:shared"))
            }
        }

        getByName("jsTest") {
            dependencies {
                implementation(project(":test-utils"))
            }
        }
    }
}

tasks.named("jsBrowserDevelopmentRun") {
    dependsOn("jsDevelopmentExecutableCompileSync")
}

tasks.named("jsBrowserProductionWebpack") {
    dependsOn("jsProductionExecutableCompileSync")
}