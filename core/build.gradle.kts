plugins {
    id("org.jetbrains.kotlin.jvm")
}

// :core has ZERO runtime dependencies. The Kotlin stdlib is compileOnly here and is provided at
// runtime by the IntelliJ Platform, which bundles it — so core.jar ships depending on nothing.
dependencies {
    compileOnly(kotlin("stdlib"))

    testImplementation(kotlin("stdlib"))
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Property-based testing, for the one class of failure that cannot appear in a single-invocation
    // test: a second paste contradicting the first. See LedgerHistoryTest.
    //
    // **Licence checked rather than assumed**, against the allow-list in CONTRIBUTING.md: jqwik is
    // EPL-2.0, which that list allows as an unmodified dependency, and this is one — no fork, no
    // patch. It is also test scope, so it is not on the shipped runtime classpath and carries no
    // outbound obligation at all; `assertNothingThirdPartyIsShipped` holds that to be true rather
    // than taking it on trust.
    testImplementation("net.jqwik:jqwik:1.10.1")
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

tasks.test {
    useJUnitPlatform()
}
