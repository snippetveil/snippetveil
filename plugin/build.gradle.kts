plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

dependencies {
    // A plain project dependency, not a plugin module: core.jar stays a separate jar in the
    // distribution's lib/, which is the directory a later ticket's bytecode scan walks.
    implementation(project(":core"))

    intellijPlatform {
        // Compiled against the compatibility floor itself, so an API newer than 2024.1 cannot be
        // used by accident. Raising the floor later is free; lowering it is unverified work.
        intellijIdeaCommunity("2024.1.7")
        bundledPlugin("com.intellij.java")

        pluginVerifier()
    }
}

kotlin {
    jvmToolchain(17)

    // Nothing here ships a Kotlin stdlib — the platform provides one, and at the 241 floor that is
    // 1.9.x. This pin narrows the gap rather than closing it: 2.0 is the oldest level this compiler
    // still accepts, so a stdlib symbol introduced in 2.0 would still compile. What actually catches
    // that is verifyPlugin, which resolves every reference against IC-241 itself.
    compilerOptions {
        apiVersion = org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0
    }
}

intellijPlatform {
    // The distribution is SnippetVeil, not "plugin" — the subproject name must not name the product.
    projectName = "SnippetVeil"

    pluginConfiguration {
        ideaVersion {
            sinceBuild = "241"

            // Never pinned: a new IDE major must not force a compatibility re-release. The Gradle
            // plugin still defaults this, so it is nulled explicitly rather than left out.
            untilBuild = provider { null }
        }
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}
