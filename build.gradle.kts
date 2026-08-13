// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0

plugins {
    java
    `java-library`
    `maven-publish`
    signing
}

group   = "com.labacacia.nps"
version = "1.0.0-alpha.18"

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
    implementation("org.msgpack:msgpack-core:0.9.11")

    // JSON
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.9")

    // Logging façade
    implementation("org.slf4j:slf4j-api:2.0.13")

    // BouncyCastle — X.509 cert building (NPS-RFC-0002).
    // Signing/verification still uses native JCA Ed25519; BC is only needed
    // for the X.509 builder API which the JDK does not expose publicly.
    implementation("org.bouncycastle:bcprov-jdk18on:1.84")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.84")

    // ── Test ──────────────────────────────────────────────────────────────────
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testImplementation("org.mockito:mockito-core:5.12.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.12.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.13")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:unchecked"))
}

tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "skipped", "failed") }
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "nps-java"

            pom {
                name.set("NPS Java SDK")
                description.set("Java SDK for the Neural Protocol Suite (NPS): NCP, NWP, NIP, NDP, and NOP.")
                url.set("https://github.com/labacacia/NPS-sdk-java")

                licenses {
                    license {
                        name.set("Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }

                developers {
                    developer {
                        id.set("labacacia")
                        name.set("LabAcacia / INNO LOTUS PTY LTD")
                        email.set("oss@labacacia.com")
                    }
                }

                scm {
                    connection.set("scm:git:https://github.com/labacacia/NPS-sdk-java.git")
                    developerConnection.set("scm:git:https://github.com/labacacia/NPS-sdk-java.git")
                    url.set("https://github.com/labacacia/NPS-sdk-java")
                }
            }
        }
    }

    repositories {
        maven {
            name = "stagingDeploy"
            url = layout.buildDirectory.dir("staging-deploy").get().asFile.toURI()
        }
    }
}

signing {
    useGpgCmd()
    sign(publishing.publications["mavenJava"])
}
