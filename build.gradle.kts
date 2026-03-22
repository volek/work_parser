plugins {
    kotlin("jvm") version "1.9.22"
    kotlin("plugin.serialization") version "1.9.22"
    application
}

group = "ru.sber"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // Kotlin
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    
    // Jackson for JSON/YAML parsing
    implementation("com.fasterxml.jackson.core:jackson-core:2.17.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.0")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.0")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.17.0")
    
    // Ktor HTTP Client
    implementation("io.ktor:ktor-client-core:2.3.9")
    implementation("io.ktor:ktor-client-cio:2.3.9")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.9")
    implementation("io.ktor:ktor-serialization-jackson:2.3.9")
    implementation("io.ktor:ktor-client-logging:2.3.9")
    
    // Logging
    implementation("org.slf4j:slf4j-api:2.0.12")
    implementation("ch.qos.logback:logback-classic:1.5.3")
    
    // Configuration
    implementation("com.sksamuel.hoplite:hoplite-core:2.7.5")
    implementation("com.sksamuel.hoplite:hoplite-yaml:2.7.5")
    
    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
    testImplementation("io.mockk:mockk:1.13.10")
    testImplementation("io.ktor:ktor-client-mock:2.3.9")
}

application {
    mainClass.set("ru.sber.parser.ApplicationKt")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "ru.sber.parser.ApplicationKt"
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}

/**
 * ZIP для переноса на Linux-хост: fat JAR, scripts/, query/, messages/, config.yaml,
 * DEPLOYMENT.md (корень архива), все *.md в дереве проекта в markdown/.
 * Собирать: ./gradlew linuxHostBundle или gradlew.bat linuxHostBundle
 */
tasks.register<Zip>("linuxHostBundle") {
    group = "distribution"
    description = "ZIP: fat JAR + scripts + query + messages + config.yaml + DEPLOYMENT.md + markdown (Java 17)"
    dependsOn(tasks.jar)

    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    archiveFileName.set("${rootProject.name}-${project.version}-linux-host.zip")

    val jarTask = tasks.jar.get()
    from(jarTask.archiveFile) {
        into("libs")
    }

    from(file("scripts")) {
        into("scripts")
    }

    from(file("query")) {
        into("query")
    }

    from(file("messages")) {
        into("messages")
    }

    from("config.yaml")

    from("distribution/DEPLOYMENT.md") {
        rename { "DEPLOYMENT.md" }
    }

    from(projectDir) {
        include("**/*.md")
        exclude("build/**", "**/build/**", ".gradle/**", "**/.gradle/**", "**/.git/**")
        into("markdown")
    }
}
