package icu.minq.memoh.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class WindowWidthClassTest {
    @Test
    fun compactBelow600Dp() {
        assertEquals(AppWindowWidthClass.Compact, classifyWindowWidth(599.9f))
    }

    @Test
    fun mediumFrom600Dp() {
        assertEquals(AppWindowWidthClass.Medium, classifyWindowWidth(600f))
        assertEquals(AppWindowWidthClass.Medium, classifyWindowWidth(767.9f))
    }

    @Test
    fun expandedAtOfficial768DpShellBreakpoint() {
        assertEquals(AppWindowWidthClass.Expanded, classifyWindowWidth(768f))
        assertEquals(AppWindowWidthClass.Expanded, classifyWindowWidth(1280f))
    }
}
