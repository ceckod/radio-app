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

rootProject.name = "Radio"
// Името на модула остава същото, за да работи GitHub workflow-ът; кодът е в папка radioapp
include(":openopenradiomobile")
project(":openopenradiomobile").projectDir = file("radioapp")
