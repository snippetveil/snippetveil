package com.snippetveil.plugin.kotlin

import org.jetbrains.kotlin.psi.KtFile

/**
 * The green path of the Kotlin isolation rule: the one package where naming a Kotlin-plugin type is
 * correct rather than fatal.
 *
 * This package is loaded only from `com.snippetveil-withKotlin.xml`, which the platform reads only
 * where the Kotlin plugin is running — so a class here links exactly when the types it names are on
 * the classloader. The rule has to leave it out, and *that it does* is asserted rather than assumed:
 * see `the main descriptor's packages do not reach for Kotlin plugin classes` and the demonstration
 * beside it.
 *
 * A fixture, in test scope, and it stays one now that `KotlinPlanBuilder` shares the package with it:
 * the demonstration below needs a class it can name *without* the isolation rule flagging it, and
 * shipped code is not the thing to point a deliberately-violating rule check at.
 */
internal class LivesInTheKotlinSubPackage {

    val file: KtFile? = null
}
