pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        // Fix #9: Use relative path instead of hard-coded Windows path
        // This works for developers on any machine
        maven {
            val flutterModuleRoot = rootProject.projectDir.parentFile
            url = uri("$flutterModuleRoot/flutter_module/build/host/outputs/repo")
        }
        maven {
            url = uri("https://storage.googleapis.com/download.flutter.io")
        }
    }
}

rootProject.name = "Safe Travel Monitor"
include(":app")
