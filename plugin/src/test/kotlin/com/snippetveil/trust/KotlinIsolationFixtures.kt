package com.snippetveil.trust

import org.jetbrains.kotlin.psi.KtFile

/**
 * The red path of the Kotlin isolation rule, baked in rather than observed once.
 *
 * The rule it feeds guards a failure that cannot be reproduced by running anything this build can
 * run: the plugin's classes link against `org.jetbrains.kotlin.*` here, because the Kotlin plugin is
 * on every classpath this project compiles and tests against. What breaks is an IDE where it is
 * switched off, and nothing in this repository boots one. So the rule is the enforcement, and a rule
 * nothing ever violates is a rule nobody has seen work — [ReachesForKotlinFromTheMainPackage] is what
 * makes it fail on demand.
 *
 * Test scope, so [SHIPPED_CLASSES] excludes it and the shipped-code rules never see it; the property
 * is never read, so the type is never loaded. It is imported by name, one class at a time, which is
 * the only way a rule gets pointed at code that is meant to violate it.
 *
 * The permitted half is [com.snippetveil.plugin.kotlin.LivesInTheKotlinSubPackage], which names the
 * same type from the sub-package the rule deliberately leaves out. Both are asserted, because a rule
 * that flagged the Kotlin half too would be one an implementer has to suppress on the day the first
 * Kotlin class is written — and a suppressed rule is where a violation eventually hides.
 */
internal class ReachesForKotlinFromTheMainPackage {

    val file: KtFile? = null
}
