package com.dohex.hyperrose.hook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiBluetoothFocusIslandPolicyTest {
    @Test
    fun inactiveSessionStartsIslandSession() {
        assertTrue(startsNewIslandSession(false, null, "AA:BB:CC:DD:EE:FF"))
    }

    @Test
    fun duplicateConnectionBroadcastKeepsCurrentSession() {
        assertFalse(
            startsNewIslandSession(
                true,
                "AA:BB:CC:DD:EE:FF",
                "aa:bb:cc:dd:ee:ff",
            ),
        )
    }

    @Test
    fun differentDeviceStartsIslandSession() {
        assertTrue(
            startsNewIslandSession(
                true,
                "AA:BB:CC:DD:EE:FF",
                "11:22:33:44:55:66",
            ),
        )
    }
}
