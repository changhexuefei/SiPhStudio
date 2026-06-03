plugins {
    kotlin("jvm")
}

group = "com.jason"
version = "unspecified"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    // 引用 toml 中定义的 ktor-network
    implementation(libs.ktor.network)

    // 引用协程库
    implementation(libs.kotlinx.coroutines.core)
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}