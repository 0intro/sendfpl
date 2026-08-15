pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "SendFPL"

// :cxp is the Connext protocol and the route model, on the Kotlin Multiplatform plugin alone, which
// is all its tests need.
include(":cxp")
