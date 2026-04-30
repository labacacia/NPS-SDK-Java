// foojay-resolver-convention enables automatic JDK provisioning so the build
// can run on hosts that don't have a Java 21 toolchain pre-installed.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "nps-java"
