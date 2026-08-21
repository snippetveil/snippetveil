package com.snippetveil.core

/**
 * Identifies the anonymization engine module inside a built distribution.
 *
 * The engine itself — a pure function from a snippet snapshot to a rename delta — arrives with the
 * anonymization tickets. This module exists first because the boundary it draws is structural: :core
 * declares no IntelliJ Platform dependency, so nothing in it can reach for the IDE.
 */
object CoreModule {
    const val NAME: String = "snippetveil-core"
}
