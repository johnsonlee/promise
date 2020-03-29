import io.johnsonlee.gradle.dsl.*

buildscript {
    repositories {
        mavenLocal()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        classpath("io.johnsonlee.buildprops:buildprops-gradle-plugin:1.0.0")
    }
}

plugins {
    kotlin("jvm") version embeddedKotlinVersion
    kotlin("kapt") version embeddedKotlinVersion
    id("application")
    id("io.codearte.nexus-staging") version "0.21.2"
    id("de.marcphilipp.nexus-publish") version "0.4.0"
    id("com.github.johnrengelman.shadow") version "5.2.0"
}

val OSSRH_USERNAME = project.properties["OSSRH_USERNAME"] as? String ?: System.getenv("OSSRH_USERNAME")
val OSSRH_PASSWORD = project.properties["OSSRH_PASSWORD"] as? String ?: System.getenv("OSSRH_PASSWORD")

repositories {
    mavenLocal()
    mavenCentral()
    jcenter()
}

apply(from = file("gradle/test.gradle.kts"))

allprojects {
    apply(plugin = "signing")
    apply(plugin = "io.johnsonlee.buildprops")

    group = "io.johnsonlee.${rootProject.name}"
    version = "1.0.0"

    repositories {
        mavenLocal()
        mavenCentral()
        google()
        jcenter()
    }

    dependencies {
        implementation(platform("org.jetbrains.kotlin:kotlin-bom"))
        implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")

        testImplementation("org.jetbrains.kotlin:kotlin-test")
        testImplementation("org.jetbrains.kotlin:kotlin-test-junit")
    }

    java {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    val sourcesJar by this@allprojects.tasks.registering(Jar::class) {
        dependsOn(JavaPlugin.CLASSES_TASK_NAME)
        archiveClassifier.set("sources")
        from(sourceSets.main.get().allSource)
    }

    val javadocJar by this@allprojects.tasks.registering(Jar::class) {
        dependsOn(JavaPlugin.JAVADOC_TASK_NAME)
        archiveClassifier.set("javadoc")
        from(tasks["javadoc"])
    }

    nexusPublishing {
        repositories {
            sonatype {
                username.set(OSSRH_USERNAME)
                password.set(OSSRH_PASSWORD)
            }
        }
    }

    publishing {
        repositories {
            maven {
                url = uri("https://oss.sonatype.org/service/local/staging/deploy/maven2/")
            }
        }
        publications {
            register("mavenJava", MavenPublication::class) {
                groupId = "${project.group}"
                artifactId = project.name
                version = "${project.version}"

                from(components["java"])

                artifact(sourcesJar.get())
                artifact(javadocJar.get())

                pom.withXml {
                    asNode().apply {
                        appendNode("name", project.name)
                        appendNode("url", "https://github.com/johnsonlee/${rootProject.name}")
                        appendNode("description", project.description ?: project.name)
                        appendNode("scm").apply {
                            appendNode("connection", "scm:git:git://github.com/johnsonlee/${rootProject.name}.git")
                            appendNode("developerConnection", "scm:git:git@github.com:johnsonlee/${rootProject.name}.git")
                            appendNode("url", "https://github.com/johnsonlee/${rootProject.name}")
                        }
                        appendNode("licenses").apply {
                            appendNode("license").apply {
                                appendNode("name", "Apache License")
                                appendNode("url", "https://www.apache.org/licenses/LICENSE-2.0")
                            }
                        }
                        appendNode("developers").apply {
                            appendNode("developer").apply {
                                appendNode("id", "johnsonlee")
                                appendNode("name", "Johnson Lee")
                                appendNode("email", "g.johnsonlee@gmail.com")
                            }
                        }
                    }
                }
            }
        }
    }

    signing {
        sign(publishing.publications["mavenJava"])
    }
}

nexusStaging {
    packageGroup = "io.johnsonlee"
    username = OSSRH_USERNAME
    password = OSSRH_PASSWORD
    numberOfRetries = 50
    delayBetweenRetriesInMillis = 3000
}

application {
    mainClassName = "io.johnsonlee.promise.MainKt"
}
