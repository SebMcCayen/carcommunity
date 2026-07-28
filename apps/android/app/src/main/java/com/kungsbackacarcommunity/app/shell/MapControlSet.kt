package com.kungsbackacarcommunity.app.shell

/**
 * One of the round floating controls in the map's bottom-right stack.
 *
 * The stack is the map's control language, and navigation is a MODE of the map,
 * not a different app — so pressing "Start" must not change which buttons are on
 * screen, what they look like, or what they do. Naming the members here (rather
 * than leaving the set implied by two separate Compose columns) is what makes
 * that assertable: [MapControlSet.rightSideStack] is the single answer to "which
 * controls, in what order", both screens render exactly it, and
 * `MapControlSetTest` pins it.
 */
enum class MapCircleControlKind {
    /** Report an incident/roadwork — opens the shared category picker. */
    Report,

    /** Map layers — opens the shared layers popup (alerts / traffic / night / 3D). */
    Layers,

    /** North-up ⇄ course-up orientation toggle. */
    Compass,

    /** Re-centre the camera on the user. */
    Recenter,

    /** Chat hub, with the unread badge. */
    Chat,
}

/**
 * The canonical bottom-right control stack, shared by the map home
 * ([MapHome]) and turn-by-turn navigation
 * (`navigation/turnbyturn/TurnByTurnNavScreen.kt`).
 *
 * Deliberately pure and Android-free so the parity requirement — "the same round
 * buttons on the right side as on the main page, with the same functions; not
 * more, not fewer, not a different kind" — is a unit test rather than a comment
 * two files have to keep agreeing with.
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
     * [incidentReportingEnabled] is the ONLY thing that varies it, and it varies
     * it the same way on both screens: when reporting is unavailable the control
     * is absent and the rest close up by one slot — no gap, no placeholder. Their
     * order relative to each other never changes.
     */
    fun rightSideStack(incidentReportingEnabled: Boolean): List<MapCircleControlKind> =
        buildList {
            if (incidentReportingEnabled) add(MapCircleControlKind.Report)
            add(MapCircleControlKind.Layers)
            add(MapCircleControlKind.Compass)
            add(MapCircleControlKind.Recenter)
            add(MapCircleControlKind.Chat)
        }
}
