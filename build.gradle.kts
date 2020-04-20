plugins {
    java
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

spotless {
    java {
        googleJavaFormat().aosp()
        removeUnusedImports()
    }
}