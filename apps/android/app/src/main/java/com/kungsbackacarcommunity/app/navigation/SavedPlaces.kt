package com.kungsbackacarcommunity.app.navigation

/**
 * Pure (Android-free) core for **saved places** — the user's favourite
 * destinations, surfaced in the search bar's empty state for one-tap routing.
 *
 * Saved places differ from [RecentSearches] in intent: recents are an automatic,
 * lossy history (anything you pick, capped at 5); saved places are a deliberate,
 * durable shortlist the user curates with a label ("Mamma", "Jobbet", the
 * workshop) and which never falls off the list on its own.
 *
 * Everything here is JVM-unit-testable list logic; the SharedPreferences
 * serialization glue lives in [PrefsSavedPlacesStore].
 */

/**
 * What kind of shortcut a saved place is.
 *
 * [Home] and [Work] are the conventional singletons every maps app offers: at
 * most one of each exists, they sort first, and the UI labels them from string
 * resources rather than the user's text. [Favourite] is everything else — an
 * arbitrary number of user-labelled places.
 */
enum class SavedPlaceKind {
    Home,
    Work,
    Favourite,
}

/**
 * A destination the user deliberately saved.
 *
 * @param id stable identity for rename/remove and de-duplication. Derived from
 *   the kind for the singletons ("home"/"work") so re-saving Home replaces the
 *   old one, and from the underlying place otherwise (see [SavedPlaces.idFor]).
 * @param kind Home / Work / Favourite.
 * @param label the user's own name for it. Ignored for display on the singletons
 *   (the UI shows a localized "Home"/"Work"), but still carried so a favourite
 *   promoted/demoted between kinds keeps its text.
 * @param place the underlying geocoded destination — the exact
 *   [PlaceSuggestion] a search result or route preview produced, so tapping a
 *   saved place feeds the identical select() → route-preview path.
 */
data class SavedPlace(
    val id: String,
    val kind: SavedPlaceKind,
    val label: String,
    val place: PlaceSuggestion,
)

/**
 * Persistence seam for [SavedPlace]s, mirroring [RecentSearchesStore]: the
 * controller and its tests depend only on this interface — production injects
 * the prefs-backed [PrefsSavedPlacesStore], tests the in-memory fake.
 *
 * Implementations are expected to be cheap/synchronous (local key-value reads),
 * which is what keeps the saved-places row instant and fully offline.
 */
interface SavedPlacesStore {
    /** All saved places, already ordered ([SavedPlaces.sort]) and capped. */
    fun saved(): List<SavedPlace>

    /**
     * Adds or replaces [place] (see [SavedPlaces.upsert] for the semantics).
     * Renaming goes through here too: re-saving an entry with a new label
     * rewrites the same id, so there is no separate rename path to diverge.
     */
    fun save(place: SavedPlace)

    /** Removes the saved place with [id]; unknown ids are a no-op. */
    fun remove(id: String)
}

/** Pure saved-places list logic, independent of any storage backend. */
object SavedPlaces {
    /**
     * How many saved places are persisted in total (Home + Work + favourites).
     * A deliberate shortlist, not an address book: high enough that nobody
     * realistically hits it, low enough that the whole list stays a single cheap
     * prefs read and the search-screen row never becomes a scrolling wall.
     */
    const val MAX = 12

    /** How many are shown inline in the empty search state before "show all". */
    const val SHOWN = 6

    /** Longest accepted label; longer input is truncated on save/rename. */
    const val MAX_LABEL = 40

    /**
     * Stable id for a saved place. The singletons key off the kind alone, so
     * saving a new Home overwrites the previous one instead of accumulating;
     * favourites key off the underlying place (its geocoder id, else its
     * coordinate) so saving the same place twice updates it in place.
     */
    fun idFor(kind: SavedPlaceKind, place: PlaceSuggestion): String =
        when (kind) {
            SavedPlaceKind.Home -> "home"
            SavedPlaceKind.Work -> "work"
            SavedPlaceKind.Favourite ->
                if (place.id.isNotBlank()) {
                    "fav:${place.id}"
                } else {
                    "fav:${place.point.longitude},${place.point.latitude}"
                }
        }

    /**
     * Builds a [SavedPlace] for [place] with the id and normalized label
     * implied by [kind] — the single constructor callers should use, so ids stay
     * consistent with [idFor] and labels with [normalizeLabel].
     */
    fun create(
        kind: SavedPlaceKind,
        place: PlaceSuggestion,
        label: String,
    ): SavedPlace =
        SavedPlace(
            id = idFor(kind, place),
            kind = kind,
            label = normalizeLabel(label).ifBlank { place.name },
            place = place,
        )

