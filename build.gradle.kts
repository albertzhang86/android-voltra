plugins {
    id("com.android.library") version "8.9.2" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    id("org.jetbrains.kotlin.jvm") version "2.1.20" apply false
}

// Keep generated classes outside cloud-synced folders when configured locally.
val localSettings = java.util.Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val externalBuildRoot = providers.gradleProperty("voltra.buildRoot").orNull
    ?: localSettings.getProperty("voltra.buildRoot")

allprojects {
    group = "io.github.albertzhang86.voltra"
    version = "0.1.0-SNAPSHOT"
    if (externalBuildRoot != null) layout.buildDirectory.set(file("$externalBuildRoot/${project.name}"))
}

subprojects {
    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> {
            publications.withType<MavenPublication>().configureEach {
                pom {
                    name.set(project.name)
                    description.set("UI-free VOLTRA protocol and Android Bluetooth library")
                    url.set("https://github.com/albertzhang86/android-voltra")
                    licenses {
                        license {
                            name.set("MIT License")
                            url.set("https://opensource.org/license/mit")
                        }
                    }
                    scm {
                        url.set("https://github.com/albertzhang86/android-voltra")
                        connection.set("scm:git:https://github.com/albertzhang86/android-voltra.git")
                    }
                }
            }
        }
    }
}
