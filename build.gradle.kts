import java.util.Locale

plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.spring") version "2.3.21"
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com"
version = "0.0.1-SNAPSHOT"
description = "fastTicket"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-liquibase")
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.0")
    implementation("tools.jackson.module:jackson-module-kotlin")
    developmentOnly("org.springframework.boot:spring-boot-devtools")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-jdbc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-liquibase-test")
    testImplementation("org.springframework.boot:spring-boot-starter-opentelemetry-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("com.tngtech.archunit:archunit:1.4.1")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

tasks.test {
    useJUnitPlatform { excludeTags("benchmark") }
}

tasks.register<Test>("benchmark") {
    description = "Compares the SeatAllocator strategies; writes build/reports/allocator-benchmark.md"
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("benchmark") }
    outputs.upToDateWhen { false }
    testLogging.showStandardStreams = false
    doFirst { layout.buildDirectory.dir("reports/allocator-benchmark").get().asFile.deleteRecursively() }
    doLast {
        val dir = layout.buildDirectory.dir("reports/allocator-benchmark").get().asFile
        val results = listOf("conditional", "pessimistic", "optimistic").associateWith {
            @Suppress("UNCHECKED_CAST")
            groovy.json.JsonSlurper().parse(File(dir, "$it.json")) as Map<String, Map<String, Any>>
        }
        val md = StringBuilder("| Scenario | Strategy | Conflict % | p50 ms | p95 ms | p99 ms | Holds/s | Errors |\n|---|---|---:|---:|---:|---:|---:|---:|\n")
        results.getValue("conditional").keys.forEach { scenario ->
            results.forEach { (strategy, byScenario) ->
                val r = byScenario.getValue(scenario)
                fun f(key: String) = String.format(Locale.ROOT, "%.1f", (r.getValue(key) as Number).toDouble())
                md.append("| $scenario | $strategy | ${f("conflict_pct")} | ${f("p50_ms")} | ${f("p95_ms")} | ${f("p99_ms")} | ${f("holds_per_sec")} | ${r.getValue("errors")} |\n")
            }
        }
        layout.buildDirectory.file("reports/allocator-benchmark.md").get().asFile.writeText(md.toString())
        println(md)
    }
}

tasks.bootJar {
    archiveFileName = "app.jar"
}
