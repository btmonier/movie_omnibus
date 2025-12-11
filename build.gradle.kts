import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties

plugins {
    kotlin("multiplatform") version "2.2.21"
    kotlin("plugin.serialization") version "2.2.21"
}

group = "org.btmonier"
version = "0.2.1"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)

    jvm()

    js(IR) {
        browser {
            commonWebpackConfig {
                cssSupport {
                    enabled = true
                }
            }
            binaries.executable()
            webpackTask {
                // Copy resources to webpack output
                doLast {
                    copy {
                        from("${layout.buildDirectory.get()}/processedResources/js/main")
                        into("${layout.buildDirectory.get()}/kotlin-webpack/js/developmentExecutable")
                    }
                }
            }
            runTask {
                // Copy resources for dev server
                doFirst {
                    copy {
                        from("${layout.buildDirectory.get()}/processedResources/js/main")
                        into("${layout.buildDirectory.get()}/kotlin-webpack/js/developmentExecutable")
                    }
                }
            }
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                // JSON serialization (shared)
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
            }
        }

        val jvmMain by getting {
            dependencies {
                // Web scraping
                implementation("org.jsoup:jsoup:1.17.2")

                // DataFrame for CSV handling (exclude slf4j-simple since we use logback)
                implementation("org.jetbrains.kotlinx:dataframe:0.14.1") {
                    exclude(group = "org.slf4j", module = "slf4j-simple")
                }

                // CLI argument parsing
                implementation("com.github.ajalt.clikt:clikt:5.0.1")

                // Coroutines for parallel processing
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

                // Progress bar
                implementation("me.tongfei:progressbar:0.10.1")

                // Ktor server dependencies
                implementation("io.ktor:ktor-server-core:3.0.3")
                implementation("io.ktor:ktor-server-netty:3.0.3")
                implementation("io.ktor:ktor-server-content-negotiation:3.0.3")
                implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.3")
                implementation("io.ktor:ktor-server-cors:3.0.3")
                implementation("io.ktor:ktor-server-call-logging:3.0.3")
                implementation("io.ktor:ktor-server-status-pages:3.0.3")

                // Exposed ORM and database dependencies
                implementation("org.jetbrains.exposed:exposed-core:0.55.0")
                implementation("org.jetbrains.exposed:exposed-dao:0.55.0")
                implementation("org.jetbrains.exposed:exposed-jdbc:0.55.0")
                implementation("org.jetbrains.exposed:exposed-java-time:0.55.0")
                implementation("org.postgresql:postgresql:42.7.4")
                implementation("com.zaxxer:HikariCP:6.2.1")

                // Logging (Logback provides SLF4J implementation)
                implementation("ch.qos.logback:logback-classic:1.5.16")
                
                // Jansi for colored console output on Windows
                implementation("org.fusesource.jansi:jansi:2.4.1")
                
                // Google Cloud Storage for signed URL generation
                implementation("com.google.cloud:google-cloud-storage:2.43.1")
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation("org.jetbrains.kotlin:kotlin-test")
                implementation("io.mockk:mockk:1.13.13")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
            }
        }

        val jsMain by getting {
            dependencies {
                // Kotlinx HTML for DOM generation
                implementation("org.jetbrains.kotlinx:kotlinx-html-js:0.11.0")

                // Coroutines for JS
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            }
        }
    }
}

// Generate BuildConfig.kt with version info for JS frontend
val generateBuildConfig by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/jsMain/kotlin/org/btmonier")
    val projectVersion = project.version.toString()
    
    outputs.dir(outputDir)
    
    doLast {
        val dir = outputDir.get().asFile
        dir.mkdirs()
        val file = File(dir, "BuildConfig.kt")
        file.writeText("""
            package org.btmonier
            
            /**
             * Auto-generated build configuration.
             * Do not edit manually - this file is regenerated during build.
             */
            object BuildConfig {
                const val VERSION = "$projectVersion"
                const val APP_NAME = "Movie Omnibus"
            }
        """.trimIndent())
    }
}

// Add generated sources to jsMain
kotlin.sourceSets.named("jsMain") {
    kotlin.srcDir(layout.buildDirectory.dir("generated/jsMain/kotlin"))
}

// Make sure JS compilation depends on generating the config
tasks.named("compileKotlinJs") {
    dependsOn(generateBuildConfig)
}

// Custom task for running the Letterboxd scraper
tasks.register<JavaExec>("scrapeLetterboxd") {
    group = "application"
    description = "Scrape movie metadata from Letterboxd URLs"
    val jvmTarget = kotlin.targets.getByName("jvm") as org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget
    classpath = jvmTarget.compilations.getByName("main").runtimeDependencyFiles + jvmTarget.compilations.getByName("main").output.allOutputs
    mainClass.set("org.btmonier.LetterboxdScraperKt")

    // Use the same Java toolchain as the project (Java 21)
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    })

    // Allow passing arguments from command line
    // Usage: ./gradlew scrapeLetterboxd --args="--input data/movies.csv --limit 10"
    if (project.hasProperty("args")) {
        args(project.property("args").toString().split(" "))
    }
}

// Custom task for running the web server
tasks.register<JavaExec>("runServer") {
    group = "application"
    description = "Run the Ktor web server with REST API"
    val jvmTarget = kotlin.targets.getByName("jvm") as org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget
    classpath = jvmTarget.compilations.getByName("main").runtimeDependencyFiles + jvmTarget.compilations.getByName("main").output.allOutputs
    mainClass.set("org.btmonier.server.ServerKt")

    // Use the same Java toolchain as the project (Java 21)
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    })
}

