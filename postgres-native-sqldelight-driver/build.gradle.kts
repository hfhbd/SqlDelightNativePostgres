import org.jetbrains.kotlin.gradle.plugin.mpp.*

plugins {
    kotlin("multiplatform")
    id("licensee")
    id("publishing")
}

repositories {
    mavenCentral()
}

kotlin {
    fun KotlinNativeTarget.config() {
        compilations.getByName("main") {
            cinterops {
                val libpq by creating {
                    defFile(project.file("src/nativeInterop/cinterop/libpq.def"))
                }
            }
        }
    }

    macosArm64 { config() }
    macosX64 { config() }
    linuxX64 { config() }
    // linuxArm64 { config() }
    // mingwX64 { config() }

    targets.all {
        compilations.all {
            kotlinOptions.allWarningsAsErrors = true
        }
    }

    explicitApi()
    sourceSets {
        all {
            languageSettings.progressiveMode = true
        }

        commonMain {
            dependencies {
                api("io.ktor:ktor-network:2.1.3")
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4")
                api("app.cash.sqldelight:runtime:2.0.0-alpha04")
                api("org.jetbrains.kotlinx:kotlinx-datetime:0.4.0")
                api("app.softwork:kotlinx-uuid-core:0.0.17")
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.6.4")
            }
        }
    }
}
