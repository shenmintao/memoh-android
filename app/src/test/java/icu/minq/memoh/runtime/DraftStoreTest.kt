package icu.minq.memoh.runtime

import icu.minq.memoh.data.DraftStore
import org.junit.Assert.*
import org.junit.Test

class DraftStoreTest {
    @Test fun `failed queue leaves draft untouched and successful queue clears exact draft`() {
        val drafts = DraftStore(); drafts.put("account:b:s", "hello")
        assertEquals("hello", drafts.get("account:b:s"))
        drafts.queued("account:b:s", "hello")
        assertEquals("", drafts.get("account:b:s"))
    }
    @Test fun `late queue callback does not clear a newer draft or another session`() {
        val drafts = DraftStore(); drafts.put("s1", "new"); drafts.put("s2", "another")
        drafts.queued("s1", "old")
        assertEquals("new", drafts.get("s1")); assertEquals("another", drafts.get("s2"))
        drafts.clear(); assertEquals("", drafts.get("s1"))
    }
}
