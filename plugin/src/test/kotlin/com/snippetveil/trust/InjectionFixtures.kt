package com.snippetveil.trust

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLanguageInjectionHost

/**
 * The red path of the union-projection rule, baked in rather than observed once.
 *
 * [CallsTheUnionProjection] maps an injected range the way the rule forbids and must be flagged;
 * [MapsPerShred] asks the platform's injected-file query, which is how shipped code maps, and must
 * not be — a rule that flagged the injection API wholesale would be one the next implementer has to
 * suppress. Test scope, so [SHIPPED_CLASSES] excludes both; neither method is ever called.
 */
internal class CallsTheUnionProjection {

    fun hostRangeOf(injected: PsiElement, range: TextRange): TextRange =
        InjectedLanguageManager.getInstance(injected.project).injectedToHost(injected, range)
}

internal class MapsPerShred {

    fun shredsOf(host: PsiLanguageInjectionHost): Int {
        var shreds = 0
        InjectedLanguageManager.getInstance(host.project).enumerate(host) { _, places -> shreds += places.size }
        return shreds
    }
}
