package com.kungsbackacarcommunity.app.shell

/**
 * One of the round floating controls in the map's bottom-right stack.
 *
 * The stack is the map's control language, and navigation is a MODE of the map,
 * not a different app — so pressing "Start" must not change which buttons are on
 * screen, what they look like, or what they do, except Saved places: map home
 * offers it in the avatar menu, which turn-by-turn does not have. Naming the
 * members here (rather than leaving the set implied by two Compose columns) makes
 * that assertable: [MapControlSet.rightSideStack] is the single answer to "which
 * controls, in what order", each screen renders its configured list, and
 * `MapControlSetTest` pins it.
 */
enum class MapCircleControlKind {
    /** Report an incident/roadwork — opens the shared category picker. */
    Report,

    /** Map layers — opens the shared layers popup (alerts / traffic / night / 3D). */
    Layers,

    /** North-up ⇄ course-up orientation toggle. Also re-centres on the user. */
    Compass,

    /** Saved places — opens the saved-locations picker; a tap jumps the map there. */
    SavedPlaces,

    /**
     * Kronjakt perk DEPLOY menu — opens the "use a perk" popup (drop a trap /
     * raise a shield / arm the boost). Present ONLY while the `crownHuntPerks`
     * flag is on, exactly like [Report] is gated on incident reporting.
     */
    Perks,
}

/**
 * The canonical bottom-right control stack, shared by the map home
 * ([MapHome]) and turn-by-turn navigation
 * (`navigation/turnbyturn/TurnByTurnNavScreen.kt`).
 *
 * Deliberately pure and Android-free so the shared controls' order and map
 * home's explicit Saved-places menu exception are pinned by unit tests.
 *
 * There used to be no such rule and the two stacks had genuinely drifted:
 * navigation led with the compass, carried a live-broadcast disc the map home
 * had already dropped (PR #539 moved starting/stopping/hiding into the centre
 * live control's manage sheet), was missing the layers and chat controls
 * entirely, and rendered its re-centre affordance as a tinted
 * `FloatingActionButton` — a different SIZE and a different KIND of button —
 * that only appeared once the follow camera had detached.
 */
object MapControlSet {
    /**
     * The stack, top to bottom.
     *
     * Two flags vary it, and each varies it the same way on both screens:
     * [incidentReportingEnabled] adds the leading [MapCircleControlKind.Report],
     * and [crownHuntPerksEnabled] adds the trailing [MapCircleControlKind.Perks]
     * (the Kronjakt "use a perk" control, default OFF via the `crownHuntPerks`
     * flag). When a flag is off its control is absent and the rest close up by
     * one slot — no gap, no placeholder. The order of the always-present controls
     * relative to each other never changes.
     * [savedPlacesInProfileMenu] removes only Saved places on map home, where
     * that same action is available from the avatar popup. Navigation keeps
     * the round control because it has no avatar popup.
     */
    fun rightSideStack(
        incidentReportingEnabled: Boolean,
        crownHuntPerksEnabled: Boolean = false,
        savedPlacesInProfileMenu: Boolean = false,
    ): List<MapCircleControlKind> =
        buildList {
            if (incidentReportingEnabled) add(MapCircleControlKind.Report)
            add(MapCircleControlKind.Layers)
            add(MapCircleControlKind.Compass)
            if (!savedPlacesInProfileMenu) add(MapCircleControlKind.SavedPlaces)
            if (crownHuntPerksEnabled) add(MapCircleControlKind.Perks)
        }
}
