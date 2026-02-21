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
    }
}

rootProject.name = "crispy-rewrite"

include(":android:app")
include(":android:tv")
include(":android:core-domain")
include(":android:contract-tests")
include(":android:player")
include(":android:native-engine")
include(":android:network")
