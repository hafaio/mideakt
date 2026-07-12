import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.2.20"
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
    id("org.jetbrains.dokka") version "2.2.0"
    `maven-publish`
}

group = "io.hafa"
version = "0.2.0"

repositories {
    mavenCentral()
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

// Full default rule set plus a public-API doc gate; deviations in config/detekt/detekt.yml.
detekt {
    source.setFrom("src/main/kotlin")
    config.setFrom("$projectDir/config/detekt/detekt.yml")
    buildUponDefaultConfig = true
}

// Bundles Dokka's HTML as a javadoc-classified jar; JitPack serves it at javadoc.jitpack.io.
val dokkaJavadocJar by tasks.registering(Jar::class) {
    dependsOn(tasks.named("dokkaGeneratePublicationHtml"))
    from(layout.buildDirectory.dir("dokka/html"))
    archiveClassifier.set("javadoc")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "mideakt"
            from(components["java"])
            artifact(dokkaJavadocJar)
            pom {
                name.set("mideakt")
                description.set("Native Kotlin library for local (LAN) control of Midea air conditioners")
                url.set("https://github.com/hafaio/mideakt")
                licenses {
                    license {
                        name.set("MIT")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                developers {
                    developer {
                        id.set("erikbrinkman")
                        name.set("Erik Brinkman")
                        email.set("erik.brinkman@gmail.com")
                    }
                }
                scm {
                    url.set("https://github.com/hafaio/mideakt")
                    connection.set("scm:git:https://github.com/hafaio/mideakt.git")
                    developerConnection.set("scm:git:git@github.com:hafaio/mideakt.git")
                }
            }
        }
    }
}
