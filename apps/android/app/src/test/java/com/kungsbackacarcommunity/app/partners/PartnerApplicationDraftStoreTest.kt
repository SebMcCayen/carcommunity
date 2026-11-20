package com.kungsbackacarcommunity.app.partners

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class PartnerApplicationDraftStoreTest {
    @Test fun `drafts are account isolated exclude logos and clear on submission`() {
        val directory = Files.createTempDirectory("partner-draft-test").toFile()
        try {
            val store = PartnerApplicationDraftStore(directory) { 100_000L }
            val form = PartnerApplicationForm(companyName = "Private company", contactEmail = "private@example.com", logoBase64 = "DO_NOT_PERSIST_LOGO", logoRightsConfirmed = true)
            store.save("account-a", PartnerApplicationDraft(form, 2))
            assertNull(store.read("account-b"))
            assertEquals("Private company", store.read("account-a")!!.form.companyName)
            assertEquals(2, store.read("account-a")!!.step)
            assertNull(store.read("account-a")!!.form.logoBase64)
            assertFalse(store.read("account-a")!!.form.logoRightsConfirmed)
            assertFalse(directory.listFiles()!!.single().readText().contains("DO_NOT_PERSIST_LOGO"))
            store.clear("account-a")
            assertNull(store.read("account-a"))
            assertTrue(directory.listFiles()!!.isEmpty())
        } finally { directory.deleteRecursively() }
    }

    @Test fun `seven day expiry removes drafts of every account`() {
        val directory = Files.createTempDirectory("partner-draft-expiry").toFile()
        try {
            var clock = 100_000L
            val store = PartnerApplicationDraftStore(directory) { clock }
            store.save("account-a", PartnerApplicationDraft(PartnerApplicationForm(companyName = "A"), 1))
            store.save("account-b", PartnerApplicationDraft(PartnerApplicationForm(companyName = "B"), 1))
            clock += PartnerApplicationDraftStore.TTL_MILLIS
            store.purgeExpired()
            assertNull(store.read("account-a"))
            assertNull(store.read("account-b"))
            assertTrue(directory.listFiles()!!.isEmpty())
        } finally { directory.deleteRecursively() }
    }
}
