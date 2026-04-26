// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0

plugins {
    java
    `java-library`
    `maven-publish`
    signing
}

group   = "com.labacacia.nps"
version = "1.0.0-alpha.3"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
    withSourcesJar()
    withJavadocJar()
}

repositories {
    mavenCentral()
}

dependencies {
    // MsgPack
    implementation("org.msgpack:msgpack-core:0.9.8")

    // JSON
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")

    // Logging façade
    implementation("org.slf4j:slf4j-api:2.0.13")

    // ── Test ──────────────────────────────────────────────────────────────────
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testImplementation("org.mockito:mockito-core:5.12.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.12.0")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.13")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "skipped", "failed") }
}

// ── Publishing ────────────────────────────────────────────────────────────────

publishing {
    repositories {
        maven {
            name = "stagingDeploy"
            url  = uri(layout.buildDirectory.dir("staging-deploy"))
        }
    }
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            groupId    = "com.labacacia.nps"
            artifactId = "nps-java"
            version    = "1.0.0-alpha.3"

            pom {
                name        = "NPS Java SDK"
                description = "Java 21 SDK for the Neural Protocol Suite (NPS) — NCP, NWP, NIP, NDP, NOP"
                url         = "https://github.com/labacacia/NPS-sdk-java"
                inceptionYear = "2026"

                licenses {
                    license {
                        name = "Apache License, Version 2.0"
                        url  = "https://www.apache.org/licenses/LICENSE-2.0"
                    }
                }
                developers {
                    developer {
                        id    = "orilynn"
                        name  = "Ori Lynn"
                        email = "iamzerolin@gmail.com"
                        organization = "LabAcacia / INNO LOTUS PTY LTD"
                    }
                }
                scm {
                    connection          = "scm:git:git://github.com/labacacia/NPS-sdk-java.git"
                    developerConnection = "scm:git:ssh://github.com/labacacia/NPS-sdk-java.git"
                    url                 = "https://github.com/labacacia/NPS-sdk-java"
                }
            }
        }
    }
}

signing {
    sign(publishing.publications["mavenJava"])
}

// Artifacts published to build/staging-deploy/
// then zipped and uploaded to Sonatype Central Portal via REST API