// Custom task for data migration from JSON to database
tasks.register<JavaExec>("migrateData") {
    group = "application"
    description = "Import movie metadata from JSON file into the database"
    val jvmTarget = kotlin.targets.getByName("jvm") as org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget
    classpath = jvmTarget.compilations.getByName("main").runtimeDependencyFiles + jvmTarget.compilations.getByName("main").output.allOutputs
    mainClass.set("org.btmonier.migration.DataMigrationKt")

    // Use the same Java toolchain as the project (Java 21)
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    })

    // Allow passing arguments from command line
    // Usage: ./gradlew migrateData --args="--input output/movie_collection_meta_20251111.json --skip-existing"
    if (project.hasProperty("args")) {
        args(project.property("args").toString().split(" "))
    }
}

// Custom task for listing all movies in the database
tasks.register<JavaExec>("listMovies") {
    group = "application"
    description = "List all movie titles from the database"
    val jvmTarget = kotlin.targets.getByName("jvm") as org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget
    classpath = jvmTarget.compilations.getByName("main").runtimeDependencyFiles + jvmTarget.compilations.getByName("main").output.allOutputs
    mainClass.set("org.btmonier.migration.ListMoviesKt")

    // Use the same Java toolchain as the project (Java 21)
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    })

    // Allow passing arguments from command line
    // Usage: ./gradlew listMovies
    // Usage: ./gradlew listMovies --args="--count"
    // Usage: ./gradlew listMovies --args="--with-url"
    // Usage: ./gradlew listMovies --args="--output movies.txt"
    if (project.hasProperty("args")) {
        args(project.property("args").toString().split(" "))
    }
}

// Custom task for comparing database with JSON file
tasks.register<JavaExec>("compareData") {
    group = "application"
    description = "Compare movies in database with a JSON file to see differences"
    val jvmTarget = kotlin.targets.getByName("jvm") as org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget
    classpath = jvmTarget.compilations.getByName("main").runtimeDependencyFiles + jvmTarget.compilations.getByName("main").output.allOutputs
    mainClass.set("org.btmonier.migration.DataComparisonKt")

    // Use the same Java toolchain as the project (Java 21)
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    })

    // Allow passing arguments from command line
    // Usage: ./gradlew compareData --args="--input output/movies.json"
    // Usage: ./gradlew compareData --args="--input output/movies.json --show-existing"
    if (project.hasProperty("args")) {
        args(project.property("args").toString().split(" "))
    }
}

// Custom task for resetting the database
tasks.register<JavaExec>("resetDatabase") {
    group = "application"
    description = "Reset the database by dropping and recreating all tables (WARNING: Deletes all data!)"
    val jvmTarget = kotlin.targets.getByName("jvm") as org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget
    classpath = jvmTarget.compilations.getByName("main").runtimeDependencyFiles + jvmTarget.compilations.getByName("main").output.allOutputs
    mainClass.set("org.btmonier.database.DatabaseResetKt")

    // Use the same Java toolchain as the project (Java 21)
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    })

    // Allow passing arguments from command line
    // Usage: ./gradlew resetDatabase --args="--force"
    if (project.hasProperty("args")) {
        args(project.property("args").toString().split(" "))
    }
}

// Custom task for testing extraction on example.html
tasks.register<JavaExec>("testExampleHtml") {
    group = "verification"
    description = "Test metadata extraction on the example.html file"
    val jvmTarget = kotlin.targets.getByName("jvm") as org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget
    classpath = jvmTarget.compilations.getByName("main").runtimeDependencyFiles + jvmTarget.compilations.getByName("main").output.allOutputs
    mainClass.set("org.btmonier.TestExampleHtmlKt")

    // Use the same Java toolchain as the project (Java 21)
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    })
}

// Custom task for backing up the database
tasks.register<Exec>("backupDatabase") {
    group = "application"
    description = "Create a backup of the PostgreSQL database using pg_dump"

    doFirst {
        // Load database configuration
        val props = Properties()
        val propsFile = file("database.properties")
        if (propsFile.exists()) {
            propsFile.inputStream().use { props.load(it) }
        }

        val dbUrl = System.getenv("DATABASE_URL")
            ?: props.getProperty("database.url")
            ?: "jdbc:postgresql://localhost:5432/moviedb"
        val dbUser = System.getenv("DB_USER")
            ?: props.getProperty("database.user")
            ?: "postgres"
        val dbPassword = System.getenv("DB_PASSWORD")
            ?: props.getProperty("database.password")
            ?: "postgres"

        // Parse database name and host from JDBC URL
        // Format: jdbc:postgresql://host:port/database
        val urlPattern = Regex("jdbc:postgresql://([^:]+):(\\d+)/(.+)")
        val matchResult = urlPattern.find(dbUrl)
        val dbHost = matchResult?.groupValues?.get(1) ?: "localhost"
        val dbPort = matchResult?.groupValues?.get(2) ?: "5432"
        val dbName = matchResult?.groupValues?.get(3) ?: "moviedb"

        // Create backups directory if it doesn't exist
        val backupDir = file("backups")
        backupDir.mkdirs()

        // Generate timestamp for backup filename
        val timestamp = LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val backupFile = file("backups/moviedb_backup_${timestamp}.sql")

        // Set environment variable for password (pg_dump will read it)
        environment("PGPASSWORD", dbPassword)

        // Configure pg_dump command
        commandLine(
            "pg_dump",
            "-h", dbHost,
            "-p", dbPort,
            "-U", dbUser,
            "-d", dbName,
            "-f", backupFile.absolutePath,
            "--verbose"
        )

        println("Creating database backup...")
        println("Host: $dbHost:$dbPort")
        println("Database: $dbName")
        println("Output: ${backupFile.absolutePath}")
    }

    doLast {
        println("Database backup completed successfully!")
    }
}

tasks.register<Exec>("devAll") {
    dependsOn("jsBrowserDevelopmentWebpack")
    finalizedBy("runServer")
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
}


