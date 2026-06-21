import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.gradle.internal.os.OperatingSystem
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

val currentOs = OperatingSystem.current()
val currentArch = System.getProperty("os.arch").lowercase()

val javafxPlatform = when {
    currentOs.isWindows -> "win"
    currentOs.isMacOsX -> {
        if (currentArch == "aarch64" || currentArch == "arm64") {
            "mac-aarch64"
        } else {
            "mac"
        }
    }
    currentOs.isLinux -> "linux"
    else -> error("Unsupported JavaFX platform: ${currentOs.name}")
}

val lwjglPlatform = when {
    currentOs.isWindows -> "natives-windows"
    currentOs.isMacOsX -> {
        if (currentArch == "aarch64" || currentArch == "arm64") {
            "natives-macos-arm64"
        } else {
            "natives-macos"
        }
    }
    currentOs.isLinux -> "natives-linux"
    else -> error("Unsupported LWJGL platform: ${currentOs.name}")
}

val javafxVersion = libs.versions.javafx.get()
val lwjglVersion = libs.versions.lwjgl.get()

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

            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)

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
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutinesSwing)

            // =====================================================
            // JavaFX
            //
            // JavaFX 在桌面端需要平台 classifier：
            // Windows: win
            // macOS x64: mac
            // macOS ARM64: mac-aarch64
            // Linux: linux
            // =====================================================
            implementation("org.openjfx:javafx-base:$javafxVersion:$javafxPlatform")
            implementation("org.openjfx:javafx-controls:$javafxVersion:$javafxPlatform")
            implementation("org.openjfx:javafx-graphics:$javafxVersion:$javafxPlatform")
            implementation("org.openjfx:javafx-swing:$javafxVersion:$javafxPlatform")

            // =====================================================
            // LWJGL
            //
            // 1. 用 BOM 统一 org.lwjgl:* 的版本。
            // 2. lwjgl / opengl / jawt 用普通 implementation。
            // 3. native jar 用 runtimeOnly，并且显式写版本 + classifier。
            // 4. 不要写 ${lwjgl.natives}。
            // =====================================================
            implementation(
                project.dependencies.platform("org.lwjgl:lwjgl-bom:$lwjglVersion")
            )

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
    }
}

compose.desktop {
    application {
        mainClass = "org.jason.siph.MainKt"

        nativeDistributions {
            targetFormats(
                TargetFormat.Dmg,
                TargetFormat.Msi,
                TargetFormat.Deb
            )

            packageName = "org.jason.siph"
            packageVersion = "1.0.0"
        }
    }
}
