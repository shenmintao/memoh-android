package icu.minq.memoh.runtime

import icu.minq.memoh.data.*
import icu.minq.memoh.model.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class AttachmentDraftTest {
    private fun file(id: String) = DraftAttachment(id, "content://fixture/$id", "$id.txt", payload = ChatAttachment(name = "$id.txt", base64 = "data:text/plain;base64,YQ=="))
    @Test fun `queue clears submitted files only in their originating account and session`() {
        val store = AttachmentDraftStore()
        store.add("account:s1", file("old")); store.add("account:s1", file("new")); store.add("account:s2", file("other"))
        store.queued("account:s1", setOf("old"))
        assertEquals(listOf("new"), store.get("account:s1").map { it.id })
        assertEquals(listOf("other"), store.get("account:s2").map { it.id })
        store.remove("account:s1", "new")
        store.update("account:s1", file("new"))
        assertTrue(store.get("account:s1").isEmpty())
        store.clear(); assertTrue(store.get("account:s2").isEmpty())
    }
    @Test fun `oversized combined attachments fail visibly and bounded count rejects more`() {
        val store = AttachmentDraftStore()
        store.add("s", file("a")); store.update("s", file("a").copy(size = AttachmentDraftStore.MAX_BYTES))
        store.add("s", file("b")); store.update("s", file("b").copy(size = 1))
        assertNotNull(store.get("s").last().error)
        assertNull(store.get("s").last().payload)
        repeat(8) { assertTrue(store.add("s", file("$it"))) }
        assertFalse(store.add("s", file("too-many")))
    }
    @Test fun `device availability and session binding follow official routing rules`() {
        assertTrue(WorkspaceTarget("native", "native").available())
        assertTrue(WorkspaceTarget("remote:r", "remote", online = true, status = "online").available())
        assertFalse(WorkspaceTarget("remote:r", "remote", online = false, status = "online").available())
        assertFalse(WorkspaceTarget("remote:r", "remote", online = true, status = "connecting").available())
        assertTrue(Session("s", "b").canSelectDevice())
        assertFalse(Session("s", "b", workdirId = "folder").canSelectDevice())
        assertFalse(Session("s", "b", runtimeType = "acp_agent").canSelectDevice())
        assertFalse(Session("s", "b", runtimeType = "codex").canSelectDevice())
        assertFalse(Session("s", "b", channelType = "telegram").canSelectDevice())
    }
    @Test fun `history retains attachment names without exposing encoded bytes in UI fallback`() {
        val turn = Json { ignoreUnknownKeys = true }.decodeFromString(ChatTurn.serializer(), """{"role":"user","text":"","attachments":[{"type":"file","name":"报告.pdf","content_hash":"hash","mime":"application/pdf","url":"ignored"}]}""")
        assertEquals("报告.pdf", turn.attachments.single().name)
        assertEquals("hash", turn.attachments.single().contentHash)
    }
}
