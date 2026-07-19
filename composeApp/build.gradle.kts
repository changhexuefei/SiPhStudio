import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.gradle.internal.os.OperatingSystem
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

val currentOs = OperatingSystem.current()
val currentArch = System.getProperty("os.arch").lowercase()

val lwjglPlatform = when {
    currentOs.isWindows -> "natives-windows"
    currentOs.isMacOsX -> if (currentArch == "aarch64" || currentArch == "arm64") "natives-macos-arm64" else "natives-macos"
    currentOs.isLinux -> "natives-linux"
    else -> error("Unsupported LWJGL platform: ${currentOs.name}")
}

val lwjglVersion = libs.versions.lwjgl.get()
val surfacePlotComposeAbiVersion = "1.11.1"

check(libs.versions.composeMultiplatform.get() == surfacePlotComposeAbiVersion) {
    "surface-plot JVM JARs were compiled against Compose $surfacePlotComposeAbiVersion. " +
        "Rebuild the JARs before changing composeMultiplatform."
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    jvmToolchain(21)

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
            implementation(compose.preview)

            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)

            implementation(libs.koin.core)
            implementation(libs.koin.compose)

            implementation(libs.compose.material3)
            implementation(libs.compose.material3.adaptive)
            implementation(libs.compose.material3.adaptive.layout)
            implementation(libs.compose.material3.adaptive.navigation)
            implementation(libs.compose.material3.adaptive.nav3)

            implementation(libs.compose.navigation3.ui)
            implementation(libs.compose.navigationevent)
            implementation(libs.androidx.savedstate)
            implementation(libs.androidx.window.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.lets.plot.kotlin)
            implementation(libs.lets.plot.compose)
        }

        jvmMain.dependencies {
            implementation(project(":pi-gcs-kotlin"))
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutinesSwing)
            implementation(files("libs/surface-plot-jvm-1.0.0.jar"))
            implementation(files("libs/surface-plot-opengl-jvm-1.0.0.jar"))

            implementation(project.dependencies.platform("org.lwjgl:lwjgl-bom:$lwjglVersion"))
            implementation(libs.lwjgl)
            implementation(libs.lwjgl.opengl)
            implementation(libs.lwjgl.jawt)
            implementation("org.lwjglx:lwjgl3-awt:0.2.4") {
                isTransitive = false
            }

            runtimeOnly("org.lwjgl:lwjgl:$lwjglVersion:$lwjglPlatform")
            runtimeOnly("org.lwjgl:lwjgl-opengl:$lwjglVersion:$lwjglPlatform")
            runtimeOnly(libs.slf4j.simple)
        }

        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}

compose.desktop {
    application {
        mainClass = "org.jason.siph.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "org.jason.siph"
            packageVersion = "1.0.0"
        }
    }
}
