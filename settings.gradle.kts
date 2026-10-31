pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "OpenTabs"
include(":core")
// Keep iOS builds independent of an Android SDK installation.
if (!providers.gradleProperty("iosOnly").orElse("false").get().toBoolean()) {
    include(":app")
}
