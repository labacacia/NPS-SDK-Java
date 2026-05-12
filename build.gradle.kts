// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0

plugins {
    java
    `java-library`
}

group   = "com.labacacia.nps"
version = "1.0.0-alpha.6"

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

    // BouncyCastle — X.509 cert building (NPS-RFC-0002).
    // Signing/verification still uses native JCA Ed25519; BC is only needed
    // for the X.509 builder API which the JDK does not expose publicly.
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.79")

    // ── Test ──────────────────────────────────────────────────────────────────
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testImplementation("org.mockito:mockito-core:5.12.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.12.0")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.13")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "skipped", "failed") }
}
