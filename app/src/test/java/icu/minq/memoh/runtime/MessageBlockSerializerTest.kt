package icu.minq.memoh.runtime

import icu.minq.memoh.model.MessageBlock
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageBlockSerializerTest {
    @Test fun `unknown block fields are preserved`() {
        val block = Json.decodeFromString(MessageBlock.serializer(), """{"id":9,"type":"future_block","future":{"flag":true}}""")
        assertEquals("future_block", block.type)
        assertEquals(true, block.raw["future"]?.let { it.toString().contains("true") })
    }
}
