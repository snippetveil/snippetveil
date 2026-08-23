package com.snippetveil.plugin

import com.intellij.lang.java.JavaLanguage
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.augment.PsiAugmentProvider
import com.intellij.psi.impl.light.LightMethodBuilder
import com.intellij.psi.util.PropertyUtilBase
import com.snippetveil.core.AnonymizationSettings
import com.snippetveil.core.LedgerSnapshot
import com.snippetveil.core.MintedName
import com.snippetveil.core.SymbolOrigin
import com.snippetveil.core.anonymize

/**
 * **The case forced sharing rule 3 exists for: an accessor with no declaration in source at all.**
 *
 * With Lombok, `@Getter` means the IDE synthesizes `getMerchantId()` as a *light* member — no
 * `TextRange`, no `setName()` that does anything, and nothing in the file to look at. Only the call
 * sites are real. An unrelated placeholder there leaves a method with no visible origin: `Type1`
 * would expose a `method2()` that nothing in the output explains, which is a *plausible* artifact
 * and therefore the prohibited class.
 *
 * Lombok itself is not on this build's classpath and putting it there would be testing Lombok. What
 * is under test is the mechanism Lombok uses — a `PsiAugmentProvider` contributing light methods to
 * a class — so the test contributes them itself. That is also why the rules match on a method's
 * *name and arity* rather than on its body: a body-reading match would find every accessor except
 * the ones that need it.
 */
class LombokLightAccessorTest : JavaSnippetTestCase() {

    fun `test a light accessor with no declaration in source derives from its field`() {
        assertTheHarnessResolves()
        PsiAugmentProvider.EP_NAME.point.registerExtension(SynthesizedGetters(), testRootDisposable)

        myFixture.addFileToProject(
            "com/acme/Payment.java",
            "package com.acme; public class Payment { private String merchantId; }",
        )
        val plan = planFor(
            "com/acme/Checkout.java",
            """
            package com.acme;

            class Checkout {
                String describe(Payment payment) { <selection>return payment.getMerchantId();</selection> }
            }
            """.trimIndent(),
        )

        val accessor = plan.symbols().single { it.text == "getMerchantId" }.symbol

        // Keyed by (owner FQN, kind, name) and nothing else — there is no text range to anchor on,
        // and a key that reached for one would have nothing to read.
        assertEquals("method:class:com.acme.Payment#getMerchantId", accessor.key)

        // A light member has no file of its own, so ownership fails closed onto the class it was
        // synthesized from — which here is the right answer as well as the safe one.
        assertEquals(SymbolOrigin.IN_CONTENT, accessor.origin)
        assertEquals(
            "field:class:com.acme.Payment#merchantId" to "get",
            accessor.accessor?.let { it.fieldKey to it.prefix },
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        // The field is nowhere in the snippet and is named anyway: the accessor's placeholder is
        // derived from it, so it has to exist before the accessor can be written down.
        assertEquals(
            "forced sharing rule 3: a light accessor derives from a field that is not in the snippet",
            "return param1.getField2();",
            result.text,
        )
        // And the field is written down **by name** as well as by placeholder, which is the row a
        // reversal reads: with Lombok this is often the only record there will ever be of it, since
        // neither the accessor nor the field has to appear in any snippet the field is named from.
        assertEquals(
            MintedName("field2", "merchantId"),
            result.delta.placeholders["field:class:com.acme.Payment#merchantId"],
        )
    }
}

/** What `@Getter` does, reduced to the one mechanism these rules have to survive. */
private class SynthesizedGetters : PsiAugmentProvider() {

    override fun <Psi : PsiElement> getAugments(element: PsiElement, type: Class<Psi>, nameHint: String?): List<Psi> {
        if (element !is PsiClass || type != PsiMethod::class.java) return emptyList()

        @Suppress("UNCHECKED_CAST")
        return element.fields.map { field ->
            LightMethodBuilder(element.manager, JavaLanguage.INSTANCE, PropertyUtilBase.suggestGetterName(field))
                .setContainingClass(element)
                .setMethodReturnType(field.type)
                .addModifier(PsiModifier.PUBLIC) as Psi
        }
    }
}
