package com.kungsbackacarcommunity.app.notifications

/**
 * What an event-referencing inbox row can be OPENED to.
 *
 * WHY THIS EXISTS
 * ---------------
 * Every event notification carries the event id in [AppNotification.relatedEntityId]
 * (the backend writes actionType `open_event` + relatedEntityId = eventId for all
 * of them), so tapping the row can take the member straight to that event's
 * detail — which is exactly the "a link the user can use to get to it" the
 * event_created notice is for. Before this, an event row in the inbox only marked
 * itself read on tap and went nowhere; only convoy invites navigated
 * ([ConvoyNotifications]). The push-tap path already deep-links an event
 * (PushTarget.EVENT → the events route opens that event), so this makes the
 * IN-APP inbox match the push, rather than being the one surface where the link
 * is inert.
 *
 * Unlike the convoy case there is NO staleness to re-derive: an event that was
 * cancelled or completed still has a detail screen (it renders the cancelled
 * state), so the row always navigates when it carries an id — the destination,
 * not the row, owns "this event is over". That is why this is a two-line id
 * lookup rather than a state machine.
 *
 * Pure and total: no I/O, no Compose, so the category/id extraction is
 * JVM-unit-testable without a device.
 */
object EventNotifications {
    /**
     * Categories whose `relatedEntityId` is an event id. All four event
     * categories carry it and deep-link to the same event-detail destination
     * (backend buildPushDeepLink maps every one to target `event`), so a tap on
     * any of them opens that event — event_created is simply the newest of them.
     */
    private val EVENT_CATEGORIES =
        setOf(
            NotificationCategory.EVENT_CREATED,
            NotificationCategory.EVENT_REMINDER,
            NotificationCategory.EVENT_UPDATED,
            NotificationCategory.EVENT_CANCELLED,
        )

    /** The event this row is about, or null when it is not about one. */
    fun eventId(item: AppNotification): String? =
        if (item.category in EVENT_CATEGORIES) {
            item.relatedEntityId?.takeIf { it.isNotBlank() }
        } else {
            null
        }
}
