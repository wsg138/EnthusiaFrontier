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

val releaseVersionValue = providers.gradleProperty("releaseVersion").get()
val minecraftVersionValue = providers.gradleProperty("minecraftVersion").get()
val paperApiVersionValue = providers.gradleProperty("paperApiVersion").get()
val javaVersionValue = providers.gradleProperty("javaVersion").get()
version = releaseVersionValue

// These are source-controlled compatibility invariants. Validate them while the
// build model is created so the check is configuration-cache safe and cannot be
// bypassed by skipping a particular verification task.
check(minecraftVersionValue == "1.21.11") {
    "Minecraft baseline changed without an intentional platform migration."
}
check(paperApiVersionValue == "1.21.11-R0.1-SNAPSHOT") {
    "Paper API baseline changed without an intentional platform migration."
}
check(javaVersionValue == "21") {
    "Java baseline changed without an intentional platform migration."
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:$paperApiVersionValue")
    implementation("org.xerial:sqlite-jdbc:${providers.gradleProperty("sqliteJdbcVersion").get()}")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(javaVersionValue.toInt()))
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
    inputs.property("pluginVersion", releaseVersionValue)
    filesMatching("plugin.yml") {
        expand("version" to releaseVersionValue)
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

tasks.register("verifyCurrentPlatformBaseline") {
    group = "verification"
    description = "Compatibility alias; platform baseline is validated during build configuration."
}

tasks.check {
    dependsOn(tasks.spotbugsMain, tasks.jacocoTestCoverageVerification, "verifyCurrentPlatformBaseline")
}
