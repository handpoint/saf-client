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
        // Handpoint Nexus: URL and credentials come from ~/.gradle/gradle.properties.
        maven {
            url = uri(providers.gradleProperty("MAVEN_URL").get())
            isAllowInsecureProtocol = true
            credentials {
                username = providers.gradleProperty("MAVEN_USER").get()
                password = providers.gradleProperty("MAVEN_PASSWORD").get()
            }
        }
    }
}

rootProject.name = "saf-client"
include(":app")
