plugins {
    kotlin("jvm") version "2.2.0"
    `java-library`
    id("com.google.protobuf") version "0.9.4"
    id("com.vanniktech.maven.publish") version "0.37.0"
}

repositories {
    mavenCentral()
}

group = providers.gradleProperty("GROUP").orElse(providers.gradleProperty("group")).get()
// Single source of truth for releases (bumped by .github/workflows/release.yml).
version = providers.fileContents(layout.projectDirectory.file("VERSION")).asText.map { it.trim() }.get()

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api("com.squareup.okhttp3:okhttp:4.12.0")
    api("com.fasterxml.jackson.core:jackson-databind:2.18.3")
    api("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.3")
    api("org.jetbrains.kotlin:kotlin-reflect:2.2.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    api("com.google.protobuf:protobuf-java:4.30.2")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.30.2"
    }
}

tasks.test {
    useJUnitPlatform()
}

// Protobuf plugin already registers generated sources; avoid duplicates in sourcesJar.
tasks.withType<Jar>().configureEach {
    if (name.contains("sources", ignoreCase = true)) {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()

    coordinates("io.pickpoint", "pickpoint", version.toString())

    pom {
        name.set("Pickpoint JVM SDK")
        description.set("Official Kotlin/Java SDK for Pickpoint — geocoding, routing, devices, and realtime tracking")
        inceptionYear.set("2026")
        url.set("https://github.com/pickpoint/jvm-sdk")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("pickpoint")
                name.set("Pickpoint")
                url.set("https://pickpoint.io")
            }
        }
        scm {
            url.set("https://github.com/pickpoint/jvm-sdk")
            connection.set("scm:git:git://github.com/pickpoint/jvm-sdk.git")
            developerConnection.set("scm:git:ssh://git@github.com/pickpoint/jvm-sdk.git")
        }
    }
}
