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

rootProject.name = "Alibaba"

include(":app")
include(":domain")
include(":data")
include(":core:common")
include(":core:network")
include(":core:parser")
include(":core:storage")
include(":feature:home")
include(":feature:manual")
include(":feature:auto")
include(":feature:analyze")
include(":feature:search")
include(":feature:compare")
include(":feature:export")
include(":feature:panelscan")
include(":feature:exploittest")
include(":feature:expirycheck")
include(":feature:panelcheck")
