package com.dohex.hyperrose.ipc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BroadcastSenderValidatorTest {
    @Test
    fun `allows a uid containing a trusted package`() {
        assertTrue(
            BroadcastSenderValidator.isAllowedPackages(
                arrayOf("unrelated.package", HyperRoseIpc.PACKAGE_APP),
                setOf(HyperRoseIpc.PACKAGE_APP),
            ),
        )
    }

    @Test
    fun `rejects unknown or untrusted uid packages`() {
        assertFalse(
            BroadcastSenderValidator.isAllowedPackages(
                arrayOf("untrusted.package"),
                setOf(HyperRoseIpc.PACKAGE_APP),
            ),
        )
        assertFalse(
            BroadcastSenderValidator.isAllowedPackages(
                null,
                setOf(HyperRoseIpc.PACKAGE_APP),
            ),
        )
    }
}
