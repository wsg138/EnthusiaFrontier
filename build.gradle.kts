import com.github.spotbugs.snom.Confidence
import com.github.spotbugs.snom.Effort
import com.github.spotbugs.snom.SpotBugsExtension
import com.github.spotbugs.snom.SpotBugsTask
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    java
    jacoco
    id("com.gradleup.shadow") version "8.3.6"
    id("com.github.spotbugs") version "6.0.10"
}

group = "net.enthusia.frontier"
version = providers.gradleProperty("releaseVersion").get()

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:${providers.gradleProperty("paperApiVersion").get()}")
    implementation("org.xerial:sqlite-jdbc:${providers.gradleProperty("sqliteJdbcVersion").get()}")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(providers.gradleProperty("javaVersion").get().toInt()))
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    finalizedBy(tasks.jacocoTestReport)
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.withType<ShadowJar>().configureEach {
    archiveClassifier.set("")
    mergeServiceFiles()
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.jar {
    enabled = false
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}

configure<SpotBugsExtension> {
    ignoreFailures = false
    showProgress = true
    effort = Effort.MAX
    reportLevel = Confidence.LOW
    toolVersion = "4.8.4"
}

tasks.withType<SpotBugsTask>().configureEach {
    if (name == "spotbugsTest") {
        enabled = false
    }
    reports.create("html") {
        required.set(true)
    }
}

jacoco {
    toolVersion = "0.8.13"
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.70".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.spotbugsMain, tasks.jacocoTestCoverageVerification)
}

tasks.register("verifyCurrentPlatformBaseline") {
    doLast {
        check(providers.gradleProperty("minecraftVersion").get() == "1.21.11") {
            "Minecraft baseline changed without an intentional platform migration."
        }
        check(providers.gradleProperty("paperApiVersion").get() == "1.21.11-R0.1-SNAPSHOT") {
            "Paper API baseline changed without an intentional platform migration."
        }
        check(providers.gradleProperty("javaVersion").get() == "21") {
            "Java baseline changed without an intentional platform migration."
        }
    }
}

tasks.check {
    dependsOn("verifyCurrentPlatformBaseline")
}
