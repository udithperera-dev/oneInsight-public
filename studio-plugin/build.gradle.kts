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
    implementation(project(":protocol")) {
        exclude(group = "org.jetbrains.kotlin")
        exclude(group = "org.jetbrains.kotlinx")
        exclude(group = "org.jetbrains", module = "annotations")
    }

    intellijPlatform {
        local("/Applications/Android Studio.app")
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.android")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }

    testImplementation(libs.junit)
}

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

    named("buildSearchableOptions") {
        enabled = false
    }

    named<org.gradle.jvm.tasks.Jar>("jar") {
        archiveBaseName.set("OneInsight")
    }

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
