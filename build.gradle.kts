buildscript {
    rootProject.dependencyLocking {
        lockMode.set(org.gradle.api.artifacts.dsl.LockMode.STRICT)
    }
    val securityVersions = java.util.Properties().apply {
        rootProject.file("gradle/security-versions.properties").inputStream().use { load(it) }
    }
    configurations.configureEach {
        resolutionStrategy.activateDependencyLocking()
        resolutionStrategy.eachDependency {
            val key = requested.group + "." + requested.name
            val pinned = securityVersions.getProperty(key)
                ?: securityVersions.getProperty(requested.group)?.takeUnless {
                    requested.name.startsWith("netty-tcnative")
                }
            if (pinned != null) {
                val version = if (key == "com.google.guava.guava") {
                    pinned + if (requested.version?.endsWith("-android") == true) "-android" else "-jre"
                } else pinned
                useVersion(version)
                because("Pin patched transitive libraries for the security-reviewed build")
            }
        }
    }
}

plugins {
    id("com.android.application") version "9.2.1" apply false
    id("org.jetbrains.kotlin.android") version "2.4.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.20" apply false
}

// Apply the same reviewed policy to runtime, lint and Android test dependencies.
val securityVersions = java.util.Properties().apply {
    rootProject.file("gradle/security-versions.properties").inputStream().use { load(it) }
}
allprojects {
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            val key = requested.group + "." + requested.name
            val pinned = securityVersions.getProperty(key)
                ?: securityVersions.getProperty(requested.group)?.takeUnless {
                    requested.name.startsWith("netty-tcnative")
                }
            if (pinned != null) {
                val version = if (key == "com.google.guava.guava") {
                    pinned + if (requested.version?.endsWith("-android") == true) "-android" else "-jre"
                } else pinned
                useVersion(version)
                because("Pin patched transitive libraries for the security-reviewed build")
            }
        }
    }
}
