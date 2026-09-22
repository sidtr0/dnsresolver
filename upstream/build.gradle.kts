plugins {
    java
}

dependencies {
    // Netty for DNS wire format handling
    implementation("io.netty:netty-all:4.1.108.Final")

    // Configuration
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.1")
}