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
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.3")
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
tasks.register<JavaExec>("runVirtualPiGcs") {
    group = "application"
    description = "Run a read-only virtual PI GCS server on loopback (no hardware)"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.jason.pi.gcs.simulation.VirtualPiGcsLauncher")
    standardInput = System.`in`
}

tasks.test {
    // Allow the E2E test to fork the actual production launcher on a separate JVM.
    systemProperty("virtualPiLauncherClasspath", sourceSets.main.get().runtimeClasspath.asPath)
}

tasks.test {
    systemProperty("gcsEvidenceRunId", System.getenv("GITHUB_RUN_ID") ?: "local-recovery-test")
    systemProperty("gcsEvidenceRevision", System.getenv("GITHUB_SHA") ?: "local-unverified")
}
