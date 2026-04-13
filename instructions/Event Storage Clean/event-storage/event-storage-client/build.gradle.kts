plugins {
    `java-library`
}

dependencies {
    implementation(libs.okhttp)
    implementation(libs.jackson.databind)
    
    testImplementation(platform("org.junit:junit-bom:5.10.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

tasks.test {
    useJUnitPlatform()
}
