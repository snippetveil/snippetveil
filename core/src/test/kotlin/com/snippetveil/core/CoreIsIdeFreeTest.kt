package com.snippetveil.core

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * The compile-time half of the module boundary needs no test: :core declares no IntelliJ Platform
 * dependency, so a reference to `com.intellij.*` does not compile here at all. This covers the
 * runtime half — the platform is absent from :core's test classpath too, which is what keeps core
 * tests plain JUnit at millisecond speed. Bytecode-level enforcement of the ban arrives with the
 * trust-check ticket, snippetveil/snippetveil#1.
 */
class CoreIsIdeFreeTest {

    @Test
    fun `the IntelliJ Platform is not on core's classpath`() {
        assertThrows(ClassNotFoundException::class.java) {
            Class.forName("com.intellij.openapi.project.Project")
        }
    }
}
