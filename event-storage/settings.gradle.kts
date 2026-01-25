pluginManagement {
    repositories {
        maven("https://nexus.odkl.ru/repository/maven-public")
        mavenLocal()
        gradlePluginPortal()
    }
}

rootProject.name = "event-storage"


include("event-storage-server")
include("event-storage-client")

