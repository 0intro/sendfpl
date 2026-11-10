pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "SendFPL"

// :cxp is the Connext protocol and the route model, on the Kotlin Multiplatform plugin alone, which
// is all its tests need. :ui is the screens the Android and iPhone apps share, and :app is the
// Android app.
include(":app", ":cxp", ":ui")
