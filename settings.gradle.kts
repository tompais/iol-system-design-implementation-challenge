pluginManagement {
    val kotlinVersion: String by settings
    val springBootVersion: String by settings
    val springDependencyManagementVersion: String by settings
    val ktlintPluginVersion: String by settings
    val detektPluginVersion: String by settings
    plugins {
        kotlin("jvm") version kotlinVersion
        kotlin("plugin.spring") version kotlinVersion
        id("org.springframework.boot") version springBootVersion
        id("io.spring.dependency-management") version springDependencyManagementVersion
        id("org.jlleitschuh.gradle.ktlint") version ktlintPluginVersion
        id("dev.detekt") version detektPluginVersion
        id("jacoco")
    }
}
rootProject.name = "sd-implementation-challenge"
