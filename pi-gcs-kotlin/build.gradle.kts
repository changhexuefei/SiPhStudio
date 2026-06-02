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
    implementation(libs.kotlinx.coroutinesSwing)
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}