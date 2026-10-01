plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

group = "me.brekhin"
version = "0.1.0"

kotlin {
    jvmToolchain(25)
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test"))

    intellijPlatform {
        // Must match your GoLand version exactly, e.g. "2026.2.1" — see Help | About.
        goland(providers.gradleProperty("platformVersion"))
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "262"
            untilBuild = provider { null }
        }
    }
    // Plugin Verifier is not needed for a private plugin.
    buildSearchableOptions = false
}
