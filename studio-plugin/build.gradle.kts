import proguard.gradle.ProGuardTask

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("com.guardsquare:proguard-gradle:7.9.1")
    }
}

plugins {
    id("java")
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.intellij.platform)
}

base {
    archivesName.set("OneInsight")
}

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    // Android Studio already ships Kotlin stdlib, coroutines, and serialization.
    // Bundling our own copies causes PluginClassLoader linkage failures when calling
    // Studio App Inspection suspend APIs ("cannot load class" / Continuation mismatches).
    implementation(project(":protocol")) {
        exclude(group = "org.jetbrains.kotlin")
        exclude(group = "org.jetbrains.kotlinx")
        exclude(group = "org.jetbrains", module = "annotations")
    }
    implementation("org.brotli:dec:0.1.2")

    intellijPlatform {
        local("/Applications/Android Studio.app")
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.android")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }

    testImplementation(libs.junit)
}

// Keep stdlib on the compile classpath (Kotlin Gradle Plugin adds it), but never package it.
configurations.configureEach {
    if (name.equals("runtimeClasspath", ignoreCase = true) ||
        name.contains("intellijPlatformPluginClasspath", ignoreCase = true) ||
        name.contains("RuntimeClasspath", ignoreCase = false)
    ) {
        exclude(group = "org.jetbrains.kotlin")
        exclude(group = "org.jetbrains.kotlinx")
        exclude(group = "org.jetbrains", module = "annotations")
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "dev.akurupela.oneinsight"
        name = "OneInsight"
        version = project.version.toString()
        ideaVersion {
            sinceBuild = "261"
            untilBuild = "261.*"
        }
        changeNotes = """
            <ul>
              <li>Adds support for IntelliJ IDEA with the Android plugin installed.</li>
              <li>Uses Android App Inspection with in-process ART hooks.</li>
              <li>No VPN, proxy, CA, or app source changes.</li>
            </ul>
        """.trimIndent()
    }

    pluginVerification {
        ides {
            local("/Applications/Android Studio.app")
        }
    }
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }

    test {
        useJUnit()
    }

    // Headless Android Studio local installs can hang during searchable-options indexing.
    named("buildSearchableOptions") {
        enabled = false
    }

    named<org.gradle.jvm.tasks.Jar>("jar") {
        archiveBaseName.set("OneInsight")
    }


    // Kotlin Gradle Plugin still puts kotlin-stdlib on runtimeClasspath; strip it (and
    // kotlinx-*/annotations) from the sandbox so the ZIP does not bundle Studio-provided jars.
    withType<org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask> {
        doLast {
            val libDir = destinationDir.resolve(pluginName.get()).resolve("lib")
            if (!libDir.isDirectory) return@doLast
            libDir.listFiles()
                ?.filter { file ->
                    val name = file.name
                    name.startsWith("kotlin-stdlib") ||
                        name.startsWith("kotlinx-") ||
                        name.startsWith("annotations-")
                }
                ?.forEach { it.delete() }
        }
    }

    named<Zip>("buildPlugin") {
        archiveFileName.set("OneInsight-${project.version}.zip")
    }
}

val composedPluginJarTask = tasks.named("composedJar")
val composedPluginJar = layout.buildDirectory.file(
    "libs/studio-plugin-${project.version}.jar",
)
val protocolJar = project(":protocol").tasks.named<Jar>("jar")
val obfuscatedPluginJar = layout.buildDirectory.file(
    "obfuscated/studio-plugin-${project.version}.jar",
)

val obfuscatePluginJar by tasks.registering(ProGuardTask::class) {
    group = "build"
    description = "Obfuscates the OneInsight plugin implementation jar."
    dependsOn(composedPluginJarTask, protocolJar)

    injars(composedPluginJar)
    injars(protocolJar.flatMap { it.archiveFile })
    outjars(obfuscatedPluginJar)
    configuration(file("proguard-rules.pro"))

    // IntelliJ Platform, Android Studio, and third-party dependencies are compile-time
    // libraries. The first-party protocol JAR is an input and is obfuscated/merged.
    libraryjars(
        configurations.compileClasspath.get().filterNot {
            it.name.startsWith("protocol-")
        },
    )

    val toolchainHome = javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    }.get().metadata.installationPath
    val bundledJmods = toolchainHome.dir("jmods").asFile
    val macJdks = file("${System.getProperty("user.home")}/Library/Java/JavaVirtualMachines")
    val jmodsDir = bundledJmods.takeIf { it.isDirectory }
        ?: macJdks.listFiles()
            ?.asSequence()
            ?.map { it.resolve("Contents/Home/jmods") }
            ?.firstOrNull { it.isDirectory }
        ?: error(
            "A JDK with jmods is required for obfuscation. " +
                "Set JAVA_HOME to a full JDK (Android Studio's bundled JBR omits jmods).",
        )
    libraryjars(
        mapOf("jarfilter" to "!**.jar", "filter" to "!module-info.class"),
        fileTree(jmodsDir) { include("*.jmod") },
    )

    printmapping(layout.buildDirectory.file("obfuscated/mapping.txt").get().asFile)
    printseeds(layout.buildDirectory.file("obfuscated/seeds.txt").get().asFile)
    printusage(layout.buildDirectory.file("obfuscated/usage.txt").get().asFile)
}

val buildObfuscatedPlugin by tasks.registering(Zip::class) {
    group = "build"
    description = "Builds an installable OneInsight ZIP with obfuscated implementation classes."
    dependsOn(tasks.named("buildPlugin"), obfuscatePluginJar)

    val regularZip = tasks.named<Zip>("buildPlugin").flatMap { it.archiveFile }
    from(regularZip.map { zipTree(it) }) {
        exclude("*/lib/studio-plugin-${project.version}.jar")
        exclude("*/lib/protocol-${project.version}.jar")
    }
    from(obfuscatedPluginJar) {
        into("studio-plugin/lib")
        rename { "studio-plugin-${project.version}.jar" }
    }

    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    archiveFileName.set("OneInsight-${project.version}-obfuscated.zip")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

