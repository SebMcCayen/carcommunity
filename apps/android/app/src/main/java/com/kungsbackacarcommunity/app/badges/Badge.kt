package com.kungsbackacarcommunity.app.badges

/**
 * Badges domain (Phase 12 slice 14). Read-only: awards live at
 * users/{uid}/badges/{badgeKey} (owner read, backend-only writes). The doc
 * denormalizes name/awardedAt so the client renders without a catalog lookup;
 * the screen prefers the localized name for known keys. Pure Kotlin.
 */
data class Badge(
    val key: String,
    val fallbackName: String?,
    val awardedAtMillis: Long?,
)

object Badges {
    /** Newest award first; undated badges sort last. */
    fun sortedForList(badges: List<Badge>): List<Badge> =
        badges.sortedByDescending { it.awardedAtMillis ?: Long.MIN_VALUE }
}
