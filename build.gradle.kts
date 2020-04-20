plugins {
    java
    `maven-publish`
    signing
    id("com.diffplug.gradle.spotless") version "3.27.1"
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

java {
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

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            pom {
                name.set(project.name)
                description.set("A generic JDBC driver wrapper connecting using IAM RDS token authentication")
                url.set("https://github.com/magJ/iam-jdbc-driver")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
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
