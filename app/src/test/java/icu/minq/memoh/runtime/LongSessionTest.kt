package icu.minq.memoh.runtime

import icu.minq.memoh.model.*
import icu.minq.memoh.ui.*
import org.junit.Assert.*
import org.junit.Test

class LongSessionTest {
    @Test fun pagesRetainEveryCharacterAndNeverSplitSurrogatePairs() {
        val text = "x".repeat(TEXT_PAGE_CHARS - 1) + "😀" + "abc😀中".repeat(12000)
        val pages = (text.length - 1) / TEXT_PAGE_CHARS + 1
        val pieces = (0 until pages).map { text.substring(textPageRange(text, it)) }
        assertEquals(text, pieces.joinToString(""))
        assertTrue(pieces.all { it.length <= TEXT_PAGE_CHARS + 1 && !it.first().isLowSurrogate() && !it.last().isHighSurrogate() })
        assertEquals("", "".substring(textPageRange("", 0)))
    }

    @Test fun longToolTurnHasIndependentRowsAndStableKeysWhileStreaming() {
        val blocks = (0 until 2000).map { MessageBlock(it, "tool", name = "tool-$it", toolCallId = "call-$it") }
        val run = RuntimeRun("r", "t", status = "running", messages = blocks)
        val rows = chatRows(emptyList(), run, emptyList(), null)
        val updated = chatRows(emptyList(), run.copy(messages = blocks + MessageBlock(2000, "text", content = "next")), emptyList(), null)
        assertEquals(2000, rows.filterIsInstance<ChatRow.Block>().size)
        assertEquals(rows.size, rows.map { it.key }.distinct().size)
        assertEquals(rows.take(2000).map { it.key }, updated.take(2000).map { it.key })
        assertSame(blocks[1500], (rows[1500] as ChatRow.Block).block)
    }
}
