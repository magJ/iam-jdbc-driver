plugins {
    java
    `maven-publish`
    signing
    id("com.diffplug.gradle.spotless") version "3.27.1"
    id("com.github.johnrengelman.shadow") version "5.2.0"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.amazonaws:aws-java-sdk-rds:1.11.745")
    testImplementation("junit:junit:3.8.1")
    testImplementation("org.postgresql:postgresql:42.2.12")
}

group = "io.magj"

val release: String? by project
val baseVersion = "0.1.0"

version = if (release != null && release!!.toBoolean()) {
    baseVersion
} else {
    "$baseVersion-SNAPSHOT"
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
    withJavadocJar()
    withSourcesJar()
}

spotless {
    java {
        googleJavaFormat().aosp()
        removeUnusedImports()
    }

    kotlinGradle {
        ktlint()
    }
}
val mavenUploadUser: String? by project
val mavenUploadPassword: String? by project


publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifact(tasks.shadowJar.get()) {
                classifier = "all"
            }
            pom {
                name.set(project.name)
                description.set("A generic JDBC driver wrapper connecting using IAM RDS token authentication")
                url.set("https://github.com/magJ/iam-jdbc-driver")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                developers {
                    developer {
                        id.set("magj")
                        name.set("Magnus Jason")
                        email.set("magnus@magnusjason.com")
                    }
                }
                scm {
                    url.set(pom.url)
                }
            }
        }
    }
    repositories {
        maven {
            val releasesRepoUrl = uri("https://oss.sonatype.org/service/local/staging/deploy/maven2")
            val snapshotsRepoUrl = uri("https://oss.sonatype.org/content/repositories/snapshots")
            url = if (version.toString().endsWith("SNAPSHOT")) snapshotsRepoUrl else releasesRepoUrl
            credentials {
                username = mavenUploadUser
                password = mavenUploadPassword
            }
        }
    }
}

signing {
    val signingKey: String? by project
    val signingPassword: String? by project
    if (signingKey != null && signingPassword != null) {
        useInMemoryPgpKeys(signingKey, signingPassword)
        sign(publishing.publications["mavenJava"])
    }
}

tasks.javadoc {
    if (JavaVersion.current().isJava9Compatible) {
        (options as StandardJavadocDocletOptions).addBooleanOption("html5", true)
    }
}

tasks.shadowJar {
    listOf(
            "com.amazonaws",
            "com.fasterxml",
            "org.apache.commons",
            "org.apache.http",
            "org.joda",
            "com.fasterxml"
    ).forEach {
        relocate(it, "repackaged.$it")
    }
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}