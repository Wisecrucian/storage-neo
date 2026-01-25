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
    apply(plugin = "io.spring.dependency-management")
    
    configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    
    dependencies {
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }
    
    tasks.named<Test>("test") {
        useJUnitPlatform()
    }
}

// ============================================
// Custom Tasks
// ============================================

// Clean all build directories
tasks.register("cleanAll") {
    group = "build"
    description = "Clean all build directories in all modules"
    dependsOn(subprojects.map { it.tasks.named("clean") })
}

// Build all modules
tasks.register("buildAll") {
    group = "build"
    description = "Build all modules"
    dependsOn(subprojects.map { it.tasks.named("build") })
}

// Run tests in all modules
tasks.register("testAll") {
    group = "verification"
    description = "Run tests in all modules"
    dependsOn(subprojects.map { it.tasks.withType<Test>() })
}

// Show project structure
tasks.register("showStructure") {
    group = "help"
    description = "Show project structure and modules"
    doLast {
        println("\n" + "=".repeat(50))
        println("Project Structure")
        println("=".repeat(50))
        println("Root: ${rootProject.name} ($version)")
        subprojects.forEach { subproject ->
            println("  ├── ${subproject.name}")
            println("  │   └── Path: ${subproject.projectDir}")
        }
        println("=".repeat(50) + "\n")
    }
}

// Create distribution package
tasks.register<Copy>("dist") {
    group = "distribution"
    description = "Create distribution package"
    dependsOn(":event-storage-server:bootJar")
    
    val distDir = layout.buildDirectory.dir("distributions").get().asFile
    from(project(":event-storage-server").tasks.named("bootJar"))
    into(distDir)
    
    doLast {
        println("\n" + "=".repeat(50))
        println("Distribution created in: $distDir")
        println("=".repeat(50) + "\n")
    }
}

// Docker tasks
tasks.register<Exec>("dockerBuild") {
    group = "docker"
    description = "Build Docker image"
    commandLine("docker", "build", "-t", "event-storage-server:latest", ".")
}

tasks.register<Exec>("dockerUp") {
    group = "docker"
    description = "Start Docker containers"
    commandLine("docker-compose", "up", "-d")
}

tasks.register<Exec>("dockerDown") {
    group = "docker"
    description = "Stop Docker containers"
    commandLine("docker-compose", "down")
}

tasks.register<Exec>("dockerLogs") {
    group = "docker"
    description = "Show Docker container logs"
    commandLine("docker-compose", "logs", "-f")
}

// Quick development task
tasks.register("dev") {
    group = "development"
    description = "Clean, build and run server"
    dependsOn("cleanAll", "buildAll", ":event-storage-server:bootRun")
}

tasks.register("quickBuild") {
    group = "development"
    description = "Quick build without tests"
    dependsOn("buildAll")
    
    subprojects.forEach { subproject ->
        subproject.tasks.withType<Test>().configureEach {
            enabled = false
        }
    }
}

