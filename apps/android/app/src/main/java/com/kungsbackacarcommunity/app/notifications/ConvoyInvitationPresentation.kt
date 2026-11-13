package com.kungsbackacarcommunity.app.notifications

/** Session-owned presentation only. Expiry never mutates the durable invitation. */
data class ConvoyInvitationPresentation(
    val startedAtMillis: Long,
    val initialized: Boolean = false,
    val seenIds: Set<String> = emptySet(),
    val pendingIds: List<String> = emptyList(),
    val currentId: String? = null,
    val deadlineMillis: Long = 0,
) {
    fun update(items: List<AppNotification>, elapsedMillis: Long, available: Boolean = true): ConvoyInvitationPresentation {
        val valid = items.filter {
            it.category == NotificationCategory.CONVOY_INVITE && !it.isRead &&
                !it.relatedEntityId.isNullOrBlank()
        }.associateBy { it.id }
        val arrivals = Notifications.sortedForInbox(items).asReversed().filter {
            it.id !in seenIds && it.id in valid &&
                (initialized || (it.createdAtMillis ?: Long.MIN_VALUE) >= startedAtMillis)
        }.map { it.id }
        val current = currentId?.takeIf { it in valid && elapsedMillis < deadlineMillis }
        val waiting = (pendingIds + arrivals).distinct().filter { it in valid && it != currentId }
        val next = if (available && current == null) waiting.firstOrNull() else current
        return copy(
            initialized = true,
            seenIds = seenIds + items.map { it.id },
            pendingIds = if (available) waiting.filter { it != next } else emptyList(),
            currentId = if (available) next else null,
            deadlineMillis = if (next != null && next != currentId) elapsedMillis + DISPLAY_MILLIS else deadlineMillis,
        )
    }

    fun dismiss(expectedId: String): ConvoyInvitationPresentation =
        if (currentId == expectedId) copy(currentId = null) else this

    companion object { const val DISPLAY_MILLIS = 30_000L }
}
