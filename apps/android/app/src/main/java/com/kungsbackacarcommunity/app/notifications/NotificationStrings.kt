package com.kungsbackacarcommunity.app.notifications

import androidx.annotation.StringRes
import com.kungsbackacarcommunity.app.R

/** Localized category label lookup (Phase 12 slice 21). */
@StringRes
fun NotificationCategory.labelRes(): Int =
    when (this) {
        NotificationCategory.EVENT_REMINDER -> R.string.notifications_categoryEventReminder
        NotificationCategory.EVENT_UPDATED -> R.string.notifications_categoryEventUpdated
        NotificationCategory.EVENT_CANCELLED -> R.string.notifications_categoryEventCancelled
        NotificationCategory.ADMIN_MESSAGE -> R.string.notifications_categoryAdminMessage
        NotificationCategory.ACCOUNT_WARNING -> R.string.notifications_categoryAccountWarning
        NotificationCategory.ACCOUNT_SUSPENSION -> R.string.notifications_categoryAccountSuspension
        NotificationCategory.SUBSCRIPTION_STATUS -> R.string.notifications_categorySubscription
        NotificationCategory.SYSTEM_NOTICE -> R.string.notifications_categorySystem
    }
