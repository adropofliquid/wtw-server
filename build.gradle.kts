// Versions checked on Maven Central, 2026-10-11 (see DECISIONS.md D-001).
plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.spring") version "2.4.21"
    kotlin("plugin.jpa") version "2.4.21"
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.adropofliquid"
version = "0.1.0"

kotlin {
    jvmToolchain(25)
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

repositories { mavenCentral() }

dependencyManagement {
    imports { mavenBom("software.amazon.awssdk:bom:2.55.14") }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("com.github.ben-manes.caffeine:caffeine")
    implementation("com.google.firebase:firebase-admin:9.11.0")
    implementation("software.amazon.awssdk:s3")
    implementation("com.google.genai:google-genai:1.76.0")
    // WebP encode/decode through ImageIO. Ships natives for linux x86_64/aarch64 and macOS arm64/x86_64 (D-010).
    implementation("com.github.usefulness:webp-imageio:0.11.0")
    runtimeOnly("org.postgresql:postgresql")
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // Local runs default to the dev profile (stubs, local storage, dev auth). Override with SPRING_PROFILES_ACTIVE.
    environment("SPRING_PROFILES_ACTIVE", System.getenv("SPRING_PROFILES_ACTIVE") ?: "dev")
    project.findProperty("args")?.let { args(it.toString().split(" ")) }
}

tasks.named<Jar>("jar") { enabled = false }
