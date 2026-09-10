package icu.minq.memoh

import android.graphics.Bitmap
import android.text.Spanned
import android.text.style.URLSpan
import android.widget.TextView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry
import icu.minq.memoh.ui.*
import io.noties.markwon.core.spans.CodeSpan
import io.noties.markwon.core.spans.StrongEmphasisSpan
import io.noties.markwon.ext.tables.Table
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class MarkdownTableTest {
    @get:Rule val compose = createComposeRule()

    @Test fun parserPreservesReferencesEscapesFormattingAndSurroundingBlocks() {
        val markwon = markdownRenderer(ApplicationProvider.getApplicationContext())
        val parts = markdownParts(markwon, """
            说明 **加粗**。

            | 项目 | 内容 | 数量 |
            | :--- | :---: | ---: |
            | A\|B | **启用**<br>`media_pause` [文档][ref] | 12 |
            | 空白 | | |

            ```text
            | 代码 | 示例 |
            | --- | --- |
            ```

            [ref]: https://example.com/guide
        """.trimIndent())
        assertEquals(3, parts.size)
        val grid = (parts[1] as MarkdownPart.Grid).table
        val columns = grid.rows()[1].columns()
        assertEquals("A|B", columns[0].content().toString())
        assertEquals(Table.Alignment.CENTER, columns[1].alignment())
        assertEquals(Table.Alignment.RIGHT, columns[2].alignment())
        val formatted = columns[1].content()
        // Markwon adds non-breaking spaces around CodeSpan for its visual padding.
        assertTrue(formatted.toString(), formatted.toString().contains("启用\n"))
        assertTrue(formatted.toString().contains("media_pause"))
        assertEquals(1, formatted.getSpans(0, formatted.length, StrongEmphasisSpan::class.java).size)
        assertEquals(1, formatted.getSpans(0, formatted.length, CodeSpan::class.java).size)
        assertEquals("https://example.com/guide", formatted.getSpans(0, formatted.length, URLSpan::class.java).single().url)
        assertEquals("", grid.rows()[2].columns()[1].content().toString())
        assertTrue((parts.last() as MarkdownPart.Prose).text.toString().contains("| --- | --- |"))
        val unsafe = markdownParts(markwon, "| 链接 |\n| --- |\n| [禁止][bad] |\n\n[bad]: intent://example")
        val safe = safeMarkdown((unsafe.single() as MarkdownPart.Grid).table.rows()[1].columns()[0].content())
        assertEquals("禁止", safe.toString())
        assertEquals(0, safe.getSpans(0, safe.length, URLSpan::class.java).size)
    }

    @Test fun shortTableFitsPhoneAndLongChineseTextWrapsWithEqualRowHeights() {
        val description = "支持在手机上查看家中设备的状态，长文本会在单元格里自动换行，完整显示所有内容。"
        fixture("""
            ### 家中设备

            两列表格会适应手机宽度。

            | 设备 | 说明 |
            | --- | --- |
            | **卧室音箱** | $description |
            | NAS | `在线`<br>[打开文档](https://example.com) |

            表格后仍可继续阅读回复。
        """.trimIndent())
        compose.onNodeWithTag("markdown-table").assertWidthIsEqualTo(320.dp)
        compose.onNodeWithText("左右滑动查看完整表格").assertDoesNotExist()
        onView(withText(description)).check { view, error ->
            if (error != null) throw error
            val cell = view as TextView
            assertTrue("Long text must wrap", cell.lineCount > 2)
            assertTrue("Last line must remain visible", cell.layout.getLineBottom(cell.lineCount - 1) <= cell.height - cell.totalPaddingTop - cell.totalPaddingBottom)
            val row = cell.parent as android.widget.TableRow
            assertEquals(row.getChildAt(0).height, cell.height)
        }
        capture("table-phone-light.png")
    }

    @Test fun wideTableScrollsToLastColumnAndKeepsPositionDuringStreaming() {
        val markdown = mutableStateOf("""
            ### 设备对比

            | 设备 | 连接 | MCP 工具 | 运行版本 | 最后一列 |
            | --- | :---: | --- | --- | ---: |
            | NAS | 在线 | Home Assistant | 0.19.0 | **可访问** |
            | MinQ-PC | 在线 | 本地功能 | 0.19.0 | 已更新 |
        """.trimIndent())
        fixture(markdown, dark = true)
        compose.onNodeWithText("左右滑动查看完整表格").assertIsDisplayed()
        capture("table-wide-dark.png")
        val table = compose.onNodeWithTag("markdown-table")
        repeat(4) { table.performTouchInput { swipeLeft() } }
        val before = table.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value()
        assertTrue("Table must scroll horizontally", before > 0f)
        onView(withText("可访问")).check { view, error ->
            if (error != null) throw error
            val rect = android.graphics.Rect()
            assertTrue(view.getGlobalVisibleRect(rect))
            assertEquals(view.width, rect.width())
        }
        compose.runOnIdle { markdown.value += "\n| Mac | 在线 | 本地工具 | 0.19.0 | 刚刚完成 |" }
        compose.waitForIdle()
        assertEquals(before, table.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value(), 1f)
        capture("table-wide-scrolled-dark.png")
    }

    @Test fun streamingHeaderBecomesTableAndNestedTablesRenderInOrder() {
        val markdown = mutableStateOf("| 名称 | 状态 |")
        fixture(markdown)
        compose.onNodeWithTag("markdown-table").assertDoesNotExist()
        compose.runOnIdle { markdown.value += "\n| --- | --- |\n| 音箱 | 暂停 |" }
        compose.onNodeWithTag("markdown-table").assertExists()
        compose.runOnIdle { markdown.value = """
            > 设备状态
            >
            > | 名称 | 状态 |
            > | --- | --- |
            > | 音箱 | 暂停 |

            3. 更新记录

               | 版本 | 结果 |
               | --- | --- |
               | 0.2.13 | 完成 |

            4. 继续使用
        """.trimIndent() }
        compose.onAllNodesWithTag("markdown-table").assertCountEquals(2)
        compose.onNodeWithText("3.").assertIsDisplayed()
        compose.onNodeWithText("4.").assertIsDisplayed()
        capture("table-nested-light.png")
    }

    private fun fixture(markdown: String, dark: Boolean = false) = fixture(mutableStateOf(markdown), dark)
    private fun fixture(markdown: State<String>, dark: Boolean = false) {
        compose.setContent {
            MemohTheme(darkTheme = dark) {
                Surface(Modifier.fillMaxSize().testTag("fixture")) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
                        Box(Modifier.width(320.dp)) { MarkdownText(markdown.value) }
                    }
                }
            }
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("qa")!!
        directory.mkdirs()
        compose.onNodeWithTag("fixture").captureToImage().asAndroidBitmap().let { bitmap ->
            File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
