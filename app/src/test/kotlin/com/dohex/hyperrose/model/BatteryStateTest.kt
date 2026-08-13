package com.dohex.hyperrose.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryStateTest {

    @Test
    fun `overall state is single value and displays overall`() {
        val state = TwsBatteryState(overall = 60)
        assertTrue(state.isSingleValue())
        assertEquals(60, state.singleDisplayValue())
    }

    @Test
    fun `component state with left only is single value`() {
        val state = TwsBatteryState(left = EarBatteryState(60, false))
        assertTrue(state.isSingleValue())
        assertEquals(60, state.singleDisplayValue())
    }

    @Test
    fun `component state with left and right is not single value`() {
        val state = TwsBatteryState(
            left = EarBatteryState(60, false),
            right = EarBatteryState(58, false),
        )
        assertFalse(state.isSingleValue())
        assertNull(state.singleDisplayValue())
    }

    @Test
    fun `withLastKnownCaseBattery keeps overall`() {
        val state = TwsBatteryState(overall = 60)
        val merged = state.withLastKnownCaseBattery(TwsBatteryState(caseBattery = 90))
        assertEquals(60, merged.overall)
    }
}