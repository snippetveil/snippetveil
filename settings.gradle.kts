import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

pluginManagement {
    plugins {
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
