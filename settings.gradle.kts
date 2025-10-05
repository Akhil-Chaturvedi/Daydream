// C:\Users\Electrobot\AndroidStudioProjects\Daydream\settings.gradle.kts

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        
        flatDir {
            dirs("app/libs")
        }
    }
}

rootProject.name = "Daydream"
include(":app")