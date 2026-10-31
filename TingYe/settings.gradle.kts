pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        exclusiveContent { forRepository { mavenCentral() }; filter { includeGroup("org.jsoup") } }
        google(); mavenCentral()
    }
}
rootProject.name = "TingYe"
include(":app")
