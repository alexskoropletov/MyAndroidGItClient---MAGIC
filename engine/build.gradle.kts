plugins {
    id("org.jetbrains.kotlin.jvm")
    id("java-library")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api("org.eclipse.jgit:org.eclipse.jgit:6.10.0.202406032230-r")
    implementation("org.eclipse.jgit:org.eclipse.jgit.ssh.jsch:6.10.0.202406032230-r") {
        exclude(group = "com.jcraft", module = "jsch")
    }
    implementation("com.github.mwiede:jsch:2.28.8")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("org.slf4j:slf4j-nop:1.7.36")

    testImplementation("junit:junit:4.13.2")
}
