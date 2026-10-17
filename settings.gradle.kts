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
rootProject.name = "AppStore"
include(":app")
includeBuild("./ina")    {
    dependencySubstitution {
        substitute(module("app.accrescent.ina:Ina")).using(project(":android"))
    }
}
