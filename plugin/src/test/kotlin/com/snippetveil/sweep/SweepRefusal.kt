package com.snippetveil.sweep

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.snippetveil.plugin.GateVerdict
import com.snippetveil.plugin.gate
import org.jetbrains.kotlin.idea.KotlinFileType

/**
 * **Refuses the whole sweep if any Kotlin file in [files] cannot be anonymised in this IDE** — asked of
 * the gate a user's `Copy Anonymized` asks, so that *cannot be anonymised here* means exactly what it
 * means to a user.
 *
 * In a sweep that is a K1 session — the Kotlin plugin's default on the floor platform, which every
 * default run is on — or a Kotlin plugin switched off. **Refused rather than swept around**: a report
 * over the Java half would say nothing of a Kotlin half nobody anonymised, and silence about it is the
 * one answer the coverage habit rules out. A target with no Kotlin in it has nothing to refuse, so a
 * Java codebase still sweeps on the floor.
 *
 * Counts only, never a path: a file name is a real identifier too.
 */
internal fun refuseKotlinThatCannotBeSwept(project: Project, files: List<VirtualFile>) {
    val refused = smartly(project) {
        files.filter { it.fileType == KotlinFileType.INSTANCE }
            .map { gate(PsiManager.getInstance(project).findFile(it)) }
            .filter { it != GateVerdict.Offer }
    }
    check(refused.isEmpty()) {
        val causes = refused.map { (it as? GateVerdict.Refuse)?.cause ?: "no PSI" }.distinct()
        "${refused.size} Kotlin file(s) in this project cannot be anonymised in this IDE ($causes). " +
            "Where SnippetVeil's Kotlin support is not registered, the Kotlin plugin is running in K1 — " +
            "its default on the floor platform — or is switched off. Sweeping the Java half alone " +
            "would report on a Kotlin half nobody anonymised, so nothing is swept. Run it where the " +
            "Kotlin plugin runs K2: ./gradlew corpusSweep -PplatformProfile=k2 -PsweepProject=…"
    }
}
