plugins {
    java
}

dependencies {
    // Netty for UDP/TCP DNS server
    implementation("io.netty:netty-all:4.1.108.Final")

    // Configuration and logging
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.1")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.17.1")
}