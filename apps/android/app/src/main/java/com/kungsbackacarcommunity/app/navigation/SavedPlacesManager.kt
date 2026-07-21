package com.kungsbackacarcommunity.app.navigation

/**
 * Pure (Android-free) management operations over a [SavedPlacesStore] for the
 * standalone **Saved places** screen ([SavedPlacesScreen]): list, rename, delete.
 *
 * This is deliberately a thin adapter over the same [SavedPlaces] primitives the
 * search bar's inline save flow uses, NOT a second store or a second source of
 * truth: it holds the identical injected [SavedPlacesStore] (production: the
 * per-uid [PrefsSavedPlacesStore]) that [NavigationController] does, so an edit
 * made on the management screen and one made from the route preview's save button
 * read and write the exact same list.
 *
 * **Re-setting a place's LOCATION is intentionally absent here.** Changing "which
 * address is my Home" reuses the existing address search / place picker
 * ([NavigationSearchScreen]) and its save path ([NavigationController.savePlace]),
 * which already sweeps stale duplicates when a place is re-kinded or re-located.
 * Duplicating that write path in a second place is exactly the divergence this
 * class avoids — the screen delegates "Change address" back to that picker rather
 * than mutating the store itself.
 *
 * Every method is a synchronous local key-value read/write, so the screen reflects
 * an edit on the same frame with no spinner and no network.
 */
class SavedPlacesManager(private val store: SavedPlacesStore) {
    /** All saved places, already ordered ([SavedPlaces.sort]) and capped. */
    fun places(): List<SavedPlace> = store.saved()

    /**
     * Re-labels [place], keeping its kind and its underlying location.
     *
     * Goes through the same [SavedPlaces.upsert] the save dialog uses: re-saving
     * an entry with an unchanged id (kind + place are untouched, so [idFor] yields
     * the same id) replaces it **in place** rather than re-appending — preserving
     * its slot, and so its favourite cap-eviction age.
     *
     * Meaningful only for favourites. Home and Work render a localized
     * "Home"/"Work" and ignore their stored label (see `SavedPlace.displayLabel`),
     * so relabelling a singleton is a harmless no-op on screen — which is why the
     * screen offers rename for favourites only. The operation itself stays
     * kind-agnostic so no caller can desync it from that rule; a blank/whitespace
     * label falls back to the place's own name (see [SavedPlaces.create]).
     */
    fun rename(place: SavedPlace, newLabel: String) {
        store.save(SavedPlaces.create(place.kind, place.place, newLabel))
    }

    /** Deletes the saved place [id]. Unknown ids are a no-op ([SavedPlaces.remove]). */
    fun delete(id: String) {
        store.remove(id)
    }
}
