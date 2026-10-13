plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    `maven-publish`
}
android {
    namespace = "io.github.albertzhang86.voltra.android"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    publishing { singleVariant("release") { withSourcesJar() } }
    lint { abortOnError = true }
}
dependencies {
    api(project(":voltra-core"))
    testImplementation(libs.junit)
}
publishing {
    publications {
        register<MavenPublication>("release") {
            afterEvaluate { from(components["release"]) }
        }
    }
}
