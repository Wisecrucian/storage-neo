plugins {
    java
    alias(libs.plugins.spring.boot) apply false
}

allprojects {
    group = "one.idsstorage"
    version = "1.0.0"
    
    repositories {
        mavenCentral()
    }
}

subprojects {
    configure(
        listOf(
            project(":event-storage-client"),
        )
    ) {
        plugins.apply("java")

        java {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
    }
}

