
buildscript {
    repositories {
        google()
        mavenCentral()
    }
}

@Suppress("DSL_SCOPE_VIOLATION")
plugins {
    kotlin("multiplatform") version "1.7.20"

    // cross-platform serialization support
    alias(libs.plugins.serialization)

    // https://github.com/hovinen/kotlin-auto-formatter
    // Creates a `formatKotlin` Gradle action that seems to be reliable.
    id("tech.formatter-kt.formatter") version "0.7.9"

    id("maven-publish")

    java
    application
}

group = "electionguard-kotlin-multiplatform"
version = "1.52.6-SNAPSHOT"

val kotlinVersion by extra("1.7.20")

repositories {
    google()
    mavenCentral()
}

kotlin {
    jvm {
        compilations.all { kotlinOptions.jvmTarget = "1.8" }
        //        withJava()
        testRuns["test"].executionTask
            .configure {
                useJUnitPlatform()
                minHeapSize = "512m"
                maxHeapSize = "2048m"
                jvmArgs = listOf("-Xss128m")

                // Make tests run in parallel
                // More info: https://www.jvt.me/posts/2021/03/11/gradle-speed-parallel/
                systemProperties["junit.jupiter.execution.parallel.enabled"] = "true"
                systemProperties["junit.jupiter.execution.parallel.mode.default"] = "same_thread"
                systemProperties["junit.jupiter.execution.parallel.mode.classes.default"] =
                    "concurrent"
            }
    }

    val hostOs = System.getProperty("os.name")
    val isMingwX64 = hostOs.startsWith("Windows")
    val arch = System.getProperty("os.arch")
    val nativeTarget =
        when {
            hostOs == "Mac OS X" && arch == "aarch64" -> macosArm64("native")
            hostOs == "Mac OS X"-> macosX64("native")
            hostOs == "Linux" -> linuxX64("native")
            isMingwX64 -> mingwX64("native")
            else -> throw GradleException("Host OS is not supported.")
        }

    nativeTarget.apply {
        binaries {
            executable("RunBatchEncryption") {
                entryPoint = "electionguard.encrypt.main"
            }
            sharedLib() {
                baseName = "ekm" // on Linux and macOS
                // baseName = "libekm // on Windows
            }
        }
    }

    sourceSets {
        all { languageSettings.optIn("kotlin.RequiresOptIn") }

        val commonMain by
            getting {
                dependencies {
                    // JSON serialization
                    implementation(libs.kotlinx.serialization.json)

                    // Coroutines
                    implementation(libs.kotlinx.coroutines.core)

                    // Useful, portable routines
                    implementation(libs.ktor.utils)

                    // Portable logging interface. On the JVM, we'll get "logback", which gives
                    // us lots of features. On Native, it ultimately just prints to stdout.
                    // On JS, it uses console.log, console.error, etc.
                    implementation(libs.bundles.logging)

                    // A multiplatform Kotlin library for working with date and time.
                    implementation(libs.kotlinx.datetime)

                    // A multiplatform Kotlin library for working with protobuf.
                    implementation(libs.pbandk)

                    // A multiplatform Kotlin library for command-line parsing (could use enableEndorsedLibs instead)
                    implementation(libs.kotlinx.cli)

                    // A multiplatform Kotlin library for Result monads
                    implementation(libs.kotlin.result)
                }
            }
        val commonTest by
            getting {
                dependencies {
                    implementation(kotlin("test-common", kotlinVersion))
                    implementation(kotlin("test-annotations-common", kotlinVersion))

                    // runTest() for running suspend functions in tests
                    implementation(libs.kotlinx.coroutines.test)

                    // Fancy property-based testing
                    implementation(libs.kotest.property)

                    // mocking
                    implementation("io.mockk:mockk:1.13.2")
                }
            }
        val jvmMain by
            getting {
                dependencies {
                    implementation(kotlin("stdlib-jdk8", kotlinVersion))

                    // Logging implementation (used by "kotlin-logging"). Note that we need
                    // a bleeding-edge implementation to ensure we don't have vulnerabilities
                    // similar to (but not as bad) as the log4j issues.
                    implementation("ch.qos.logback:logback-classic:1.3.4")
                }
            }
        val jvmTest by
            getting {
                dependencies {
                    // Progress bars
                    implementation("me.tongfei:progressbar:0.9.3")

                    // Unclear if we really need all the extra features of JUnit5, but it would
                    // at least be handy if we could get its parallel test runner to work.
                    implementation(kotlin("test-junit5", kotlinVersion))
                }
            }
        val nativeMain by getting { dependencies {
            implementation(project(":hacllib"))
        } }
        val nativeTest by getting { dependencies {} }
    }
}

val protoGenSource by
    extra("build/generated/source/proto")

val compileProtobuf =
    tasks.register("compileProtobuf") {
        doLast {
            print("* Compiling protobuf *\n")
            /* project.exec {
             *        commandLine = "rm -f ./src/commonMain/kotlin/electionguard/protogen".split("
             * ")
             * } */
            // TODO lame
            val commandLineStr =
                "protoc --pbandk_out=./src/commonMain/kotlin/ --proto_path=./src/commonMain/proto " +
                    "common.proto encrypted_ballot.proto encrypted_tally.proto " +
                    "election_record.proto manifest.proto " +
                    "plaintext_ballot.proto decrypted_tally.proto " +
                    "trustees.proto"
            project.exec { commandLine = commandLineStr.split(" ") }
        }
    }

tasks.withType<Test> { testLogging { showStandardStreams = true } }

// LOOK some kind of javascript security thing, but may be causing coupled projects
// https://docs.gradle.org/current/userguide/multi_project_configuration_and_execution.html#sec:decoupled_projects
// allprojects {
    tasks.withType<org.jetbrains.kotlin.gradle.targets.js.npm.tasks.KotlinNpmInstallTask> {
        args += "--ignore-scripts"
    }
// }

// Workaround the Gradle bug resolving multi-platform dependencies.
// Fix courtesy of https://github.com/square/okio/issues/647
configurations.forEach {
    if (it.name.toLowerCase().contains("kapt") || it.name.toLowerCase().contains("proto")) {
        it.attributes
            .attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>()
    .configureEach { kotlinOptions.freeCompilerArgs += "-opt-in=kotlin.RequiresOptIn" }

publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/danwallach/electionguard-kotlin-multiplatform")
            credentials {
                username = project.findProperty("github.user") as String? ?: System.getenv("USERNAME")
                password = project.findProperty("github.key") as String? ?: System.getenv("TOKEN")
            }
        }
    }
    publications {
        register<MavenPublication>("gpr") {
            from(components["java"])
        }
    }
}