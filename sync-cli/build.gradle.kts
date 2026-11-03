import org.gradle.jvm.tasks.Jar

plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

application {
    mainClass.set("com.alexskoropletov.sync.cli.MainKt")
    applicationName = "magic-sync"
}

dependencies {
    implementation(project(":sync-core"))
    testImplementation("junit:junit:4.13.2")
}

tasks.named<Jar>("jar") {
    archiveFileName.set("magic-sync.jar")
    manifest {
        attributes["Main-Class"] = "com.alexskoropletov.sync.cli.MainKt"
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    val runtime = configurations.runtimeClasspath
    dependsOn(runtime)
    from({
        runtime.get().map { if (it.isDirectory) it else zipTree(it) }
    }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/MANIFEST.MF")
    }
}
