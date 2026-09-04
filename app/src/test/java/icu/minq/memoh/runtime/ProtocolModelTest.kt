package icu.minq.memoh.runtime

import icu.minq.memoh.model.Bot
import icu.minq.memoh.model.MessageBlock
import icu.minq.memoh.model.MessageBlockSerializer
import icu.minq.memoh.model.Session
import icu.minq.memoh.model.isExternalChannel
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ProtocolModelTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun `bot permissions and external channel are decoded`() {
        val bot = json.decodeFromString(Bot.serializer(), """{"id":"b","current_user_permissions":["workspace_exec"]}""")
        val session = json.decodeFromString(Session.serializer(), """{"id":"s","bot_id":"b","channel_type":"telegram"}""")
        assertEquals(listOf("workspace_exec"), bot.currentUserPermissions)
        assertTrue(session.isExternalChannel())
        assertFalse(session.copy(channelType = "local").isExternalChannel())
    }

    @Test fun `approval options and selected option survive message parsing`() {
        val block = json.decodeFromString(MessageBlockSerializer, """{
            "id":1,"type":"tool","approval":{
              "approval_id":"approval-1","status":"pending","can_approve":true,
              "options":[{"id":"always","name":"Always","kind":"allow_always"}],
              "selected_option_id":"always"
            }
        }""")
        assertEquals("always", block.approval?.options?.single()?.id)
        assertEquals("allow_always", block.approval?.options?.single()?.kind)
        assertEquals("always", block.approval?.selectedOptionId)
    }
}