    /**
     * Returns [existing] with [saved] added or replaced.
     *
     * Replacement is by id, which gives both conventions for free: a new Home
     * (id "home") swaps the old Home's address, while re-saving an already-saved
     * favourite refreshes its label/coordinate rather than duplicating it. A
     * replacement keeps the entry **where it already is** rather than re-adding
     * it at the end: renaming a favourite must not shuffle the user's list, and
     * — since favourites are held oldest-first (see [sort]) — must not change
     * which one the cap considers oldest.
     *
     * A genuinely new entry is appended, so favourites stay in insertion order.
     * When the id is new and the list is already at [max], the oldest *favourite*
     * is dropped — never Home or Work, which the user set deliberately and would
     * be astonished to lose to a cap. If [max] is somehow already reached with no
     * favourite to evict, the new entry is rejected (the list is returned
     * unchanged) rather than growing past the cap.
     */
    fun upsert(
        existing: List<SavedPlace>,
        saved: SavedPlace,
        max: Int = MAX,
    ): List<SavedPlace> {
        val at = existing.indexOfFirst { it.id == saved.id }
        if (at >= 0) {
            // Replace in place. A replacement never grows the list, so it also
            // skips the cap handling. Any further copies of the id (unreachable
            // through this path — only a hand-seeded list could hold them)
            // collapse into the one at [at].
            val deduped = existing.filterIndexed { i, e -> i == at || e.id != saved.id }
            return sort(deduped.map { if (it.id == saved.id) saved else it })
        }
        if (existing.size < max) return sort(existing + saved)
        // Favourites are held in insertion order (see [sort]), so the OLDEST is
        // the first of them — not the last.
        val oldestFavourite =
            existing.firstOrNull { it.kind == SavedPlaceKind.Favourite } ?: return sort(existing)
        return sort(existing.filterNot { it.id == oldestFavourite.id } + saved)
    }

    /** Returns [existing] without the entry [id]. */
    fun remove(existing: List<SavedPlace>, id: String): List<SavedPlace> =
        existing.filterNot { it.id == id }

    /**
     * Forces an arbitrary, untrusted list into the store contract: at most one
     * Home and one Work, no duplicate ids, [sort]ed, and capped at [MAX].
     *
     * This is what a store applies to a list it did not build through [upsert] —
     * a decoded on-disk payload, or a hand-seeded in-memory list — neither of
     * which can be trusted to hold the invariants the UI reads.
     *
     * The singletons' ids are **re-derived from their kind** rather than taken on
     * faith, because a persisted id is untrusted input: a corrupt entry claiming
     * `kind=Home` with some other id would otherwise look like a distinct row and
     * survive de-duplication as a second Home. Favourites keep their stored id —
     * theirs is meaningful identity (it pins the underlying place), not a
     * function of the kind.
     *
     * Where a duplicate must be dropped, the **first occurrence wins**, matching
     * [upsert]'s replace-in-place: earlier means older, and older keeps its slot.
     */
    fun normalize(items: List<SavedPlace>): List<SavedPlace> =
        sort(
            items
                .map { if (it.kind == SavedPlaceKind.Favourite) it else it.copy(id = idFor(it.kind, it.place)) }
                .distinctBy { it.id },
        ).take(MAX)

    /**
     * Canonical display order: Home, then Work, then the favourites, whose
     * relative order is passed through untouched. That incoming order is the
     * order they were added, because [upsert] is the only writer and it appends
     * new favourites while replacing existing ones in place — so favourites read
     * oldest-first here and the cap can trust the first of them to be the oldest.
     *
     * Applied on every write so a store's on-disk payload — and thus the UI — is
     * already ordered without the screen re-sorting.
     */
    fun sort(items: List<SavedPlace>): List<SavedPlace> {
        val home = items.filter { it.kind == SavedPlaceKind.Home }
        val work = items.filter { it.kind == SavedPlaceKind.Work }
        val favourites = items.filter { it.kind == SavedPlaceKind.Favourite }
        return home + work + favourites
    }

    /**
     * The saved entry matching [place], if any — what the route preview's save
     * button reads to decide between "save this" and "edit/remove the existing
     * one". Matched the same way [idFor] de-duplicates: by geocoder id when both
     * have one, else by coordinate (so a dropped pin re-pressed on the same spot
     * still resolves to its saved entry).
     */
    fun find(existing: List<SavedPlace>, place: PlaceSuggestion): SavedPlace? =
        existing.firstOrNull { it.place.samePlaceAs(place) }

    /** Trims and caps a user-entered label. */
    fun normalizeLabel(label: String): String = label.trim().take(MAX_LABEL)

    private fun PlaceSuggestion.samePlaceAs(other: PlaceSuggestion): Boolean =
        if (id.isNotBlank() && other.id.isNotBlank()) id == other.id else point == other.point
}

/**
 * In-memory [SavedPlacesStore] for unit tests, Compose previews, and as the
 * default so a caller without persistence still works (saves just don't survive
 * process death).
 */
class InMemorySavedPlacesStore(
    initial: List<SavedPlace> = emptyList(),
) : SavedPlacesStore {
    // Same normalization as the prefs store's decode: [initial] is hand-written,
    // so it gets the same distrust as an on-disk payload rather than a bare sort.
    private var items: List<SavedPlace> = SavedPlaces.normalize(initial)

    override fun saved(): List<SavedPlace> = items

    override fun save(place: SavedPlace) {
        items = SavedPlaces.upsert(items, place)
    }

    override fun remove(id: String) {
        items = SavedPlaces.remove(items, id)
    }
}
