package com.snippetveil.trust

import com.snippetveil.sweep.SourceSpellings

/**
 * The red path of the leak-check rule, baked in rather than observed once.
 *
 * What it stands for is an anonymiser that asked the sweep which spellings a declaration has, rather
 * than asking PSI — the shortcut that would make the leak check agree with the anonymiser by
 * construction. Test scope, so [SHIPPED_CLASSES] excludes it; the property is never read, so the type
 * is never loaded. It is imported by name, which is the only way a rule gets pointed at code that is
 * meant to violate it.
 */
internal class ReachesForTheSweep {

    val closure: SourceSpellings? = null
}
