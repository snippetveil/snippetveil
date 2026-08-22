package com.snippetveil.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * **An annotation attribute name inherits the ownership of the annotation type that declares it.**
 *
 * Annotations are the densest metadata in a snippet and often the reason it is answerable at all,
 * so the spine rule preserving a library annotation whole — its type, its attribute names and the
 * enum constants it is given — is right rather than merely convenient.
 *
 * The half that does not fall out is the other side of the same rule. A project's own
 * `@AuditLogged(action = …)` declares `action` itself, and attribute names on project annotations
 * are domain vocabulary (`merchantScope`, `settlementStage`); preserving them unconditionally would
 * leak exactly the words this product exists to conceal.
 *
 * **There is deliberately no carve-out for project annotations that behave like framework markers.**
 * A rule such as *preserve a project annotation that is itself meta-annotated by a library one*
 * fires on precisely the annotations most likely to be domain-named — `@MerchantFacing`,
 * `@PciScope` — is unpredictable to the user, and breaks the one-sentence spine rule that makes the
 * model explainable. Nothing in a [SnippetPlan] reports a meta-annotation, which is the same
 * decision stated as a shape: no rule here can fire on evidence the plan does not carry.
 */
class AnnotationOwnershipTest {

    /**
     * A library annotation survives byte-identically, down to the enum constant naming its mode.
     * `fetch` and `LAZY` are Hibernate's words, not the project's, and a reader who cannot see them
     * cannot tell what the mapping does.
     */
    @Test
    fun `a library annotation's type attribute names and enum constants are all preserved`() {
        val text = "@ManyToOne(fetch = FetchType.LAZY) private Account owner;"

        val plan = planOf(
            text,
            symbol("ManyToOne", SymbolRole.ANNOTATION, SymbolOrigin.LIBRARY, key = "class:javax.persistence.ManyToOne"),
            symbol(
                "fetch",
                SymbolRole.ATTRIBUTE,
                SymbolOrigin.LIBRARY,
                key = "method:class:javax.persistence.ManyToOne#fetch",
            ),
            symbol("FetchType", SymbolRole.TYPE, SymbolOrigin.LIBRARY, key = "class:javax.persistence.FetchType"),
            symbol("LAZY", SymbolRole.FIELD, SymbolOrigin.LIBRARY, key = "field:class:javax.persistence.FetchType#LAZY"),
            symbol("Account", SymbolRole.TYPE, SymbolOrigin.IN_CONTENT, key = "class:com.acme.Account"),
            symbol("owner", SymbolRole.FIELD, SymbolOrigin.IN_CONTENT, key = "field:class:com.acme.Payment#owner"),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals("@ManyToOne(fetch = FetchType.LAZY) private Type1 field2;", result.text)
    }

    /**
     * A project annotation goes whole the other way: the type **and** the attribute names it
     * declares. Both render in namespaces of their own — `@Anno1(attr2 = …)` rather than
     * `@Type1(method2 = …)` — because what a reader has to map back is an annotation and its
     * attribute, and that is what Java's grammar calls them wherever they are written.
     */
    @Test
    fun `a project annotation's type and its attribute names are anonymized`() {
        val text = """@AuditLogged(action = "AUTHORIZE", scope = "PAYMENTS") void settle() {}"""

        val plan = planOf(
            text,
            symbol("AuditLogged", SymbolRole.ANNOTATION, SymbolOrigin.IN_CONTENT, key = "class:com.acme.AuditLogged"),
            symbol(
                "action",
                SymbolRole.ATTRIBUTE,
                SymbolOrigin.IN_CONTENT,
                key = "method:class:com.acme.AuditLogged#action",
            ),
            symbol(
                "scope",
                SymbolRole.ATTRIBUTE,
                SymbolOrigin.IN_CONTENT,
                key = "method:class:com.acme.AuditLogged#scope",
            ),
            symbol("settle", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT, key = "method:class:com.acme.Payment#settle"),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals("""@Anno1(attr2 = "AUTHORIZE", attr3 = "PAYMENTS") void method4() {}""", result.text)
    }
}
