plugins {
    java
    id("com.github.johnrengelman.shadow") version "8.1.1" apply false
}

// Configure Java for all projects
allprojects {
    group = "com.resolver"
    version = "1.0.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

// Common dependencies for all modules
val nettyVersion = "4.1.108.Final"
val caffeineVersion = "3.1.8"
val slf4jVersion = "2.0.12"
val jacksonVersion = "2.17.1"
val junitVersion = "5.10.2"
val mockitoVersion = "5.11.0"

// Helper function to configure test dependencies
fun DependencyHandlerScope.commonTestDependencies() {
    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testImplementation("org.junit.jupiter:junit-jupiter-params:$junitVersion")
    testImplementation("org.mockito:mockito-core:$mockitoVersion")
    testImplementation("org.mockito:mockito-junit-jupiter:$mockitoVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Common configuration for non-application modules
subprojects {

    apply(plugin = "java")

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-parameters")
    }

    dependencies {
        // Common runtime dependencies
        implementation("org.slf4j:slf4j-api:$slf4jVersion")
        implementation("ch.qos.logback:logback-classic:1.5.3")

        commonTestDependencies()
    }

    // Configure tests
    tasks.named<Test>("test") {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
        }
    }

    // Add source jar task
    tasks.register<Jar>("sourcesJar") {
        archiveClassifier.set("sources")
        from(sourceSets.main.get().allSource)
    }

    // Add javadoc jar task
    tasks.register<Jar>("javadocJar") {
        archiveClassifier.set("javadoc")
        from(tasks.javadoc)
    }
}

// Configure specific modules
project(":transport") {
    dependencies {
        implementation("io.netty:netty-all:$nettyVersion")
        implementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
        implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:$jacksonVersion")
    }
}

project(":cache") {
    dependencies {
        implementation("com.github.ben-manes.caffeine:caffeine:$caffeineVersion")
        implementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
    }
}

project(":upstream") {
    dependencies {
        implementation("io.netty:netty-all:$nettyVersion")
        implementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
    }
}

// Task to run all tests across all modules
tasks.register("allTests") {
    dependsOn(subprojects.map { it.tasks.named("test") })
    description = "Runs all tests across all modules"
}

// Task to build all modules
tasks.register("buildAll") {
    dependsOn(subprojects.map { it.tasks.named("build") })
    description = "Builds all modules"
}

// Task to clean all modules
tasks.register("cleanAll") {
    dependsOn(subprojects.map { it.tasks.named("clean") })
    description = "Cleans all modules"
}

// Print project structure
tasks.register("printProjectStructure") {
    doLast {
        println("╔════════════════════════════════════════════════════════════╗")
        println("║                 DNS Resolver Project Structure             ║")
        println("╠════════════════════════════════════════════════════════════╣")
        println("║ Root project: dns-resolver                                 ║")
        println("║ Modules:                                                   ║")
        println("║   • transport   - Netty UDP/TCP server (Person 1)         ║")
        println("║   • cache       - Caffeine caching engine (Person 2)      ║")
        println("║   • upstream    - DoH client & routing (Person 3)         ║")
        println("║   • application - Main app + Windows integration (Person 4)║")
        println("║                                                            ║")
        println("║ Common commands:                                           ║")
        println("║   • gradlew buildAll      - Build all modules             ║")
        println("║   • gradlew allTests      - Run all tests                 ║")
        println("║   • gradlew shadowJar     - Create Uber-JAR in application║")
        println("║                                                            ║")
        println("║ Module-specific commands:                                  ║")
        println("║   • gradlew :transport:build                              ║")
        println("║   • gradlew :cache:build                                  ║")
        println("║   • gradlew :upstream:build                               ║")
        println("║   • gradlew :application:build                            ║")
        println("╚════════════════════════════════════════════════════════════╝")
    }
}