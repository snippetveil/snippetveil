package com.snippetveil.plugin

import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.snippetveil.core.mappingCsv

/**
 * **Writes one invocation's mapping to a file the user picks.**
 *
 * The write first, the balloon second, and the failure told apart from the cancel — the same shape
 * `deliver` follows on the way out, for the same reason: the file being on disk is the single moment
 * this has happened at all, and nothing may be said about a file that is not.
 *
 * A cancelled chooser is not a failure and says nothing. The user closed a dialog; a balloon
 * announcing that would be the tool reporting its own no-op.
 *
 * **No arm for `ProcessCanceledException`, unlike the analysis this feature hangs off.** That arm
 * exists in [startAnonymizing] because a read action is cancelled routinely by a concurrent write,
 * and reporting a keystroke as an error would be wrong. Nothing here runs under an indicator or in a
 * read action: the chooser is modal, the write is a write, and a cancel arrives as `null` rather than
 * as a throw. [restoreClipboard] catches the same way for the same reason.
 */
internal fun exportMapping(project: Project, analysis: Analysis, files: MappingFiles = SavedMappingFiles) {
    val saved = try {
        files.save(project, analysis.result.mappingCsv())
    } catch (failure: Throwable) {
        SnippetVeilNotifications.exportFailed(project, failure)
        return
    }
    if (saved) SnippetVeilNotifications.mappingSaved(project)
}

/**
 * The save dialog and the write behind it, or whatever a test puts in their place.
 *
 * The seam exists for the same reason [Clipboard] does: *"a cancelled save writes nothing and says
 * nothing"* and *"a failed write says so"* are the two guarantees here, and neither is assertable
 * against a modal file chooser.
 */
internal interface MappingFiles {

    /**
     * Writes [csv] wherever the user says.
     *
     * @return whether they said anywhere at all — `false` is a cancelled chooser, which is the one
     *   outcome that is neither a success nor a failure
     */
    fun save(project: Project, csv: String): Boolean
}

/** The real one: the platform's save dialog, and the file it comes back with. */
internal object SavedMappingFiles : MappingFiles {

    override fun save(project: Project, csv: String): Boolean {
        // The varargs constructor is deprecated on current platforms, where the extension filter has
        // moved onto a builder — and it is the **only** one the `sinceBuild` floor has, so it stays
        // until the floor rises. `verifyPlugin` reports it as a deprecated usage on the newer IDEs
        // and compatible on all five; the alternative is dropping the filter on every IDE to quiet a
        // warning on some of them.
        val descriptor = FileSaverDescriptor(
            "Export Mapping",
            "Save this snippet's placeholder mapping, which reverses the anonymized text",
            "csv",
        )
        val chosen = FileChooserFactory.getInstance()
            .createSaveFileDialog(descriptor, project)
            .save(null as VirtualFile?, DEFAULT_NAME)
            ?: return false

        // UTF-8, and **no byte-order mark, deliberately.** A BOM is the one thing that would make
        // older Excel on Windows read a non-ASCII literal correctly, and it puts a zero-width
        // character in front of `Placeholder` for every other reader there is — including the eye,
        // which sees a header that no longer matches the column it names. The cost of refusing it is
        // a display problem in one program over a file whose bytes are right; the cost of writing it
        // is a wrong first field everywhere else.
        chosen.file.writeText(csv)
        return true
    }
}

/**
 * What the chooser opens with. Named for the product rather than for the snippet: a name built out
 * of the class or the method it came from would put the domain vocabulary this file exists to hide
 * into the one place a file name is routinely seen — a chooser's recent list, a shell history, a
 * backup index.
 */
private const val DEFAULT_NAME = "snippetveil-mapping.csv"
