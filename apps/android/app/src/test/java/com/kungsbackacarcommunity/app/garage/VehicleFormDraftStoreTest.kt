package com.kungsbackacarcommunity.app.garage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure (de)serialisation + TTL + dirty-decision half of
 * [VehicleFormDraftStore], which is where the new-car draft's guarantees actually
 * live (issue #796). The `Context`/SharedPreferences half is a thin wrapper over
 * these functions — the same JVM-testable split as
 * `com.kungsbackacarcommunity.app.navigation.PrefsSavedPlacesStore`.
 */
class VehicleFormDraftStoreTest {

    private val fullForm =
        VehicleForm(
            makeId = "volvo",
            modelId = "240",
            modelYear = 1989,
            powertrain = VehiclePowertrain.PETROL,
            engineDescription = "B230F",
            modifications = "IPD sway bars",
            registrationPlate = "ABC123",
        )

    // ── round-trip ──────────────────────────────────────────────────────────

    @Test
    fun `a full form round-trips through encode and decode`() {
        val encoded = VehicleFormDraftStore.encode(fullForm, savedAtMillis = 1_000L)
        val decoded = VehicleFormDraftStore.decode(encoded)
        assertEquals(fullForm, decoded?.form)
        assertEquals(1_000L, decoded?.savedAtMillis)
    }

    @Test
    fun `an empty add form round-trips with every selection unset`() {
        val encoded = VehicleFormDraftStore.encode(VehicleForm(), savedAtMillis = 42L)
        val decoded = VehicleFormDraftStore.decode(encoded)
        assertEquals(VehicleForm(), decoded?.form)
        assertEquals(42L, decoded?.savedAtMillis)
    }

    @Test
    fun `null selections stay null and are not resurrected as empty strings`() {
        val partial = VehicleForm(makeId = "volvo", registrationPlate = "XY 99")
        val decoded = VehicleFormDraftStore.decode(
            VehicleFormDraftStore.encode(partial, savedAtMillis = 5L),
        )
        assertEquals("volvo", decoded?.form?.makeId)
        assertNull(decoded?.form?.modelId)
        assertNull(decoded?.form?.modelYear)
        assertNull(decoded?.form?.powertrain)
        assertEquals("XY 99", decoded?.form?.registrationPlate)
    }

    // ── decode robustness ─────────────────────────────────────────────────────

    @Test
    fun `decode returns null for absent, blank or non-JSON input`() {
        assertNull(VehicleFormDraftStore.decode(null))
        assertNull(VehicleFormDraftStore.decode(""))
        assertNull(VehicleFormDraftStore.decode("   "))
        assertNull(VehicleFormDraftStore.decode("not json {"))
    }

    @Test
    fun `decode returns null when the timestamp is missing`() {
        // A record with no savedAt cannot have its freshness judged, so it is
        // treated as no draft rather than an eternally-fresh one.
        assertNull(VehicleFormDraftStore.decode("""{"makeId":"volvo"}"""))
    }

    @Test
    fun `a retired or unknown powertrain wire decodes to unset, never a crash`() {
        // A draft written by an older build could carry a wire this build no
        // longer offers; it must not throw when the add form reopens.
        val raw = """{"savedAt":7,"powertrain":"steam"}"""
        val decoded = VehicleFormDraftStore.decode(raw)
        assertNull(decoded?.form?.powertrain)
        assertEquals(7L, decoded?.savedAtMillis)
    }

    // ── TTL ───────────────────────────────────────────────────────────────────

    @Test
    fun `a fresh draft is within the TTL`() {
        assertTrue(VehicleFormDraftStore.isFresh(savedAtMillis = 0L, nowMillis = 0L))
        assertTrue(
            VehicleFormDraftStore.isFresh(
                savedAtMillis = 0L,
                nowMillis = VehicleFormDraftStore.TTL_MILLIS - 1,
            ),
        )
    }

    @Test
    fun `exactly at the TTL is still fresh, one past it is stale`() {
        assertTrue(
            VehicleFormDraftStore.isFresh(
                savedAtMillis = 0L,
                nowMillis = VehicleFormDraftStore.TTL_MILLIS,
            ),
        )
        assertFalse(
            VehicleFormDraftStore.isFresh(
                savedAtMillis = 0L,
                nowMillis = VehicleFormDraftStore.TTL_MILLIS + 1,
            ),
        )
    }

    @Test
    fun `the TTL is 24 hours`() {
        assertEquals(24L * 60 * 60 * 1000, VehicleFormDraftStore.TTL_MILLIS)
    }

    @Test
    fun `a draft stamped in the future is treated as stale, not infinitely fresh`() {
        // A backwards clock (or a draft written by a device set to the future)
        // gives a negative age; the restore prompt must fail closed.
        assertFalse(VehicleFormDraftStore.isFresh(savedAtMillis = 1_000L, nowMillis = 500L))
    }

    // ── dirty / confirm decision ───────────────────────────────────────────────

    @Test
    fun `an untouched form has no user content`() {
        assertFalse(VehicleFormDraftStore.hasUserContent(VehicleForm()))
    }

    @Test
    fun `each editable field alone counts as user content`() {
        assertTrue(VehicleFormDraftStore.hasUserContent(VehicleForm(makeId = "volvo")))
        assertTrue(VehicleFormDraftStore.hasUserContent(VehicleForm(modelId = "240")))
        assertTrue(VehicleFormDraftStore.hasUserContent(VehicleForm(modelYear = 1989)))
        assertTrue(VehicleFormDraftStore.hasUserContent(VehicleForm(powertrain = VehiclePowertrain.PETROL)))
        assertTrue(VehicleFormDraftStore.hasUserContent(VehicleForm(engineDescription = "B230F")))
        assertTrue(VehicleFormDraftStore.hasUserContent(VehicleForm(modifications = "coilovers")))
        assertTrue(VehicleFormDraftStore.hasUserContent(VehicleForm(registrationPlate = "ABC123")))
    }

    @Test
    fun `the read-only legacy carriers are not user content`() {
        // legacyMake/legacyModel are shown for a pre-catalogue EDIT; they are not
        // input and must never make an add form look dirty.
        assertFalse(
            VehicleFormDraftStore.hasUserContent(
                VehicleForm(legacyMake = "Wolwo", legacyModel = "245"),
            ),
        )
    }

    @Test
    fun `only a dirty new car confirms on dismiss`() {
        // Dirty add -> confirm.
        assertTrue(VehicleFormDraftStore.shouldConfirmDismiss(isAddMode = true, form = fullForm))
        // Untouched add -> nothing to lose, close straight away.
        assertFalse(VehicleFormDraftStore.shouldConfirmDismiss(isAddMode = true, form = VehicleForm()))
        // Edit, even dirty -> has a saved vehicle to fall back to, no confirm.
        assertFalse(VehicleFormDraftStore.shouldConfirmDismiss(isAddMode = false, form = fullForm))
    }

    // ── draft-sync decision (write / clear / none) ─────────────────────────────

    @Test
    fun `an open add form with content is written`() {
        assertEquals(
            DraftSyncAction.WRITE,
            VehicleFormDraftStore.draftSyncAction(
                formOpen = true,
                isAddMode = true,
                restorePromptShowing = false,
                form = fullForm,
            ),
        )
    }

    @Test
    fun `typing then clearing every field back to empty CLEARS the draft`() {
        // The Copilot edge: a user typed something (a draft was written), then
        // deleted it all. The now-empty add form must CLEAR the stale draft so an
        // unclean exit (tab switch / process death) can't offer it again.
        assertEquals(
            DraftSyncAction.CLEAR,
            VehicleFormDraftStore.draftSyncAction(
                formOpen = true,
                isAddMode = true,
                restorePromptShowing = false,
                form = VehicleForm(),
            ),
        )
    }

    @Test
    fun `the restore prompt is a hands-off window - neither write nor clear`() {
        // While "continue your unsaved car?" is up, the empty form underneath it
        // must NOT wipe the draft being offered.
        assertEquals(
            DraftSyncAction.NONE,
            VehicleFormDraftStore.draftSyncAction(
                formOpen = true,
                isAddMode = true,
                restorePromptShowing = true,
                form = VehicleForm(),
            ),
        )
        // Even with content on screen, the prompt window still defers to the user.
        assertEquals(
            DraftSyncAction.NONE,
            VehicleFormDraftStore.draftSyncAction(
                formOpen = true,
                isAddMode = true,
                restorePromptShowing = true,
                form = fullForm,
            ),
        )
    }

    @Test
    fun `closing an EDIT form does not clear a parked add-draft`() {
        // The store is add-scoped. Sequence: user parks a new-car draft, later
        // edits an existing vehicle and cancels — that edit-close must NOT wipe
        // the add-draft. Only an add-mode close clears.
        assertFalse(VehicleFormDraftStore.clearsDraftOnClose(isAddMode = false))
        assertTrue(VehicleFormDraftStore.clearsDraftOnClose(isAddMode = true))
    }

    @Test
    fun `edit mode and a closed form never touch the draft`() {
        // Edit has a saved vehicle and never drafts.
        assertEquals(
            DraftSyncAction.NONE,
            VehicleFormDraftStore.draftSyncAction(
                formOpen = true,
                isAddMode = false,
                restorePromptShowing = false,
                form = fullForm,
            ),
        )
        // A closed form is not syncing anything.
        assertEquals(
            DraftSyncAction.NONE,
            VehicleFormDraftStore.draftSyncAction(
                formOpen = false,
                isAddMode = true,
                restorePromptShowing = false,
                form = fullForm,
            ),
        )
    }
}
