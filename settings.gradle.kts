import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

pluginManagement {
    plugins {
        // **No newer than CodeQL can read.** Code scanning builds this project with its own Kotlin
        // extractor, which supports compiler versions up to a ceiling and fails outright above it:
        // 2.4.20 was *"too recent"* for CodeQL 2.27.0, and the Java/Kotlin analysis on `main` was
        // dead from that bump until this line went back. A red `Analyze (java-kotlin)` on a pull
        // request that raises this is that ceiling, and the bump waits for it to move.
        id("org.jetbrains.kotlin.jvm") version "2.4.10"

        // CHANGELOG.md's parser, and the source of the descriptor's change notes. It is applied in
        // `:plugin` rather than at the root, for the reason `assertNoRoadmapIsPublished` lives
        // there too: the change notes are the *distribution's*, and the distribution is `:plugin`'s.
        // The file it reads is still the one at the repository root — see the `changelog` block.
        id("org.jetbrains.changelog") version "2.5.0"
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("org.jetbrains.intellij.platform.settings") version "2.18.1"
}

rootProject.name = "snippetveil"

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        intellijPlatform {
            defaultRepositories()
        }
    }
}

include(":core", ":plugin")
