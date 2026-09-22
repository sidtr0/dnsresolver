plugins {
    java
}

dependencies {
    // Caffeine for high-performance caching
    implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")

    // Configuration
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.1")
}