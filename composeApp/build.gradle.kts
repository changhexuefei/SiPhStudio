import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.gradle.internal.os.OperatingSystem

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

val javafxPlatform = with(OperatingSystem.current()) {
    when {
        isWindows -> "win"
        isMacOsX -> if (System.getProperty("os.arch") == "aarch64") "mac-aarch64" else "mac"
        isLinux -> "linux"
        else -> error("Unsupported JavaFX platform: $name")
    }
}
val javafxVersion = libs.versions.javafx.get()

kotlin {
    jvm()
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
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.compose.material3)
            implementation(libs.compose.material3.adaptive)
            implementation(libs.compose.material3.adaptive.layout)
            implementation(libs.compose.material3.adaptive.navigation)
            implementation(libs.compose.navigation3.ui)
            implementation(libs.compose.material3.adaptive.nav3)
            implementation(libs.compose.navigationevent)
            implementation(libs.androidx.savedstate)
            implementation(libs.androidx.window.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.lets.plot.kotlin)
            implementation(libs.lets.plot.compose)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutinesSwing)
            implementation("org.openjfx:javafx-base:$javafxVersion:$javafxPlatform")
            implementation("org.openjfx:javafx-controls:$javafxVersion:$javafxPlatform")
            implementation("org.openjfx:javafx-graphics:$javafxVersion:$javafxPlatform")
            implementation("org.openjfx:javafx-swing:$javafxVersion:$javafxPlatform")
            runtimeOnly(libs.slf4j.simple)
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
