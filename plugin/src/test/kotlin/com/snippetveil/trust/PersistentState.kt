package com.snippetveil.trust

import com.tngtech.archunit.core.domain.JavaClass

/**
 * **Whether the platform can write this class to disk**: a `PersistentStateComponent`, or anything
 * carrying `@State`. Either is enough on its own — the annotation names the file, the interface
 * produces the content, and a class with one and not the other is a class mid-way through being
 * given both.
 *
 * It lives here rather than in either test because two rules are statements about it — nothing
 * shipped persists a reduction, and nothing shipped roams what it does persist — and for the same
 * reason [SHIPPED_CLASSES] is shared rather than re-imported per test: a second definition of *what
 * the platform can write to disk* would drift from this one on the day either is updated, and both
 * rules would still pass while meaning different things.
 */
internal fun JavaClass.isStateHolder(): Boolean =
    allRawInterfaces.any { it.name == PERSISTENT_STATE_COMPONENT } ||
        annotations.any { it.rawType.name == STATE_ANNOTATION }

/**
 * Everything that ships and that the platform can write to disk.
 *
 * Not empty, and the tests that read it say so out loud: a rule over an empty list passes, and both
 * of the rules here are worth nothing the day this stops matching anything.
 */
internal val SHIPPED_STATE_HOLDERS: List<JavaClass> = SHIPPED_CLASSES.filter { it.isStateHolder() }

internal const val PERSISTENT_STATE_COMPONENT = "com.intellij.openapi.components.PersistentStateComponent"

internal const val STATE_ANNOTATION = "com.intellij.openapi.components.State"

internal const val STORAGE_ANNOTATION = "com.intellij.openapi.components.Storage"
