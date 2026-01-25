plugins{
    alias(libs.plugins.spring.boot)
    java
}
java.sourceCompatibility = JavaVersion.VERSION_17
java.targetCompatibility = JavaVersion.VERSION_17

tasks.withType<Test> {
    systemProperty("user.timezone", "Europe/Moscow")
    jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED")
    useJUnitPlatform()
}


tasks.withType<Jar> {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
