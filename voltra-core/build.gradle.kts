plugins {
    id("org.jetbrains.kotlin.jvm")
    `java-library`
    `maven-publish`
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
}
kotlin.compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
dependencies {
    api(libs.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
publishing {
    publications { create<MavenPublication>("maven") { from(components["java"]) } }
}
