import org.jetbrains.kotlin.konan.target.*

plugins {
    kotlin("multiplatform")
    repos
}

kotlin {

    when (HostManager.host) {
        KonanTarget.LINUX_X64 -> linuxX64()
        KonanTarget.MACOS_ARM64 -> macosArm64()
        KonanTarget.MACOS_X64 -> macosX64()
        else -> error("Not supported")
    }

    sourceSets {
        commonTest {
            dependencies {
                implementation(projects.postgresNativeSqldelightDriver)
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.0")
            }
        }
    }
}
