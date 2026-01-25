plugins {
    java
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
}

allprojects {
    group = "one.idsstorage"
    version = "1.0.0"
    
    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")
    
    configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    
    if (name == "event-storage-server") {
        apply(plugin = "io.spring.dependency-management")
    }
}

