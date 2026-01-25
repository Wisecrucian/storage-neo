plugins {
    alias(libs.plugins.spring.boot)
    java
}

dependencies {
    // Spring Boot
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.kafka)
    
    // Validation
    implementation(libs.spring.boot.starter.validation)
    
    // Monitoring
    implementation(libs.spring.boot.starter.actuator)
    
    // Testing
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.kafka.test)
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("${project.name}-${project.version}.jar")
}

