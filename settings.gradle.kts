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

rootProject.name = "MAGIC"
include(":sync-core")
include(":sync-cli")

fun hasAndroidSdk(): Boolean {
    val local = file("local.properties")
    if (local.exists() && local.readText().lineSequence().any { it.startsWith("sdk.dir=") }) return true
    val home = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT") ?: return false
    return file(home).isDirectory
}

if (hasAndroidSdk()) {
    include(":app")
}
