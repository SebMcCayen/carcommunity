package com.kungsbackacarcommunity.app.badges

import androidx.annotation.StringRes
import com.kungsbackacarcommunity.app.R

/**
 * Localized badge-name lookup (Phase 12 slice 14). Returns the res for a known
 * badge key, or null for an unknown key (the screen falls back to the
 * denormalized name from the document).
 */
@StringRes
fun badgeNameRes(key: String): Int? =
    when (key) {
        "first_event" -> R.string.badges_badgeNames_first_event
        "five_events" -> R.string.badges_badgeNames_five_events
        "helpful_member" -> R.string.badges_badgeNames_helpful_member
        "early_member" -> R.string.badges_badgeNames_early_member
        "garage_created" -> R.string.badges_badgeNames_garage_created
        else -> null
    }
