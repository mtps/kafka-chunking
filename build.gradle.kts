buildscript {
    repositories {
        mavenCentral()
    }
}

plugins {
    // Declared here with specific versions so we can apply them below
    kotlin("jvm") apply false

    // We need to declare these plugins here to gain access to their type safe DSL in the subprojects {}
    // unfortunately since they are "core" plugins we cannot declared them unapplied here apparently
    java
    `java-library`
}

val javaVersion = JavaVersion.VERSION_11

subprojects {
    group = "com.github.mtps.kafka"
    version = "1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }

    apply {
        plugin("kotlin")
        plugin("java")
        plugin("java-library")
    }

    java {
        sourceCompatibility = javaVersion
        targetCompatibility = javaVersion

        withSourcesJar()
        withJavadocJar()
    }

    compileKotlin {
        kotlinOptions {
            jvmTarget = javaVersion.toString()
        }
    }
}
