plugins {
    java
    id("com.github.johnrengelman.shadow")
}

dependencies {
    // Internal module dependencies
    implementation(project(":transport"))
    implementation(project(":cache"))
    implementation(project(":upstream"))

    // Configuration management
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.1")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.17.1")

    // All Netty and Caffeine dependencies come transitively from other modules
}

// Configure the shadow JAR (Uber-JAR)
tasks.shadowJar {
    archiveFileName.set("dns-resolver.jar")

    manifest {
        attributes["Main-Class"] = "com.resolver.DnsResolverApplication"
        attributes["Implementation-Title"] = "Advanced DNS Resolver"
        attributes["Implementation-Version"] = project.version
    }

    // Merge service descriptors
    mergeServiceFiles()
}

// Make the build task depend on shadowJar
tasks.build {
    dependsOn(tasks.shadowJar)
}