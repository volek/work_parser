import org.apache.commons.compress.archivers.zip.ZipFile

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("org.apache.commons:commons-compress:1.27.1")
    }
}

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
    implementation("io.ktor:ktor-client-auth:2.3.9")
    
    // Logging
    implementation("org.slf4j:slf4j-api:2.0.12")
    implementation("ch.qos.logback:logback-classic:1.5.3")
    
    // Configuration
    implementation("com.sksamuel.hoplite:hoplite-core:2.7.5")
    implementation("com.sksamuel.hoplite:hoplite-yaml:2.7.5")
    
    // PostgreSQL metastore
    implementation("org.postgresql:postgresql:42.7.3")
    implementation("com.zaxxer:HikariCP:5.1.0")
    
    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
    testImplementation("io.mockk:mockk:1.13.10")
    testImplementation("io.ktor:ktor-client-mock:2.3.9")
    testImplementation("org.testcontainers:junit-jupiter:1.20.1")
    testImplementation("org.testcontainers:postgresql:1.20.1")
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
 * Собирать: ./gradlew linuxHostBundle
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

    from(file("distribution/cert")) {
        into("distribution/cert")
    }

    from(projectDir) {
        include("**/*.md")
        exclude("build/**", "**/build/**", ".gradle/**", "**/.gradle/**", "**/.git/**")
        into("markdown")
    }

    // Ensure every shell script in the archive is executable on Linux.
    eachFile {
        if (name.endsWith(".sh")) {
            mode = Integer.parseInt("755", 8)
        }
    }
}

tasks.register("verifyLinuxHostBundleScriptModes") {
    group = "verification"
    description = "Verifies that all *.sh files in linuxHostBundle ZIP have 0755 mode"
    dependsOn("linuxHostBundle")

    doLast {
        val zipTask = tasks.named<Zip>("linuxHostBundle").get()
        val zipFile = zipTask.archiveFile.get().asFile
        val invalidEntries = mutableListOf<String>()

        ZipFile(zipFile).use { archive ->
            val entries = archive.entries
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (!entry.isDirectory && entry.name.endsWith(".sh")) {
                    val mode = entry.unixMode and 0x1FF // permission bits only
                    if (mode != Integer.parseInt("755", 8)) {
                        invalidEntries.add("${entry.name}: ${mode.toString(8).padStart(3, '0')}")
                    }
                }
            }
        }

        if (invalidEntries.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Found *.sh entries without 0755 mode in ${zipFile.name}:")
                    invalidEntries.forEach { appendLine(" - $it") }
                }
            )
        }
    }
}

val pythonBinProvider = providers.environmentVariable("PYTHON_BIN").orElse(
    "python3"
)

tasks.register<Exec>("generateQueries") {
    group = "query"
    description = "Generates SQL queries for all strategies from scripts/query-manifest.json"
    workingDir = projectDir
    commandLine(
        pythonBinProvider.get(),
        "scripts/generate_queries.py"
    )
}

tasks.register<Exec>("generateCompcomQueries") {
    group = "query"
    description = "Generates compcom SQL queries from combined rules in scripts/query-manifest.json"
    workingDir = projectDir
    commandLine(
        pythonBinProvider.get(),
        "scripts/generate_queries.py",
        "--strategy",
        "compcom"
    )
}

tasks.register<Exec>("verifyQueryManifest") {
    group = "verification"
    description = "Checks that query/* matches manifest generation rules (no file writes)"
    workingDir = projectDir
    commandLine(
        pythonBinProvider.get(),
        "scripts/generate_queries.py",
        "--check"
    )
}
