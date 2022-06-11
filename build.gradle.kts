import org.jetbrains.kotlin.gradle.plugin.mpp.*
import org.jetbrains.kotlin.konan.target.*

plugins {
    kotlin("multiplatform") version "1.7.0"
}

repositories {
    mavenCentral()
    maven(url = "https://oss.sonatype.org/content/repositories/snapshots")
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

    when (HostManager.host) {
        KonanTarget.MACOS_ARM64 -> {
            macosArm64 { config() }
        }
        KonanTarget.MACOS_X64 -> {
            macosX64 { config() }
        }
        KonanTarget.LINUX_X64 -> {
            linuxX64 { config() }
        }
        KonanTarget.MINGW_X64 -> {
            mingwX64 { config() }
        }
        else -> error("Not yet supported")
    }

    sourceSets {
        commonMain {
            dependencies {
                api("app.cash.sqldelight:runtime:2.0.0-SNAPSHOT")
                api("org.jetbrains.kotlinx:kotlinx-datetime:0.3.3")
                api("app.softwork:kotlinx-uuid-core:0.0.15")
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.6.2")
            }
        }
    }
}
