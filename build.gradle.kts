// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0

plugins {
    java
    `java-library`
}

group   = "com.labacacia.nps"
version = "1.0.0-alpha.1"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
    withSourcesJar()
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
