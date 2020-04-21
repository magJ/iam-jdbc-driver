plugins {
    java
    `maven-publish`
    signing
    id("com.diffplug.gradle.spotless") version "3.27.1"
    id("com.github.johnrengelman.shadow") version "5.2.0"
    id("de.marcphilipp.nexus-publish") version "0.4.0"
    id("io.codearte.nexus-staging") version "0.21.2"
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
val baseVersion = "0.1.2"

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
}

nexusPublishing {
    repositories {
        sonatype()
    }
}

val mavenUploadUser: String? by project
val mavenUploadPassword: String? by project
nexusStaging {
    username = mavenUploadUser
    password = mavenUploadPassword
}

tasks.closeRepository.configure {
    mustRunAfter(tasks.publish)
}

signing {
    val signingKeyId: String? by project
    val signingKey: String? by project
    val signingPassword: String? by project
    if (signingKey != null && signingPassword != null) {
        useInMemoryPgpKeys(signingKeyId, signingKey, signingPassword)
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
            "software.amazon",
            "com.fasterxml",
            "org.apache.commons",
            "org.apache.http",
            "org.joda",
            "com.fasterxml"
    ).forEach {
        relocate(it, "io.magj.iamjdbcdriver.repackaged.$it")
    }
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}
