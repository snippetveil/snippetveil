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
 * A fixture, in test scope, standing in for a Kotlin half that does not exist yet. It goes away, or
 * stops being the only class here, on the day the Kotlin plan builder lands.
 */
internal class LivesInTheKotlinSubPackage {

    val file: KtFile? = null
}
