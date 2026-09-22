package com.snippetveil.trust

import com.intellij.sql.psi.SqlFile

/**
 * The red path of the database isolation rule, baked in rather than observed once — the counterpart
 * of [ReachesForKotlinFromTheMainPackage], for the other optional dependency.
 *
 * Test scope, so [SHIPPED_CLASSES] excludes it; the property is never read, so the type is never
 * loaded — which matters here more than it does for Kotlin, because the IC cells compile against the
 * database plugin's API and run without it. The permitted half is
 * [com.snippetveil.plugin.sql.LivesInTheSqlSubPackage].
 */
internal class ReachesForSqlFromTheMainPackage {

    // Private, so that it compiles to a field and no accessor: the IC cells' test engine lists every
    // class's methods while it discovers tests, and a method returning a type the running IDE does not
    // have fails that listing for the whole suite. A field is read by nothing but the rule.
    @Suppress("unused")
    private val file: SqlFile? = null
}
