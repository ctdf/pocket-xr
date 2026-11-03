pluginManagement {
    repositories {
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "pocket-xr"
include(":core")
include(":app")
// 1.1.0: the add-on with the accessibility service and the notification listener (addon/build.gradle.kts).
include(":addon")
