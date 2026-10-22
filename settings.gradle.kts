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
            // Ask this repository only for Handpoint artifacts; everything else comes from
            // Google and Maven Central, never over this plain-HTTP connection.
            content { includeGroupByRegex("com\\.handpoint.*") }
            credentials {
                username = providers.gradleProperty("MAVEN_USER").get()
                password = providers.gradleProperty("MAVEN_PASSWORD").get()
            }
        }
    }
}

rootProject.name = "saf-client"
include(":app")
