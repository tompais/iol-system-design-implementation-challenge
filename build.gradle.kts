plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("org.jlleitschuh.gradle.ktlint")
    id("dev.detekt")
    id("jacoco")
}

group = "com.iol"
version = "0.0.1-SNAPSHOT"
description = "sd-implementation-challenge"

val springCloudVersion: String by project
val springDocVersion: String by project
val assertkVersion: String by project
val restAssuredVersion: String by project
val springmockkVersion: String by project
val jacocoToolVersion: String by project
val ktlintVersion: String by project

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(24)
    }
}

val jvmTargetVersion =
    java.toolchain.languageVersion
        .get()
        .asInt()

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
    // spring-boot-starter-logging (Logback) conflicts with our Log4j2 setup:
    //   - log4j-to-slf4j (from starter-logging) routes Log4j2 API → SLF4J
    //   - log4j-slf4j2-impl (from starter-log4j2) routes SLF4J → Log4j2
    // Both on classpath creates a cycle. Exclude the entire logging starter + its artifacts.
    all {
        exclude(group = "org.springframework.boot", module = "spring-boot-starter-logging")
        exclude(group = "ch.qos.logback")
        exclude(group = "org.apache.logging.log4j", module = "log4j-to-slf4j")
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-log4j2")
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("io.projectreactor.kotlin:reactor-kotlin-extensions")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
    implementation("org.springdoc:springdoc-openapi-starter-webflux-ui:$springDocVersion")
    // Optional defensive pattern: circuit breaker for the storage layer.
    // Not wired in RateLimiterConfig — InMemoryBucketStore is in-process and cannot fail.
    // Activate when replacing InMemoryBucketStore with a Redis backend.
    implementation("org.springframework.cloud:spring-cloud-starter-circuitbreaker-reactor-resilience4j")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    developmentOnly("org.springframework.boot:spring-boot-devtools")
    developmentOnly("org.springframework.boot:spring-boot-docker-compose")
    runtimeOnly("io.micrometer:micrometer-registry-otlp")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    testImplementation("com.ninja-squad:springmockk:$springmockkVersion")
    testImplementation("com.willowtreeapps.assertk:assertk:$assertkVersion")
    testImplementation("io.rest-assured:spring-web-test-client:$restAssuredVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:$springCloudVersion")
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

// dev.detekt:2.0.0-alpha.1 was compiled with Kotlin 2.2.20.
// Spring Dependency Management upgrades kotlin-compiler-embeddable to 2.2.21 in all configurations,
// which causes detekt's strict version check to fail. Pin detekt's Kotlin to what it expects.
configurations.matching { it.name.startsWith("detekt") }.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin") {
            useVersion("2.2.20")
        }
    }
}

ktlint {
    // ktlint 1.5+ uses the updated Kotlin AST API compatible with Kotlin 2.2.x (EXPECT_KEYWORD replaces HEADER_KEYWORD)
    version = ktlintVersion
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom("detekt.yml")
}

jacoco {
    // 0.8.13 adds Java 24 (class file version 68) support
    toolVersion = jacocoToolVersion
}

tasks.test {
    useJUnitPlatform()
    // Apply JVM flags required for Java 17+ module-system access restrictions and JaCoCo instrumentation.
    // The toolchain targets Java 24; gating on version keeps these flags from silently relaxing older JDKs.
    if (jvmTargetVersion >= 17) {
        jvmArgs(
            "--enable-native-access=ALL-UNNAMED",
            "--add-opens",
            "java.base/sun.misc=ALL-UNNAMED",
            "-Xshare:off",
        )
    }
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = true
    }
}

tasks.jacocoTestCoverageVerification {
    violationRules {
        rule {
            // 80% line coverage required on core + infra packages (adapter layer excluded — covered by integration tests)
            element = "PACKAGE"
            includes = listOf("com/iol/ratelimiter/core/*", "com/iol/ratelimiter/infra/*")
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}
