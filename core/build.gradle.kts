import org.gradle.api.tasks.testing.Test

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

kotlin {
    jvmToolchain(17)
    // Android continues to consume the same JVM implementation and public API.
    jvm()
    iosArm64()
    iosSimulatorArm64()
    iosX64()
    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
        binaries.framework {
            baseName = "OpenTabsCore"
            isStatic = true
        }
    }
    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
        }
        commonTest.dependencies { implementation(kotlin("test")) }
        jvmMain {
            kotlin.srcDir("src/main/kotlin")
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
                implementation("com.squareup.okhttp3:okhttp:4.12.0")
            }
        }
        jvmTest {
            kotlin.srcDir("src/test/kotlin")
            resources.srcDir("src/test/resources")
            dependencies {
                implementation("junit:junit:4.13.2")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
                implementation("com.squareup.okhttp3:mockwebserver:4.12.0")
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    systemProperty("opentabs.liveProviders", providers.gradleProperty("liveProviders").orElse("false").get())
}
// Preserve existing developer commands and Android workflows during migration.
tasks.register("test") { dependsOn("jvmTest") }
