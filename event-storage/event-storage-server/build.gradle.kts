plugins {
    alias(libs.plugins.spring.boot)
    java
}

dependencies {
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.kafka)
    
    testImplementation(libs.spring.boot.starter.test)
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("${project.name}-${project.version}.jar")
}
