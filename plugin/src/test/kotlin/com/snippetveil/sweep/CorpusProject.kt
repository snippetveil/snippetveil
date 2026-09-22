package com.snippetveil.sweep

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Files
import java.nio.file.Path

/*
 * **What both halves of the corpus instrument have to do to somebody's real checkout before they can
 * read a line of it.** Shared rather than copied: the two halves open different projects and ask
 * different questions of them, but *what a source file of this project is* and *which JDK is attached*
 * are one answer each, and two copies of either is that agreement waiting to drift.
 */

/**
 * **The running JDK, attached to the application's SDK table under the name the project already asks
 * for — and never to the corpus project itself.**
 *
 * A real `.idea` or `.ipr` names its SDK by a name the IDE's own configuration resolves, and this
 * process has no such configuration — so without this every `java.lang` reference resolves to nothing.
 * For the leak half that is not a leak (an unresolved name fails closed and is anonymized) but it is a
 * sweep of a codebase the anonymiser could not see the JDK in, which is not the codebase anybody runs.
 * For the query half it is worse than that: the query container's trigger *is* resolution, so a corpus
 * with no JDK is a corpus where every JPQL fragment falls back for a reason that is about this process
 * rather than about the code.
 *
 * The SDK goes into [ProjectJdkTable], which is application state, under the name the project already
 * asks for. **The corpus project is never written to** — it is somebody's real checkout, and an
 * instrument that dirtied `.idea/misc.xml` while reading would be one nobody runs twice.
 *
 * @return the name it was attached under, or `null` when the project names no SDK or the table already
 *   holds one under that name — so that each half can say so in its own words
 */
internal fun attachTheRunningJdkUnderTheNameTheProjectExpects(project: Project, disposable: Disposable): String? {
    val wanted = ProjectRootManager.getInstance(project).projectSdkName ?: return null
    if (ProjectJdkTable.getInstance().findJdk(wanted) != null) return null

    WriteAction.runAndWait<RuntimeException> {
        val jdk = JavaSdk.getInstance().createJdk(wanted, System.getProperty("java.home"), false)
        ProjectJdkTable.getInstance().addJdk(jdk, disposable)
    }
    return wanted
}

/**
 * Every file under [root] with one of [extensions] that is in the project's **own source content**.
 *
 * **Found on disk rather than by walking the project index**, because an instrument can be pointed at
 * a live working tree: the VFS serves a directory listing it cached the last time something looked, and
 * a file written since is invisible to `iterateContent`. Reporting on a checkout as it stood an hour ago
 * is the one kind of wrong answer these instruments must not give. What the *project* says is still
 * authoritative for whether a file counts — hence the source-content filter, which is what keeps build
 * output, generated sources and `.git` out.
 */
internal fun sourceFilesOf(project: Project, root: Path, extensions: List<String>): List<VirtualFile> {
    // The disk walk and the refresh happen **outside a read action**: a synchronous VFS refresh under
    // the read lock deadlocks, and the platform says so out loud. Only the question of whether a file
    // is source content needs the lock, so only that part takes it.
    val fileSystem = LocalFileSystem.getInstance()
    val onDisk = Files.walk(root).use { paths ->
        paths.filter { Files.isRegularFile(it) && extensions.any(it.toString()::endsWith) }
            .filter { path -> generateSequence(path.parent) { it.parent }.none { it.fileName?.toString() == ".git" } }
            .toList()
    }
    // Asked of the VFS one path at a time. `refreshAndFindFileByNioFile` refreshes exactly the path it
    // is given, so a file written since the IDE last looked at its directory is found — where a
    // recursive refresh of the root is not enough, because a directory whose listing is already cached
    // is not necessarily re-read.
    val known = onDisk.mapNotNull { fileSystem.refreshAndFindFileByNioFile(it) }

    val index = ProjectRootManager.getInstance(project).fileIndex
    return smartly(project) { known.filter { index.isInSourceContent(it) } }.sortedBy { it.path }
}
