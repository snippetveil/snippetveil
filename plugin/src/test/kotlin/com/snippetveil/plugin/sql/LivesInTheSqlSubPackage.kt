package com.snippetveil.plugin.sql

import com.intellij.sql.psi.SqlFile

/**
 * The green path of the database isolation rule: the one package where naming a database-plugin type
 * is correct rather than fatal, because it is loaded only from `com.snippetveil-withDatabase.xml`.
 *
 * A fixture, in test scope, beside [SqlContainer] for the reason
 * [com.snippetveil.plugin.kotlin.LivesInTheKotlinSubPackage] sits beside the Kotlin walk: the
 * demonstration needs a class it can name *without* the rule flagging it, and shipped code is not the
 * thing to point a deliberately-violating rule check at.
 */
internal class LivesInTheSqlSubPackage {

    // Private, so that it compiles to a field and no accessor: the IC cells' test engine lists every
    // class's methods while it discovers tests, and a method returning a type the running IDE does not
    // have fails that listing for the whole suite. A field is read by nothing but the rule.
    @Suppress("unused")
    private val file: SqlFile? = null
}
