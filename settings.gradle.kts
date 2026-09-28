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
        maven(url = "https://jitpack.io")
    }
}

rootProject.name = "crispy-rewrite"

include(":android:app")
include(":android:androidApp")
include(":android:youtube-extractor")
include(":android:tv")
include(":android:home")
include(":android:core-domain")
include(":android:platform-core")
include(":android:platform-android")
include(":android:sharedUI")
include(":android:desktopApp")
include(":android:player")
include(":android:native-engine")
include(":android:torrent-engine")
include(":android:network")
include(":android:watchhistory")
include(":android:backend")
include(":android:addons")
include(":android:ui-assets")
include(":android:plugins")
