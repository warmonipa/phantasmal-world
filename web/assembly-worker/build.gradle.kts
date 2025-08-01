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

tasks.named("jsBrowserProductionWebpack") {
    dependsOn("jsDevelopmentExecutableCompileSync")
}

tasks.named("jsBrowserDevelopmentRun") {
    dependsOn("jsProductionExecutableCompileSync")
}